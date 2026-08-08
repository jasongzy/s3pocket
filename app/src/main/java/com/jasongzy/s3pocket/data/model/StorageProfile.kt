package com.jasongzy.s3pocket.data.model

import kotlinx.serialization.Serializable

@Serializable
data class StorageProfile(
  val id: String,
  val name: String,
  val provider: StorageProvider,
  val accountId: String = "",
  val accessKeyId: String,
  val secretAccessKey: String,
  val managementApiToken: String = "",
  val regionName: String = "",
  val endpointUrl: String = "",
  val publicBaseUrl: String = "",
) {
  val endpoint: String
    get() = when (provider) {
      StorageProvider.CLOUDFLARE_R2 -> "https://$accountId.r2.cloudflarestorage.com"
      StorageProvider.AWS_S3 -> endpointUrl.ifBlank { "https://s3.$region.amazonaws.com" }
    }

  val region: String
    get() = when (provider) {
      StorageProvider.CLOUDFLARE_R2 -> "auto"
      StorageProvider.AWS_S3 -> regionName
    }

  val endpointOverride: String?
    get() = when (provider) {
      StorageProvider.CLOUDFLARE_R2 -> endpoint
      StorageProvider.AWS_S3 -> endpointUrl.trim().ifBlank { null }
    }

  fun publicObjectUrl(key: String): String? =
    publicBaseUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() }?.let { "$it/${key.encodeUrlPath()}" }
}

private fun String.encodeUrlPath(): String =
  split('/').joinToString("/") { java.net.URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20") }

@Serializable
enum class StorageProvider {
  AWS_S3,
  CLOUDFLARE_R2,
}
