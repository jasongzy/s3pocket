package com.jasongzy.s3pocket

import com.jasongzy.s3pocket.data.model.ObjectItem
import com.jasongzy.s3pocket.data.model.StorageProfile
import com.jasongzy.s3pocket.data.model.StorageProvider
import com.jasongzy.s3pocket.data.model.UpdateObject
import com.jasongzy.s3pocket.data.model.UploadObject
import com.jasongzy.s3pocket.data.storage.UploadSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream
import com.jasongzy.s3pocket.data.storage.BucketRegionResolver
import com.jasongzy.s3pocket.data.storage.ObjectMove
import com.jasongzy.s3pocket.data.storage.S3ObjectStorage
import com.jasongzy.s3pocket.data.storage.StorageCapabilityCache
import com.jasongzy.s3pocket.data.storage.StorageOperationCanceledException
import com.jasongzy.s3pocket.ui.buildDeletePlan
import com.jasongzy.s3pocket.ui.buildMovePlan
import com.jasongzy.s3pocket.ui.FolderMove
import com.jasongzy.s3pocket.ui.readTextPreview
import com.jasongzy.s3pocket.data.storage.R2CopyConditions
import aws.smithy.kotlin.runtime.ServiceException
import aws.smithy.kotlin.runtime.client.ProtocolRequestInterceptorContext
import aws.smithy.kotlin.runtime.http.request.HttpRequest
import aws.smithy.kotlin.runtime.operation.ExecutionContext
import aws.smithy.kotlin.runtime.net.url.Url
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class StorageOperationsTest {
  @Test
  fun headPreservesTheCountOfOmittedMetadataFields() = withStorage({ exchange ->
    assertEquals("HEAD", exchange.requestMethod)
    exchange.responseHeaders.add("x-amz-meta-tag", "visible")
    exchange.responseHeaders.add("x-amz-missing-meta", "1")
    exchange.sendResponseHeaders(200, -1)
    exchange.close()
  }) { storage ->
    val details = storage.getObjectDetails("bucket", "file")
    assertEquals(1, details.missingMetadataCount)
    assertEquals(mapOf("tag" to "visible"), details.metadata)
  }

  @Test
  fun listingCancellationClosesResponsesSafely() {
    listOf(false, true).forEach { cancelParent ->
      val reading = CompletableDeferred<Unit>()
      val closed = CompletableDeferred<Unit>()
      val releaseRead = CountDownLatch(1)
      val isReading = AtomicBoolean()
      val concurrentClose = AtomicBoolean()
      val requests = AtomicInteger()
      val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
        val response = chain.proceed(chain.request())
        if (requests.incrementAndGet() != 1) return@addNetworkInterceptor response
        val body = response.body
        val source = object : ForwardingSource(body.source()) {
          override fun read(sink: Buffer, byteCount: Long): Long {
            isReading.set(true)
            reading.complete(Unit)
            try {
              check(releaseRead.await(5, TimeUnit.SECONDS)) { "Read was not released" }
              if (chain.call().isCanceled()) throw IOException("Canceled")
              return super.read(sink, byteCount)
            } finally {
              isReading.set(false)
            }
          }

          override fun close() {
            if (isReading.get()) concurrentClose.set(true)
            check(!isReading.get()) { "Response body closed during a read" }
            super.close()
            closed.complete(Unit)
          }
        }
        response.newBuilder().body(source.buffer().asResponseBody(body.contentType(), body.contentLength())).build()
      }.build()
      try {
        withStorage({ exchange ->
          exchange.respond("<ListBucketResult><Contents><Key>file</Key></Contents></ListBucketResult>")
        }, client = client) { storage ->
          coroutineScope {
            val screen = Job(coroutineContext[Job])
            try {
              val listing = CoroutineScope(coroutineContext + screen).launch { storage.listObjects("bucket", "") }
              withTimeout(5_000) {
                reading.await()
                try {
                  if (cancelParent) screen.cancel() else listing.cancel()
                } finally {
                  releaseRead.countDown()
                }
                assertFalse(concurrentClose.get())
                listing.join()
                closed.await()
              }
              assertTrue(listing.isCancelled)
              assertEquals(listOf("file"), storage.listObjects("bucket", "").items.map { it.key })
            } finally {
              releaseRead.countDown()
              screen.cancelAndJoin()
            }
          }
        }
      } finally {
        releaseRead.countDown()
      }
    }
  }

  @Test
  fun browsingAndRejectedUploadsKeepListingsUnchanged() {
    val changedBuckets = mutableListOf<String>()
    var rejectUpload = true
    withStorage({ exchange ->
      when (exchange.requestMethod) {
        "GET" -> exchange.respond(if (exchange.requestURI.query.orEmpty().contains("list-type")) "<ListBucketResult/>" else "file")
        "HEAD" -> {
          exchange.responseHeaders.add("ETag", "\"source\"")
          exchange.sendResponseHeaders(200, -1)
          exchange.close()
        }
        "PUT" -> {
          if (rejectUpload) exchange.respond("<Error><Code>AccessDenied</Code></Error>", 403)
          else exchange.respond("")
        }
        else -> fail("Unexpected request")
      }
    }, changedBuckets::add) { storage ->
      storage.listObjects("bucket", "")
      storage.getObjectDetails("bucket", "file")
      storage.createPreviewUrl("bucket", "file", 15)
      storage.download("bucket", "file", ByteArrayOutputStream())
      assertTrue(changedBuckets.isEmpty())
      val upload = UploadObject("file", "text/plain", 1, emptyMap())
      val source = UploadSource { ByteArrayInputStream(byteArrayOf(1)) }
      assertTrue(runCatching { storage.upload("bucket", upload, source) }.isFailure)
      assertTrue(changedBuckets.isEmpty())
      rejectUpload = false
      storage.upload("bucket", upload, source)
      assertEquals(listOf("bucket"), changedBuckets)
    }
  }

  @Test
  fun uploadsEncodeUnicodeMetadata() = withStorage({ exchange ->
    assertEquals("=?UTF-8?B?5Lit5paH?=", exchange.requestHeaders.getFirst("x-amz-meta-note"))
    exchange.respond("")
  }) { storage ->
    storage.upload("bucket", UploadObject("file", "text/plain", 1, mapOf("note" to "中文")),
      UploadSource { ByteArrayInputStream(byteArrayOf(1)) })
  }

  @Test
  fun detailsDecodeUnicodeMetadata() = withStorage({ exchange ->
    exchange.responseHeaders.add("x-amz-meta-note", "=?UTF-8?B?5Lit5paH?=")
    exchange.sendResponseHeaders(200, -1)
    exchange.close()
  }) { storage ->
    assertEquals(mapOf("note" to "中文"), storage.getObjectDetails("bucket", "file").metadata)
  }

  @Test
  fun downloadsPreserveEncodedObjectBytesWhilePreviewsDecodeThem() {
    val text = "compressed object"
    val stored = ByteArrayOutputStream().apply {
      GZIPOutputStream(this).use { it.write(text.toByteArray()) }
    }.toByteArray()
    withStorage({ exchange ->
      exchange.responseHeaders.add("Content-Encoding", "gzip")
      exchange.sendResponseHeaders(200, stored.size.toLong())
      exchange.responseBody.use { it.write(stored) }
    }) { storage ->
      val output = ByteArrayOutputStream()
      storage.download("bucket", "archive.gz", output)
      assertArrayEquals(stored, output.toByteArray())
      assertEquals(text, readTextPreview {
        storage.download("bucket", "archive.gz", it, decodeContentEncoding = true)
      })
    }
  }

  @Test
  fun r2CopiesUseDestinationConditionsWithoutChangingOtherRequests() = runBlocking {
    listOf("account.r2.cloudflarestorage.com", "s3.amazonaws.com").forEach { host ->
      listOf(true, false).forEach { copy ->
        val httpRequest = HttpRequest {
          url.copyFrom(Url.parse("https://$host/bucket/key"))
          headers["If-Match"] = "\"source\""
          headers["If-None-Match"] = "*"
          if (copy) headers["x-amz-copy-source"] = "bucket/source"
        }
        val context = object : ProtocolRequestInterceptorContext<Any, HttpRequest> {
          override val request: Any = Unit
          override val protocolRequest = httpRequest
          override val executionContext = ExecutionContext()
        }
        val result = R2CopyConditions.modifyBeforeRetryLoop(context)
        val r2Copy = copy && host.endsWith(".r2.cloudflarestorage.com")
        assertEquals("\"source\"".takeIf { r2Copy }, result.headers["cf-copy-destination-if-match"])
        assertEquals("*".takeIf { r2Copy }, result.headers["cf-copy-destination-if-none-match"])
      }
    }
  }

  @Test
  fun multipartSizeMismatchAbortsInsteadOfCompleting() {
    val declared = 16 * 1024 * 1024
    listOf(1, declared + 1).forEach { actual ->
      var aborted = false
      withStorage({ exchange ->
        when {
          exchange.requestURI.rawQuery.contains("uploads") ->
            exchange.respond("<InitiateMultipartUploadResult><UploadId>upload</UploadId></InitiateMultipartUploadResult>")
          exchange.requestMethod == "PUT" -> {
            exchange.responseHeaders.add("ETag", "\"part\"")
            exchange.respond("")
          }
          exchange.requestMethod == "DELETE" -> {
            aborted = true
            exchange.respond("")
          }
          else -> fail("Mismatched upload must not complete")
        }
      }) { storage ->
        val result = runCatching {
          storage.upload(
            "bucket", UploadObject("large", "application/octet-stream", declared.toLong(), emptyMap()),
            UploadSource { ByteArrayInputStream(ByteArray(actual)) },
          )
        }
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("declared size"))
      }
      assertTrue(aborted)
    }
  }

  @Test
  fun moveAndRenameRejectConcurrentChanges() {
    listOf(false, true).forEach { rename ->
      listOf("PUT", "DELETE").forEach { conflictAt ->
        var copies = 0
        var deletes = 0
        val changedBuckets = mutableListOf<String>()
        withStorage({ exchange ->
          when (exchange.requestMethod) {
            "GET" -> {
              assertTrue(rename)
              exchange.respond("<ListBucketResult/>")
            }
            "HEAD" -> {
              exchange.responseHeaders.add("ETag", "\"source\"")
              exchange.sendResponseHeaders(200, -1)
              exchange.close()
            }
            "PUT" -> {
              copies++
              assertEquals("*", exchange.requestHeaders.getFirst("If-None-Match"))
              assertEquals("\"source\"", exchange.requestHeaders.getFirst("x-amz-copy-source-if-match"))
              if (conflictAt == "PUT") exchange.respond("<Error><Code>PreconditionFailed</Code></Error>", 412)
              else exchange.respond("<CopyObjectResult><ETag>\"target\"</ETag></CopyObjectResult>")
            }
            "DELETE" -> {
              deletes++
              assertEquals("\"source\"", exchange.requestHeaders.getFirst("If-Match"))
              exchange.respond("<Error><Code>PreconditionFailed</Code></Error>", 412)
            }
            else -> fail("Unexpected request")
          }
        }, changedBuckets::add) { storage ->
          val result = runCatching {
            if (rename) storage.updateObject("bucket", UpdateObject("source", "target", "text/plain", null, null, emptyMap()))
            else storage.moveObjects("bucket", listOf(ObjectMove("source", "target")), { false }) { fail("Move must not complete") }
          }
          assertTrue(result.isFailure)
        }
        assertEquals(1, copies)
        assertEquals(if (conflictAt == "DELETE") 1 else 0, deletes)
        assertEquals(if (conflictAt == "DELETE") listOf("bucket") else emptyList<String>(), changedBuckets)
      }
    }
  }

  @Test
  fun uploadOnlyOverwritesAfterExplicitConfirmation() {
    var uploads = 0
    withStorage({ exchange ->
      assertEquals("PUT", exchange.requestMethod)
      assertEquals(if (uploads++ == 0) "*" else null, exchange.requestHeaders.getFirst("If-None-Match"))
      exchange.respond("")
    }) { storage ->
      val source = UploadSource { ByteArrayInputStream(byteArrayOf(1)) }
      storage.upload("bucket", UploadObject("new", "text/plain", 1, emptyMap()), source)
      storage.upload("bucket", UploadObject("new", "text/plain", 1, emptyMap(), overwrite = true), source)
    }
    assertEquals(2, uploads)
  }

  @Test
  fun multipartUploadPreservesMetadataAndWriteCondition() {
    var completed = false
    val changedBuckets = mutableListOf<String>()
    withStorage({ exchange ->
      when {
        exchange.requestURI.rawQuery.contains("uploads") -> {
          assertEquals("=?UTF-8?B?5Lit5paH?=", exchange.requestHeaders.getFirst("x-amz-meta-note"))
          exchange.respond("<InitiateMultipartUploadResult><UploadId>upload</UploadId></InitiateMultipartUploadResult>")
        }
        exchange.requestMethod == "PUT" -> {
          exchange.responseHeaders.add("ETag", "\"part\"")
          exchange.respond("")
        }
        else -> {
          assertEquals("*", exchange.requestHeaders.getFirst("If-None-Match"))
          completed = true
          exchange.respond("<CompleteMultipartUploadResult><ETag>\"done\"</ETag></CompleteMultipartUploadResult>")
        }
      }
    }, changedBuckets::add) { storage ->
      val bytes = ByteArray(16 * 1024 * 1024)
      storage.upload("bucket", UploadObject("large", "application/octet-stream", bytes.size.toLong(), mapOf("note" to "中文")),
        UploadSource { ByteArrayInputStream(bytes) })
    }
    assertTrue(completed)
    assertEquals(listOf("bucket"), changedBuckets)
  }

  @Test
  fun multipartFallbackOnlyCachesExplicitlyUnsupportedOperations() {
    val bytes = ByteArray(16 * 1024 * 1024)
    listOf("InvalidRequest", "NotImplemented", "UnsupportedOperation").forEach { code ->
      val multipartRequests = AtomicInteger()
      val singleUploads = AtomicInteger()
      val unsupported = code != "InvalidRequest"
      withStorage({ exchange ->
        when (exchange.requestMethod) {
          "POST" -> {
            multipartRequests.incrementAndGet()
            exchange.respond("<Error><Code>$code</Code><Message>Upload rejected</Message></Error>", 400)
          }
          "PUT" -> {
            singleUploads.incrementAndGet()
            assertEquals("*", exchange.requestHeaders.getFirst("If-None-Match"))
            exchange.respond("")
          }
          else -> fail("Unexpected request")
        }
      }) { storage ->
        repeat(2) {
          val result = runCatching {
            storage.upload("bucket", UploadObject("large", "application/octet-stream", bytes.size.toLong(), emptyMap()),
              UploadSource { ByteArrayInputStream(bytes) })
          }
          if (unsupported) result.getOrThrow()
          else {
            val error = result.exceptionOrNull() as? ServiceException
            assertEquals(code, error?.sdkErrorMetadata?.errorCode)
          }
        }
      }
      assertEquals(if (unsupported) 1 else 2, multipartRequests.get())
      assertEquals(if (unsupported) 2 else 0, singleUploads.get())
    }
  }

  @Test
  fun batchConflictCheckListsASharedDirectoryOnce() {
    var requests = 0
    withStorage({ exchange ->
      requests++
      exchange.respond("<ListBucketResult><IsTruncated>false</IsTruncated><Contents><Key>folder/2</Key></Contents></ListBucketResult>")
    }) { storage ->
      assertEquals(setOf("folder/2"), storage.existingObjectKeys("bucket", (1..500).map { "folder/$it" }))
    }
    assertEquals(1, requests)
  }

  @Test
  fun conflictChecksPreserveRootSlashPrefixes() = withStorage({ exchange ->
    assertTrue(exchange.requestURI.query.split('&').contains("prefix=/"))
    exchange.respond("<ListBucketResult><Contents><Key>/first</Key></Contents></ListBucketResult>")
  }) { storage ->
    assertEquals(setOf("/first"), storage.existingObjectKeys("bucket", listOf("/first", "/second")))
  }

  @Test
  fun folderMovePlanPreservesBothPrefixes() = withStorage({ exchange ->
    val params = exchange.requestURI.query.split('&')
    if ("prefix=/reports//" in params) {
      exchange.respond("<ListBucketResult><Contents><Key>/reports//file</Key></Contents></ListBucketResult>")
    } else {
      assertTrue("prefix=/archive//file" in params)
      exchange.respond("<ListBucketResult/>")
    }
  }) { storage ->
    val plan = buildMovePlan(storage, "bucket", listOf(ObjectItem("/reports//", "/", true)), "/archive/") { false }
    assertEquals(listOf(FolderMove("/reports//", "/archive//")), plan.folders)
    assertEquals(1, plan.total)
  }

  @Test
  fun folderMovesRejectAncestorDestinationsBeforeRequests() {
    val requests = AtomicInteger()
    withStorage({ exchange ->
      requests.incrementAndGet()
      exchange.respond("<ListBucketResult/>")
    }) { storage ->
      val plan = runCatching {
        buildMovePlan(storage, "bucket", listOf(ObjectItem("photos/photos/", "photos", true)), "") { false }
      }
      assertTrue(plan.exceptionOrNull() is IllegalArgumentException)
      val move = runCatching {
        storage.movePrefix("bucket", "photos/photos/", "photos/", { false }) {}
      }
      assertTrue(move.exceptionOrNull() is IllegalArgumentException)
    }
    assertEquals(0, requests.get())
  }

  @Test
  fun movePreflightRejectsExistingTargetsBeforeWriting() = withStorage({ exchange ->
    assertEquals("GET", exchange.requestMethod)
    exchange.respond("<ListBucketResult><Contents><Key>target/file</Key></Contents></ListBucketResult>")
  }) { storage ->
    val result = runCatching {
      buildMovePlan(storage, "bucket", listOf(ObjectItem("source/file", "file", false)), "target/") { false }
    }
    assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("already exists"))
    val rename = runCatching {
      storage.updateObject("bucket", UpdateObject("source/file", "target/file", null, null, null, emptyMap()))
    }
    assertTrue(rename.exceptionOrNull()?.message.orEmpty().contains("already exists"))
  }

  @Test
  fun folderPreflightCountsAndChecksTargetsInOnePass() {
    val prefixes = mutableListOf<String>()
    withStorage({ exchange ->
      val prefix = exchange.requestURI.query.split('&').first { it.startsWith("prefix=") }.removePrefix("prefix=")
      prefixes += prefix
      val contents = if (prefix == "source/") (1..500).joinToString("") {
        "<Contents><Key>source/$it</Key></Contents>"
      } else ""
      exchange.respond("<ListBucketResult><IsTruncated>false</IsTruncated>$contents</ListBucketResult>")
    }) { storage ->
      val plan = buildMovePlan(storage, "bucket", listOf(ObjectItem("source/", "source", true)), "archive/") { false }
      assertEquals(500, plan.total)
    }
    assertEquals(listOf("source/", "archive/source/"), prefixes)
  }

  @Test
  fun canceledPreflightStopsBeforeIndividualTargetQueries() {
    var canceled = false
    var requests = 0
    withStorage({ exchange ->
      requests++
      canceled = true
      exchange.respond("<ListBucketResult><IsTruncated>true</IsTruncated></ListBucketResult>")
    }) { storage ->
      val result = runCatching {
        storage.validateMoves("bucket", (1..500).map { ObjectMove("source/$it", "target/$it") }) { canceled }
      }
      assertTrue(result.exceptionOrNull() is StorageOperationCanceledException)
    }
    assertEquals(1, requests)
  }

  @Test
  fun metadataEditPreservesUneditedHeaders() = withStorage({ exchange ->
    when (exchange.requestMethod) {
      "HEAD" -> {
        exchange.responseHeaders.add("Content-Encoding", "gzip")
        exchange.responseHeaders.add("Content-Language", "en")
        exchange.responseHeaders.add("Expires", "Wed, 21 Oct 2037 07:28:00 GMT")
        exchange.responseHeaders.add("x-amz-website-redirect-location", "/new")
        exchange.responseHeaders.add("ETag", "\"source\"")
        exchange.sendResponseHeaders(200, -1)
        exchange.close()
      }
      "PUT" -> {
        assertEquals("\"source\"", exchange.requestHeaders.getFirst("If-Match"))
        assertEquals("\"source\"", exchange.requestHeaders.getFirst("x-amz-copy-source-if-match"))
        assertEquals("gzip", exchange.requestHeaders.getFirst("Content-Encoding"))
        assertEquals("en", exchange.requestHeaders.getFirst("Content-Language"))
        assertNotNull(exchange.requestHeaders.getFirst("Expires"))
        assertEquals("/new", exchange.requestHeaders.getFirst("x-amz-website-redirect-location"))
        assertEquals("=?UTF-8?B?5Lit5paH?=", exchange.requestHeaders.getFirst("x-amz-meta-note"))
        exchange.respond("<CopyObjectResult><ETag>\"target\"</ETag></CopyObjectResult>")
      }
      else -> error("Unexpected request")
    }
  }) { storage ->
    storage.updateObject("bucket", UpdateObject("source", "source", "text/plain", null, null, mapOf("note" to "中文")))
  }

  @Test
  fun mixedDeletionReportsSuccessBeforeFailure() = withStorage({ exchange ->
    exchange.respond("<DeleteResult><Error><Key>denied</Key><Code>AccessDenied</Code><Message>Denied</Message></Error></DeleteResult>")
  }) { storage ->
    val deleted = mutableListOf<String>()
    val result = runCatching {
      storage.deleteObjects("bucket", listOf("ok", "denied"), { false }) { deleted += it }
    }
    assertEquals(listOf("ok"), deleted)
    assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("AccessDenied"))
  }

  @Test
  fun emptyFolderPlanOnlyDeletesItsMarker() = withStorage({ exchange ->
    exchange.respond("<ListBucketResult><IsTruncated>false</IsTruncated><Contents><Key>folder/</Key></Contents></ListBucketResult>")
  }) { storage ->
    val plan = buildDeletePlan(storage, "bucket", listOf(ObjectItem("folder/", "folder", true))) { false }
    assertEquals(listOf("folder/"), plan.fileKeys)
    assertTrue(plan.recursivePrefixes.isEmpty())
    assertFalse(plan.hasNonEmptyFolder)
  }

  @Test
  fun cancelingRecursiveDeletionDuringListingStopsBeforeTheNextWrite() {
    val canceled = AtomicBoolean(false)
    val deletes = AtomicInteger()
    withStorage({ exchange ->
      if (exchange.requestMethod == "GET") {
        canceled.set(true)
        exchange.respond("<ListBucketResult><Contents><Key>folder/file</Key></Contents></ListBucketResult>")
      } else {
        deletes.incrementAndGet()
        exchange.respond("<DeleteResult/>")
      }
    }) { storage ->
      val result = runCatching { storage.deletePrefix("bucket", "folder/", canceled::get) {} }
      assertTrue(result.exceptionOrNull() is StorageOperationCanceledException)
    }
    assertEquals(0, deletes.get())
  }

  @Test
  fun recursiveDeletionKeepsRepeatedAndLeadingSlashes() {
    var listings = 0
    withStorage({ exchange ->
      if (exchange.requestMethod == "GET") {
        assertTrue(exchange.requestURI.query.split('&').contains("prefix=/reports//"))
        val contents = if (listings++ < 2) "<Contents><Key>/reports//file</Key></Contents>" else ""
        exchange.respond("<ListBucketResult><IsTruncated>false</IsTruncated>$contents</ListBucketResult>")
      } else {
        assertEquals("POST", exchange.requestMethod)
        exchange.respond("<DeleteResult/>")
      }
    }) { storage ->
      val plan = buildDeletePlan(storage, "bucket", listOf(ObjectItem("/reports//", "/", true))) { false }
      assertEquals(listOf("/reports//"), plan.recursivePrefixes)
      val deleted = mutableListOf<String>()
      storage.deletePrefix("bucket", plan.recursivePrefixes.single(), { false }) { deleted += it }
      assertEquals(listOf("/reports//file"), deleted)
      assertTrue(runCatching { storage.deletePrefix("bucket", "", { false }) {} }.isFailure)
      assertTrue(runCatching { storage.movePrefix("bucket", "", "target/", { false }) {} }.isFailure)
      assertTrue(runCatching { storage.movePrefix("bucket", "source/", "source/child/", { false }) {} }.isFailure)
    }
  }

  @Test
  fun creatingFolderKeepsItsExactPrefix() = withStorage({ exchange ->
    assertEquals("PUT", exchange.requestMethod)
    assertEquals("/bucket//reports//new/", exchange.requestURI.path)
    exchange.respond("")
  }) { storage ->
    storage.createFolder("bucket", "/reports//new")
  }
}

private fun withStorage(
  handler: (HttpExchange) -> Unit,
  onObjectsChanged: (String) -> Unit = {},
  client: OkHttpClient = OkHttpClient(),
  block: suspend (S3ObjectStorage) -> Unit,
) {
  val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
  val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
  server.createContext("/") { exchange ->
    try {
      exchange.requestBody.readBytes()
      handler(exchange)
    } catch (error: Throwable) {
      errors += error
      exchange.sendResponseHeaders(500, -1)
      exchange.close()
    }
  }
  server.start()
  val profile = StorageProfile(
    "test", "test", StorageProvider.AWS_S3,
    accessKeyId = "test", secretAccessKey = "test", regionName = "us-east-1",
    endpointUrl = "http://127.0.0.1:${server.address.port}", forcePathStyle = true,
  )
  try {
    runBlocking {
      block(S3ObjectStorage(profile, client, BucketRegionResolver(client), StorageCapabilityCache(), { _, _, _ -> }, onObjectsChanged))
    }
    errors.firstOrNull()?.let { throw it }
  } finally {
    server.stop(0)
    client.connectionPool.evictAll()
    client.dispatcher.executorService.shutdown()
  }
}

private fun HttpExchange.respond(xml: String, status: Int = 200) {
  val bytes = xml.toByteArray()
  responseHeaders.add("Content-Type", "application/xml")
  sendResponseHeaders(status, bytes.size.toLong())
  responseBody.use { it.write(bytes) }
}
