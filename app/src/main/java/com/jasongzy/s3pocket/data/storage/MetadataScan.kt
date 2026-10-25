package com.jasongzy.s3pocket.data.storage

import com.jasongzy.s3pocket.data.model.ObjectItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

data class MetadataScan(
  val values: Map<String, Map<String, String>> = emptyMap(),
  val running: Boolean = false,
  val complete: Boolean = false,
  val failed: Int = 0,
  val error: String? = null,
)

internal suspend fun sampleObjectMetadata(
  items: List<ObjectItem>,
  list: suspend () -> MetadataPage?,
  read: suspend (String) -> Map<String, String>,
  onResult: (String, Map<String, String>) -> Unit,
) {
  val page = try {
    list()
  } catch (error: CancellationException) {
    throw error
  } catch (_: Exception) {
    null
  }
  if (page != null && page.metadata.isNotEmpty()) {
    page.metadata.forEach(onResult)
    return
  }
  val files = items.filterNot(ObjectItem::isFolder)
  val count = minOf(8, files.size)
  val keys = List(count) { files[it * files.size / count].key }
  readObjectMetadata(keys, read) { key, result -> result.onSuccess { onResult(key, it) } }
}

internal suspend fun readObjectMetadata(
  keys: List<String>,
  read: suspend (String) -> Map<String, String>,
  onResult: (String, Result<Map<String, String>>) -> Unit,
) {
  keys.chunked(4).forEach { batch ->
    coroutineScope {
      batch.forEach { key ->
        launch {
          val result = try {
            Result.success(read(key))
          } catch (error: CancellationException) {
            throw error
          } catch (error: Exception) {
            Result.failure(error)
          }
          onResult(key, result)
        }
      }
    }
  }
}
