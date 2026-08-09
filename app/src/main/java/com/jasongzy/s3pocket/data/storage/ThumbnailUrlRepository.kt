package com.jasongzy.s3pocket.data.storage

import com.jasongzy.s3pocket.data.ProfileRepository
import com.jasongzy.s3pocket.data.SettingsRepository
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ThumbnailUrlRepository(
  private val profiles: ProfileRepository,
  private val settings: SettingsRepository,
  private val storageFactory: StorageFactory,
) {
  private val cache = ConcurrentHashMap<String, CachedUrl>()
  private val signingSlots = Semaphore(4)

  suspend fun get(profileId: String, bucket: String, key: String): String {
    val profile = profiles.get(profileId)
    val cacheKey = listOf(
      profile.id,
      profile.endpoint,
      profile.region,
      profile.forcePathStyle,
      profile.accessKeyId,
      bucket,
      key,
    ).joinToString("\n")
    val now = Instant.now()
    cache[cacheKey]?.takeIf { it.validUntil.isAfter(now) }?.let { return it.url }
    return signingSlots.withPermit {
      cache[cacheKey]?.takeIf { it.validUntil.isAfter(Instant.now()) }?.url ?: run {
        val expiryMinutes = settings.presignedUrlExpiryMinutes.first()
        val url = storageFactory.create(profile).createPreviewUrl(bucket, key, expiryMinutes)
        cache[cacheKey] = CachedUrl(url, Instant.now().plusSeconds(expiryMinutes * 60L - 30L))
        url
      }
    }
  }

  private data class CachedUrl(val url: String, val validUntil: Instant)
}
