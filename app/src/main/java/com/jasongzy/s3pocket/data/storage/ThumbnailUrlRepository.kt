package com.jasongzy.s3pocket.data.storage

import com.jasongzy.s3pocket.data.ProfileRepository
import com.jasongzy.s3pocket.data.SettingsRepository
import com.jasongzy.s3pocket.data.model.StorageProfile
import android.util.LruCache
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ThumbnailUrlRepository(
  private val profiles: ProfileRepository,
  private val settings: SettingsRepository,
  private val storageFactory: StorageFactory,
) {
  private val cache = LruCache<CacheKey, CachedUrl>(256)
  private val signingSlots = Semaphore(4)

  suspend fun get(profileId: String, bucket: String, key: String): String {
    val profile = profiles.get(profileId)
    val expiryMinutes = settings.settings.first().presignedUrlExpiryMinutes
    val cacheKey = CacheKey(profile, bucket, key, expiryMinutes)
    val now = Instant.now()
    cache.get(cacheKey)?.takeIf { it.validUntil.isAfter(now) }?.let { return it.url }
    return signingSlots.withPermit {
      cache.get(cacheKey)?.takeIf { it.validUntil.isAfter(Instant.now()) }?.url ?: run {
        val url = storageFactory.create(profile).createPreviewUrl(bucket, key, expiryMinutes)
        cache.put(cacheKey, CachedUrl(url, Instant.now().plusSeconds(expiryMinutes * 60L - 30L)))
        url
      }
    }
  }

  private data class CachedUrl(val url: String, val validUntil: Instant)
  private data class CacheKey(val profile: StorageProfile, val bucket: String, val key: String, val expiryMinutes: Int)
}
