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
import aws.smithy.kotlin.runtime.client.ProtocolRequestInterceptorContext
import aws.smithy.kotlin.runtime.http.engine.okhttp.OkHttpEngine
import aws.smithy.kotlin.runtime.http.interceptors.HttpInterceptor
import aws.smithy.kotlin.runtime.http.request.HttpRequest
import aws.smithy.kotlin.runtime.http.request.toBuilder
import com.jasongzy.s3pocket.data.model.BucketItem
import com.jasongzy.s3pocket.data.model.ObjectDetails
import com.jasongzy.s3pocket.data.model.ObjectItem
import com.jasongzy.s3pocket.data.model.StorageProfile
import com.jasongzy.s3pocket.data.model.UploadObject
import com.jasongzy.s3pocket.data.model.UpdateObject
import com.jasongzy.s3pocket.data.useResponse
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.io.InputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSink

class S3ObjectStorage(
  private val profile: StorageProfile,
  private val httpClient: OkHttpClient,
  private val regionResolver: BucketRegionResolver,
  private val capabilities: StorageCapabilityCache,
  private val onObjectsChanged: (String) -> Unit,
) : ObjectStorage {
  private val sdkHttpClient = httpClient.newBuilder()
    .followRedirects(false)
    .followSslRedirects(false)
    .connectTimeout(10, SECONDS)
    .readTimeout(20, SECONDS)
    .writeTimeout(20, SECONDS)
    .addInterceptor { chain ->
      // Buffer SDK metadata responses before its cancellation handler can close an active socket read.
      chain.proceed(chain.request()).use { response ->
        val body = response.body
        response.newBuilder().body(body.bytes().toResponseBody(body.contentType())).build()
      }
    }
    .build()

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
          ObjectItem(key = key, name = key.removePrefix(prefix).removeSuffix("/").ifEmpty { "/" }, isFolder = true)
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
      nextToken = nextPageToken(response.isTruncated, response.nextContinuationToken, continuationToken),
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
      metadata = response.metadata.orEmpty().mapValues { decodeMetadataValue(it.value) }.toSortedMap(),
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
          metadata = request.metadata.mapValues { encodeMetadataValue(it.value) }
          ifNoneMatch = "*".takeUnless { request.overwrite }
        },
        TRANSFER_URL_EXPIRY_MINUTES.minutes,
      )
    }
    val body = progressRequestBody(request.contentType, request.contentLength, source::open, shouldCancel, onProgress)
    val builder = Request.Builder().url(signed.url.toString()).put(body)
    signed.headers.forEach { key, values -> builder.header(key, values.joinToString(", ")) }
    withContext(Dispatchers.IO) { execute(builder.build()) { onObjectsChanged(bucket) } }
  }

  override suspend fun download(
    bucket: String,
    key: String,
    output: OutputStream,
    decodeContentEncoding: Boolean,
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
    if (!decodeContentEncoding) builder.header("Accept-Encoding", "identity")
    withContext(Dispatchers.IO) {
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
      metadata = request.metadata.mapValues { encodeMetadataValue(it.value) }
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
          check(completedBytes + bytes.size <= request.contentLength) { "Upload source is longer than its declared size" }
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
      check(completedBytes == request.contentLength) { "Upload source is shorter than its declared size" }
      if (shouldCancel()) throw TransferCanceledException()
      client.completeMultipartUpload {
        this.bucket = bucket
        key = request.key
        this.uploadId = uploadId
        multipartUpload = CompletedMultipartUpload { this.parts = parts }
        ifNoneMatch = "*".takeUnless { request.overwrite }
      }
      onObjectsChanged(bucket)
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

  override suspend fun objectExists(bucket: String, key: String): Boolean =
    withClient(bucket) { it.objectExists(bucket, key) }

  override suspend fun existingObjectKeys(
    bucket: String,
    keys: List<String>,
    shouldCancel: () -> Boolean,
  ): Set<String> = withClient(bucket) { client ->
    val found = mutableSetOf<String>()
    keys.distinct().groupBy { it.substring(0, it.lastIndexOf('/') + 1) }
      .forEach { (parent, candidates) ->
        if (shouldCancel()) throw StorageOperationCanceledException()
        if (candidates.size == 1) {
          if (client.objectExists(bucket, candidates.single())) found += candidates.single()
        } else {
          val page = client.listObjectsV2 {
            this.bucket = bucket
            prefix = parent
            delimiter = "/"
            maxKeys = 1000
          }
          val candidateSet = candidates.toSet()
          found += page.contents.orEmpty().mapNotNull { it.key }.filter { it in candidateSet }
          if (page.isTruncated == true) {
            candidates.filterNot { it in found }.chunked(4).forEach { chunk ->
              if (shouldCancel()) throw StorageOperationCanceledException()
              found += coroutineScope {
                chunk.map { key -> async { key.takeIf { client.objectExists(bucket, it) } } }.awaitAll().filterNotNull()
              }
            }
          }
        }
      }
    if (shouldCancel()) throw StorageOperationCanceledException()
    found
  }

  override suspend fun createFolder(bucket: String, key: String) {
    require(key.isNotBlank()) { "Invalid folder name" }
    val folderKey = if (key.endsWith('/')) key else "$key/"
    upload(
      bucket,
      UploadObject(folderKey, "application/x-directory", 0, emptyMap()),
      UploadSource { ByteArrayInputStream(ByteArray(0)) },
    )
  }

  override suspend fun scanObjectKeys(
    bucket: String,
    prefix: String,
    shouldCancel: () -> Boolean,
    onPage: suspend (List<String>) -> Unit,
  ) = withClient(bucket) { client ->
    var token: String? = null
    do {
      if (shouldCancel()) throw StorageOperationCanceledException()
      val response = client.listObjectsV2 {
        this.bucket = bucket
        this.prefix = prefix
        continuationToken = token
      }
      token = nextPageToken(response.isTruncated, response.nextContinuationToken, token)
      onPage(response.contents.orEmpty().mapNotNull { it.key })
    } while (token != null)
    if (shouldCancel()) throw StorageOperationCanceledException()
  }

  override suspend fun deleteObjects(
    bucket: String,
    keys: List<String>,
    shouldCancel: () -> Boolean,
    onDeleted: suspend (List<String>) -> Unit,
  ) = withClient(bucket) { client ->
    keys.distinct().chunked(DELETE_BATCH_SIZE).forEach { chunk ->
      if (shouldCancel()) throw StorageOperationCanceledException()
      client.deleteBatch(bucket, chunk, onDeleted)
    }
  }

  override suspend fun deletePrefix(
    bucket: String,
    prefix: String,
    shouldCancel: () -> Boolean,
    onDeleted: suspend (List<String>) -> Unit,
  ) = withClient(bucket) { client ->
    require(prefix.isNotEmpty() && prefix.endsWith('/')) { "Invalid folder prefix" }
    while (true) {
      if (shouldCancel()) throw StorageOperationCanceledException()
      val keys = client.listObjectsV2 {
        this.bucket = bucket
        this.prefix = prefix
        maxKeys = DELETE_BATCH_SIZE
      }.contents.orEmpty().mapNotNull { it.key }
      if (shouldCancel()) throw StorageOperationCanceledException()
      if (keys.isEmpty()) break
      client.deleteBatch(bucket, keys, onDeleted)
    }
  }

  override suspend fun moveObjects(
    bucket: String,
    moves: List<ObjectMove>,
    shouldCancel: () -> Boolean,
    onMoved: suspend (ObjectMove) -> Unit,
  ) =
    withClient(bucket) { client ->
      val distinctMoves = moves.distinctBy { it.sourceKey }
      require(distinctMoves.map { it.targetKey }.distinct().size == distinctMoves.size) { "Duplicate destination object" }
      require(distinctMoves.none { it.sourceKey == it.targetKey }) { "Source and destination are the same" }
      distinctMoves.forEach { move ->
        if (shouldCancel()) throw StorageOperationCanceledException()
        client.moveObject(bucket, move)
        onMoved(move)
      }
    }

  override suspend fun validateMoves(bucket: String, moves: List<ObjectMove>, shouldCancel: () -> Boolean) {
    val existing = existingObjectKeys(bucket, moves.map { it.targetKey }, shouldCancel)
    require(existing.isEmpty()) { "Object already exists: ${existing.first()}" }
  }

  override suspend fun movePrefix(
    bucket: String,
    sourcePrefix: String,
    targetPrefix: String,
    shouldCancel: () -> Boolean,
    onMoved: suspend (ObjectMove) -> Unit,
  ) = withClient(bucket) { client ->
    require(sourcePrefix.isNotEmpty() && sourcePrefix.endsWith('/')) { "Invalid folder prefix" }
    require(targetPrefix.isNotEmpty() && targetPrefix.endsWith('/')) { "Invalid folder prefix" }
    require(!targetPrefix.startsWith(sourcePrefix) && !sourcePrefix.startsWith(targetPrefix)) {
      "Source and destination folders overlap"
    }
    while (true) {
      if (shouldCancel()) throw StorageOperationCanceledException()
      val keys = client.listObjectsV2 {
        this.bucket = bucket
        prefix = sourcePrefix
        maxKeys = 1000
      }.contents.orEmpty().mapNotNull { it.key }
      if (keys.isEmpty()) break
      folderMoves(keys, sourcePrefix, targetPrefix).forEach { move ->
        if (shouldCancel()) throw StorageOperationCanceledException()
        client.moveObject(bucket, move)
        onMoved(move)
      }
    }
  }

  override suspend fun updateObject(bucket: String, request: UpdateObject) = withClient(bucket) { client ->
    if (request.sourceKey != request.targetKey) {
      require(!client.objectExists(bucket, request.targetKey)) { "Object already exists: ${request.targetKey}" }
    }
    client.moveObject(bucket, ObjectMove(request.sourceKey, request.targetKey), request)
  }

  private suspend fun <T> withClient(bucket: String? = null, block: suspend (S3Client) -> T): T =
    withContext(Dispatchers.IO) {
      val region = bucket?.let { regionResolver.resolve(profile, it) } ?: profile.region
      OkHttpEngine(sdkHttpClient).use { engine ->
        createClient(region, engine).use { client -> block(client) }
      }
    }

  private fun createClient(resolvedRegion: String, engine: OkHttpEngine) = S3Client {
    region = resolvedRegion
    profile.endpointOverride?.let { endpointUrl = Url.parse(it) }
    forcePathStyle = profile.forcePathStyle
    interceptors += R2CopyConditions
    callTimeout = 30.seconds
    httpClient = engine
    credentialsProvider =
      StaticCredentialsProvider(Credentials(profile.accessKeyId, profile.secretAccessKey))
  }

  private suspend fun S3Client.objectExists(bucket: String, key: String): Boolean =
    listObjectsV2 {
      this.bucket = bucket
      prefix = key
      maxKeys = 1
    }.contents.orEmpty().any { it.key == key }

  private suspend fun S3Client.moveObject(bucket: String, move: ObjectMove, update: UpdateObject? = null) {
    val renamed = move.sourceKey != move.targetKey
    val source = headObject { this.bucket = bucket; key = move.sourceKey }
    val etag = requireNotNull(source.eTag?.takeIf(String::isNotBlank)) { "S3 did not return a source ETag" }
    val copied = copyObject {
      this.bucket = bucket
      key = move.targetKey
      copySource = "$bucket/${move.sourceKey.encodePath()}"
      copySourceIfMatch = etag
      ifNoneMatch = "*".takeIf { renamed }
      ifMatch = etag.takeUnless { renamed }
      websiteRedirectLocation = source.websiteRedirectLocation
      if (update != null) {
        metadataDirective = MetadataDirective.Replace
        metadata = update.metadata.mapValues { encodeMetadataValue(it.value) }
        contentType = update.contentType
        cacheControl = update.cacheControl
        contentDisposition = update.contentDisposition
        contentEncoding = source.contentEncoding
        contentLanguage = source.contentLanguage
        expires = source.expiresString?.let(aws.smithy.kotlin.runtime.time.Instant::fromRfc5322)
      }
    }
    check(!copied.copyObjectResult?.eTag.isNullOrBlank()) { "S3 did not confirm the copied object" }
    try {
      if (renamed) {
        deleteObject { this.bucket = bucket; key = move.sourceKey; ifMatch = etag }
      }
    } finally {
      onObjectsChanged(bucket)
    }
  }

  private suspend fun S3Client.deleteBatch(bucket: String, keys: List<String>, onDeleted: suspend (List<String>) -> Unit) {
    val response = deleteObjects {
      this.bucket = bucket
      delete = Delete {
        objects = keys.map { objectKey -> ObjectIdentifier { key = objectKey } }
        quiet = true
      }
    }
    val errors = response.errors.orEmpty()
    val failedKeys = errors.mapTo(mutableSetOf()) { it.key }
    val deleted = keys.filterNot { it in failedKeys }
    if (deleted.isNotEmpty()) onObjectsChanged(bucket)
    onDeleted(deleted)
    require(errors.isEmpty()) { errors.joinToString { "${it.key}: ${it.code}: ${it.message}" } }
  }

  private suspend fun <T> execute(request: Request, block: (okhttp3.Response) -> T): T =
    httpClient.newCall(request).useResponse { response ->
      if (!response.isSuccessful) {
        val details = response.peekBody(500).string()
        throw IOException("S3 request failed (${response.code}): $details")
      }
      block(response)
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
    val UNSUPPORTED_MULTIPART_ERRORS = setOf("NotImplemented", "UnsupportedOperation")
  }
}

internal object R2CopyConditions : HttpInterceptor {
  override suspend fun modifyBeforeRetryLoop(context: ProtocolRequestInterceptorContext<Any, HttpRequest>): HttpRequest {
    val request = context.protocolRequest
    if (!request.url.host.toString().endsWith(".r2.cloudflarestorage.com") || request.headers["x-amz-copy-source"] == null) {
      return request
    }
    return request.toBuilder().apply {
      request.headers["If-Match"]?.let { headers["cf-copy-destination-if-match"] = it }
      request.headers["If-None-Match"]?.let { headers["cf-copy-destination-if-none-match"] = it }
    }.build()
  }
}

internal fun nextPageToken(truncated: Boolean?, nextToken: String?, currentToken: String?): String? {
  if (truncated != true) return null
  check(!nextToken.isNullOrBlank() && nextToken != currentToken) { "S3 returned an invalid continuation token" }
  return nextToken
}
