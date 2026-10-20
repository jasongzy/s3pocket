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
  val forcePathStyle: Boolean = false,
  val publicBaseUrls: Map<String, String> = emptyMap(),
  val unassignedPublicUrl: String = "",
) {
  val isAmazonS3: Boolean
    get() = provider == StorageProvider.AWS_S3 && isAmazonS3Endpoint(endpointUrl)

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

  fun publicObjectUrl(bucket: String, key: String): String? =
    publicBaseUrls[bucket]?.trim()?.trimEnd('/')?.takeIf(String::isNotEmpty)
      ?.let { "$it/${key.encodeUrlPath()}" }

  fun validationError(checkName: Boolean = true): ProfileError? = when {
    checkName && name.isBlank() -> ProfileError.NAME
    provider == StorageProvider.CLOUDFLARE_R2 && !accountId.matches(Regex("[a-fA-F0-9]{32}")) -> ProfileError.ACCOUNT_ID
    provider == StorageProvider.AWS_S3 && regionName.isBlank() -> ProfileError.REGION
    accessKeyId.isBlank() -> ProfileError.ACCESS_KEY
    secretAccessKey.isBlank() -> ProfileError.SECRET_KEY
    endpointUrl.isNotBlank() && !endpointUrl.isHttpsBaseUrl() -> ProfileError.ENDPOINT
    publicBaseUrls.keys.any { !it.isBucketName() || it != it.trim() } -> ProfileError.PUBLIC_BUCKET
    publicBaseUrls.values.any { !it.isHttpsBaseUrl() } -> ProfileError.PUBLIC_URL
    unassignedPublicUrl.isNotEmpty() && !unassignedPublicUrl.isHttpsBaseUrl() -> ProfileError.PUBLIC_URL
    else -> null
  }
}

enum class ProfileError { NAME, ACCOUNT_ID, REGION, ACCESS_KEY, SECRET_KEY, ENDPOINT, PUBLIC_URL, PUBLIC_BUCKET, DUPLICATE_PUBLIC_BUCKET }

internal fun String.isBucketName(): Boolean = isNotBlank() && '/' !in this && none(Char::isISOControl)

private fun String.isHttpsBaseUrl(): Boolean = runCatching {
  val uri = java.net.URI(this)
  uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
    uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
}.getOrDefault(false)

fun isAmazonS3Endpoint(endpointUrl: String): Boolean {
  if (endpointUrl.isBlank()) return true
  val host = runCatching { java.net.URI(endpointUrl.trim()).host }.getOrNull()?.lowercase() ?: return false
  return host == "amazonaws.com" || host.endsWith(".amazonaws.com")
}

private fun String.encodeUrlPath(): String =
  split('/').joinToString("/") { java.net.URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20") }

@Serializable
enum class StorageProvider {
  AWS_S3,
  CLOUDFLARE_R2,
}
