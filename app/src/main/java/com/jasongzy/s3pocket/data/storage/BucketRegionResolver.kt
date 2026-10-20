package com.jasongzy.s3pocket.data.storage

import com.jasongzy.s3pocket.data.AppLog
import com.jasongzy.s3pocket.data.useResponse
import com.jasongzy.s3pocket.data.model.StorageProfile
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class BucketRegionResolver(private val httpClient: OkHttpClient) {
  private val regions = ConcurrentHashMap<String, String>()

  suspend fun resolve(profile: StorageProfile, bucket: String): String {
    if (!profile.isAmazonS3) return profile.region
    val cacheKey = "${profile.id}:$bucket"
    regions[cacheKey]?.let { return it }
    return discover(bucket)?.also { regions[cacheKey] = it } ?: profile.region
  }

  private suspend fun discover(bucket: String): String? = withContext(Dispatchers.IO) {
    runCatching {
      val encodedBucket = URLEncoder.encode(bucket, StandardCharsets.UTF_8.name()).replace("+", "%20")
      val request = Request.Builder().url("https://s3.amazonaws.com/$encodedBucket").head().build()
      httpClient.newCall(request).useResponse { it.header(REGION_HEADER)?.takeIf(String::isNotBlank) }
    }.onFailure {
      if (it is CancellationException) throw it
      AppLog.debug(TAG) { "Bucket region discovery failed: ${it.message}" }
    }.getOrNull()
  }

  private companion object {
    const val REGION_HEADER = "x-amz-bucket-region"
    const val TAG = "S3Pocket.Region"
  }
}
