package com.jasongzy.s3pocket.data.storage

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.copyObject
import aws.sdk.kotlin.services.s3.deleteObject
import aws.sdk.kotlin.services.s3.deleteObjects
import aws.sdk.kotlin.services.s3.headObject
import aws.sdk.kotlin.services.s3.listObjectsV2
import aws.sdk.kotlin.services.s3.model.Delete
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.MetadataDirective
import aws.sdk.kotlin.services.s3.model.ObjectIdentifier
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.sdk.kotlin.services.s3.presigners.presignPutObject
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.net.url.Url
import com.jasongzy.s3pocket.data.model.BucketItem
import com.jasongzy.s3pocket.data.model.ObjectDetails
import com.jasongzy.s3pocket.data.model.ObjectItem
import com.jasongzy.s3pocket.data.model.StorageProfile
import com.jasongzy.s3pocket.data.model.UploadObject
import com.jasongzy.s3pocket.data.model.UpdateObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

class S3ObjectStorage(
  private val profile: StorageProfile,
  private val httpClient: OkHttpClient,
) : ObjectStorage {
  override suspend fun listBuckets(): List<BucketItem> = withClient { client ->
    client.listBuckets().buckets.orEmpty().map { bucket ->
      BucketItem(bucket.name.orEmpty(), bucket.creationDate?.let { Instant.parse(it.toString()) })
    }
  }

  override suspend fun listObjects(bucket: String, prefix: String): List<ObjectItem> = withClient { client ->
    val result = mutableListOf<ObjectItem>()
    var token: String? = null
    do {
      val response = client.listObjectsV2 {
        this.bucket = bucket
        this.prefix = prefix
        delimiter = "/"
        continuationToken = token
      }
      result += response.commonPrefixes.orEmpty().mapNotNull { item ->
        item.prefix?.takeIf { it != prefix }?.let { key ->
          ObjectItem(key = key, name = key.removePrefix(prefix).removeSuffix("/"), isFolder = true)
        }
      }
      result += response.contents.orEmpty().mapNotNull { item ->
        val key = item.key ?: return@mapNotNull null
        if (key == prefix) return@mapNotNull null
        ObjectItem(
          key = key,
          name = key.removePrefix(prefix),
          isFolder = false,
          size = item.size ?: 0,
          lastModified = item.lastModified?.let { Instant.parse(it.toString()) },
          etag = item.eTag,
        )
      }
      token = response.nextContinuationToken
    } while (token != null)
    result.sortedWith(compareByDescending<ObjectItem> { it.isFolder }.thenBy { it.name.lowercase() })
  }

  override suspend fun getObjectDetails(bucket: String, key: String): ObjectDetails = withClient { client ->
    val response = client.headObject {
      this.bucket = bucket
      this.key = key
    }
    ObjectDetails(
      key = key,
      size = response.contentLength ?: 0,
      contentType = response.contentType,
      lastModified = response.lastModified?.let { Instant.parse(it.toString()) },
      etag = response.eTag,
      cacheControl = response.cacheControl,
      contentDisposition = response.contentDisposition,
      metadata = response.metadata.orEmpty().toSortedMap(),
    )
  }

  override suspend fun createPreviewUrl(bucket: String, key: String, expiryMinutes: Int): String = withClient { client ->
    client.presignGetObject(
      GetObjectRequest {
        this.bucket = bucket
        this.key = key
      },
      expiryMinutes.minutes,
    ).url.toString()
  }

  override suspend fun upload(bucket: String, request: UploadObject, source: UploadSource) {
    val signed = withClient { client ->
      client.presignPutObject(
        PutObjectRequest {
          this.bucket = bucket
          key = request.key
          contentType = request.contentType
          contentLength = request.contentLength
          metadata = request.metadata
        },
        TRANSFER_URL_EXPIRY_MINUTES.minutes,
      )
    }
    val body = object : RequestBody() {
      override fun contentType() = request.contentType.toMediaTypeOrNull()

      override fun contentLength() = request.contentLength

      override fun writeTo(sink: BufferedSink) {
        source.open().use { input -> input.copyTo(sink.outputStream()) }
      }
    }
    val builder = Request.Builder().url(signed.url.toString()).put(body)
    signed.headers.forEach { key, values -> builder.header(key, values.joinToString(", ")) }
    withContext(Dispatchers.IO) { execute(builder.build()) { } }
  }

  override suspend fun download(bucket: String, key: String, output: OutputStream) {
    val signed = withClient { client ->
      client.presignGetObject(
        GetObjectRequest {
          this.bucket = bucket
          this.key = key
        },
        TRANSFER_URL_EXPIRY_MINUTES.minutes,
      )
    }
    val builder = Request.Builder().url(signed.url.toString()).get()
    signed.headers.forEach { name, values -> builder.header(name, values.joinToString(", ")) }
    withContext(Dispatchers.IO) {
      execute(builder.build()) { response -> response.body.byteStream().use { it.copyTo(output) } }
    }
  }

  override suspend fun objectExists(bucket: String, key: String): Boolean = withClient { client ->
    client.listObjectsV2 {
      this.bucket = bucket
      prefix = key
      maxKeys = 1
    }.contents.orEmpty().any { it.key == key }
  }

  override suspend fun createFolder(bucket: String, key: String) {
    val folderKey = key.trim('/').plus('/')
    upload(
      bucket,
      UploadObject(folderKey, "application/x-directory", 0, emptyMap()),
      UploadSource { ByteArrayInputStream(ByteArray(0)) },
    )
  }

  override suspend fun listObjectKeys(
    bucket: String,
    prefix: String,
    shouldCancel: () -> Boolean,
  ): List<String> = withClient { client ->
    val keys = mutableListOf<String>()
    var token: String? = null
    do {
      if (shouldCancel()) throw DeleteCanceledException()
      val response = client.listObjectsV2 {
        this.bucket = bucket
        this.prefix = prefix
        continuationToken = token
      }
      keys += response.contents.orEmpty().mapNotNull { it.key }
      token = response.nextContinuationToken
    } while (token != null)
    if (shouldCancel()) throw DeleteCanceledException()
    keys
  }

  override suspend fun deleteObjects(
    bucket: String,
    keys: List<String>,
    shouldCancel: () -> Boolean,
    onProgress: (completed: Int, total: Int) -> Unit,
  ) = withClient { client ->
    val distinctKeys = keys.distinct()
    onProgress(0, distinctKeys.size)
    var completed = 0
    distinctKeys.chunked(DELETE_BATCH_SIZE).forEach { chunk ->
      if (shouldCancel()) throw DeleteCanceledException()
      val response = client.deleteObjects {
        this.bucket = bucket
        delete = Delete {
          objects = chunk.map { objectKey -> ObjectIdentifier { key = objectKey } }
          quiet = true
        }
      }
      require(response.errors.orEmpty().isEmpty()) {
        response.errors.orEmpty().joinToString { error -> "${error.key}: ${error.message}" }
      }
      completed += chunk.size
      onProgress(completed, distinctKeys.size)
      if (shouldCancel()) throw DeleteCanceledException()
    }
  }

  override suspend fun moveObjects(bucket: String, keys: List<String>, destinationPrefix: String) =
    withClient { client ->
      val prefix = destinationPrefix.trim('/').let { if (it.isEmpty()) "" else "$it/" }
      val moves = keys.distinct().map { source -> source to prefix + source.substringAfterLast('/') }
      require(moves.none { (source, target) -> source == target }) { "Source and destination are the same" }
      moves.forEach { (_, target) ->
        val exists = client.listObjectsV2 {
          this.bucket = bucket
          this.prefix = target
          maxKeys = 1
        }.contents.orEmpty().any { it.key == target }
        require(!exists) { "Object already exists: $target" }
      }
      moves.forEach { (source, target) ->
        client.copyObject {
          this.bucket = bucket
          key = target
          copySource = "$bucket/${source.encodePath()}"
        }
        client.deleteObject {
          this.bucket = bucket
          key = source
        }
      }
    }

  override suspend fun updateObject(bucket: String, request: UpdateObject) = withClient { client ->
    if (request.sourceKey != request.targetKey) {
      val exists = client.listObjectsV2 {
        this.bucket = bucket
        prefix = request.targetKey
        maxKeys = 1
      }.contents.orEmpty().any { it.key == request.targetKey }
      require(!exists) { "Object already exists: ${request.targetKey}" }
    }
    client.copyObject {
      this.bucket = bucket
      key = request.targetKey
      copySource = "$bucket/${request.sourceKey.encodePath()}"
      metadataDirective = MetadataDirective.Replace
      metadata = request.metadata
      contentType = request.contentType
      cacheControl = request.cacheControl
      contentDisposition = request.contentDisposition
    }
    if (request.sourceKey != request.targetKey) {
      client.deleteObject {
        this.bucket = bucket
        key = request.sourceKey
      }
    }
    Unit
  }

  private suspend fun <T> withClient(block: suspend (S3Client) -> T): T =
    withContext(Dispatchers.IO) {
      createClient().use { client -> block(client) }
    }

  private fun createClient() = S3Client {
    region = profile.region
    profile.endpointOverride?.let { endpointUrl = Url.parse(it) }
    callTimeout = 30.seconds
    httpClient {
      connectTimeout = 10.seconds
      connectionAcquireTimeout = 10.seconds
      socketReadTimeout = 20.seconds
      socketWriteTimeout = 20.seconds
    }
    credentialsProvider =
      StaticCredentialsProvider(Credentials(profile.accessKeyId, profile.secretAccessKey))
  }

  private fun execute(request: Request, block: (okhttp3.Response) -> Unit) {
    httpClient.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        val details = response.body.string().take(500)
        throw IOException("S3 request failed (${response.code}): $details")
      }
      block(response)
    }
  }

  private fun String.encodePath(): String =
    split('/').joinToString("/") { URLEncoder.encode(it, StandardCharsets.UTF_8.name()).replace("+", "%20") }

  private companion object {
    const val DELETE_BATCH_SIZE = 100
    const val TRANSFER_URL_EXPIRY_MINUTES = 15
  }
}
