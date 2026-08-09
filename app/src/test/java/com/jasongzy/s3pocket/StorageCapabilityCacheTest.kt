package com.jasongzy.s3pocket

import com.jasongzy.s3pocket.data.storage.StorageCapabilityCache
import com.jasongzy.s3pocket.data.model.StorageProfile
import com.jasongzy.s3pocket.data.model.StorageProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageCapabilityCacheTest {
  @Test
  fun multipartSupportIsCachedPerProfile() {
    val cache = StorageCapabilityCache()
    val first = profile("a", "https://storage.example.com")
    val second = profile("b", "https://storage.example.com")
    val edited = profile("a", "https://other.example.com")

    assertTrue(cache.supportsMultipart(first))
    cache.markMultipartUnsupported(first)

    assertFalse(cache.supportsMultipart(first))
    assertTrue(cache.supportsMultipart(second))
    assertTrue(cache.supportsMultipart(edited))
  }

  private fun profile(id: String, endpoint: String) = StorageProfile(
    id = id,
    name = id,
    provider = StorageProvider.AWS_S3,
    accessKeyId = "key",
    secretAccessKey = "secret",
    regionName = "us-east-1",
    endpointUrl = endpoint,
  )
}
