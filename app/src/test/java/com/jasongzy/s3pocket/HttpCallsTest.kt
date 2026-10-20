package com.jasongzy.s3pocket

import com.jasongzy.s3pocket.data.useResponse
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
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpCallsTest {
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
