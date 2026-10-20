package com.jasongzy.s3pocket.data.storage

import com.jasongzy.s3pocket.data.model.StorageProfile
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient

class StorageFactory(private val httpClient: OkHttpClient = OkHttpClient()) {
  private val regionResolver = BucketRegionResolver(httpClient)
  private val capabilities = StorageCapabilityCache()
  private val revisions = ConcurrentHashMap<Pair<String, String>, Long>()

  fun revision(profileId: String, bucket: String): Long = revisions[profileId to bucket] ?: 0L

  fun create(profile: StorageProfile): ObjectStorage =
    S3ObjectStorage(profile, httpClient, regionResolver, capabilities) { bucket ->
      revisions.merge(profile.id to bucket, 1L, Long::plus)
    }
}
