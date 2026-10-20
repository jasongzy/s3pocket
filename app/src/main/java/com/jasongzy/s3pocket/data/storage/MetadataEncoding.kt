package com.jasongzy.s3pocket.data.storage

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.util.Base64

private val encodedWord = Regex("=\\?([^?\\s]+)\\?([bBqQ])\\?([^?\\s]*)\\?=")

internal fun encodeMetadataValue(value: String): String {
  if (value.all { it in ' '..'~' } && value == value.trim() && !encodedWord.containsMatchIn(value)) return value
  val bytes = value.toByteArray(Charsets.UTF_8)
  return buildList {
    var start = 0
    while (start < bytes.size) {
      // Keep RFC 2047 words below 75 characters without splitting UTF-8 sequences.
      var end = minOf(start + 45, bytes.size)
      while (end < bytes.size && bytes[end].toInt() and 0xc0 == 0x80) end--
      add("=?UTF-8?B?${Base64.getEncoder().encodeToString(bytes.copyOfRange(start, end))}?=")
      start = end
    }
  }.joinToString(" ")
}

internal fun decodeMetadataValue(value: String): String = buildString {
  var end = 0
  var previousDecoded = false
  for (match in encodedWord.findAll(value)) {
    val (charset, encoding, text) = match.destructured
    val decoded = try {
      val bytes = if (encoding.equals("B", true)) Base64.getDecoder().decode(text) else decodeQuotedPrintable(text)
      Charset.forName(charset).newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: IllegalArgumentException) {
      null
    } catch (_: CharacterCodingException) {
      null
    }
    val gap = value.substring(end, match.range.first)
    if (!(previousDecoded && decoded != null && gap.all { it in " \t\r\n" })) append(gap)
    append(decoded ?: match.value)
    previousDecoded = decoded != null
    end = match.range.last + 1
  }
  append(value.substring(end))
}

private fun decodeQuotedPrintable(text: String): ByteArray {
  val bytes = ByteArray(text.length)
  var index = 0
  var count = 0
  while (index < text.length) {
    val char = text[index++]
    bytes[count++] = when (char) {
      '_' -> 32
      '=' -> {
        require(index + 2 <= text.length)
        val byte = text[index].digitToInt(16) * 16 + text[index + 1].digitToInt(16)
        index += 2
        byte
      }
      else -> { require(char in '!'..'~'); char.code }
    }.toByte()
  }
  return bytes.copyOf(count)
}
