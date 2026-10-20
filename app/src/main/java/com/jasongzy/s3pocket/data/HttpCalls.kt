package com.jasongzy.s3pocket.data

import java.io.IOException
import java.net.URLDecoder
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.Response

internal fun downloadFileName(url: HttpUrl, contentDisposition: String?): String {
  val parameters = Regex("""(?:^|;)\s*([^=;\s]+)\s*=\s*(?:"((?:\\.|[^"\\])*)"|([^;]*))""")
    .findAll(contentDisposition.orEmpty()).associate { match ->
      match.groupValues[1].lowercase() to (match.groups[2]?.value
        ?.replace(Regex("""\\(.)""")) { it.groupValues[1] } ?: match.groupValues[3].trim())
    }
  val extended = parameters["filename*"]?.split('\'', limit = 3)?.takeIf { it.size == 3 }?.let { parts ->
    runCatching { URLDecoder.decode(parts[2].replace("+", "%2B"), parts[0]) }.getOrNull()
  }
  val name = sequenceOf(extended, parameters["filename"], url.pathSegments.lastOrNull())
    .filterNotNull().firstOrNull(String::isNotBlank) ?: "download"
  return name.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
}

internal suspend fun <T> Call.useResponse(block: (Response) -> T): T = coroutineScope {
  val cancelCall = launch(start = CoroutineStart.UNDISPATCHED) {
    try {
      awaitCancellation()
    } finally {
      this@useResponse.cancel()
    }
  }
  try {
    withContext(Dispatchers.IO) {
      try {
        execute().use(block)
      } catch (error: IOException) {
        currentCoroutineContext().ensureActive()
        throw error
      }
    }
  } finally {
    cancelCall.cancel()
  }
}
