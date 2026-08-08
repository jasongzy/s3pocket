package com.jasongzy.s3pocket.ui

import android.content.ContentResolver
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jasongzy.s3pocket.R
import com.jasongzy.s3pocket.data.ProfileRepository
import com.jasongzy.s3pocket.data.SettingsRepository
import com.jasongzy.s3pocket.data.ObjectSortMode
import com.jasongzy.s3pocket.data.model.ObjectItem
import com.jasongzy.s3pocket.data.model.FavoriteItem
import com.jasongzy.s3pocket.data.model.FavoriteKind
import com.jasongzy.s3pocket.data.storage.StorageFactory

private data class SelectedDocument(
  val uri: Uri,
  val name: String,
  val contentType: String,
  val size: Long,
  val sourceUrl: String? = null,
)

private data class MetadataEntry(val key: String = "", val value: String = "")

private data class PendingUpload(
  val document: SelectedDocument,
  val key: String,
  val contentType: String,
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
  onBack: () -> Unit,
  onOpenFolder: (String) -> Unit,
  onOpenObject: (String) -> Unit,
  onNavigateToPrefix: (String) -> Unit,
) {
  val context = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val viewModel: ObjectsViewModel = viewModel {
    ObjectsViewModel(
      profileId,
      bucket,
      prefix,
      repository,
      settingsRepository,
      storageFactory,
      context.contentResolver,
      context.cacheDir,
    )
  }
  val state by viewModel.state.collectAsStateWithLifecycle()
  val favorites by viewModel.favorites.collectAsStateWithLifecycle()
  val metadataTemplate by settingsRepository.metadataTemplate.collectAsStateWithLifecycle(emptyMap())
  var selected by remember { mutableStateOf<SelectedDocument?>(null) }
  var selectionError by remember { mutableStateOf<String?>(null) }
  var selectedKeys by remember(prefix) { mutableStateOf(emptySet<String>()) }
  var createFolder by remember { mutableStateOf(false) }
  var deletingItems by remember { mutableStateOf<List<ObjectItem>?>(null) }
  var movingItems by remember { mutableStateOf<List<ObjectItem>?>(null) }
  var pendingUpload by remember { mutableStateOf<PendingUpload?>(null) }
  val sort by settingsRepository.objectSort.collectAsStateWithLifecycle(ObjectSortMode.NAME)
  val ascending by settingsRepository.objectSortAscending.collectAsStateWithLifecycle(true)
  var showMore by remember { mutableStateOf(false) }
  var showUploadSources by remember { mutableStateOf(false) }
  var showUrlUpload by remember { mutableStateOf(false) }
  var searchActive by remember(prefix) { mutableStateOf(false) }
  var query by remember(prefix) { mutableStateOf("") }
  var showPathMenu by remember { mutableStateOf(false) }
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
    if (selectedKeys.isNotEmpty()) selectedKeys = emptySet() else { searchActive = false; query = "" }
  }
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      runCatching { context.contentResolver.document(uri, unknownFileSize) }
        .onSuccess { selected = it }
        .onFailure { selectionError = it.userMessage() }
    }
  }
  state.deletedCount?.let { count ->
    val message = pluralStringResource(R.plurals.deleted_count, count, count)
    LaunchedEffect(count) {
      selectedKeys = emptySet()
      Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
      viewModel.consumeDeletedCount()
    }
  }
  if (state.deleteCanceled) {
    val message = stringResource(R.string.deletion_canceled)
    LaunchedEffect(Unit) {
      Toast.makeText(context, message, Toast.LENGTH_LONG).show()
      viewModel.consumeDeleteCanceled()
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
          } else if (searchActive) {
            TextField(
              value = query,
              onValueChange = { query = it },
              placeholder = { Text(stringResource(R.string.search_hint)) },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
            )
          } else {
            Box {
              Row(
                modifier = Modifier.clickable { showPathMenu = true },
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Column {
                  Text(if (prefix.isEmpty()) bucket else prefix.removeSuffix("/").substringAfterLast('/'))
                  Text(if (prefix.isEmpty()) stringResource(R.string.bucket_root) else prefix, style = MaterialTheme.typography.labelSmall)
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
        },
        navigationIcon = {
          BackButton {
            when {
              selectedKeys.isNotEmpty() -> selectedKeys = emptySet()
              searchActive -> { searchActive = false; query = "" }
              else -> onBack()
            }
          }
        },
        actions = {
          if (selectedKeys.isEmpty()) {
            if (searchActive) {
              TooltipIconButton(stringResource(R.string.close), { searchActive = false; query = "" }) {
                AppIcon(R.drawable.ms_close_24, null)
              }
            } else {
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
              TooltipIconButton(stringResource(R.string.create_folder), { createFolder = true }) {
                AppIcon(R.drawable.ms_create_new_folder_24, null)
              }
              TooltipIconButton(stringResource(R.string.search_files), { searchActive = true }) {
                AppIcon(R.drawable.ms_search_24, null)
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
                    onClick = { showMore = false; viewModel.setSort(settingsRepository, option, ascending) },
                  )
                }
                DropdownMenuItem(
                  text = { Text(stringResource(R.string.sort_ascending)) },
                  leadingIcon = { RadioButton(selected = ascending, onClick = null) },
                  onClick = { showMore = false; viewModel.setSort(settingsRepository, sort, true) },
                )
                DropdownMenuItem(
                  text = { Text(stringResource(R.string.sort_descending)) },
                  leadingIcon = { RadioButton(selected = !ascending, onClick = null) },
                  onClick = { showMore = false; viewModel.setSort(settingsRepository, sort, false) },
                )
              }
              }
            }
          } else {
            val selectedItems = state.objects.filter { it.key in selectedKeys }
            val selectedFavorites = selectedItems.map { it.favorite(profileId, bucket) }
            val allFavorite = selectedFavorites.isNotEmpty() && selectedFavorites.all { candidate ->
              favorites.any { it.id == candidate.id }
            }
            TextButton(onClick = { selectedKeys = state.objects.map { it.key }.toSet() }) {
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
              enabled = selectedItems.none { it.isFolder },
            ) {
              AppIcon(R.drawable.ms_drive_file_move_24, null)
            }
            TooltipIconButton(stringResource(R.string.delete), { deletingItems = state.objects.filter { it.key in selectedKeys } }) {
              AppIcon(R.drawable.ms_delete_24, null)
            }
          }
        },
      )
    },
    floatingActionButton = {
      Box {
        ExtendedFloatingActionButton(
          onClick = { showUploadSources = true },
          icon = { AppIcon(R.drawable.ms_upload_file_24, null) },
          text = { Text(stringResource(R.string.upload)) },
        )
        DropdownMenu(expanded = showUploadSources, onDismissRequest = { showUploadSources = false }) {
          DropdownMenuItem(
            text = { Text(stringResource(R.string.upload_local_file)) },
            onClick = { showUploadSources = false; picker.launch(arrayOf("*/*")) },
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
      if (state.isDeleting) {
        val progress = state.deleteProgress
        if (progress == null) {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
          LinearProgressIndicator(
            progress = { if (progress.total == 0) 1f else progress.completed.toFloat() / progress.total },
            modifier = Modifier.fillMaxWidth(),
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            if (progress == null) stringResource(R.string.preparing_deletion)
            else stringResource(R.string.delete_progress, progress.completed, progress.total),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          TextButton(
            onClick = viewModel::cancelDelete,
            enabled = !state.isCancelingDelete,
          ) {
            Text(
              if (state.isCancelingDelete) stringResource(R.string.canceling)
              else stringResource(R.string.cancel_deletion),
            )
          }
        }
      } else if (state.isUploading || state.isWorking || state.isDownloadingSource) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      }
      when {
        state.isLoading -> LoadingPane(PaddingValues())
        state.error != null || state.errorRes != null ->
          MessagePane(
            stringResource(R.string.storage_request_failed),
            state.errorRes?.let { stringResource(it) } ?: state.error.orEmpty(),
            PaddingValues(),
            stringResource(R.string.dismiss),
            viewModel::clearError,
          )
        state.objects.isEmpty() ->
          MessagePane(
            stringResource(R.string.empty_folder),
            stringResource(R.string.empty_folder_description),
            PaddingValues(),
          )
        displayedObjects.isEmpty() ->
          MessagePane(
            stringResource(R.string.no_search_results),
            query,
            PaddingValues(),
          )
        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
              onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                selectedKeys = selectedKeys.toggle(item.key)
              },
            )
          }
          item { Spacer(Modifier.size(88.dp)) }
        }
      }
    }
  }

  selected?.let { document ->
    UploadDialog(
      document = document,
      initialKey = prefix + document.name,
      isUploading = state.isUploading,
      initialMetadata = metadataTemplate.filterKeys { !it.equals("source-url", true) } +
        document.sourceUrl?.let { mapOf("source-url" to it) }.orEmpty(),
      onDismiss = { if (!state.isUploading) selected = null },
      onUpload = { key, type, metadata ->
        val upload = PendingUpload(document, key, type, metadata)
        if (state.objects.any { !it.isFolder && it.key == key }) {
          pendingUpload = upload
        } else {
          viewModel.upload(document.uri, key, type, document.size, metadata)
        }
        selected = null
      },
    )
  }
  if (showUrlUpload) {
    TextInputDialog(
      title = stringResource(R.string.upload_from_url),
      label = stringResource(R.string.source_url),
      initialValue = context.clipboardHttpUrl().orEmpty(),
      supportingText = stringResource(R.string.url_download_description),
      confirmLabel = stringResource(R.string.download_and_upload),
      onDismiss = { showUrlUpload = false },
      onConfirm = { url -> showUrlUpload = false; viewModel.downloadSource(url) },
    )
  }
  selectionError?.let { error ->
    AlertDialog(
      onDismissRequest = { selectionError = null },
      title = { Text(stringResource(R.string.cannot_use_file)) },
      text = { Text(error) },
      confirmButton = { TextButton(onClick = { selectionError = null }) { Text(stringResource(R.string.ok)) } },
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
    AlertDialog(
      onDismissRequest = { deletingItems = null },
      icon = { AppIcon(R.drawable.ms_warning_24, null, tint = MaterialTheme.colorScheme.error) },
      title = { Text(pluralStringResource(R.plurals.delete_items_title, items.size, items.size)) },
      text = { Text(stringResource(R.string.delete_items_description)) },
      confirmButton = {
        TextButton(
          onClick = { viewModel.requestDelete(items); deletingItems = null },
          colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
          Text(stringResource(R.string.delete))
        }
      },
      dismissButton = { TextButton(onClick = { deletingItems = null }) { Text(stringResource(R.string.cancel)) } },
    )
  }
  state.pendingRecursiveDelete?.let { items ->
    AlertDialog(
      onDismissRequest = viewModel::cancelRecursiveDelete,
      icon = { AppIcon(R.drawable.ms_warning_24, null, tint = MaterialTheme.colorScheme.error) },
      title = {
        Text(
          stringResource(R.string.recursive_delete_title),
          color = MaterialTheme.colorScheme.error,
        )
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(stringResource(R.string.recursive_delete_description))
          Text(
            pluralStringResource(R.plurals.recursive_delete_selection, items.size, items.size),
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
          )
        }
      },
      confirmButton = {
        TextButton(
          onClick = viewModel::confirmRecursiveDelete,
          colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
          Text(stringResource(R.string.delete_everything))
        }
      },
      dismissButton = {
        TextButton(onClick = viewModel::cancelRecursiveDelete) { Text(stringResource(R.string.cancel)) }
      },
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
      onConfirm = { destination ->
        viewModel.move(items, destination)
        selectedKeys = emptySet()
        movingItems = null
      },
    )
  }
  pendingUpload?.let { upload ->
    AlertDialog(
      onDismissRequest = { pendingUpload = null },
      title = { Text(stringResource(R.string.object_exists_title)) },
      text = { Text(stringResource(R.string.object_exists_description, upload.key)) },
      confirmButton = {
        Row {
          TextButton(onClick = {
            val key = nextAvailableKey(upload.key, state.objects.map { it.key }.toSet())
            viewModel.upload(upload.document.uri, key, upload.contentType, upload.document.size, upload.metadata)
            pendingUpload = null
          }) { Text(stringResource(R.string.auto_rename)) }
          TextButton(onClick = {
            viewModel.upload(upload.document.uri, upload.key, upload.contentType, upload.document.size, upload.metadata)
            pendingUpload = null
          }) { Text(stringResource(R.string.overwrite)) }
        }
      },
      dismissButton = { TextButton(onClick = { pendingUpload = null }) { Text(stringResource(R.string.cancel)) } },
    )
  }
}

private fun ObjectItem.favorite(profileId: String, bucket: String): FavoriteItem =
  FavoriteItem(
    profileId = profileId,
    bucket = bucket,
    key = key,
    kind = if (isFolder) FavoriteKind.FOLDER else FavoriteKind.FILE,
  )

private fun pathEntries(bucket: String, prefix: String): List<Pair<String, String>> {
  val entries = mutableListOf(bucket to "")
  var current = ""
  prefix.removeSuffix("/").split('/').filter { it.isNotEmpty() }.forEach { segment ->
    current += "$segment/"
    entries += segment to current
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
private fun UploadDialog(
  document: SelectedDocument,
  initialKey: String,
  isUploading: Boolean,
  initialMetadata: Map<String, String>,
  onDismiss: () -> Unit,
  onUpload: (String, String, Map<String, String>) -> Unit,
) {
  var key by remember(document.uri) { mutableStateOf(initialKey) }
  var contentType by remember(document.uri) { mutableStateOf(document.contentType) }
  var errorRes by remember { mutableStateOf<Int?>(null) }
  val metadata = remember(document.uri) {
    mutableStateListOf<MetadataEntry>().apply {
      addAll(initialMetadata.map { MetadataEntry(it.key, it.value) })
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.upload_file)) },
    text = {
      LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(stringResource(R.string.file_summary, document.name, formatBytes(document.size)), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
          OutlinedTextField(value = key, onValueChange = { key = it; errorRes = null }, label = { Text(stringResource(R.string.object_key)) }, modifier = Modifier.fillMaxWidth())
        }
        item {
          OutlinedTextField(value = contentType, onValueChange = { contentType = it }, label = { Text(stringResource(R.string.content_type)) }, modifier = Modifier.fillMaxWidth())
        }
        item { Text(stringResource(R.string.custom_metadata), style = MaterialTheme.typography.titleSmall) }
        items(metadata.size) { index ->
          val entry = metadata[index]
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = entry.key, onValueChange = { metadata[index] = entry.copy(key = it) }, label = { Text(stringResource(R.string.metadata_key)) }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = entry.value, onValueChange = { metadata[index] = entry.copy(value = it) }, label = { Text(stringResource(R.string.metadata_value)) }, modifier = Modifier.weight(1f))
            TooltipIconButton(stringResource(R.string.remove_metadata), { metadata.removeAt(index) }) {
              AppIcon(R.drawable.ms_delete_24, null)
            }
          }
        }
        item { TextButton(onClick = { metadata += MetadataEntry() }) { Text(stringResource(R.string.add_metadata)) } }
        errorRes?.let { item { Text(stringResource(it), color = MaterialTheme.colorScheme.error) } }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val pairs = metadata.filter { it.key.isNotBlank() }
          when {
            key.isBlank() || key.endsWith('/') -> errorRes = R.string.error_object_key
            pairs.map { it.key.lowercase() }.distinct().size != pairs.size -> errorRes = R.string.error_metadata_duplicate
            else -> onUpload(key.trimStart('/'), contentType, pairs.associate { it.key.trim() to it.value })
          }
        },
        enabled = !isUploading,
      ) { Text(stringResource(R.string.upload)) }
    },
    dismissButton = { TextButton(onClick = onDismiss, enabled = !isUploading) { Text(stringResource(R.string.cancel)) } },
  )
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
) {
  var value by remember { mutableStateOf(initialValue) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column {
        OutlinedTextField(value, { value = it }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
        supportingText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
      }
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) {
        Text(confirmLabel ?: stringResource(R.string.ok))
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
  )
}

private fun Set<String>.toggle(key: String): Set<String> = if (key in this) this - key else this + key

private fun nextAvailableKey(key: String, existing: Set<String>): String {
  val slash = key.lastIndexOf('/')
  val prefix = if (slash >= 0) key.substring(0, slash + 1) else ""
  val fileName = key.substring(slash + 1)
  val dot = fileName.lastIndexOf('.').takeIf { it > 0 } ?: fileName.length
  val base = fileName.substring(0, dot)
  val extension = fileName.substring(dot)
  var number = 1
  var candidate: String
  do {
    candidate = "$prefix$base ($number)$extension"
    number++
  } while (candidate in existing)
  return candidate
}

private fun ObjectItem.iconResource(): Int {
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

private fun ContentResolver.document(uri: Uri, unknownSizeError: String): SelectedDocument {
  var name: String? = null
  var size: Long? = null
  val cursor = query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
  cursor?.use {
    if (it.moveToFirst()) {
      name = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
      val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
      if (sizeIndex >= 0 && !it.isNull(sizeIndex)) size = it.getLong(sizeIndex)
    }
  }
  val length = size?.takeIf { it >= 0 } ?: openAssetFileDescriptor(uri, "r")?.use { it.length }.takeIf { it != null && it >= 0 }
  require(length != null) { unknownSizeError }
  return SelectedDocument(uri, name ?: "upload", getType(uri) ?: "application/octet-stream", length)
}

private fun Context.clipboardHttpUrl(): String? {
  val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  return clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
    ?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
}
