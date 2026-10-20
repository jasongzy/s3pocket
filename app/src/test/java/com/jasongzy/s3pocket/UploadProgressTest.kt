package com.jasongzy.s3pocket

import com.jasongzy.s3pocket.data.transfer.TransferItem
import com.jasongzy.s3pocket.data.transfer.TransferStatus
import com.jasongzy.s3pocket.data.transfer.throttledProgress
import com.jasongzy.s3pocket.ui.uploadProgress
import org.junit.Assert.*
import org.junit.Test

class UploadProgressTest {
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
