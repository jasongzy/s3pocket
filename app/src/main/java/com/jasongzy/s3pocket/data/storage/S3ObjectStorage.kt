package com.jasongzy.s3pocket.data.storage

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.copyObject
import aws.sdk.kotlin.services.s3.abortMultipartUpload
import aws.sdk.kotlin.services.s3.completeMultipartUpload
import aws.sdk.kotlin.services.s3.createMultipartUpload
import aws.sdk.kotlin.services.s3.deleteObject
import aws.sdk.kotlin.services.s3.deleteObjects
import aws.sdk.kotlin.services.s3.headObject
import aws.sdk.kotlin.services.s3.listObjectsV2
import aws.sdk.kotlin.services.s3.model.Delete
import aws.sdk.kotlin.services.s3.model.CompletedMultipartUpload
import aws.sdk.kotlin.services.s3.model.CompletedPart
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.MetadataDirective
import aws.sdk.kotlin.services.s3.model.ObjectIdentifier
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.model.UploadPartRequest
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.sdk.kotlin.services.s3.presigners.presignPutObject
import aws.sdk.kotlin.services.s3.presigners.presignUploadPart
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.net.url.Url
import aws.smithy.kotlin.runtime.ServiceException
import com.jasongzy.s3pocket.data.model.BucketItem
import com.jasongzy.s3pocket.data.model.ObjectDetails
import com.jasongzy.s3pocket.data.model.ObjectItem
import com.jasongzy.s3pocket.data.model.StorageProfile
import com.jasongzy.s3pocket.data.model.UploadObject
import com.jasongzy.s3pocket.data.model.UpdateObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.io.InputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

class S3ObjectStorage(
  private val profile: StorageProfile,
  private val httpClient: OkHttpClient,
  private val regionResolver: BucketRegionResolver,
  private val capabilities: StorageCapabilityCache,
) : ObjectStorage {
  override suspend fun listBuckets(): List<BucketItem> = withClient { client ->
    client.listBuckets().buckets.orEmpty().map { bucket ->
      BucketItem(bucket.name.orEmpty(), bucket.creationDate?.let { Instant.parse(it.toString()) })
    }
  }

  override suspend fun listObjects(
    bucket: String,
    prefix: String,
    continuationToken: String?,
    pageSize: Int,
  ): ObjectPage = withClient(bucket) { client ->
    require(pageSize in 1..1000) { "Page size must be between 1 and 1000" }
    val response = client.listObjectsV2 {
      this.bucket = bucket
      this.prefix = prefix
      delimiter = "/"
      this.continuationToken = continuationToken
      maxKeys = pageSize
    }
    val result =
      response.commonPrefixes.orEmpty().mapNotNull { item ->
        item.prefix?.takeIf { it != prefix }?.let { key ->
          ObjectItem(key = key, name = key.removePrefix(prefix).removeSuffix("/"), isFolder = true)
        }
      } + response.contents.orEmpty().mapNotNull { item ->
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
    ObjectPage(
      items = result.distinctBy(ObjectItem::key),
      nextToken = response.nextContinuationToken?.takeIf {
        response.isTruncated == true && it != continuationToken
      },
    )
  }

  override suspend fun getObjectDetails(bucket: String, key: String): ObjectDetails = withClient(bucket) { client ->
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

  override suspend fun createPreviewUrl(bucket: String, key: String, expiryMinutes: Int): String = withClient(bucket) { client ->
    client.presignGetObject(
      GetObjectRequest {
        this.bucket = bucket
        this.key = key
      },
      expiryMinutes.minutes,
    ).url.toString()
  }

  override suspend fun upload(
    bucket: String,
    request: UploadObject,
    source: UploadSource,
    shouldCancel: () -> Boolean,
    onProgress: (Long, Long) -> Unit,
  ) {
    if (request.contentLength >= MULTIPART_THRESHOLD_BYTES && capabilities.supportsMultipart(profile)) {
      try {
        uploadMultipart(bucket, request, source, shouldCancel, onProgress)
        return
      } catch (error: ServiceException) {
        if (error.sdkErrorMetadata.errorCode !in UNSUPPORTED_MULTIPART_ERRORS) throw error
        capabilities.markMultipartUnsupported(profile)
      }
    }
    val signed = withClient(bucket) { client ->
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
    val body = progressRequestBody(request.contentType, request.contentLength, source::open, shouldCancel, onProgress)
    val builder = Request.Builder().url(signed.url.toString()).put(body)
    signed.headers.forEach { key, values -> builder.header(key, values.joinToString(", ")) }
    withContext(Dispatchers.IO) { execute(builder.build()) { } }
  }

  override suspend fun download(
    bucket: String,
    key: String,
    output: OutputStream,
    shouldCancel: () -> Boolean,
    onProgress: (Long, Long) -> Unit,
  ) {
    val signed = withClient(bucket) { client ->
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
    withContext(Dispatchers.IO + NonCancellable) {
      execute(builder.build()) { response ->
        val total = response.body.contentLength().coerceAtLeast(0)
        response.body.byteStream().use { input ->
          input.copyTo(output, shouldCancel) { completed -> onProgress(completed, total) }
        }
      }
    }
  }

  private suspend fun uploadMultipart(
    bucket: String,
    request: UploadObject,
    source: UploadSource,
    shouldCancel: () -> Boolean,
    onProgress: (Long, Long) -> Unit,
  ) = withClient(bucket) { client ->
    val created = client.createMultipartUpload {
      this.bucket = bucket
      key = request.key
      contentType = request.contentType
      metadata = request.metadata
    }
    val uploadId = requireNotNull(created.uploadId) { "S3 did not return a multipart upload ID" }
    try {
      val parts = mutableListOf<CompletedPart>()
      var completedBytes = 0L
      source.open().use { input ->
        var partNumber = 1
        while (true) {
          if (shouldCancel()) throw TransferCanceledException()
          val bytes = input.readPart(MULTIPART_PART_SIZE_BYTES)
          if (bytes.isEmpty()) break
          val signed = client.presignUploadPart(
            UploadPartRequest {
              this.bucket = bucket
              key = request.key
              this.uploadId = uploadId
              this.partNumber = partNumber
              contentLength = bytes.size.toLong()
            },
            TRANSFER_URL_EXPIRY_MINUTES.minutes,
          )
          val body = progressRequestBody(
            request.contentType,
            bytes.size.toLong(),
            { ByteArrayInputStream(bytes) },
            shouldCancel,
          ) { partCompleted, _ -> onProgress(completedBytes + partCompleted, request.contentLength) }
          val builder = Request.Builder().url(signed.url.toString()).put(body)
          signed.headers.forEach { name, values -> builder.header(name, values.joinToString(", ")) }
          val etag = execute(builder.build()) { response ->
            requireNotNull(response.header("ETag")) { "S3 multipart response did not include an ETag" }
          }
          parts += CompletedPart { eTag = etag; this.partNumber = partNumber }
          completedBytes += bytes.size
          onProgress(completedBytes, request.contentLength)
          partNumber++
        }
      }
      client.completeMultipartUpload {
        this.bucket = bucket
        key = request.key
        this.uploadId = uploadId
        multipartUpload = CompletedMultipartUpload { this.parts = parts }
      }
    } catch (error: Throwable) {
      withContext(NonCancellable) {
        runCatching {
          client.abortMultipartUpload {
            this.bucket = bucket
            key = request.key
            this.uploadId = uploadId
          }
        }
      }
      throw error
    }
  }

  override suspend fun objectExists(bucket: String, key: String): Boolean = withClient(bucket) { client ->
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
  ): List<String> = withClient(bucket) { client ->
    val keys = mutableListOf<String>()
    var token: String? = null
    do {
      if (shouldCancel()) throw StorageOperationCanceledException()
      val response = client.listObjectsV2 {
        this.bucket = bucket
        this.prefix = prefix
        continuationToken = token
      }
      keys += response.contents.orEmpty().mapNotNull { it.key }
      token = response.nextContinuationToken
    } while (token != null)
    if (shouldCancel()) throw StorageOperationCanceledException()
    keys
  }

  override suspend fun deleteObjects(
    bucket: String,
    keys: List<String>,
    shouldCancel: () -> Boolean,
    onProgress: (completed: Int, total: Int) -> Unit,
  ) = withClient(bucket) { client ->
    val distinctKeys = keys.distinct()
    onProgress(0, distinctKeys.size)
    var completed = 0
    distinctKeys.chunked(DELETE_BATCH_SIZE).forEach { chunk ->
      if (shouldCancel()) throw StorageOperationCanceledException()
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
      if (shouldCancel()) throw StorageOperationCanceledException()
    }
  }

  override suspend fun moveObjects(
    bucket: String,
    moves: List<ObjectMove>,
    shouldCancel: () -> Boolean,
    onProgress: (completed: Int, total: Int) -> Unit,
  ) =
    withClient(bucket) { client ->
      val distinctMoves = moves.distinctBy { it.sourceKey }
      require(distinctMoves.map { it.targetKey }.distinct().size == distinctMoves.size) { "Duplicate destination object" }
      require(distinctMoves.none { it.sourceKey == it.targetKey }) { "Source and destination are the same" }
      onProgress(0, distinctMoves.size)
      distinctMoves.forEach { move ->
        if (shouldCancel()) throw StorageOperationCanceledException()
        val exists = client.listObjectsV2 {
          this.bucket = bucket
          prefix = move.targetKey
          maxKeys = 1
        }.contents.orEmpty().any { it.key == move.targetKey }
        require(!exists) { "Object already exists: ${move.targetKey}" }
      }
      distinctMoves.forEachIndexed { index, move ->
        if (shouldCancel()) throw StorageOperationCanceledException()
        client.copyObject {
          this.bucket = bucket
          key = move.targetKey
          copySource = "$bucket/${move.sourceKey.encodePath()}"
        }
        client.deleteObject {
          this.bucket = bucket
          key = move.sourceKey
        }
        onProgress(index + 1, distinctMoves.size)
      }
    }

  override suspend fun updateObject(bucket: String, request: UpdateObject) = withClient(bucket) { client ->
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

  private suspend fun <T> withClient(bucket: String? = null, block: suspend (S3Client) -> T): T =
    withContext(Dispatchers.IO + NonCancellable) {
      val region = bucket?.let { regionResolver.resolve(profile, it) } ?: profile.region
      createClient(region).use { client -> block(client) }
    }

  private fun createClient(resolvedRegion: String) = S3Client {
    region = resolvedRegion
    profile.endpointOverride?.let { endpointUrl = Url.parse(it) }
    forcePathStyle = profile.forcePathStyle
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

  private fun <T> execute(request: Request, block: (okhttp3.Response) -> T): T {
    httpClient.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        val details = response.body.string().take(500)
        throw IOException("S3 request failed (${response.code}): $details")
      }
      return block(response)
    }
  }

  private fun progressRequestBody(
    contentType: String,
    contentLength: Long,
    openSource: () -> InputStream,
    shouldCancel: () -> Boolean,
    onProgress: (Long, Long) -> Unit,
  ) = object : RequestBody() {
    override fun contentType() = contentType.toMediaTypeOrNull()

    override fun contentLength() = contentLength

    override fun writeTo(sink: BufferedSink) {
      openSource().use { input ->
        input.copyTo(sink.outputStream(), shouldCancel) { completed -> onProgress(completed, contentLength) }
      }
    }
  }

  private fun InputStream.copyTo(
    output: OutputStream,
    shouldCancel: () -> Boolean,
    onProgress: (Long) -> Unit,
  ) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var completed = 0L
    while (true) {
      if (shouldCancel()) throw TransferCanceledException()
      val count = read(buffer)
      if (count < 0) break
      output.write(buffer, 0, count)
      completed += count
      onProgress(completed)
    }
  }

  private fun InputStream.readPart(maxBytes: Int): ByteArray {
    val buffer = ByteArray(maxBytes)
    var count = 0
    while (count < maxBytes) {
      val read = read(buffer, count, maxBytes - count)
      if (read < 0) break
      count += read
    }
    return if (count == buffer.size) buffer else buffer.copyOf(count)
  }

  private fun String.encodePath(): String =
    split('/').joinToString("/") { URLEncoder.encode(it, StandardCharsets.UTF_8.name()).replace("+", "%20") }

  private companion object {
    const val DELETE_BATCH_SIZE = 100
    const val TRANSFER_URL_EXPIRY_MINUTES = 15
    const val MULTIPART_THRESHOLD_BYTES = 16L * 1024 * 1024
    const val MULTIPART_PART_SIZE_BYTES = 8 * 1024 * 1024
    val UNSUPPORTED_MULTIPART_ERRORS = setOf("NotImplemented", "UnsupportedOperation", "InvalidRequest")
  }
}
