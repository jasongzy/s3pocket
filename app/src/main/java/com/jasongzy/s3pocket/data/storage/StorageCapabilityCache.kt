package com.jasongzy.s3pocket.data.storage

import com.jasongzy.s3pocket.data.model.StorageProfile
import java.util.concurrent.ConcurrentHashMap

class StorageCapabilityCache {
  private val multipartUpload = ConcurrentHashMap<String, Boolean>()

  fun supportsMultipart(profile: StorageProfile): Boolean = multipartUpload[profile.capabilityKey()] != false

  fun markMultipartUnsupported(profile: StorageProfile) {
    multipartUpload[profile.capabilityKey()] = false
  }
}

private fun StorageProfile.capabilityKey(): String = listOf(id, endpoint, region, forcePathStyle).joinToString("\n")
