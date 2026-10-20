package com.jasongzy.s3pocket

import com.jasongzy.s3pocket.data.model.ObjectDetails
import com.jasongzy.s3pocket.ui.isText
import com.jasongzy.s3pocket.ui.isVideo
import com.jasongzy.s3pocket.ui.readTextPreview
import com.jasongzy.s3pocket.ui.TEXT_PREVIEW_MAX_BYTES
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectPreviewTest {
  @Test
  fun textPreviewEnforcesTheLimitWhileReading() = runTest {
    assertEquals("文本", readTextPreview { it.write("文本".toByteArray()) })
    val result = runCatching {
      readTextPreview {
        it.write(ByteArray(TEXT_PREVIEW_MAX_BYTES.toInt()))
        it.write(1)
      }
    }
    assertTrue(result.exceptionOrNull() is IllegalStateException)
    assertTrue(runCatching {
      readTextPreview { it.write(ByteArray(TEXT_PREVIEW_MAX_BYTES.toInt() + 1)) }
    }.isFailure)
  }

  @Test
  fun detectsTextFromMimeTypeWithCharset() {
    assertTrue(details("data", "application/json; charset=utf-8").isText())
  }

  @Test
  fun fallsBackToKnownExtensions() {
    assertTrue(details("notes.md", null).isText())
    assertTrue(details("clip.mp4", null).isVideo())
    assertFalse(details("archive.zip", null).isText())
  }

  private fun details(key: String, contentType: String?): ObjectDetails =
    ObjectDetails(key, 0, contentType, null, null, null, null, emptyMap())
}
