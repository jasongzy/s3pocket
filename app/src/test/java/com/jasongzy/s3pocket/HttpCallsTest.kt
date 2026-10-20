package com.jasongzy.s3pocket

import com.jasongzy.s3pocket.data.useResponse
import com.jasongzy.s3pocket.data.downloadFileName
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpCallsTest {
  @Test
  fun downloadNamesRespectHeaderParametersAndEncoding() {
    val url = "https://example.com/fallback.txt".toHttpUrl()
    mapOf(
      "attachment; filename=report.txt; size=42" to "report.txt",
      "attachment; FILENAME=\"report; final.txt\"; size=42" to "report; final.txt",
      "attachment; filename=README" to "README",
      "attachment; filename=ascii.txt; filename*=UTF-8''%E4%B8%AD%E6%96%87+%20file.txt" to "中文+ file.txt",
      "attachment; filename*=ISO-8859-1'en'%A3.txt" to "£.txt",
      "attachment; filename=report.txt; filename*=UTF-8''%zz" to "report.txt",
      "attachment; filename*=unknown''report.txt" to "fallback.txt",
      "attachment; filename=\"a\\\"b.txt\"" to "a_b.txt",
      "attachment; filename*=UTF-8''path%2Ffile%00.txt" to "path_file_.txt",
    ).forEach { (header, expected) ->
      assertEquals(header, expected, downloadFileName(url, header))
    }
    assertEquals("report file.txt", downloadFileName("https://example.com/report%20file.txt?token=123".toHttpUrl(), null))
    assertEquals("download", downloadFileName("https://example.com/".toHttpUrl(), "inline"))
  }

  @Test
  fun cancellationInterruptsAStalledResponseBody() = runBlocking {
    val releaseServer = CountDownLatch(1)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      exchange.sendResponseHeaders(200, 16)
      exchange.responseBody.write(1)
      exchange.responseBody.flush()
      releaseServer.await(10, TimeUnit.SECONDS)
      exchange.close()
    }
    server.start()
    val client = OkHttpClient()
    val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.address.port}/").build())
    try {
      val reading = CompletableDeferred<Unit>()
      val job = launch {
        call.useResponse {
          reading.complete(Unit)
          it.body.string()
        }
      }
      withTimeout(5_000) {
        reading.await()
        job.cancelAndJoin()
      }
      assertTrue(call.isCanceled())
    } finally {
      releaseServer.countDown()
      server.stop(0)
      client.connectionPool.evictAll()
      client.dispatcher.executorService.shutdown()
    }
  }
}
