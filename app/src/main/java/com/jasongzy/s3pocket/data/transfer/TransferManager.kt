package com.jasongzy.s3pocket.data.transfer

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.jasongzy.s3pocket.data.ProfileRepository
import com.jasongzy.s3pocket.data.model.UploadObject
import com.jasongzy.s3pocket.data.storage.StorageFactory
import com.jasongzy.s3pocket.data.storage.TransferCanceledException
import com.jasongzy.s3pocket.data.storage.UploadSource
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class TransferStatus {
  QUEUED,
  RUNNING,
  COMPLETED,
  FAILED,
  CANCELED,
}

data class TransferItem(
  val id: String,
  val completedBytes: Long = 0,
  val totalBytes: Long,
  val status: TransferStatus = TransferStatus.QUEUED,
  val error: String? = null,
)

sealed interface TransferRequest {
  val profileId: String
  val bucket: String
  val key: String
  val contentLength: Long
}

data class UploadTransfer(
  override val profileId: String,
  override val bucket: String,
  val uri: Uri,
  override val key: String,
  val contentType: String,
  override val contentLength: Long,
  val metadata: Map<String, String>,
  val overwrite: Boolean = false,
) : TransferRequest

data class DownloadTransfer(
  override val profileId: String,
  override val bucket: String,
  val uri: Uri,
  override val key: String,
  override val contentLength: Long,
) : TransferRequest

class TransferManager(
  private val application: Application,
  private val profiles: ProfileRepository,
  private val storageFactory: StorageFactory,
  val staging: TransferStaging,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val slots = Semaphore(2)
  private val requests = mutableMapOf<String, TransferRequest>()
  private val cancellations = mutableMapOf<String, AtomicBoolean>()
  private val retained = mutableSetOf<String>()
  private val _items = MutableStateFlow<List<TransferItem>>(emptyList())
  val items: StateFlow<List<TransferItem>> = _items

  fun enqueue(requests: List<UploadTransfer>): List<String> = enqueueRequests(requests)

  @Synchronized
  fun reservedUploadKeys(profileId: String, bucket: String): Set<String> {
    val activeIds = _items.value.filter { !it.status.isFinished }.mapTo(mutableSetOf()) { it.id }
    return requests.filterKeys { it in activeIds }.values.filterIsInstance<UploadTransfer>()
      .filter { it.profileId == profileId && it.bucket == bucket }.mapTo(mutableSetOf()) { it.key }
  }

  fun enqueueDownload(request: DownloadTransfer): String = enqueueRequests(listOf(request)).single()

  @Synchronized
  private fun enqueueRequests(requests: List<TransferRequest>): List<String> {
    if (requests.isEmpty()) return emptyList()
    val uploads = requests.filterIsInstance<UploadTransfer>()
    require(uploads.map { Triple(it.profileId, it.bucket, it.key) }.distinct().size == uploads.size) {
      "Duplicate upload destination"
    }
    uploads.forEach {
      require(it.key !in reservedUploadKeys(it.profileId, it.bucket)) { "An upload is already queued for: ${it.key}" }
    }
    val ids = requests.map { request ->
      val id = UUID.randomUUID().toString()
      if (request is UploadTransfer) staging.claim(request.uri)
      this.requests[id] = request
      retained += id
      _items.update { it + request.toItem(id) }
      id
    }
    startTransfers(ids)
    return ids
  }

  @Synchronized
  fun cancel(id: String) {
    cancellations[id]?.set(true)
    update(id) { item ->
      if (item.status == TransferStatus.QUEUED) item.copy(status = TransferStatus.CANCELED) else item
    }
    cleanupFinished(id)
  }

  fun cancel(ids: Collection<String>) = ids.forEach(::cancel)

  fun cancelActive() = _items.value.filter { it.status == TransferStatus.QUEUED || it.status == TransferStatus.RUNNING }
    .forEach { cancel(it.id) }

  @Synchronized
  fun retry(id: String) {
    val item = _items.value.firstOrNull { it.id == id } ?: return
    if (item.status != TransferStatus.FAILED) return
    val request = requests[id]
    if (request is UploadTransfer && request.key in reservedUploadKeys(request.profileId, request.bucket)) {
      update(id) { it.copy(error = "An upload is already queued for: ${request.key}") }
      return
    }
    update(id) { it.copy(completedBytes = 0, status = TransferStatus.QUEUED, error = null) }
    startTransfers(listOf(id))
  }

  private fun startTransfers(ids: List<String>) {
    runCatching { ContextCompat.startForegroundService(application, Intent(application, TransferService::class.java)) }
      .onSuccess { ids.forEach(::launch) }
      .onFailure { error ->
        ids.forEach { id ->
          update(id) { it.copy(status = TransferStatus.FAILED, error = error.message ?: error.javaClass.simpleName) }
        }
      }
  }

  @Synchronized
  fun dismissFinished(ids: Collection<String>) {
    val idSet = ids.toSet()
    val finishedIds = _items.value.filter { it.id in idSet && it.status.isFinished }.mapTo(mutableSetOf()) { it.id }
    _items.update { items -> items.filterNot { it.id in finishedIds } }
    finishedIds.forEach { id ->
      retained.remove(id)
      requests.remove(id)?.let(::deleteCachedUploadSource)
      cancellations.remove(id)
    }
  }

  @Synchronized
  fun release(ids: Collection<String>) {
    retained.removeAll(ids.toSet())
    dismissFinished(ids)
  }

  private fun launch(id: String) {
    val request = requests[id] ?: return
    val canceled = AtomicBoolean(false)
    cancellations[id] = canceled
    scope.launch {
      slots.withPermit {
        if (canceled.get()) return@withPermit
        update(id) { it.copy(status = TransferStatus.RUNNING) }
        var transferredBytes = 0L
        val publishProgress = throttledProgress { completed, total ->
          update(id) { it.copy(completedBytes = completed, totalBytes = total.takeIf { size -> size > 0 } ?: it.totalBytes) }
        }
        val reportProgress: (Long, Long) -> Unit = { completed, total ->
          transferredBytes = completed
          publishProgress(completed, total)
        }
        try {
          val profile = profiles.get(request.profileId)
          val storage = storageFactory.create(profile)
          when (request) {
            is UploadTransfer -> storage.upload(
              bucket = request.bucket,
              request = UploadObject(
                key = request.key,
                contentType = request.contentType.ifBlank { "application/octet-stream" },
                contentLength = request.contentLength,
                metadata = request.metadata,
                overwrite = request.overwrite,
              ),
              source = UploadSource {
                application.contentResolver.openInputStream(request.uri) ?: error("Unable to open selected file")
              },
              shouldCancel = canceled::get,
              onProgress = reportProgress,
            )
            is DownloadTransfer -> application.contentResolver.openOutputStream(request.uri, "wt")?.use { output ->
              storage.download(
                request.bucket,
                request.key,
                output,
                shouldCancel = canceled::get,
                onProgress = reportProgress,
              )
            } ?: error("Unable to open destination")
          }
          update(id) { it.copy(completedBytes = transferredBytes, totalBytes = transferredBytes, status = TransferStatus.COMPLETED) }
          cleanupFinished(id)
        } catch (error: Throwable) {
          val isCanceled = canceled.get() || error is TransferCanceledException || error is CancellationException
          update(id) {
            it.copy(
              status = if (isCanceled) TransferStatus.CANCELED else TransferStatus.FAILED,
              error = error.message ?: error.javaClass.simpleName,
            )
          }
          cleanupFinished(id)
        }
      }
    }
  }

  @Synchronized
  private fun cleanupFinished(id: String) {
    if (id !in retained) dismissFinished(listOf(id))
  }

  private fun deleteCachedUploadSource(request: TransferRequest) {
    if (request is UploadTransfer && requests.values.none { it is UploadTransfer && it.uri == request.uri }) {
      staging.discard(request.uri)
    }
  }

  @Synchronized
  private fun update(id: String, block: (TransferItem) -> TransferItem) {
    _items.update { items -> items.map { if (it.id == id) block(it) else it } }
  }
}

val TransferStatus.isFinished: Boolean
  get() = this == TransferStatus.COMPLETED || this == TransferStatus.FAILED || this == TransferStatus.CANCELED

private fun TransferRequest.toItem(id: String) =
  TransferItem(
    id = id,
    totalBytes = contentLength,
  )

internal fun throttledProgress(
  nanoTime: () -> Long = System::nanoTime,
  publish: (Long, Long) -> Unit,
): (Long, Long) -> Unit {
  var lastUpdate: Long? = null
  return { completed, total ->
    val now = nanoTime()
    val previous = lastUpdate
    if (previous == null || now - previous >= 200_000_000L || completed == total) {
      lastUpdate = now
      publish(completed, total)
    }
  }
}
