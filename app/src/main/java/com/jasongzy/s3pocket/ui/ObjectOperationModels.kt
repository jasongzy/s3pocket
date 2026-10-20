package com.jasongzy.s3pocket.ui

import com.jasongzy.s3pocket.data.model.FavoriteItem
import com.jasongzy.s3pocket.data.model.FavoriteKind
import com.jasongzy.s3pocket.data.model.ObjectItem
import com.jasongzy.s3pocket.data.storage.ObjectMove
import com.jasongzy.s3pocket.data.storage.ObjectStorage
import com.jasongzy.s3pocket.data.storage.folderMoves
import com.jasongzy.s3pocket.data.storage.StorageOperationCanceledException

internal fun ObjectItem.favorite(profileId: String, bucket: String): FavoriteItem =
  FavoriteItem(
    profileId = profileId,
    bucket = bucket,
    key = key,
    kind = if (isFolder) FavoriteKind.FOLDER else FavoriteKind.FILE,
  )

internal data class DeletePlan(
  val fileKeys: List<String>,
  val recursivePrefixes: List<String>,
  val total: Int,
) {
  val hasNonEmptyFolder: Boolean get() = recursivePrefixes.isNotEmpty()
}

internal suspend fun buildDeletePlan(
  storage: ObjectStorage,
  bucket: String,
  items: List<ObjectItem>,
  shouldCancel: () -> Boolean,
): DeletePlan {
  val fileKeys = items.filterNot { it.isFolder }.map { it.key }.distinct().toMutableList()
  val recursivePrefixes = mutableListOf<String>()
  var total = fileKeys.size
  items.filter { it.isFolder }.forEach { folder ->
    val prefix = folder.key
    require(prefix.isNotEmpty() && prefix.endsWith('/')) { "Invalid folder prefix" }
    var count = 0
    var hasChildren = false
    storage.scanObjectKeys(bucket, prefix, shouldCancel) { keys ->
      count += keys.size
      hasChildren = hasChildren || keys.any { it != prefix }
    }
    total += maxOf(1, count)
    if (hasChildren) recursivePrefixes += prefix else fileKeys += prefix
  }
  return DeletePlan(fileKeys, recursivePrefixes, total)
}

internal data class MovePlan(
  val files: List<ObjectMove>,
  val folders: List<FolderMove>,
  val total: Int,
)

internal data class FolderMove(val source: String, val target: String)

internal suspend fun buildMovePlan(
  storage: ObjectStorage,
  bucket: String,
  items: List<ObjectItem>,
  destinationPrefix: String,
  shouldCancel: () -> Boolean,
): MovePlan {
  val destination = destinationPrefix.let { if (it.isEmpty() || it.endsWith('/')) it else "$it/" }
  val files = mutableListOf<ObjectMove>()
  val folders = mutableListOf<FolderMove>()
  var total = 0
  items.forEach { item ->
    if (shouldCancel()) throw StorageOperationCanceledException()
    if (item.isFolder) {
      val sourcePrefix = item.key
      require(sourcePrefix.isNotEmpty() && sourcePrefix.endsWith('/')) { "Invalid folder prefix" }
      val targetPrefix = destination + sourcePrefix.removeSuffix("/").substringAfterLast('/') + "/"
      require(sourcePrefix != targetPrefix) { "Source and destination are the same" }
      require(!targetPrefix.startsWith(sourcePrefix) && !sourcePrefix.startsWith(targetPrefix)) {
        "Source and destination folders overlap"
      }
      var count = 0
      storage.scanObjectKeys(bucket, sourcePrefix, shouldCancel) { keys ->
        storage.validateMoves(bucket, folderMoves(keys, sourcePrefix, targetPrefix), shouldCancel)
        count += keys.size
      }
      folders += FolderMove(sourcePrefix, targetPrefix)
      total += count
    } else {
      val targetKey = destination + item.key.substringAfterLast('/')
      require(item.key != targetKey) { "Source and destination are the same" }
      files += ObjectMove(item.key, targetKey)
      total++
    }
  }
  storage.validateMoves(bucket, files, shouldCancel)
  return MovePlan(files.distinctBy { it.sourceKey }, folders, total)
}
