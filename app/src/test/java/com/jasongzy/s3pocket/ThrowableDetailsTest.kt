package com.jasongzy.s3pocket

import com.jasongzy.s3pocket.ui.userMessage
import com.jasongzy.s3pocket.ui.runSuspendCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThrowableDetailsTest {
  @Test
  fun userMessage_includesExceptionTypesAndCauseChain() {
    val error = IllegalStateException("Outer failure", IllegalArgumentException("Invalid account"))

    assertEquals(
      "Exception: IllegalStateException: Outer failure\nCaused by: IllegalArgumentException: Invalid account",
      error.userMessage(),
    )
  }

  @Test
  fun userMessage_identifiesExceptionWithoutMessage() {
    assertTrue(IllegalStateException().userMessage().contains("IllegalStateException"))
  }

  @Test
  fun cancellationPropagatesThroughErrorHandling() = runTest {
    val canceled = CancellationException("Page closed")
    val result = runCatching { runSuspendCatching { throw canceled } }
    assertSame(canceled, result.exceptionOrNull())
  }
}
