package com.jasongzy.s3pocket.data

import com.jasongzy.s3pocket.data.model.StorageProfile
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class CloudflareMetrics(
  val storageBytes: Long,
  val objectCount: Long,
  val classAOperations: Long,
  val classBOperations: Long,
)

class CloudflareMetricsRepository(private val client: OkHttpClient = OkHttpClient()) {
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun fetch(profile: StorageProfile): CloudflareMetrics = withContext(Dispatchers.IO) {
    require(profile.managementApiToken.isNotBlank()) { "Cloudflare Management API token is missing" }
    val storage = get("https://api.cloudflare.com/client/v4/accounts/${profile.accountId}/r2/metrics", profile.managementApiToken)
    val root = json.parseToJsonElement(storage).jsonObject
    ensureSuccess(root)
    val result = root.getValue("result").jsonObject
    val classes = listOfNotNull(result["standard"] as? JsonObject, result["infrequentAccess"] as? JsonObject)
    val published = classes.mapNotNull { it["published"] as? JsonObject }
    val storageBytes = published.sumOf { it["payloadSize"]?.jsonPrimitive?.longOrNull ?: 0 }
    val objectCount = published.sumOf { it["objects"]?.jsonPrimitive?.longOrNull ?: 0 }
    val operations = fetchOperations(profile)
    CloudflareMetrics(storageBytes, objectCount, operations.first, operations.second)
  }

  private fun fetchOperations(profile: StorageProfile): Pair<Long, Long> {
    val now = Instant.now()
    val start = now.atZone(ZoneOffset.UTC).withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()
    val body = buildJsonObject {
      put("query", OPERATIONS_QUERY)
      put("variables", buildJsonObject {
        put("accountTag", profile.accountId)
        put("startDate", start.toString())
        put("endDate", now.toString())
      })
    }.toString()
    val request = Request.Builder()
      .url("https://api.cloudflare.com/client/v4/graphql")
      .header("Authorization", "Bearer ${profile.managementApiToken}")
      .post(body.toRequestBody(JSON_MEDIA_TYPE))
      .build()
    val response = execute(request)
    val root = json.parseToJsonElement(response).jsonObject
    (root["errors"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { throw IOException(it.toString()) }
    val data = root["data"] as? JsonObject ?: throw IOException("Cloudflare GraphQL returned no data")
    val viewer = data["viewer"] as? JsonObject ?: throw IOException("Cloudflare GraphQL returned no viewer data")
    val accounts = viewer["accounts"] as? JsonArray ?: throw IOException("Cloudflare GraphQL returned no account data")
    val account = accounts.firstOrNull() as? JsonObject ?: throw IOException("Cloudflare account was not found")
    val groups = account["r2OperationsAdaptiveGroups"] as? JsonArray
      ?: throw IOException("Cloudflare GraphQL returned no R2 operation data")
    var classA = 0L
    var classB = 0L
    groups.forEach { element ->
      val group = element.jsonObject
      val action = group["dimensions"]?.jsonObject?.get("actionType")?.jsonPrimitive?.contentOrNull.orEmpty()
      val requests = group["sum"]?.jsonObject?.get("requests")?.jsonPrimitive?.longOrNull ?: 0
      if (action in CLASS_A_ACTIONS) classA += requests
      if (action in CLASS_B_ACTIONS) classB += requests
    }
    return classA to classB
  }

  private fun get(url: String, token: String): String = execute(
    Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
  )

  private fun execute(request: Request): String = client.newCall(request).execute().use { response ->
    val body = response.body.string()
    if (!response.isSuccessful) throw IOException("Cloudflare API failed (${response.code}): ${body.take(500)}")
    body
  }

  private fun ensureSuccess(root: JsonObject) {
    if (root["success"]?.jsonPrimitive?.contentOrNull != "true") {
      throw IOException(root["errors"]?.toString() ?: "Cloudflare API request failed")
    }
  }

  private companion object {
    val JSON_MEDIA_TYPE = "application/json".toMediaType()
    val CLASS_A_ACTIONS = setOf(
      "ListBuckets", "PutBucket", "ListObjects", "ListObjectsV2", "PutObject", "CopyObject",
      "CompleteMultipartUpload", "CreateMultipartUpload", "ListMultipartUploads", "UploadPart",
      "UploadPartCopy", "ListParts", "PutBucketEncryption", "PutBucketCors", "PutBucketLifecycleConfiguration",
    )
    val CLASS_B_ACTIONS = setOf(
      "HeadBucket", "HeadObject", "GetObject", "UsageSummary", "GetBucketEncryption", "GetBucketLocation",
      "GetBucketCors", "GetBucketLifecycleConfiguration",
    )
    const val OPERATIONS_QUERY = """
      query R2Operations(${'$'}accountTag: string!, ${'$'}startDate: Time!, ${'$'}endDate: Time!) {
        viewer { accounts(filter: { accountTag: ${'$'}accountTag }) {
          r2OperationsAdaptiveGroups(limit: 10000, filter: { datetime_geq: ${'$'}startDate, datetime_leq: ${'$'}endDate }) {
            sum { requests }
            dimensions { actionType }
          }
        } }
      }
    """
  }
}
