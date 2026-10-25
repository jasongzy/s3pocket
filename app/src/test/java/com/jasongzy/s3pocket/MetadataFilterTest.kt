package com.jasongzy.s3pocket

import com.jasongzy.s3pocket.data.model.MetadataCondition
import com.jasongzy.s3pocket.data.model.MetadataFilter
import com.jasongzy.s3pocket.data.model.MetadataOperator.*
import com.jasongzy.s3pocket.data.model.ObjectItem
import com.jasongzy.s3pocket.data.model.StorageProfile
import com.jasongzy.s3pocket.data.model.StorageProvider
import com.jasongzy.s3pocket.data.storage.R2MetadataClient
import com.jasongzy.s3pocket.data.storage.MetadataKeyCache
import com.jasongzy.s3pocket.data.storage.metadataKeySuggestions
import com.jasongzy.s3pocket.data.storage.mergeMetadataKeys
import com.jasongzy.s3pocket.data.storage.sampleObjectMetadata
import com.jasongzy.s3pocket.data.storage.parseMetadataPage
import com.jasongzy.s3pocket.data.storage.readObjectMetadata
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class MetadataFilterTest {
  @Test
  fun missingEmptyAndNegativeConditionsAreDistinct() {
    val metadata = mapOf("Empty" to "", "space" to " ", "source" to "HTTPS://Example.com/中文")
    assertTrue(MetadataCondition("empty", EXISTS).matches(metadata, false))
    assertTrue(MetadataCondition("empty", EMPTY).matches(metadata, false))
    assertFalse(MetadataCondition("space", EMPTY).matches(metadata, false))
    assertTrue(MetadataCondition("space", NOT_EMPTY).matches(metadata, false))
    listOf(EMPTY, NOT_EMPTY, EQUALS, NOT_EQUALS, CONTAINS, NOT_CONTAINS).forEach { operator ->
      assertFalse(MetadataCondition("missing", operator, "value").matches(metadata, false))
    }
    assertTrue(MetadataCondition("missing", MISSING).matches(metadata, false))
    assertFalse(MetadataCondition("source", CONTAINS, "example").matches(metadata, false))
    assertTrue(MetadataCondition("source", CONTAINS, "example").matches(metadata, true))
    val filter = MetadataFilter(listOf(MetadataCondition("empty", EMPTY), MetadataCondition("source", CONTAINS, "中文")))
    assertTrue(filter.matches(metadata))
    assertFalse(filter.matches(mapOf("Empty" to "")))
    assertTrue(filter.copy(matchAll = false).matches(mapOf("Empty" to "")))
    assertFalse(MetadataFilter().isValid)
    assertFalse(MetadataFilter(listOf(MetadataCondition("key", CONTAINS))).isValid)
    val missing = MetadataFilter(listOf(MetadataCondition("missing", MISSING)))
    val file = ObjectItem("file", "file", false)
    assertTrue(missing.matches(file, emptyMap()))
    assertFalse(missing.matches(file, null))
    assertFalse(missing.matches(file.copy(isFolder = true), emptyMap()))
  }

  @Test
  fun batchPagesKeepEmptyMetadataSeparateFromMissingMetadataAndHonorCursors() {
    val page = parseMetadataPage(PAGE, "dir/", null)
    assertEquals(listOf("dir/sub/", "dir/a", "dir/b", "dir/c"), page.listing.items.map { it.key })
    assertEquals("next", page.listing.nextToken)
    assertEquals(mapOf("tag" to "中文", "empty" to ""), page.metadata["dir/a"])
    assertEquals(page.metadata, parseMetadataPage(PAGE.replace("中文", "=?UTF-8?B?5Lit5paH?="), "dir/", null).metadata)
    assertEquals(emptyMap<String, String>(), page.metadata["dir/b"])
    assertFalse(page.metadata.containsKey("dir/c"))
    assertThrows(IllegalStateException::class.java) { parseMetadataPage(PAGE, "dir/", "next") }
    assertThrows(IllegalStateException::class.java) { parseMetadataPage(PAGE.replace("dir/a", "elsewhere/a"), "dir/", null) }
    assertNull(parseMetadataPage("""{"success":true,"result":[],"result_info":{"delimited":[]}}""", "", null).listing.nextToken)
    assertEquals("next", parseMetadataPage(PAGE.replace("\"is_truncated\":true,", ""), "dir/", null).listing.nextToken)
  }

  @Test
  fun rejectedBatchCredentialsAreRememberedButTransientErrorsCanRetry() = runTest {
    var requests = 0
    var code = 403
    var pageSize = 1000
    val client = R2MetadataClient(OkHttpClient.Builder().addInterceptor { chain ->
      requests++
      assertEquals("GET", chain.request().method)
      assertEquals("/", chain.request().url.queryParameter("delimiter"))
      assertEquals(pageSize.toString(), chain.request().url.queryParameter("per_page"))
      Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
        .body(PAGE.toResponseBody()).build()
    }.build())
    val profile = StorageProfile("id", "name", StorageProvider.CLOUDFLARE_R2, "account", "access", "secret", "token")
    assertNull(client.list(profile, "bucket", "dir/", null))
    assertNull(client.list(profile, "bucket", "dir/", null))
    assertEquals(1, requests)
    val newToken = profile.copy(managementApiToken = "replacement")
    code = 429
    try {
      client.list(newToken, "bucket", "dir/", null)
      fail("Expected rate limit failure")
    } catch (_: IOException) { }
    code = 200
    pageSize = 50
    assertNotNull(client.list(newToken, "bucket", "dir/", null, pageSize))
    assertEquals(3, requests)
    assertNull(client.list(profile.copy(managementApiToken = ""), "bucket", "dir/", null))
    assertEquals(3, requests)
  }

  @Test
  fun keySuggestionsReuseDirectoryKnowledgeAndStayWithinTheConnectionAndBucket() {
    val cache = MetadataKeyCache()
    assertTrue(cache.beginSample("p", "b", "dir/"))
    assertFalse(cache.beginSample("p", "b", "dir/"))
    cache.remember("p", "b", "elsewhere/file", setOf("source-url", "Tag", "other"))
    cache.remember("p", "b", "dir/file", setOf("tag", "source"))
    cache.remember("p", "b", "empty/file", emptySet())
    assertFalse(cache.beginSample("p", "b", "empty/"))
    cache.remember("p2", "b", "dir/file", setOf("private"))
    cache.remember("p", "b2", "dir/file", setOf("another"))
    val keys = cache.keys("p", "b", "dir/")
    assertEquals(listOf("tag", "source", "source-url", "other"), keys)
    assertEquals(listOf("source", "source-url"), metadataKeySuggestions(keys, "SOU"))
    assertEquals(listOf("other", "source", "source-url"), metadataKeySuggestions(keys, "O"))
    assertEquals(6, metadataKeySuggestions((1..12).map { "key$it" }, "").size)
    assertTrue(metadataKeySuggestions(keys, "new-field").isEmpty())
  }

  @Test
  fun arrivingMetadataKeepsExistingSuggestionsInPlaceAndAppendsNewKeys() {
    val initial = mergeMetadataKeys(emptyList(), listOf(" source-url ", "Tag", "author", "title", "category", "date", ""))
    val updated = mergeMetadataKeys(initial, listOf("tag", "caption", "SOURCE-URL", "content-type", "author"))
    assertEquals(initial + listOf("caption", "content-type"), updated)
    assertEquals(initial, metadataKeySuggestions(updated, ""))
    assertEquals(listOf("category", "caption", "content-type", "source-url"), metadataKeySuggestions(updated, "c"))
    assertEquals(listOf("content-type"), metadataKeySuggestions(updated, " CONTENT "))
    assertEquals(updated, mergeMetadataKeys(updated, listOf("CAPTION", "tag")))
    assertEquals(updated, mergeMetadataKeys(updated, emptyList()))
  }

  @Test
  fun samplingUsesOneBatchOrAtMostEightHeadRequestsAndRetainsOnlySuccessfulReads() = runTest {
    val items = (0..39).map { ObjectItem("dir/$it", "$it", false) } + ObjectItem("dir/sub/", "sub", true)
    val results = mutableMapOf<String, Map<String, String>>()
    val reads = mutableListOf<String>()
    var lists = 0
    sampleObjectMetadata(items, { lists++; parseMetadataPage(PAGE, "dir/", null) }, { error("Unexpected HEAD") }) { k, v -> results[k] = v }
    assertEquals(1, lists)
    assertEquals(setOf("dir/a", "dir/b"), results.keys)
    results.clear()
    sampleObjectMetadata(items, { lists++; throw IOException("unavailable") }, { key ->
      reads += key
      if (key == "dir/5") throw IOException("denied")
      mapOf("tag" to "")
    }) { k, v -> results[k] = v }
    assertEquals(2, lists)
    assertEquals((0..35 step 5).map { "dir/$it" }, reads)
    assertEquals(7, results.size)
    assertFalse("dir/5" in results)
    sampleObjectMetadata(emptyList(), { null }, { error("Unexpected HEAD") }) { _, _ -> fail("Unexpected result") }
    val canceled = launch {
      sampleObjectMetadata(items, { throw CancellationException() }, { error("Unexpected HEAD") }) { _, _ -> fail("Unexpected result") }
    }
    canceled.join()
    assertTrue(canceled.isCancelled)
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun headReadsAreBoundedAndCancellationDoesNotBecomeMissingMetadata() = runTest {
    val release = CompletableDeferred<Unit>()
    val started = mutableListOf<String>()
    val results = mutableMapOf<String, Result<Map<String, String>>>()
    val job = launch {
      readObjectMetadata((1..9).map(Int::toString), { key ->
        started += key
        release.await()
        if (key == "2") throw IOException("denied")
        emptyMap()
      }) { key, result -> results[key] = result }
    }
    runCurrent()
    assertEquals(4, started.size)
    release.complete(Unit)
    job.join()
    assertEquals(9, results.size)
    assertTrue(results.getValue("2").isFailure)
    assertEquals(emptyMap<String, String>(), results.getValue("1").getOrThrow())
    started.clear()
    results.clear()
    val canceled = launch {
      readObjectMetadata((1..9).map(Int::toString), { key ->
        started += key
        if (key == "1") emptyMap() else awaitCancellation()
      }) { key, result -> results[key] = result }
    }
    runCurrent()
    canceled.cancelAndJoin()
    assertEquals(4, started.size)
    assertEquals(setOf("1"), results.keys)
    assertEquals(emptyMap<String, String>(), results.getValue("1").getOrThrow())
  }

  private companion object {
    const val PAGE = """{"success":true,"result":[
      {"key":"dir/a","size":1,"custom_metadata":{"tag":"中文","empty":""}},
      {"key":"dir/b","size":0,"custom_metadata":{}},
      {"key":"dir/c","size":2}
    ],"result_info":{"cursor":"next","is_truncated":true,"delimited":["dir/sub/"]}}"""
  }
}
