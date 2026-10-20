package com.jasongzy.s3pocket

import com.jasongzy.s3pocket.data.storage.nextPageToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import com.jasongzy.s3pocket.data.storage.ObjectPage
import com.jasongzy.s3pocket.data.model.ObjectItem
import com.jasongzy.s3pocket.ui.ObjectsUiState
import com.jasongzy.s3pocket.ui.loadObjectPages
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class PaginationTest {
  @Test
  fun listingProgressOnlyAppearsAfterTheFirstPage() {
    val initial = ObjectsUiState(isLoadingAll = true)
    assertFalse(initial.showListingProgress)
    assertFalse(initial.copy(isLoading = false).showListingProgress)
    val loading = initial.copy(
      objects = listOf(ObjectItem("file", "file", false)),
      isLoading = false,
    )
    assertTrue(loading.showListingProgress)
    assertFalse(loading.copy(isLoading = true).showListingProgress)
    assertFalse(loading.copy(isLoadingAll = false).showListingProgress)
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun searchContinuesTheInFlightPageAndCanStopAfterThatPage() = runTest {
    var scan = false
    val first = CompletableDeferred<ObjectPage>()
    val second = CompletableDeferred<ObjectPage>()
    val requests = mutableListOf<String?>()
    val pages = mutableListOf<String?>()
    val job = launch {
      loadObjectPages("page2", { scan }, { token ->
        requests += token
        if (token == "page2") first.await() else second.await()
      }) { pages += it.nextToken }
    }
    runCurrent()
    scan = true
    first.complete(ObjectPage(emptyList(), "page3"))
    runCurrent()
    assertEquals(listOf("page2", "page3"), requests)
    scan = false
    second.complete(ObjectPage(emptyList(), "page4"))
    job.join()
    assertEquals(listOf("page3", "page4"), pages)
    assertEquals(2, requests.size)
  }

  @Test
  fun onlyTruncatedPagesContinue() {
    assertEquals("next", nextPageToken(true, "next", null))
    assertEquals("next", nextPageToken(true, "next", "current"))
    assertNull(nextPageToken(false, "unused", "current"))
    assertNull(nextPageToken(null, null, null))
  }

  @Test
  fun incompleteOrRepeatedTokensFail() {
    listOf(null, "", " ", "current").forEach { token ->
      assertThrows(IllegalStateException::class.java) {
        nextPageToken(true, token, "current")
      }
    }
  }
}
