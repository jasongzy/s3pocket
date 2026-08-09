package com.jasongzy.s3pocket.data.storage

import com.jasongzy.s3pocket.data.model.BucketItem
import com.jasongzy.s3pocket.data.model.ObjectDetails
import com.jasongzy.s3pocket.data.model.ObjectItem
import com.jasongzy.s3pocket.data.model.UploadObject
import com.jasongzy.s3pocket.data.model.UpdateObject
import java.io.InputStream
import java.io.OutputStream

fun interface UploadSource {
  fun open(): InputStream
}

interface ObjectStorage {
  suspend fun listBuckets(): List<BucketItem>

  suspend fun listObjects(
    bucket: String,
    prefix: String,
    continuationToken: String? = null,
    pageSize: Int = 500,
  ): ObjectPage

  suspend fun getObjectDetails(bucket: String, key: String): ObjectDetails

  suspend fun createPreviewUrl(bucket: String, key: String, expiryMinutes: Int): String

  suspend fun upload(
    bucket: String,
    request: UploadObject,
    source: UploadSource,
    shouldCancel: () -> Boolean = { false },
    onProgress: (completed: Long, total: Long) -> Unit = { _, _ -> },
  )

  suspend fun download(
    bucket: String,
    key: String,
    output: OutputStream,
    shouldCancel: () -> Boolean = { false },
    onProgress: (completed: Long, total: Long) -> Unit = { _, _ -> },
  )

  suspend fun objectExists(bucket: String, key: String): Boolean

  suspend fun createFolder(bucket: String, key: String)

  suspend fun listObjectKeys(bucket: String, prefix: String, shouldCancel: () -> Boolean): List<String>

  suspend fun deleteObjects(
    bucket: String,
    keys: List<String>,
    shouldCancel: () -> Boolean,
    onProgress: (completed: Int, total: Int) -> Unit,
  )

  suspend fun moveObjects(bucket: String, keys: List<String>, destinationPrefix: String)

  suspend fun updateObject(bucket: String, request: UpdateObject)
}

data class ObjectPage(
  val items: List<ObjectItem>,
  val nextToken: String?,
)

class DeleteCanceledException : Exception()

class TransferCanceledException : Exception()
