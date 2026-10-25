package com.jasongzy.s3pocket.data.storage

import com.jasongzy.s3pocket.data.model.ObjectItem
import com.jasongzy.s3pocket.data.model.StorageProfile
import com.jasongzy.s3pocket.data.model.StorageProvider
import com.jasongzy.s3pocket.data.useResponse
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit.SECONDS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.encodeUtf8

internal data class MetadataPage(val listing: ObjectPage, val metadata: Map<String, Map<String, String>>)

internal class R2MetadataClient(client: OkHttpClient) {
  private val client = client.newBuilder().callTimeout(30, SECONDS).followRedirects(false).build()
  private val unavailable = ConcurrentHashMap.newKeySet<String>()

  suspend fun list(profile: StorageProfile, bucket: String, prefix: String, cursor: String?, pageSize: Int = 1000): MetadataPage? {
    require(pageSize in 1..1000)
    if (profile.provider != StorageProvider.CLOUDFLARE_R2 || profile.managementApiToken.isBlank()) return null
    val capability = "${profile.accountId}/$bucket/${profile.managementApiToken.encodeUtf8().sha256().hex()}"
    if (capability in unavailable) return null
    val url = "https://api.cloudflare.com/client/v4/accounts".toHttpUrl().newBuilder()
      .addPathSegment(profile.accountId).addPathSegments("r2/buckets").addPathSegment(bucket).addPathSegment("objects")
      .addQueryParameter("prefix", prefix).addQueryParameter("delimiter", "/").addQueryParameter("per_page", pageSize.toString())
      .apply { if (cursor != null) addQueryParameter("cursor", cursor) }.build()
    val request = Request.Builder().url(url).header("Authorization", "Bearer ${profile.managementApiToken}").build()
    return client.newCall(request).useResponse { response ->
      if (response.code in setOf(401, 403, 404, 405, 501)) {
        unavailable += capability
        null
      } else {
        if (!response.isSuccessful) throw IOException("Cloudflare metadata listing failed (HTTP ${response.code})")
        parseMetadataPage(response.body.string(), prefix, cursor)
      }
    }
  }
}

internal fun parseMetadataPage(body: String, prefix: String, cursor: String?): MetadataPage {
  val root = Json.parseToJsonElement(body).jsonObject
  check(root["success"]?.jsonPrimitive?.booleanOrNull == true) { "Cloudflare metadata listing failed" }
  val result = root["result"] as? JsonArray ?: error("Cloudflare returned no object list")
  val info = (root["result_info"] as? JsonObject).orEmpty()
  val metadata = mutableMapOf<String, Map<String, String>>()
  val files = result.mapNotNull { element ->
    val item = element.jsonObject
    val key = item.getValue("key").jsonPrimitive.content
    if (key == prefix) return@mapNotNull null
    check(key.startsWith(prefix) && '/' !in key.removePrefix(prefix)) { "Object is outside the current directory" }
    (item["custom_metadata"] as? JsonObject)?.let { values ->
      metadata[key] = values.mapValues { decodeMetadataValue(it.value.jsonPrimitive.content) }
    }
    ObjectItem(
      key, key.removePrefix(prefix), false,
      size = item.getValue("size").jsonPrimitive.long,
      lastModified = item["last_modified"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse),
      etag = item["etag"]?.jsonPrimitive?.contentOrNull,
    )
  }
  val folders = (info["delimited"] as? JsonArray).orEmpty().map { element ->
    val key = element.jsonPrimitive.content
    check(key.startsWith(prefix) && key.endsWith('/') && key != prefix) { "Invalid directory prefix" }
    ObjectItem(key, key.removePrefix(prefix).removeSuffix("/"), true)
  }
  val nextCursor = info["cursor"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
  val next = nextPageToken(info["is_truncated"]?.jsonPrimitive?.booleanOrNull ?: (nextCursor != null), nextCursor, cursor)
  return MetadataPage(ObjectPage(folders + files, next), metadata)
}
