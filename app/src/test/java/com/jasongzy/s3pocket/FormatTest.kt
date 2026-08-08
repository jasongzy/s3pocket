package com.jasongzy.s3pocket

import com.jasongzy.s3pocket.ui.formatBytes
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
  @Test
  fun formatBytes_usesReadableUnits() {
    assertEquals("999 B", formatBytes(999))
    assertEquals("1 KB", formatBytes(1_000))
    assertEquals("1.5 MB", formatBytes(1_500_000))
  }
}
