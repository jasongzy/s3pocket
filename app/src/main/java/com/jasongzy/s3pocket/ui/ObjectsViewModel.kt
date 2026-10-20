package com.jasongzy.s3pocket.ui

import android.content.ContentResolver
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.net.URL
import com.jasongzy.s3pocket.data.AppLog
import com.jasongzy.s3pocket.data.GalleryImageScale
import com.jasongzy.s3pocket.data.GalleryThumbnailSize
import com.jasongzy.s3pocket.data.ObjectSortMode
import com.jasongzy.s3pocket.data.ObjectViewMode
import com.jasongzy.s3pocket.data.ProfileRepository
import com.jasongzy.s3pocket.data.SettingsRepository
import com.jasongzy.s3pocket.data.model.FavoriteItem
import com.jasongzy.s3pocket.data.model.ObjectItem
import com.jasongzy.s3pocket.data.storage.ObjectMove
import com.jasongzy.s3pocket.data.storage.ObjectStorage
import com.jasongzy.s3pocket.data.storage.StorageOperationCanceledException
import com.jasongzy.s3pocket.data.storage.StorageFactory
import com.jasongzy.s3pocket.data.transfer.TransferManager
import com.jasongzy.s3pocket.data.transfer.TransferStatus
import com.jasongzy.s3pocket.data.transfer.UploadTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class DownloadedSource(
  val uri: Uri,
  val name: String,
  val contentType: String,
  val size: Long,
  val sourceUrl: String,
)

data class ObjectsUiState(
  val objects: List<ObjectItem> = emptyList(),
  val isLoading: Boolean = true,
  val isUploading: Boolean = false,
  val isWorking: Boolean = false,
  val error: String? = null,
  val errorRes: Int? = null,
  val deletedCount: Int? = null,
  val isDownloadingSource: Boolean = false,
  val downloadedSource: DownloadedSource? = null,
  val deleteProgress: ItemProgress? = null,
  val pendingRecursiveDelete: List<ObjectItem>? = null,
  val isDeleting: Boolean = false,
  val isCancelingDelete: Boolean = false,
  val deleteCanceled: Boolean = false,
  val moveProgress: ItemProgress? = null,
  val isMoving: Boolean = false,
  val isCancelingMove: Boolean = false,
  val moveCanceled: Boolean = false,
  val uploadProgress: UploadProgress? = null,
  val uploadedCount: Int? = null,
  val nextContinuationToken: String? = null,
  val isLoadingMore: Boolean = false,
  val isScanning: Boolean = false,
)

data class ItemProgress(val completed: Int, val total: Int)

data class UploadProgress(
  val completed: Int,
  val total: Int,
  val completedBytes: Long = 0,
  val totalBytes: Long = 0,
  val error: String? = null,
)

data class BatchUploadItem(
  val uri: Uri,
  val key: String,
  val contentType: String,
  val contentLength: Long,
  val metadata: Map<String, String>,
)

class ObjectsViewModel(
  private val profileId: String,
  private val bucket: String,
  private val prefix: String,
  private val profiles: ProfileRepository,
  private val settings: SettingsRepository,
  private val storageFactory: StorageFactory,
  private val contentResolver: ContentResolver,
  private val cacheDir: File,
  private val transferManager: TransferManager,
) : ViewModel() {
  @Volatile private var deleteCancelRequested = false
  @Volatile private var moveCancelRequested = false
  private var scanJob: Job? = null
  private var listingGeneration = 0
  private var scanGeneration = 0
  private val httpClient = OkHttpClient()
  private val _state = MutableStateFlow(ObjectsUiState())
  private val uploadIds = mutableSetOf<String>()
  private var uploadsRefreshed = false
  val state: StateFlow<ObjectsUiState> = _state
  val favorites: StateFlow<List<FavoriteItem>> =
    profiles.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  init {
    refresh()
    viewModelScope.launch {
      transferManager.items.collect { transfers ->
        val uploads = transfers.filter { it.id in uploadIds }
        if (uploads.isEmpty()) return@collect
        val active = uploads.filter { it.status == TransferStatus.QUEUED || it.status == TransferStatus.RUNNING }
        val failed = uploads.firstOrNull { it.status == TransferStatus.FAILED }
        val completed = uploads.count { it.status == TransferStatus.COMPLETED }
        _state.update {
          it.copy(
            isUploading = active.isNotEmpty(),
            uploadProgress = UploadProgress(
              completed = completed,
              total = uploads.size,
              completedBytes = uploads.sumOf { transfer -> transfer.completedBytes },
              totalBytes = uploads.sumOf { transfer -> transfer.totalBytes },
              error = failed?.error,
            ).takeIf { active.isNotEmpty() || failed != null },
          )
        }
        if (active.isEmpty() && failed == null && !uploadsRefreshed) {
          uploadsRefreshed = true
          val uploadedCount = uploads.count { it.status == TransferStatus.COMPLETED }
          _state.update { it.copy(uploadedCount = uploadedCount.takeIf { count -> count == uploads.size }) }
          refresh()
          transferManager.dismissFinished(uploadIds)
          uploadIds.clear()
        }
      }
    }
  }

  fun refresh() {
    val generation = ++listingGeneration
    scanGeneration++
    scanJob = null
    viewModelScope.launch {
      val startedAt = SystemClock.elapsedRealtime()
      AppLog.debug(TAG) { "ListObjects started" }
      _state.update {
        it.copy(
          isLoading = true,
          isLoadingMore = false,
          isScanning = false,
          nextContinuationToken = null,
          error = null,
          errorRes = null,
        )
      }
      runSuspendCatching {
          val profile = profiles.get(profileId)
          storageFactory.create(profile).listObjects(bucket, prefix)
        }
        .onSuccess { page ->
          if (generation != listingGeneration) return@onSuccess
          AppLog.debug(TAG) { "ListObjects returned ${page.items.size} items in ${SystemClock.elapsedRealtime() - startedAt} ms" }
          _state.update {
            it.copy(objects = page.items, nextContinuationToken = page.nextToken, isLoading = false)
          }
        }
        .onFailure { error ->
          if (generation != listingGeneration) return@onFailure
          AppLog.error(TAG, error) { "ListObjects failed in ${SystemClock.elapsedRealtime() - startedAt} ms" }
          _state.update { it.copy(isLoading = false, error = error.userMessage()) }
        }
    }
  }

  fun loadMore() {
    val snapshot = _state.value
    val token = snapshot.nextContinuationToken ?: return
    if (snapshot.isLoading || snapshot.isLoadingMore || snapshot.isScanning) return
    val generation = listingGeneration
    viewModelScope.launch { loadPage(token, generation = generation) }
  }

  fun setCompleteListingRequired(required: Boolean) {
    if (!required) {
      scanGeneration++
      scanJob = null
      _state.update { it.copy(isScanning = false) }
      return
    }
    if (_state.value.nextContinuationToken == null || scanJob?.isActive == true) return
    val currentScan = ++scanGeneration
    val generation = listingGeneration
    scanJob = viewModelScope.launch {
      _state.update { it.copy(isScanning = true) }
      try {
        while (currentScan == scanGeneration && generation == listingGeneration) {
          val token = _state.value.nextContinuationToken ?: break
          if (!loadPage(token, scanning = true, generation = generation)) break
        }
      } finally {
        if (currentScan == scanGeneration) {
          scanJob = null
          _state.update { it.copy(isScanning = false, isLoadingMore = false) }
        }
      }
    }
  }

  private suspend fun loadPage(token: String, scanning: Boolean = false, generation: Int): Boolean {
    _state.update { it.copy(isLoadingMore = !scanning, error = null, errorRes = null) }
    return runSuspendCatching {
        val profile = profiles.get(profileId)
        storageFactory.create(profile).listObjects(bucket, prefix, token)
      }
      .fold(onSuccess = { page ->
        if (generation != listingGeneration) return@fold false
        _state.update { state ->
          state.copy(
            objects = (state.objects + page.items).distinctBy(ObjectItem::key),
            nextContinuationToken = page.nextToken,
            isLoadingMore = false,
          )
        }
        true
      }, onFailure = { error ->
        if (generation != listingGeneration) return@fold false
        AppLog.error(TAG, error) { "ListObjects page failed" }
        _state.update { it.copy(isLoadingMore = false, isScanning = false, error = error.userMessage()) }
        false
      })
  }

  fun upload(uri: Uri, key: String, contentType: String, contentLength: Long, metadata: Map<String, String>) {
    enqueueUploads(listOf(BatchUploadItem(uri, key, contentType, contentLength, metadata)))
  }

  fun uploadBatch(items: List<BatchUploadItem>) {
    enqueueUploads(items)
  }

  private fun enqueueUploads(items: List<BatchUploadItem>) {
    if (items.isEmpty()) return
    uploadsRefreshed = false
    val ids = transferManager.enqueue(items.map {
      UploadTransfer(profileId, bucket, it.uri, it.key, it.contentType, it.contentLength, it.metadata)
    })
    uploadIds += ids
    _state.update { it.copy(isUploading = true, uploadProgress = UploadProgress(0, items.size)) }
  }

  fun cancelUploads() {
    val failed = transferManager.items.value.any { it.id in uploadIds && it.status == TransferStatus.FAILED }
    if (failed) {
      transferManager.dismissFinished(uploadIds)
      uploadIds.clear()
      _state.update { it.copy(isUploading = false, uploadProgress = null) }
    } else {
      transferManager.cancel(uploadIds)
    }
  }

  fun retryUploads() {
    transferManager.items.value.filter { it.id in uploadIds && it.status == TransferStatus.FAILED }
      .forEach { transferManager.retry(it.id) }
  }

  fun consumeUploadedCount() = _state.update { it.copy(uploadedCount = null) }

  fun createFolder(name: String) {
    runOperation("CreateFolder") {
      require(name.isNotBlank() && '/' !in name.trim('/')) { "Invalid folder name" }
      it.createFolder(bucket, prefix + name.trim('/'))
    }
  }

  fun downloadSource(url: String) {
    viewModelScope.launch {
      _state.update { it.copy(isDownloadingSource = true, downloadedSource = null, error = null) }
      runSuspendCatching {
          withContext(Dispatchers.IO) {
            val parsed = URL(url.trim())
            require(parsed.protocol == "http" || parsed.protocol == "https") { "Enter an HTTP or HTTPS URL" }
            val request = Request.Builder().url(parsed).get().build()
            httpClient.newCall(request).execute().use { response ->
              if (!response.isSuccessful) error("Download failed (${response.code})")
              val body = response.body
              val name = response.header("Content-Disposition")?.substringAfter("filename=", "")
                ?.trim(' ', '"')?.takeIf { it.isNotBlank() }
                ?: parsed.path.substringAfterLast('/').takeIf { it.isNotBlank() }
                ?: "download"
              val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
              val file = File.createTempFile("url-", "-$safeName", cacheDir)
              file.outputStream().use { output -> body.byteStream().use { it.copyTo(output) } }
              DownloadedSource(
                Uri.fromFile(file),
                safeName,
                body.contentType()?.toString() ?: "application/octet-stream",
                file.length(),
                url.trim(),
              )
            }
          }
        }
        .onSuccess { source -> _state.update { it.copy(isDownloadingSource = false, downloadedSource = source) } }
        .onFailure { error -> _state.update { it.copy(isDownloadingSource = false, error = error.userMessage()) } }
    }
  }

  fun requestDelete(items: List<ObjectItem>) {
    viewModelScope.launch {
      deleteCancelRequested = false
      val startedAt = SystemClock.elapsedRealtime()
      AppLog.debug(TAG) { "PrepareDelete started (${items.size} items)" }
      _state.update {
        it.copy(
          isWorking = true,
          isDeleting = true,
          isCancelingDelete = false,
          deleteCanceled = false,
          error = null,
          errorRes = null,
          deleteProgress = null,
        )
      }
      runSuspendCatching {
          val storage = createStorage()
          val plan = buildDeletePlan(storage, items)
          if (plan.hasNonEmptyFolder) {
            require(settings.settings.first().allowRecursiveFolderDelete) { "Recursive folder deletion is disabled" }
            _state.update { it.copy(isWorking = false, isDeleting = false, pendingRecursiveDelete = items) }
          } else {
            deletePlan(storage, items, plan)
          }
        }
        .onFailure { error -> handleDeleteFailure(error) }
      AppLog.debug(TAG) { "PrepareDelete finished in ${SystemClock.elapsedRealtime() - startedAt} ms" }
    }
  }

  fun confirmRecursiveDelete() {
    val items = _state.value.pendingRecursiveDelete ?: return
    viewModelScope.launch {
      deleteCancelRequested = false
      val startedAt = SystemClock.elapsedRealtime()
      AppLog.debug(TAG) { "RecursiveDelete started (${items.size} selected items)" }
      _state.update {
        it.copy(
          isWorking = true,
          isDeleting = true,
          isCancelingDelete = false,
          deleteCanceled = false,
          pendingRecursiveDelete = null,
          error = null,
          errorRes = null,
          deleteProgress = null,
        )
      }
      runSuspendCatching {
          require(settings.settings.first().allowRecursiveFolderDelete) { "Recursive folder deletion is disabled" }
          val storage = createStorage()
          deletePlan(storage, items, buildDeletePlan(storage, items))
        }
        .onFailure { error -> handleDeleteFailure(error) }
      AppLog.debug(TAG) { "RecursiveDelete finished in ${SystemClock.elapsedRealtime() - startedAt} ms" }
    }
  }

  fun cancelRecursiveDelete() = _state.update { it.copy(pendingRecursiveDelete = null) }

  fun cancelDelete() {
    if (!_state.value.isDeleting) return
    deleteCancelRequested = true
    _state.update { it.copy(isCancelingDelete = true) }
  }

  fun move(items: List<ObjectItem>, destinationPrefix: String) {
    if (items.isEmpty()) return
    viewModelScope.launch {
      moveCancelRequested = false
      val startedAt = SystemClock.elapsedRealtime()
      AppLog.debug(TAG) { "Move started (${items.size} selected items)" }
      _state.update {
        it.copy(
          isWorking = true,
          isMoving = true,
          isCancelingMove = false,
          moveCanceled = false,
          moveProgress = null,
          error = null,
          errorRes = null,
        )
      }
      runSuspendCatching {
          val storage = createStorage()
          val plan = buildMovePlan(storage, items, destinationPrefix)
          storage.moveObjects(bucket, plan.moves, { moveCancelRequested }) { completed, total ->
            _state.update { it.copy(moveProgress = ItemProgress(completed, total)) }
          }
          updateMovedFavorites(plan.pathMappings)
        }
        .onSuccess {
          AppLog.debug(TAG) { "Move succeeded in ${SystemClock.elapsedRealtime() - startedAt} ms" }
          _state.update {
            it.copy(isWorking = false, isMoving = false, isCancelingMove = false, moveProgress = null)
          }
          refresh()
        }
        .onFailure { error -> handleMoveFailure(error, startedAt) }
    }
  }

  fun cancelMove() {
    if (!_state.value.isMoving) return
    moveCancelRequested = true
    _state.update { it.copy(isCancelingMove = true) }
  }

  private fun runOperation(
    operation: String,
    itemCount: Int = 1,
    block: suspend (ObjectStorage) -> Unit,
  ) {
    viewModelScope.launch {
      val startedAt = SystemClock.elapsedRealtime()
      AppLog.debug(TAG) { "$operation started ($itemCount items)" }
      _state.update { it.copy(isWorking = true, error = null, errorRes = null) }
      runSuspendCatching {
          val profile = profiles.get(profileId)
          block(storageFactory.create(profile))
        }
        .onSuccess {
          AppLog.debug(TAG) { "$operation succeeded in ${SystemClock.elapsedRealtime() - startedAt} ms" }
          _state.update { it.copy(isWorking = false) }
          refresh()
        }
        .onFailure { error ->
          AppLog.error(TAG, error) { "$operation failed in ${SystemClock.elapsedRealtime() - startedAt} ms" }
          val errorRes = error.operationErrorRes()
          _state.update {
            it.copy(isWorking = false, error = if (errorRes == null) error.userMessage() else null, errorRes = errorRes)
          }
        }
    }
  }

  private suspend fun createStorage(): ObjectStorage {
    val profile = profiles.get(profileId)
    return storageFactory.create(profile)
  }

  private suspend fun buildMovePlan(
    storage: ObjectStorage,
    items: List<ObjectItem>,
    destinationPrefix: String,
  ): MovePlan {
    val destination = destinationPrefix.trim('/').let { if (it.isEmpty()) "" else "$it/" }
    val moves = mutableListOf<ObjectMove>()
    val pathMappings = mutableListOf<MovePathMapping>()
    items.forEach { item ->
      if (moveCancelRequested) throw StorageOperationCanceledException()
      if (item.isFolder) {
        val sourcePrefix = item.key.trimEnd('/') + "/"
        val targetPrefix = destination + sourcePrefix.removeSuffix("/").substringAfterLast('/') + "/"
        require(sourcePrefix != targetPrefix) { "Source and destination are the same" }
        require(!destination.startsWith(sourcePrefix)) { "Destination is inside source folder" }
        storage.listObjectKeys(bucket, sourcePrefix) { moveCancelRequested }.forEach { sourceKey ->
          moves += ObjectMove(sourceKey, targetPrefix + sourceKey.removePrefix(sourcePrefix))
        }
        pathMappings += MovePathMapping(sourcePrefix, targetPrefix, isPrefix = true)
      } else {
        val targetKey = destination + item.key.substringAfterLast('/')
        require(item.key != targetKey) { "Source and destination are the same" }
        moves += ObjectMove(item.key, targetKey)
        pathMappings += MovePathMapping(item.key, targetKey, isPrefix = false)
      }
    }
    val distinctMoves = moves.distinctBy { it.sourceKey }
    require(distinctMoves.map { it.targetKey }.distinct().size == distinctMoves.size) { "Duplicate destination object" }
    return MovePlan(distinctMoves, pathMappings)
  }

  private suspend fun updateMovedFavorites(pathMappings: List<MovePathMapping>) {
    val current = profiles.favorites.first()
    val updated = current.map { favorite ->
      if (favorite.profileId != profileId || favorite.bucket != bucket) return@map favorite
      val mapping = pathMappings.filter { it.matches(favorite.key) }.maxByOrNull { it.source.length }
        ?: return@map favorite
      favorite.copy(key = mapping.target + favorite.key.removePrefix(mapping.source))
    }
    if (updated != current) profiles.replaceFavorites(updated)
  }

  private suspend fun buildDeletePlan(
    storage: ObjectStorage,
    items: List<ObjectItem>,
  ): DeletePlan {
    val keys = items.filterNot { it.isFolder }.mapTo(mutableListOf()) { it.key }
    var hasNonEmptyFolder = false
    items.filter { it.isFolder }.forEach { folder ->
      val folderPrefix = folder.key.trimEnd('/') + "/"
      val folderKeys = storage.listObjectKeys(bucket, folderPrefix) { deleteCancelRequested }
      hasNonEmptyFolder = hasNonEmptyFolder || folderKeys.any { it != folderPrefix }
      keys += folderKeys
    }
    return DeletePlan(keys.distinct(), hasNonEmptyFolder)
  }

  private suspend fun deletePlan(
    storage: ObjectStorage,
    items: List<ObjectItem>,
    plan: DeletePlan,
  ) {
    storage.deleteObjects(bucket, plan.keys, { deleteCancelRequested }) { completed, total ->
      _state.update { it.copy(deleteProgress = ItemProgress(completed, total)) }
    }
    removeDeletedFavorites(items)
    val deletedCount = maxOf(items.size, plan.keys.size)
    _state.update {
      it.copy(
        isWorking = false,
        isDeleting = false,
        isCancelingDelete = false,
        deleteProgress = null,
        deletedCount = deletedCount,
      )
    }
    refresh()
  }

  private suspend fun removeDeletedFavorites(items: List<ObjectItem>) {
    val fileKeys = items.filterNot { it.isFolder }.mapTo(mutableSetOf()) { it.key }
    val folderPrefixes = items.filter { it.isFolder }.map { it.key.trimEnd('/') + "/" }
    val deletedFavorites = profiles.favorites.first().filter { favorite ->
      favorite.profileId == profileId && favorite.bucket == bucket &&
        (favorite.key in fileKeys || folderPrefixes.any { favorite.key.startsWith(it) })
    }
    profiles.setFavorites(deletedFavorites, false)
  }

  private fun handleDeleteFailure(error: Throwable) {
    if (error is StorageOperationCanceledException) {
      AppLog.debug(TAG) { "Delete canceled" }
      _state.update {
        it.copy(
          isWorking = false,
          isDeleting = false,
          isCancelingDelete = false,
          pendingRecursiveDelete = null,
          deleteProgress = null,
          deleteCanceled = true,
        )
      }
      refresh()
      return
    }
    AppLog.error(TAG, error) { "Delete failed" }
    val errorRes = error.operationErrorRes()
    _state.update {
      it.copy(
        isWorking = false,
        isDeleting = false,
        isCancelingDelete = false,
        pendingRecursiveDelete = null,
        deleteProgress = null,
        error = if (errorRes == null) error.userMessage() else null,
        errorRes = errorRes,
      )
    }
  }

  private fun handleMoveFailure(error: Throwable, startedAt: Long) {
    if (error is StorageOperationCanceledException) {
      AppLog.debug(TAG) { "Move canceled" }
      _state.update {
        it.copy(
          isWorking = false,
          isMoving = false,
          isCancelingMove = false,
          moveProgress = null,
          moveCanceled = true,
        )
      }
      refresh()
      return
    }
    AppLog.error(TAG, error) { "Move failed in ${SystemClock.elapsedRealtime() - startedAt} ms" }
    val errorRes = error.operationErrorRes()
    _state.update {
      it.copy(
        isWorking = false,
        isMoving = false,
        isCancelingMove = false,
        moveProgress = null,
        error = if (errorRes == null) error.userMessage() else null,
        errorRes = errorRes,
      )
    }
  }

  fun clearError() = _state.update { it.copy(error = null, errorRes = null) }

  fun consumeDeletedCount() = _state.update { it.copy(deletedCount = null) }

  fun consumeDeleteCanceled() = _state.update { it.copy(deleteCanceled = false) }

  fun consumeMoveCanceled() = _state.update { it.copy(moveCanceled = false) }

  fun consumeDownloadedSource() = _state.update { it.copy(downloadedSource = null) }

  fun setSort(mode: ObjectSortMode, ascending: Boolean) {
    viewModelScope.launch { settings.setObjectSort(mode, ascending) }
  }

  fun setViewMode(mode: ObjectViewMode) {
    viewModelScope.launch { settings.setObjectViewMode(mode) }
  }

  fun setGalleryThumbnailSize(size: GalleryThumbnailSize) {
    viewModelScope.launch { settings.setGalleryThumbnailSize(size) }
  }

  fun setGalleryImageScale(scale: GalleryImageScale) {
    viewModelScope.launch { settings.setGalleryImageScale(scale) }
  }

  fun setFavorites(items: List<FavoriteItem>, enabled: Boolean, onSaved: () -> Unit) {
    viewModelScope.launch {
      profiles.setFavorites(items, enabled)
      onSaved()
    }
  }

  override fun onCleared() {
    transferManager.dismissFinished(uploadIds)
    super.onCleared()
  }

  private companion object {
    const val TAG = "S3Pocket.Objects"
  }
}

private fun ObjectItem.favorite(profileId: String, bucket: String): FavoriteItem =
  FavoriteItem(
    profileId = profileId,
    bucket = bucket,
    key = key,
    kind = if (isFolder) com.jasongzy.s3pocket.data.model.FavoriteKind.FOLDER
    else com.jasongzy.s3pocket.data.model.FavoriteKind.FILE,
  )

private fun Throwable.operationErrorRes(): Int? =
  when {
    message == "Invalid folder name" -> com.jasongzy.s3pocket.R.string.error_invalid_folder_name
    message == "Recursive folder deletion is disabled" -> com.jasongzy.s3pocket.R.string.error_recursive_delete_disabled
    message == "Source and destination are the same" -> com.jasongzy.s3pocket.R.string.error_same_destination
    message == "Destination is inside source folder" -> com.jasongzy.s3pocket.R.string.error_destination_inside_source
    message?.startsWith("Object already exists:") == true -> com.jasongzy.s3pocket.R.string.error_destination_exists
    else -> null
  }

private data class DeletePlan(val keys: List<String>, val hasNonEmptyFolder: Boolean)

private data class MovePlan(
  val moves: List<ObjectMove>,
  val pathMappings: List<MovePathMapping>,
)

private data class MovePathMapping(
  val source: String,
  val target: String,
  val isPrefix: Boolean,
) {
  fun matches(key: String): Boolean = if (isPrefix) key.startsWith(source) else key == source
}
