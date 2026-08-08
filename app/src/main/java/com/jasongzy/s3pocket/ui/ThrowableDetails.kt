package com.jasongzy.s3pocket.ui

import aws.smithy.kotlin.runtime.ServiceException
import kotlinx.coroutines.CancellationException

private const val MAX_CAUSES = 6
private const val MAX_ERROR_LENGTH = 2_000

internal suspend inline fun <T> runSuspendCatching(block: suspend () -> T): Result<T> =
  try {
    Result.success(block())
  } catch (error: CancellationException) {
    throw error
  } catch (error: Throwable) {
    Result.failure(error)
  }

internal fun Throwable.userMessage(): String {
  val lines = mutableListOf<String>()
  if (this is ServiceException) {
    val metadata = sdkErrorMetadata
    metadata.errorCode.cleanMessage()?.let { lines += "Error code: $it" }
    metadata.errorMessage.cleanMessage()?.let { lines += "Service message: $it" }
    metadata.protocolResponse.summary.cleanMessage()?.let { lines += "Response: $it" }
    metadata.requestId.cleanMessage()?.let { lines += "Request ID: $it" }
    if (metadata.isRetryable) lines += "Retryable: yes"
  }

  generateSequence(this) { it.cause }
    .take(MAX_CAUSES)
    .forEachIndexed { index, error ->
      val type = error::class.java.simpleName.ifBlank { error::class.java.name }
      val message = error.message.cleanMessage()
      val label = if (index == 0) "Exception" else "Caused by"
      lines += if (message == null) "$label: $type" else "$label: $type: $message"
    }

  return lines.distinct().joinToString("\n").take(MAX_ERROR_LENGTH)
}

private fun String?.cleanMessage(): String? =
  this?.trim()?.replace(Regex("\\s*\\n\\s*"), " ")?.takeIf { it.isNotEmpty() }
