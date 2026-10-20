package com.jasongzy.s3pocket.data

import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Response

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
