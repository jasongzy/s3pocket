package com.jasongzy.s3pocket

import com.jasongzy.s3pocket.data.storage.decodeMetadataValue
import com.jasongzy.s3pocket.data.storage.encodeMetadataValue
import com.jasongzy.s3pocket.ui.MetadataEntry
import com.jasongzy.s3pocket.ui.toMetadata
import com.jasongzy.s3pocket.ui.uploadMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataTest {
  @Test
  fun metadataValuesRoundTripThroughAsciiHeaders() {
    listOf("", "plain", " 中文 📷 ", "\tline\nbreak", "=?UTF-8?B?5Lit5paH?=", "a中文📷".repeat(50)).forEach { value ->
      val encoded = encodeMetadataValue(value)
      assertTrue(encoded.all { it in ' '..'~' })
      assertEquals(value, decodeMetadataValue(encoded))
      encoded.split(' ').forEach { assertTrue(it.length <= 75) }
    }
    assertEquals("plain", encodeMetadataValue("plain"))
  }

  @Test
  fun decodesEncodedWordsAndPreservesUnrecognizedValues() {
    assertEquals("中文", decodeMetadataValue("=?utf-8?b?5Lit?=\r\n =?UTF-8?Q?=E6=96=87?="))
    assertEquals("author: André Smith", decodeMetadataValue("author: =?ISO-8859-1?Q?Andr=E9_Smith?="))
    listOf("plain", "=?unknown?B?5Lit?=", "=?UTF-8?B?%%%?=", "=?UTF-8?B?/w==?=", "=?UTF-8?Q?=-1?=", "=?UTF-8?Q?=A?=").forEach {
      assertEquals(it, decodeMetadataValue(it))
    }
  }

  @Test
  fun localUploadsKeepTemplatesAndUrlUploadsReplaceOnlyTheirSource() {
    val template = mapOf("Source-URL" to "template", "Author" to "Alice")
    assertEquals(template, uploadMetadata(template, null))
    assertEquals(
      mapOf("source-url" to "https://example.com/file", "Author" to "Alice"),
      uploadMetadata(template, "https://example.com/file"),
    )
  }

  @Test
  fun trimsKeysAndKeepsValues() {
    assertEquals(
      mapOf("Author" to " Alice ", "source" to ""),
      listOf(MetadataEntry(" Author ", " Alice "), MetadataEntry("source"), MetadataEntry(" ")).toMetadata(),
    )
    assertEquals(emptyMap<String, String>(), emptyList<MetadataEntry>().toMetadata())
  }

  @Test
  fun rejectsDuplicatesAfterTrimmingAndIgnoringCase() {
    assertNull(listOf(MetadataEntry("Author"), MetadataEntry(" author ")).toMetadata())
  }
}
