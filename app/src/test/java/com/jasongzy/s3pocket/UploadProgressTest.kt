package com.jasongzy.s3pocket

import com.jasongzy.s3pocket.data.transfer.TransferItem
import com.jasongzy.s3pocket.data.transfer.TransferStatus
import com.jasongzy.s3pocket.data.transfer.throttledProgress
import com.jasongzy.s3pocket.ui.UploadResult
import com.jasongzy.s3pocket.ui.ObjectsUiState
import com.jasongzy.s3pocket.ui.consumeUploadResult
import com.jasongzy.s3pocket.ui.uploadProgress
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class UploadProgressTest {
  @Test
  fun onlyASuccessfulSingleUploadExposesItsFinalKey() {
    val state = ObjectsUiState(uploadKeyToOpen = "folder/image (1).jpg")
    assertNull(state.uploadedObjectKey)
    val completed = state.copy(uploadResult = UploadResult(1, 1))
    assertEquals("folder/image (1).jpg", completed.uploadedObjectKey)
    assertNull(completed.copy(uploadKeyToOpen = null).uploadedObjectKey)
    assertNull(completed.copy(uploadResult = null).uploadedObjectKey)
    listOf(UploadResult(0, 1), UploadResult(1, 2), UploadResult(2, 2)).forEach { result ->
      assertNull(state.copy(uploadResult = result).uploadedObjectKey)
    }
  }

  @Test
  fun consecutiveCompletionsWithEqualCountsRemainDistinct() {
    val first = UploadResult(1, 1)
    val second = UploadResult(1, 1)
    val state = MutableStateFlow(ObjectsUiState(uploadResult = first))
    state.value = state.value.copy(uploadResult = second)
    assertSame(second, state.value.uploadResult)
    assertNotEquals(first, second)
  }

  @Test
  fun delayedCompletionLeavesTheNextUploadIntact() {
    val previousResult = UploadResult(1, 1)
    val currentResult = UploadResult(1, 1)
    val state = MutableStateFlow(ObjectsUiState(uploadKeyToOpen = "next.txt"))
    assertNull(state.consumeUploadResult(previousResult))
    assertEquals("next.txt", state.value.uploadKeyToOpen)
    state.value = state.value.copy(uploadResult = currentResult)
    assertNull(state.consumeUploadResult(previousResult))
    assertSame(currentResult, state.value.uploadResult)
    assertEquals("next.txt", state.consumeUploadResult(currentResult))
    assertNull(state.value.uploadResult)
    assertNull(state.value.uploadKeyToOpen)
    assertNull(state.consumeUploadResult(currentResult))
  }

  @Test
  fun progressPublishesPeriodicallyAndAtCompletion() {
    var now = 0L
    val updates = mutableListOf<Long>()
    val report = throttledProgress({ now }) { completed, _ -> updates += completed }
    report(1, 100)
    report(2, 100)
    now = 200_000_000L
    report(50, 100)
    report(100, 100)
    assertEquals(listOf(1L, 50L, 100L), updates)
  }

  @Test
  fun failedItemDoesNotHideActiveTransfersOrCompletedBytes() {
    val items = listOf(
      TransferItem("done", 10, 10, TransferStatus.COMPLETED),
      TransferItem("failed", 0, 10, TransferStatus.FAILED, "Denied"),
      TransferItem("running", 4, 10, TransferStatus.RUNNING),
      TransferItem("queued", 0, 10),
    )
    val progress = uploadProgress(items)
    assertTrue(progress.active)
    assertEquals("Denied", progress.error)
    assertEquals(1, progress.completed)
    assertEquals(4, progress.total)
    assertEquals(14L, progress.completedBytes)
    assertEquals(40L, progress.totalBytes)
    val finished = uploadProgress(items.map { it.copy(status = TransferStatus.COMPLETED, completedBytes = 10, error = null) })
    assertFalse(finished.active)
    assertEquals(4, finished.completed)
    assertEquals(4, finished.total)
  }

  @Test
  fun cancellationKeepsTheCompletedCount() {
    val result = uploadProgress(listOf(
      TransferItem("done", 10, 10, TransferStatus.COMPLETED),
      TransferItem("canceled", 4, 10, TransferStatus.CANCELED),
    ))
    assertFalse(result.active)
    assertNull(result.error)
    assertEquals(1, result.completed)
    assertEquals(2, result.total)
  }
}
