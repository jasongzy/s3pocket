package com.jasongzy.s3pocket.data.storage

import java.util.Locale

internal class MetadataKeyCache {
  private val directories = linkedMapOf<Triple<String, String, String>, MutableSet<String>>()

  @Synchronized
  fun remember(profileId: String, bucket: String, objectKey: String, keys: Set<String>) {
    val prefix = objectKey.substring(0, objectKey.lastIndexOf('/') + 1)
    val known = directory(profileId, bucket, prefix)
    keys.forEach { if (known.size < 128) known += it.lowercase(Locale.ROOT) }
  }

  @Synchronized
  fun beginSample(profileId: String, bucket: String, prefix: String): Boolean {
    if (Triple(profileId, bucket, prefix) in directories) return false
    directory(profileId, bucket, prefix)
    return true
  }

  @Synchronized
  fun keys(profileId: String, bucket: String, prefix: String): List<String> {
    val current = Triple(profileId, bucket, prefix)
    return (directories[current].orEmpty() + directories.filterKeys {
      it.first == profileId && it.second == bucket && it != current
    }.values.flatten()).distinct()
  }

  private fun directory(profileId: String, bucket: String, prefix: String): MutableSet<String> =
    directories.getOrPut(Triple(profileId, bucket, prefix)) {
      if (directories.size == 128) directories.remove(directories.keys.first())
      linkedSetOf()
    }
}

internal fun mergeMetadataKeys(previous: List<String>, incoming: List<String>): List<String> =
  (previous.asSequence() + incoming.asSequence()).map(String::trim).filter(String::isNotEmpty)
    .distinctBy { it.lowercase(Locale.ROOT) }
    .toList()

internal fun metadataKeySuggestions(keys: List<String>, query: String): List<String> {
  val input = query.trim()
  return keys.asSequence()
    .filter { it.contains(input, ignoreCase = true) }
    .sortedBy { !it.startsWith(input, ignoreCase = true) }
    .take(6).toList()
}
