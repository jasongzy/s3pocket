package com.jasongzy.s3pocket.data.storage

import com.jasongzy.s3pocket.data.model.StorageProfile
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient

class StorageFactory(private val httpClient: OkHttpClient = OkHttpClient()) {
  private val regionResolver = BucketRegionResolver(httpClient)
  private val capabilities = StorageCapabilityCache()
  private val metadataClient = R2MetadataClient(httpClient)
  private val revisions = ConcurrentHashMap<Pair<String, String>, Long>()
  internal val metadataKeys = MetadataKeyCache()

  fun revision(profileId: String, bucket: String): Long = revisions[profileId to bucket] ?: 0L

  internal suspend fun listMetadata(
    profile: StorageProfile, bucket: String, prefix: String, cursor: String?, pageSize: Int = 1000,
  ): MetadataPage? = metadataClient.list(profile, bucket, prefix, cursor, pageSize)?.also { page ->
    page.metadata.forEach { (key, values) -> metadataKeys.remember(profile.id, bucket, key, values.keys) }
  }

  fun create(profile: StorageProfile): ObjectStorage =
    S3ObjectStorage(
      profile, httpClient, regionResolver, capabilities,
      onMetadataObserved = { bucket, key, keys -> metadataKeys.remember(profile.id, bucket, key, keys) },
      onObjectsChanged = { bucket -> revisions.merge(profile.id to bucket, 1L, Long::plus) },
    )
}
