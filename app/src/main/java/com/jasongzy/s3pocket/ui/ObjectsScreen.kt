package com.jasongzy.s3pocket.ui

import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jasongzy.s3pocket.R
import com.jasongzy.s3pocket.data.GalleryImageScale
import com.jasongzy.s3pocket.data.GalleryThumbnailSize
import com.jasongzy.s3pocket.data.AppSettingsSnapshot
import com.jasongzy.s3pocket.data.ObjectSortMode
import com.jasongzy.s3pocket.data.ObjectViewMode
import com.jasongzy.s3pocket.data.ProfileRepository
import com.jasongzy.s3pocket.data.SafTreeScanner
import com.jasongzy.s3pocket.data.SettingsRepository
import com.jasongzy.s3pocket.data.model.FavoriteKind
import com.jasongzy.s3pocket.data.model.FavoriteItem
import com.jasongzy.s3pocket.data.model.ObjectItem
import com.jasongzy.s3pocket.data.model.SharedUploadSource
import com.jasongzy.s3pocket.data.storage.StorageFactory
import com.jasongzy.s3pocket.data.storage.ThumbnailUrlRepository
import com.jasongzy.s3pocket.data.transfer.TransferManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SelectedDocument(
  val uri: Uri,
  val name: String,
  val contentType: String,
  val size: Long,
  val sourceUrl: String? = null,
  val sharedSource: SharedUploadSource? = null,
)

private data class PendingUpload(
  val document: SelectedDocument,
  val key: String,
  val contentType: String,
  val metadata: Map<String, String>,
)

private data class PendingBatchUpload(
  val documents: List<SelectedDocument>,
  val metadata: Map<String, String>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectsScreen(
  profileId: String,
  bucket: String,
  prefix: String,
  repository: ProfileRepository,
  storageFactory: StorageFactory,
  settingsRepository: SettingsRepository,
  settings: AppSettingsSnapshot,
  transferManager: TransferManager,
  thumbnailUrls: ThumbnailUrlRepository,
  onBack: () -> Unit,
  onOpenFolder: (String) -> Unit,
  onOpenObject: (String) -> Unit,
  onNavigateToPrefix: (String) -> Unit,
  sharedUris: List<Uri>,
  sharedRequestId: String,
  openSharedImmediately: Boolean,
  isConfirmingSharedFiles: Boolean,
  onSharedUrisOpened: () -> Boolean,
  onDismissSharedFiles: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val viewModel: ObjectsViewModel = viewModel {
    ObjectsViewModel(
      profileId,
      bucket,
      prefix,
      repository,
      settingsRepository,
      storageFactory,
      transferManager,
    )
  }
  val state by viewModel.state.collectAsStateWithLifecycle()
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LifecycleResumeEffect(viewModel) {
    viewModel.refreshIfChanged()
    onPauseOrDispose { viewModel.cancelUploadNavigation() }
  }
  val stagingSession = remember(transferManager) { transferManager.staging.openSession() }
  DisposableEffect(stagingSession) {
    onDispose { stagingSession.close() }
  }
  val favorites by viewModel.favorites.collectAsStateWithLifecycle()
  val metadataTemplate = settings.metadataTemplate
  var selected by remember { mutableStateOf<SelectedDocument?>(null) }
  var sharedDocuments by remember { mutableStateOf<List<SelectedDocument>?>(null) }
  var openSharedRequested by remember(sharedRequestId) { mutableStateOf(false) }
  var selectionError by remember { mutableStateOf<String?>(null) }
  var isScanningFolder by remember { mutableStateOf(false) }
  var selectedKeys by remember(prefix) { mutableStateOf(emptySet<String>()) }
  var createFolder by remember { mutableStateOf(false) }
  var deletingItems by remember { mutableStateOf<List<ObjectItem>?>(null) }
  var movingItems by remember { mutableStateOf<List<ObjectItem>?>(null) }
  var pendingUpload by remember { mutableStateOf<PendingUpload?>(null) }
  var pendingBatchUpload by remember { mutableStateOf<PendingBatchUpload?>(null) }
  var uploadCheck by remember { mutableStateOf<Job?>(null) }
  fun checkUpload(action: suspend () -> Unit) {
    if (uploadCheck != null) return
    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) viewModel.prepareUploadNavigation()
    uploadCheck = scope.launch(start = CoroutineStart.LAZY) {
      try {
        runSuspendCatching { action() }.onFailure { selectionError = it.userMessage() }
      } finally {
        uploadCheck = null
      }
    }
    uploadCheck?.start()
  }
  val sort = settings.objectSort
  val ascending = settings.objectSortAscending
  val viewMode = settings.objectViewMode
  val galleryThumbnailSize = settings.galleryThumbnailSize
  val galleryImageScale = settings.galleryImageScale
  var showMore by remember { mutableStateOf(false) }
  var showGalleryOptions by remember { mutableStateOf(false) }
  var showUploadSources by remember { mutableStateOf(false) }
  var showUrlUpload by remember { mutableStateOf(false) }
  var searchActive by rememberSaveable(prefix) { mutableStateOf(false) }
  var query by rememberSaveable(prefix) { mutableStateOf("") }
  val searchFocusRequester = remember { FocusRequester() }
  var showPathMenu by remember { mutableStateOf(false) }
  fun closeSearch() {
    searchActive = false
    query = ""
  }
  val displayedObjects = remember(state.objects, sort, ascending, query) {
    val comparator = when (sort) {
      ObjectSortMode.NAME -> compareBy<ObjectItem> { it.name.lowercase() }
      ObjectSortMode.DATE -> compareBy { it.lastModified }
      ObjectSortMode.SIZE -> compareBy { it.size }
    }
    state.objects.filter { it.name.contains(query.trim(), ignoreCase = true) }.sortedWith(
      compareByDescending<ObjectItem> { it.isFolder }.then(if (ascending) comparator else comparator.reversed())
    )
  }
  LaunchedEffect(displayedObjects) {
    selectedKeys = selectedKeys.intersect(displayedObjects.mapTo(mutableSetOf()) { it.key })
  }
  LaunchedEffect(query, sort, ascending, state.nextContinuationToken) {
    viewModel.setCompleteListingRequired(
      query.isNotBlank() || sort != ObjectSortMode.NAME || !ascending,
    )
  }
  LaunchedEffect(searchActive) {
    if (searchActive) searchFocusRequester.requestFocus()
  }
  val unknownFileSize = stringResource(R.string.unknown_file_size)
  val currentFavorite = remember(profileId, bucket, prefix) {
    FavoriteItem(
      profileId = profileId,
      bucket = bucket,
      key = prefix,
      kind = if (prefix.isEmpty()) FavoriteKind.BUCKET else FavoriteKind.FOLDER,
    )
  }
  val currentPathIsFavorite = favorites.any { it.id == currentFavorite.id }
  BackHandler(enabled = selectedKeys.isNotEmpty() || searchActive) {
    if (selectedKeys.isNotEmpty()) selectedKeys = emptySet() else closeSearch()
  }
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      scope.launch {
        runSuspendCatching { context.contentResolver.document(uri, unknownFileSize) }
          .onSuccess { selected = it }
          .onFailure { selectionError = it.userMessage() }
      }
    }
  }
  val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri != null) {
      runCatching {
        context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      scope.launch {
        isScanningFolder = true
        runSuspendCatching {
          SafTreeScanner(context.contentResolver, stagingSession::create).scan(uri)
        }
          .onSuccess { files ->
            sharedDocuments = files.map { SelectedDocument(it.uri, it.relativePath, it.contentType, it.size) }
          }
          .onFailure { selectionError = it.userMessage() }
        isScanningFolder = false
      }
    }
  }
  fun isUploadBusy(): Boolean =
    selected != null || sharedDocuments != null || pendingUpload != null || pendingBatchUpload != null ||
      uploadCheck != null || state.uploadProgress != null || showUrlUpload || isScanningFolder ||
      state.isDownloadingSource || state.downloadedSource != null

  LaunchedEffect(sharedRequestId) {
    if (sharedUris.isNotEmpty()) viewModel.cancelUploadNavigation()
    val draftRequestId = (selected?.sharedSource ?: sharedDocuments?.firstOrNull()?.sharedSource ?:
      pendingUpload?.document?.sharedSource ?: pendingBatchUpload?.documents?.firstOrNull()?.sharedSource)?.requestId
    if (draftRequestId != null && draftRequestId != sharedRequestId) {
      uploadCheck?.cancel()
      selected = null
      sharedDocuments = null
      pendingUpload = null
      pendingBatchUpload = null
    }
  }
  LaunchedEffect(sharedRequestId, sharedUris, openSharedImmediately, openSharedRequested, isConfirmingSharedFiles) {
    if (isConfirmingSharedFiles || sharedUris.isEmpty() || !(openSharedImmediately || openSharedRequested)) return@LaunchedEffect
    if (isUploadBusy()) {
      onSharedUrisOpened()
      openSharedRequested = false
      return@LaunchedEffect
    }
    val results = sharedUris.map { uri ->
      runSuspendCatching {
        context.contentResolver.document(uri, unknownFileSize)
          .copy(sharedSource = SharedUploadSource(sharedRequestId, uri))
      }
    }
    if (!onSharedUrisOpened()) return@LaunchedEffect
    openSharedRequested = false
    if (isUploadBusy()) return@LaunchedEffect
    results.firstOrNull { it.isFailure }?.exceptionOrNull()?.let { selectionError = it.userMessage() }
    val documents = results.mapNotNull(Result<SelectedDocument>::getOrNull)
    if (documents.size == 1) selected = documents.first()
    else if (documents.isNotEmpty()) sharedDocuments = documents
  }
  state.deletedCount?.let { count ->
    val message = pluralStringResource(R.plurals.deleted_count, count, count)
    LaunchedEffect(count) {
      selectedKeys = emptySet()
      Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
      viewModel.consumeDeletedCount()
    }
  }
  state.uploadResult?.let { result ->
    val message = if (result.completed == result.total) {
      pluralStringResource(R.plurals.uploaded_count, result.completed, result.completed)
    } else {
      pluralStringResource(R.plurals.operation_canceled_progress, result.total, result.completed, result.total)
    }
    LaunchedEffect(result) {
      val uploadedKey = viewModel.consumeUploadResult(result)
      Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
      if (uploadedKey != null && !isConfirmingSharedFiles && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
        onOpenObject(uploadedKey)
      }
    }
  }
  if (state.deleteCanceled) {
    val message = state.partialProgress?.let {
      pluralStringResource(R.plurals.operation_canceled_progress, it.total, it.completed, it.total)
    } ?: stringResource(R.string.deletion_canceled)
    LaunchedEffect(Unit) {
      Toast.makeText(context, message, Toast.LENGTH_LONG).show()
      viewModel.consumeDeleteCanceled()
    }
  }
  if (state.moveCanceled) {
    val message = state.partialProgress?.let {
      pluralStringResource(R.plurals.operation_canceled_progress, it.total, it.completed, it.total)
    } ?: stringResource(R.string.move_canceled)
    LaunchedEffect(Unit) {
      Toast.makeText(context, message, Toast.LENGTH_LONG).show()
      viewModel.consumeMoveCanceled()
    }
  }
  state.downloadedSource?.let { source ->
    LaunchedEffect(source.uri) {
      selected = SelectedDocument(source.uri, source.name, source.contentType, source.size, source.sourceUrl)
      viewModel.consumeDownloadedSource()
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          if (selectedKeys.isNotEmpty()) {
            Text(pluralStringResource(R.plurals.selected_count, selectedKeys.size, selectedKeys.size))
          } else {
            Box(modifier = Modifier.fillMaxWidth()) {
              AnimatedVisibility(
                visible = !searchActive,
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(90)),
              ) {
                Box {
                  Row(
                    modifier = Modifier.clickable { showPathMenu = true },
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Column {
                      Text(if (prefix.isEmpty()) bucket else prefix.removeSuffix("/").substringAfterLast('/'))
                      Text(
                        if (prefix.isEmpty()) stringResource(R.string.bucket_root) else prefix,
                        style = MaterialTheme.typography.labelSmall,
                      )
                    }
                    AppIcon(R.drawable.ms_arrow_drop_down_24, stringResource(R.string.path_navigation))
                  }
                  DropdownMenu(expanded = showPathMenu, onDismissRequest = { showPathMenu = false }) {
                    pathEntries(bucket, prefix).forEach { (label, targetPrefix) ->
                      DropdownMenuItem(
                        text = { Text(label) },
                        leadingIcon = {
                          AppIcon(if (targetPrefix.isEmpty()) R.drawable.ms_database_24 else R.drawable.ms_folder_24, null)
                        },
                        onClick = {
                          showPathMenu = false
                          if (targetPrefix != prefix) onNavigateToPrefix(targetPrefix)
                        },
                      )
                    }
                  }
                }
              }
              AnimatedVisibility(
                visible = searchActive,
                enter = expandHorizontally(tween(220), expandFrom = Alignment.End) + fadeIn(tween(140)),
                exit = shrinkHorizontally(tween(180), shrinkTowards = Alignment.End) + fadeOut(tween(100)),
              ) {
                TextField(
                  value = query,
                  onValueChange = { query = it },
                  placeholder = { Text(stringResource(R.string.search_hint)) },
                  singleLine = true,
                  modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester),
                )
              }
            }
          }
        },
        navigationIcon = {
          BackButton {
            when {
              selectedKeys.isNotEmpty() -> selectedKeys = emptySet()
              searchActive -> closeSearch()
              else -> onBack()
            }
          }
        },
        actions = {
          if (selectedKeys.isEmpty()) {
            AnimatedVisibility(
              visible = searchActive,
              enter = fadeIn(tween(140)),
              exit = fadeOut(tween(90)),
            ) {
              TooltipIconButton(stringResource(R.string.close), ::closeSearch) {
                AppIcon(R.drawable.ms_close_24, null)
              }
            }
            AnimatedVisibility(
              visible = !searchActive,
              enter = expandHorizontally(tween(180), expandFrom = Alignment.End) + fadeIn(tween(120)),
              exit = shrinkHorizontally(tween(180), shrinkTowards = Alignment.End) + fadeOut(tween(90)),
            ) {
              Row {
                TooltipIconButton(
                  stringResource(if (currentPathIsFavorite) R.string.remove_from_favorites else R.string.favorite_current_folder),
                  {
                    viewModel.setFavorites(listOf(currentFavorite), !currentPathIsFavorite) {
                      context.showFavoriteToast(!currentPathIsFavorite)
                    }
                  },
                ) {
                  AppIcon(if (currentPathIsFavorite) R.drawable.ms_star_24 else R.drawable.ms_star_outline_24, null)
                }
                TooltipIconButton(stringResource(R.string.search_files), { searchActive = true }) {
                  AppIcon(R.drawable.ms_search_24, null)
                }
                TooltipIconButton(
                  stringResource(if (viewMode == ObjectViewMode.LIST) R.string.gallery_view else R.string.list_view),
                  {
                    viewModel.setViewMode(
                      if (viewMode == ObjectViewMode.LIST) ObjectViewMode.GALLERY else ObjectViewMode.LIST,
                    )
                  },
                ) {
                  AppIcon(if (viewMode == ObjectViewMode.LIST) R.drawable.ms_grid_view_24 else R.drawable.ms_view_list_24, null)
                }
                Box {
                  TooltipIconButton(stringResource(R.string.more_actions), { showMore = true }) {
                    AppIcon(R.drawable.ms_more_vert_24, null)
                  }
                  DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                    DropdownMenuItem(
                      text = { Text(stringResource(R.string.refresh)) },
                      leadingIcon = { AppIcon(R.drawable.ms_refresh_24, null) },
                      onClick = { showMore = false; viewModel.refresh() },
                    )
                    ObjectSortMode.entries.forEach { option ->
                      DropdownMenuItem(
                        text = { Text(stringResource(when (option) {
                          ObjectSortMode.NAME -> R.string.sort_name
                          ObjectSortMode.DATE -> R.string.sort_date
                          ObjectSortMode.SIZE -> R.string.sort_size
                        })) },
                        leadingIcon = { RadioButton(selected = sort == option, onClick = null) },
                        onClick = { showMore = false; viewModel.setSort(option, ascending) },
                      )
                    }
                    DropdownMenuItem(
                      text = { Text(stringResource(R.string.sort_ascending)) },
                      leadingIcon = { RadioButton(selected = ascending, onClick = null) },
                      onClick = { showMore = false; viewModel.setSort(sort, true) },
                    )
                    DropdownMenuItem(
                      text = { Text(stringResource(R.string.sort_descending)) },
                      leadingIcon = { RadioButton(selected = !ascending, onClick = null) },
                      onClick = { showMore = false; viewModel.setSort(sort, false) },
                    )
                    if (viewMode == ObjectViewMode.GALLERY) {
                      DropdownMenuItem(
                        text = { Text(stringResource(R.string.gallery_options)) },
                        leadingIcon = { AppIcon(R.drawable.ms_grid_view_24, null) },
                        onClick = { showMore = false; showGalleryOptions = true },
                      )
                    }
                  }
                }
              }
            }
          } else {
            val selectedItems = displayedObjects.filter { it.key in selectedKeys }
            val selectedFavorites = selectedItems.map { it.favorite(profileId, bucket) }
            val allFavorite = selectedFavorites.isNotEmpty() && selectedFavorites.all { candidate ->
              favorites.any { it.id == candidate.id }
            }
            TextButton(onClick = { selectedKeys = displayedObjects.mapTo(mutableSetOf()) { it.key } }) {
              Text(stringResource(R.string.select_all))
            }
            TooltipIconButton(
              stringResource(if (allFavorite) R.string.remove_from_favorites else R.string.add_to_favorites),
              {
                viewModel.setFavorites(selectedFavorites, !allFavorite) { context.showFavoriteToast(!allFavorite) }
                selectedKeys = emptySet()
              },
            ) {
              AppIcon(if (allFavorite) R.drawable.ms_star_24 else R.drawable.ms_star_outline_24, null)
            }
            TooltipIconButton(
              label = stringResource(R.string.move),
              onClick = { movingItems = selectedItems },
              enabled = !state.isWorking,
            ) {
              AppIcon(R.drawable.ms_drive_file_move_24, null)
            }
            TooltipIconButton(stringResource(R.string.delete), { deletingItems = selectedItems }, enabled = !state.isWorking) {
              AppIcon(R.drawable.ms_delete_24, null)
            }
          }
        },
      )
    },
    floatingActionButton = {
      Box {
        FloatingActionButton(
          onClick = { showUploadSources = true },
        ) {
          AppIcon(R.drawable.ms_upload_file_24, stringResource(R.string.more_actions))
        }
        DropdownMenu(expanded = showUploadSources, onDismissRequest = { showUploadSources = false }) {
          DropdownMenuItem(
            text = { Text(stringResource(R.string.create_folder)) },
            onClick = { showUploadSources = false; createFolder = true },
            enabled = !state.isWorking,
          )
          DropdownMenuItem(
            text = { Text(stringResource(R.string.upload_local_file)) },
            onClick = { showUploadSources = false; picker.launch(arrayOf("*/*")) },
          )
          DropdownMenuItem(
            text = { Text(stringResource(R.string.upload_folder)) },
            onClick = { showUploadSources = false; folderPicker.launch(null) },
          )
          DropdownMenuItem(
            text = { Text(stringResource(R.string.upload_from_url)) },
            onClick = { showUploadSources = false; showUrlUpload = true },
          )
        }
      }
    },
  ) { padding ->
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
      if (sharedUris.isNotEmpty() && !openSharedImmediately) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            pluralStringResource(R.plurals.shared_files_waiting, sharedUris.size, sharedUris.size),
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
          )
          TextButton(onClick = { openSharedRequested = true }, enabled = !openSharedRequested) {
            Text(stringResource(R.string.upload_here))
          }
          TooltipIconButton(stringResource(R.string.cancel), onDismissSharedFiles) {
            AppIcon(R.drawable.ms_close_24, null)
          }
        }
      }
      if (state.isDeleting) {
        CancellableOperationProgress(
          progress = state.deleteProgress,
          label = state.deleteProgress?.let { stringResource(R.string.delete_progress, it.completed, it.total) }
            ?: stringResource(R.string.preparing_deletion),
          isCanceling = state.isCancelingDelete,
          onCancel = viewModel::cancelDelete,
        )
      } else if (state.isMoving) {
        CancellableOperationProgress(
          progress = state.moveProgress,
          label = state.moveProgress?.let { stringResource(R.string.move_progress, it.completed, it.total) }
            ?: stringResource(R.string.preparing_move),
          isCanceling = state.isCancelingMove,
          onCancel = viewModel::cancelMove,
        )
      } else if (state.isUploading || state.isWorking || state.isDownloadingSource || isScanningFolder) {
        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
      } else if (state.showListingProgress) {
        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
          pluralStringResource(R.plurals.loading_items, state.objects.size, state.objects.size),
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      PullToRefreshBox(
        isRefreshing = state.isLoading && state.objects.isNotEmpty(),
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
      ) {
        when {
          state.objects.isEmpty() && (state.isLoading || state.isLoadingAll) -> LoadingPane(PaddingValues())
          state.error != null || state.errorRes != null ->
            MessagePane(
              stringResource(R.string.storage_request_failed),
              listOfNotNull(
                state.partialProgress?.let {
                  stringResource(R.string.operation_partial_failure, it.completed, it.total)
                },
                state.errorRes?.let { stringResource(it) } ?: state.error,
              ).joinToString("\n\n"),
              PaddingValues(),
              stringResource(R.string.refresh),
              viewModel::refresh,
            )
          state.objects.isEmpty() ->
            MessagePane(
              stringResource(R.string.empty_folder),
              stringResource(R.string.empty_folder_description),
              PaddingValues(),
            )
          displayedObjects.isEmpty() && state.isLoadingAll -> LoadingPane(PaddingValues())
          displayedObjects.isEmpty() ->
            MessagePane(
              stringResource(R.string.no_search_results),
              query,
              PaddingValues(),
            )
          viewMode == ObjectViewMode.GALLERY -> LazyVerticalGrid(
            columns = GridCells.Fixed(galleryThumbnailSize.columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            gridItems(displayedObjects, key = { it.key }) { item ->
              GalleryObject(
                item = item,
                selected = item.key in selectedKeys,
                thumbnailUrl = { thumbnailUrls.get(profileId, bucket, item.key) },
                imageScale = galleryImageScale,
                onClick = {
                  if (selectedKeys.isNotEmpty()) selectedKeys = selectedKeys.toggle(item.key)
                  else if (item.isFolder) onOpenFolder(item.key) else onOpenObject(item.key)
                },
                onLongClick = { selectedKeys = selectedKeys.toggle(item.key) },
              )
            }
            if (state.nextContinuationToken != null && !state.isLoadingAll) {
              item(key = "load-more-${state.nextContinuationToken}") {
                LaunchedEffect(state.nextContinuationToken) { viewModel.loadMore() }
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
              }
            }
            item { Spacer(Modifier.size(88.dp)) }
          }
          else -> LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(displayedObjects, key = { it.key }) { item ->
              ObjectRow(
                item = item,
                selected = item.key in selectedKeys,
                onClick = {
                  if (selectedKeys.isNotEmpty()) {
                    selectedKeys = selectedKeys.toggle(item.key)
                  } else if (item.isFolder) {
                    onOpenFolder(item.key)
                  } else {
                    onOpenObject(item.key)
                  }
                },
                onLongClick = { selectedKeys = selectedKeys.toggle(item.key) },
              )
            }
            if (state.nextContinuationToken != null && !state.isLoadingAll) {
              item(key = "load-more-${state.nextContinuationToken}") {
                LaunchedEffect(state.nextContinuationToken) { viewModel.loadMore() }
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
              }
            }
            item { Spacer(Modifier.size(88.dp)) }
          }
        }
      }
    }
  }

  if (showGalleryOptions) {
    GalleryOptionsDialog(
      thumbnailSize = galleryThumbnailSize,
      imageScale = galleryImageScale,
      onThumbnailSizeChange = viewModel::setGalleryThumbnailSize,
      onImageScaleChange = viewModel::setGalleryImageScale,
      onDismiss = { showGalleryOptions = false },
    )
  }

  selected?.let { document ->
    UploadDialog(
      document = document,
      initialKey = prefix + document.name,
      isUploading = state.isUploading,
      initialMetadata = uploadMetadata(metadataTemplate, document.sourceUrl),
      onDismiss = {
        if (!state.isUploading) {
          transferManager.staging.discard(document.uri)
          selected = null
        }
      },
      onUpload = { key, type, metadata ->
        checkUpload {
          if (viewModel.hasUploadConflicts(listOf(key))) {
            pendingUpload = PendingUpload(document, key, type, metadata)
          } else {
            viewModel.upload(document.uri, key, type, document.size, metadata, sharedSource = document.sharedSource)
          }
          selected = null
        }
      },
    )
  }
  sharedDocuments?.let { documents ->
    BatchUploadDialog(
      documents = documents,
      isUploading = state.isUploading,
      initialMetadata = metadataTemplate,
      onDismiss = {
        if (!state.isUploading) {
          transferManager.staging.discard(documents.map(SelectedDocument::uri))
          sharedDocuments = null
        }
      },
      onUpload = { metadata ->
        checkUpload {
          val keys = documents.map { prefix + it.name }
          if (viewModel.hasUploadConflicts(keys)) {
            pendingBatchUpload = PendingBatchUpload(documents, metadata)
          } else {
            viewModel.uploadBatch(documents.toBatchUploadItems(keys, metadata))
          }
          sharedDocuments = null
        }
      },
    )
  }
  if (showUrlUpload) {
    TextInputDialog(
      title = stringResource(R.string.upload_from_url),
      label = stringResource(R.string.source_url),
      initialValue = context.clipboardHttpsUrl().orEmpty(),
      supportingText = stringResource(R.string.url_download_description),
      confirmLabel = stringResource(R.string.download_and_upload),
      onDismiss = { showUrlUpload = false },
      onConfirm = { url -> showUrlUpload = false; viewModel.downloadSource(url) },
    )
  }
  if (createFolder) {
    TextInputDialog(
      title = stringResource(R.string.create_folder),
      label = stringResource(R.string.folder_name),
      onDismiss = { createFolder = false },
      onConfirm = { viewModel.createFolder(it); createFolder = false },
    )
  }
  deletingItems?.let { items ->
    val folderCount = items.count { it.isFolder }
    DeleteObjectsDialog(
      fileCount = items.size - folderCount,
      folderCount = folderCount,
      onDismiss = { deletingItems = null },
      onConfirm = { viewModel.requestDelete(items); deletingItems = null },
    )
  }
  state.pendingRecursiveDelete?.let { items ->
    val folderCount = items.count { it.isFolder }
    DeleteObjectsDialog(
      fileCount = items.size - folderCount,
      folderCount = folderCount,
      recursive = true,
      onDismiss = viewModel::cancelRecursiveDelete,
      onConfirm = viewModel::confirmRecursiveDelete,
    )
  }
  movingItems?.let { items ->
    TextInputDialog(
      title = pluralStringResource(R.plurals.move_items_title, items.size, items.size),
      label = stringResource(R.string.destination_folder),
      initialValue = prefix,
      supportingText = stringResource(R.string.move_items_description),
      confirmLabel = stringResource(R.string.move),
      onDismiss = { movingItems = null },
      allowBlank = true,
      onConfirm = { destination ->
        viewModel.move(items, destination)
        selectedKeys = emptySet()
        movingItems = null
      },
    )
  }
  pendingUpload?.let { upload ->
    AlertDialog(
      onDismissRequest = {
        transferManager.staging.discard(upload.document.uri)
        pendingUpload = null
      },
      title = { Text(stringResource(R.string.object_exists_title)) },
      text = { Text(stringResource(R.string.object_exists_description, upload.key)) },
      confirmButton = {
        Row {
          TextButton(onClick = {
            checkUpload {
              val key = viewModel.availableUploadKeys(listOf(upload.key)).single()
              viewModel.upload(
                upload.document.uri, key, upload.contentType, upload.document.size, upload.metadata,
                sharedSource = upload.document.sharedSource,
              )
              pendingUpload = null
            }
          }) { Text(stringResource(R.string.auto_rename)) }
          TextButton(onClick = {
            checkUpload {
              viewModel.upload(
                upload.document.uri, upload.key, upload.contentType, upload.document.size, upload.metadata,
                overwrite = true, sharedSource = upload.document.sharedSource,
              )
              pendingUpload = null
            }
          }) { Text(stringResource(R.string.overwrite)) }
        }
      },
      dismissButton = {
        TextButton(onClick = {
          transferManager.staging.discard(upload.document.uri)
          pendingUpload = null
        }) { Text(stringResource(R.string.cancel)) }
      },
    )
  }
  pendingBatchUpload?.let { upload ->
    AlertDialog(
      onDismissRequest = {
        transferManager.staging.discard(upload.documents.map(SelectedDocument::uri))
        pendingBatchUpload = null
      },
      title = { Text(stringResource(R.string.objects_exist_title)) },
      text = { Text(stringResource(R.string.objects_exist_description)) },
      confirmButton = {
        Row {
          TextButton(onClick = {
            checkUpload {
              val keys = viewModel.availableUploadKeys(upload.documents.map { prefix + it.name })
              viewModel.uploadBatch(upload.documents.toBatchUploadItems(keys, upload.metadata))
              pendingBatchUpload = null
            }
          }) { Text(stringResource(R.string.auto_rename)) }
          TextButton(onClick = {
            checkUpload {
              viewModel.uploadBatch(
                upload.documents.toBatchUploadItems(upload.documents.map { prefix + it.name }, upload.metadata),
                overwrite = true,
              )
              pendingBatchUpload = null
            }
          }) { Text(stringResource(R.string.overwrite)) }
        }
      },
      dismissButton = {
        TextButton(onClick = {
          transferManager.staging.discard(upload.documents.map(SelectedDocument::uri))
          pendingBatchUpload = null
        }) { Text(stringResource(R.string.cancel)) }
      },
    )
  }
  uploadCheck?.let { job ->
    AlertDialog(
      onDismissRequest = { job.cancel() },
      title = { Text(stringResource(R.string.preparing_upload)) },
      text = { LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth()) },
      confirmButton = {},
      dismissButton = {
        TextButton(onClick = { job.cancel() }) { Text(stringResource(R.string.cancel)) }
      },
    )
  }
  selectionError?.let { error ->
    AlertDialog(
      onDismissRequest = { selectionError = null },
      title = { Text(stringResource(R.string.cannot_use_file)) },
      text = { SelectionContainer { Text(error) } },
      confirmButton = { TextButton(onClick = { selectionError = null }) { Text(stringResource(R.string.ok)) } },
    )
  }
  state.uploadProgress?.let { progress ->
    AlertDialog(
      onDismissRequest = {},
      title = { Text(stringResource(R.string.uploading_files)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          LinearWavyProgressIndicator(
            progress = {
              if (progress.totalBytes > 0) progress.completedBytes.toFloat() / progress.totalBytes
              else progress.completed.toFloat() / progress.total
            },
            modifier = Modifier.fillMaxWidth(),
          )
          Text(stringResource(R.string.upload_progress, progress.completed, progress.total))
          if (progress.totalBytes > 0) {
            Text(
              stringResource(R.string.transfer_bytes, formatBytes(progress.completedBytes), formatBytes(progress.totalBytes)),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          progress.error?.let { SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error) } }
        }
      },
      confirmButton = {
        if (progress.error != null && !progress.active) {
          TextButton(onClick = viewModel::retryUploads) { Text(stringResource(R.string.retry)) }
        }
      },
      dismissButton = {
        TextButton(onClick = viewModel::cancelUploads) {
          Text(stringResource(if (progress.active) R.string.cancel else R.string.dismiss))
        }
      },
    )
  }
}

@Composable
private fun CancellableOperationProgress(
  progress: ItemProgress?,
  label: String,
  isCanceling: Boolean,
  onCancel: () -> Unit,
) {
  if (progress == null) {
    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
  } else {
    LinearWavyProgressIndicator(
      progress = { if (progress.total == 0) 1f else progress.completed.toFloat() / progress.total },
      modifier = Modifier.fillMaxWidth(),
    )
  }
  Row(
    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      label,
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = onCancel, enabled = !isCanceling) {
      Text(stringResource(if (isCanceling) R.string.canceling else R.string.cancel))
    }
  }
}

private fun List<SelectedDocument>.toBatchUploadItems(
  keys: List<String>,
  metadata: Map<String, String>,
): List<BatchUploadItem> {
  require(keys.size == size)
  return zip(keys) { document, key ->
    BatchUploadItem(document.uri, key, document.contentType, document.size, metadata, document.sharedSource)
  }
}

internal fun pathEntries(bucket: String, prefix: String): List<Pair<String, String>> {
  val entries = mutableListOf(bucket to "")
  var start = 0
  prefix.forEachIndexed { index, char ->
    if (char == '/') {
      entries += prefix.substring(start, index).ifEmpty { "/" } to prefix.substring(0, index + 1)
      start = index + 1
    }
  }
  return entries
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ObjectRow(item: ObjectItem, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .padding(horizontal = 20.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    AppIcon(
      item.iconResource(),
      null,
      Modifier.size(28.dp),
      MaterialTheme.colorScheme.primary,
    )
    Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
      Text(item.name, maxLines = 2)
      if (!item.isFolder) {
        Text(
          stringResource(R.string.file_summary, formatBytes(item.size), formatInstant(item.lastModified)),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    AppIcon(
      R.drawable.ms_chevron_right_24,
      null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun TextInputDialog(
  title: String,
  label: String,
  onDismiss: () -> Unit,
  onConfirm: (String) -> Unit,
  initialValue: String = "",
  supportingText: String? = null,
  confirmLabel: String? = null,
  allowBlank: Boolean = false,
) {
  var value by remember { mutableStateOf(initialValue) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(value, { value = it }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
        supportingText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
      }
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(value) }, enabled = allowBlank || value.isNotBlank()) {
        Text(confirmLabel ?: stringResource(R.string.ok))
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
  )
}

private fun Set<String>.toggle(key: String): Set<String> = if (key in this) this - key else this + key

internal fun ObjectItem.iconResource(): Int {
  if (isFolder) return R.drawable.ms_folder_24
  return when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "avif", "svg" -> R.drawable.ms_image_24
    "mp4", "mkv", "mov", "avi", "webm", "m4v" -> R.drawable.ms_video_file_24
    "mp3", "wav", "flac", "aac", "m4a", "ogg" -> R.drawable.ms_audio_file_24
    "pdf" -> R.drawable.ms_picture_as_pdf_24
    "zip", "rar", "7z", "tar", "gz", "bz2", "xz" -> R.drawable.ms_archive_24
    "kt", "java", "py", "js", "ts", "html", "css", "xml", "json", "yaml", "yml", "sh" -> R.drawable.ms_code_24
    "csv", "xls", "xlsx", "ods" -> R.drawable.ms_table_chart_24
    "txt", "md", "doc", "docx", "rtf" -> R.drawable.ms_description_24
    else -> R.drawable.ms_draft_24
  }
}

internal fun ObjectItem.isPreviewImage(): Boolean = !isFolder &&
  name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "avif")

private suspend fun ContentResolver.document(uri: Uri, unknownSizeError: String): SelectedDocument = withContext(Dispatchers.IO) {
  require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "The sending app must share a content URI" }
  var name: String? = null
  var size: Long? = null
  val cursor = query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
  cursor?.use {
    if (it.moveToFirst()) {
      val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      if (nameIndex >= 0) name = it.getString(nameIndex)
      val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
      if (sizeIndex >= 0 && !it.isNull(sizeIndex)) size = it.getLong(sizeIndex)
    }
  }
  val length = size?.takeIf { it >= 0 } ?: openAssetFileDescriptor(uri, "r")?.use { it.length }.takeIf { it != null && it >= 0 }
  require(length != null) { unknownSizeError }
  SelectedDocument(uri, name ?: "upload", getType(uri) ?: "application/octet-stream", length)
}

private fun Context.clipboardHttpsUrl(): String? {
  val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  return clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
    ?.takeIf { parseDownloadUrl(it) != null }
}
