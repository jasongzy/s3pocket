package com.jasongzy.s3pocket.data.model

import java.time.Instant

data class BucketItem(val name: String, val createdAt: Instant?)

data class ObjectItem(
  val key: String,
  val name: String,
  val isFolder: Boolean,
  val size: Long = 0,
  val lastModified: Instant? = null,
  val etag: String? = null,
)

data class ObjectDetails(
  val key: String,
  val size: Long,
  val contentType: String?,
  val lastModified: Instant?,
  val etag: String?,
  val cacheControl: String?,
  val contentDisposition: String?,
  val metadata: Map<String, String>,
  val missingMetadataCount: Int = 0,
)

data class UploadObject(
  val key: String,
  val contentType: String,
  val contentLength: Long,
  val metadata: Map<String, String>,
  val overwrite: Boolean = false,
)

data class UpdateObject(
  val sourceKey: String,
  val targetKey: String,
  val contentType: String?,
  val cacheControl: String?,
  val contentDisposition: String?,
  val metadata: Map<String, String>,
)
