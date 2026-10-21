package com.jasongzy.s3pocket.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jasongzy.s3pocket.data.model.StorageProfile
import com.jasongzy.s3pocket.data.model.StorageProvider
import com.jasongzy.s3pocket.data.model.FavoriteItem
import com.jasongzy.s3pocket.data.model.FavoriteKind
import com.jasongzy.s3pocket.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
  viewModel: ProfilesViewModel,
  pendingSharedCount: Int,
  onAdd: () -> Unit,
  onEdit: (String) -> Unit,
  onInfo: (String) -> Unit,
  onOpen: (String) -> Unit,
  onOpenFavorite: (FavoriteItem) -> Unit,
  onSettings: () -> Unit,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val profiles = state?.profiles.orEmpty()
  val unavailableCredentialIds = state?.unavailableCredentialIds.orEmpty()
  val context = LocalContext.current
  val storedFavorites by viewModel.favorites.collectAsStateWithLifecycle()
  val favorites = storedFavorites.orEmpty()
  var selectedFavorites by rememberSaveable { mutableStateOf(emptyList<String>()) }
  var favoritesToRemove by remember { mutableStateOf<List<FavoriteItem>?>(null) }
  var isRemovingFavorites by remember { mutableStateOf(false) }
  var favoriteRemovalError by remember { mutableStateOf<String?>(null) }
  val selectingFavorites = selectedFavorites.isNotEmpty()
  fun toggleFavorite(id: String) {
    selectedFavorites = if (id in selectedFavorites) selectedFavorites - id else selectedFavorites + id
  }
  fun dismissFavoriteRemoval() {
    if (!isRemovingFavorites) {
      favoritesToRemove = null
      favoriteRemovalError = null
    }
  }
  BackHandler(selectingFavorites) { selectedFavorites = emptyList() }
  LaunchedEffect(storedFavorites) {
    storedFavorites?.let { items ->
      val ids = items.mapTo(mutableSetOf()) { it.id }
      selectedFavorites = selectedFavorites.filter { it in ids }
    }
  }
  var deleting by remember { mutableStateOf<StorageProfile?>(null) }
  var showCredentialRecovery by remember { mutableStateOf(false) }
  val orderedProfiles = remember { mutableStateListOf<StorageProfile>() }
  var dragState by remember { mutableStateOf<ItemDragState?>(null) }
  var pendingOrder by remember { mutableStateOf<List<String>?>(null) }
  val orderedFavorites = remember { mutableStateListOf<FavoriteItem>() }
  var favoriteDragState by remember { mutableStateOf<ItemDragState?>(null) }
  var pendingFavoriteOrder by remember { mutableStateOf<List<String>?>(null) }
  LaunchedEffect(unavailableCredentialIds) {
    showCredentialRecovery = unavailableCredentialIds.isNotEmpty()
  }
  LaunchedEffect(profiles, dragState == null, pendingOrder) {
    val persistedOrder = profiles.map { it.id }
    if (dragState == null && (pendingOrder == null || pendingOrder == persistedOrder)) {
      orderedProfiles.clear()
      orderedProfiles.addAll(profiles)
      pendingOrder = null
    }
  }
  LaunchedEffect(favorites, favoriteDragState == null, pendingFavoriteOrder) {
    val persistedOrder = favorites.map { it.id }
    val orderPending = pendingFavoriteOrder?.let { it != persistedOrder && it.toSet() == persistedOrder.toSet() } == true
    if (favoriteDragState == null && !orderPending) {
      orderedFavorites.clear()
      orderedFavorites.addAll(favorites)
      pendingFavoriteOrder = null
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          if (selectingFavorites) {
            Text(pluralStringResource(R.plurals.selected_count, selectedFavorites.size, selectedFavorites.size))
          } else {
            Column {
              Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold)
              Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.labelMedium)
            }
          }
        },
        navigationIcon = {
          if (selectingFavorites) {
            TooltipIconButton(stringResource(R.string.cancel), { selectedFavorites = emptyList() }) {
              AppIcon(R.drawable.ms_close_24, null)
            }
          }
        },
        actions = {
          if (selectingFavorites) {
            TooltipIconButton(
              stringResource(R.string.select_all),
              { selectedFavorites = favorites.map { it.id } },
              enabled = selectedFavorites.size < favorites.size,
            ) { AppIcon(R.drawable.ms_select_all_24, null) }
            TooltipIconButton(
              stringResource(R.string.delete),
              { favoritesToRemove = favorites.filter { it.id in selectedFavorites } },
              enabled = favorites.any { it.id in selectedFavorites },
            ) { AppIcon(R.drawable.ms_delete_24, null) }
          } else {
            TooltipIconButton(stringResource(R.string.settings), onSettings) {
              AppIcon(R.drawable.ms_settings_24, null)
            }
          }
        },
      )
    },
    floatingActionButton = {
      if (!selectingFavorites) {
        FloatingActionButton(
          onClick = onAdd,
        ) {
          AppIcon(R.drawable.ms_add_24, stringResource(R.string.add_profile))
        }
      }
    },
  ) { padding ->
    if (state == null) {
      LoadingPane(padding)
    } else if (profiles.isEmpty()) {
      MessagePane(
        title = stringResource(R.string.connect_storage),
        message = stringResource(R.string.connect_storage_description),
        padding = padding,
        action = stringResource(R.string.add_profile),
        onAction = onAdd,
      )
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (pendingSharedCount > 0) {
          item {
            Card(
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
              modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                AppIcon(R.drawable.ms_upload_file_24, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                  androidx.compose.ui.res.pluralStringResource(
                    R.plurals.choose_shared_destination,
                    pendingSharedCount,
                    pendingSharedCount,
                  ),
                  color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
              }
            }
          }
        }
        item { Text(stringResource(R.string.profiles), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
        itemsIndexed(orderedProfiles, key = { _, profile -> profile.id }) { index, profile ->
          val credentialsAvailable = profile.id !in unavailableCredentialIds
          val activeDrag = dragState
          val isDragging = activeDrag?.itemId == profile.id
          val translation = activeDrag?.translationFor(index) ?: 0f
          ProfileCard(
            modifier = Modifier.zIndex(if (isDragging) 1f else 0f),
            position = index,
            profile = profile,
            isDragActive = activeDrag != null,
            isDragging = isDragging,
            translationY = translation,
            credentialsAvailable = credentialsAvailable,
            onOpen = { if (credentialsAvailable) onOpen(profile.id) else onEdit(profile.id) },
            onEdit = { onEdit(profile.id) },
            onInfo = { onInfo(profile.id) },
            onDelete = { deleting = profile },
            onDragStart = { stride ->
              dragState = ItemDragState(profile.id, index, index, 0f, stride)
            },
            onDrag = { offset, stride ->
              dragState = dragState?.moveTo(offset, stride, orderedProfiles.size)
            },
            onDragEnd = {
              val drag = dragState
              if (drag != null && drag.targetIndex != drag.sourceIndex) {
                val reordered = orderedProfiles.toList().moveItem(drag.sourceIndex, drag.targetIndex)
                orderedProfiles.clear()
                orderedProfiles.addAll(reordered)
                pendingOrder = orderedProfiles.map { it.id }
                viewModel.reorder(orderedProfiles.toList())
              }
              dragState = null
            },
            onDragCancel = { dragState = null },
          )
        }
        if (orderedFavorites.isNotEmpty()) {
          item {
            Text(
              stringResource(R.string.favorites),
              style = MaterialTheme.typography.titleMedium,
              modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
          }
          itemsIndexed(orderedFavorites, key = { _, favorite -> favorite.id }) { index, favorite ->
            val activeDrag = favoriteDragState
            val isDragging = activeDrag?.itemId == favorite.id
            FavoriteCard(
              modifier = Modifier.zIndex(if (isDragging) 1f else 0f),
              position = index,
              favorite = favorite,
              profileName = profiles.firstOrNull { it.id == favorite.profileId }?.name.orEmpty(),
              selecting = selectingFavorites,
              selected = favorite.id in selectedFavorites,
              onSelect = { toggleFavorite(favorite.id) },
              isDragActive = activeDrag != null,
              isDragging = isDragging,
              translationY = activeDrag?.translationFor(index) ?: 0f,
              onOpen = {
                if (favorite.profileId in unavailableCredentialIds) onEdit(favorite.profileId)
                else onOpenFavorite(favorite)
              },
              onRemove = { favoritesToRemove = listOf(favorite) },
              onDragStart = { stride -> favoriteDragState = ItemDragState(favorite.id, index, index, 0f, stride) },
              onDrag = { offset, stride ->
                favoriteDragState = favoriteDragState?.moveTo(offset, stride, orderedFavorites.size)
              },
              onDragEnd = {
                val drag = favoriteDragState
                if (drag != null && drag.targetIndex != drag.sourceIndex) {
                  val reordered = orderedFavorites.toList().moveItem(drag.sourceIndex, drag.targetIndex)
                  orderedFavorites.clear()
                  orderedFavorites.addAll(reordered)
                  pendingFavoriteOrder = orderedFavorites.map { it.id }
                  viewModel.reorderFavorites(orderedFavorites.toList())
                }
                favoriteDragState = null
              },
              onDragCancel = { favoriteDragState = null },
            )
          }
        }
        item { Spacer(Modifier.size(80.dp)) }
      }
    }
  }

  favoritesToRemove?.let { items ->
    AlertDialog(
      onDismissRequest = ::dismissFavoriteRemoval,
      icon = { AppIcon(R.drawable.ms_warning_24, null, tint = MaterialTheme.colorScheme.error) },
      title = { Text(pluralStringResource(R.plurals.remove_favorites_title, items.size, items.size)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(stringResource(R.string.remove_favorites_description))
          favoriteRemovalError?.let {
            SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error) }
          }
        }
      },
      confirmButton = {
        TextButton(
          enabled = !isRemovingFavorites,
          onClick = {
            isRemovingFavorites = true
            favoriteRemovalError = null
            viewModel.removeFavorites(items) { result ->
              isRemovingFavorites = false
              result.onSuccess {
                selectedFavorites = selectedFavorites - items.map { it.id }.toSet()
                favoritesToRemove = null
                context.showFavoriteToast(false)
              }.onFailure { favoriteRemovalError = it.userMessage() }
            }
          },
          colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text(stringResource(R.string.delete)) }
      },
      dismissButton = {
        TextButton(onClick = ::dismissFavoriteRemoval, enabled = !isRemovingFavorites) {
          Text(stringResource(R.string.cancel))
        }
      },
    )
  }

  deleting?.let { profile ->
    AlertDialog(
      onDismissRequest = { deleting = null },
      icon = { AppIcon(R.drawable.ms_warning_24, null, tint = MaterialTheme.colorScheme.error) },
      title = { Text(stringResource(R.string.delete_profile_title, profile.name)) },
      text = { Text(stringResource(R.string.delete_profile_description)) },
      confirmButton = {
        TextButton(
          onClick = { viewModel.delete(profile.id); deleting = null },
          colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text(stringResource(R.string.delete)) }
      },
      dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
    )
  }
  if (showCredentialRecovery && unavailableCredentialIds.isNotEmpty()) {
    AlertDialog(
      onDismissRequest = { showCredentialRecovery = false },
      icon = { AppIcon(R.drawable.ms_warning_24, null, tint = MaterialTheme.colorScheme.error) },
      title = { Text(stringResource(R.string.credentials_unavailable_title)) },
      text = {
        Text(
          pluralStringResource(
            R.plurals.credentials_unavailable_description,
            unavailableCredentialIds.size,
            unavailableCredentialIds.size,
          )
        )
      },
      confirmButton = {
        TextButton(onClick = {
          showCredentialRecovery = false
          onEdit(unavailableCredentialIds.first())
        }) {
          Text(stringResource(R.string.reenter_credentials))
        }
      },
      dismissButton = {
        Row {
          TextButton(onClick = { showCredentialRecovery = false }) { Text(stringResource(R.string.later)) }
          TextButton(
            onClick = {
              viewModel.deleteAll(unavailableCredentialIds)
              showCredentialRecovery = false
            },
            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
          ) {
            Text(stringResource(R.string.remove_connections))
          }
        }
      },
    )
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteCard(
  modifier: Modifier,
  position: Int,
  favorite: FavoriteItem,
  profileName: String,
  selecting: Boolean,
  selected: Boolean,
  onSelect: () -> Unit,
  isDragActive: Boolean,
  isDragging: Boolean,
  translationY: Float,
  onOpen: () -> Unit,
  onRemove: () -> Unit,
  onDragStart: (Float) -> Unit,
  onDrag: (Float, Float) -> Unit,
  onDragEnd: () -> Unit,
  onDragCancel: () -> Unit,
) {
  var dragDistance by remember { mutableFloatStateOf(0f) }
  var itemStride by remember { mutableFloatStateOf(0f) }
  val itemSpacing = with(LocalDensity.current) { 12.dp.toPx() }
  val haptic = LocalHapticFeedback.current
  val interactionSource = remember { MutableInteractionSource() }
  val containerColor by animateColorAsState(
    if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
    label = "Favorite selection",
  )
  val scale by animateFloatAsState(if (isDragging) 1.02f else 1f, label = "Favorite drag scale")
  val elevation by animateDpAsState(if (isDragging) 10.dp else 0.dp, label = "Favorite drag elevation")
  val placementTranslation by animateFloatAsState(
    translationY,
    spring(stiffness = Spring.StiffnessMediumLow),
    label = "Favorite drag placement",
  )
  Card(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp)
      .onGloballyPositioned { itemStride = it.size.height.toFloat() + itemSpacing }
      .graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.translationY = when {
          isDragging -> translationY
          isDragActive -> placementTranslation
          else -> 0f
        }
      },
    colors = CardDefaults.cardColors(containerColor = containerColor),
    elevation = CardDefaults.cardElevation(defaultElevation = elevation),
  ) {
    Row(
      modifier = Modifier
        .indication(interactionSource, ripple())
        .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        modifier = Modifier.weight(1f).combinedClickable(
          interactionSource = interactionSource,
          indication = null,
          onClick = if (selecting) onSelect else onOpen,
          onLongClickLabel = stringResource(R.string.select),
          onLongClick = onSelect,
        ),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        AppIcon(
          when (favorite.kind) {
            FavoriteKind.BUCKET -> R.drawable.ms_database_24
            FavoriteKind.FOLDER -> R.drawable.ms_folder_24
            FavoriteKind.FILE -> R.drawable.ms_draft_24
          },
          null,
          Modifier.size(28.dp),
          MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
          Text(favorite.name, maxLines = 1, style = MaterialTheme.typography.titleSmall)
          Text(
            stringResource(R.string.favorite_location, profileName, favorite.bucket),
            maxLines = 1,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      if (selecting) {
        Checkbox(checked = selected, onCheckedChange = { onSelect() })
      } else {
        TooltipIconButton(stringResource(R.string.remove_from_favorites), onRemove) {
          AppIcon(R.drawable.ms_star_24, null, tint = MaterialTheme.colorScheme.primary)
        }
        Box(
          modifier = Modifier.size(48.dp).pointerInput(favorite.id, position, itemStride) {
            detectDragGesturesAfterLongPress(
              onDragStart = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                dragDistance = 0f
                onDragStart(itemStride)
              },
              onDragCancel = { dragDistance = 0f; onDragCancel() },
              onDragEnd = { dragDistance = 0f; onDragEnd() },
              onDrag = { change, amount ->
                change.consume()
                dragDistance += amount.y
                if (itemStride > 0f) onDrag(dragDistance, itemStride)
              },
            )
          },
          contentAlignment = Alignment.Center,
        ) {
          AppIcon(R.drawable.ms_drag_indicator_24, stringResource(R.string.reorder_favorites), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfileCard(
  modifier: Modifier,
  position: Int,
  profile: StorageProfile,
  isDragActive: Boolean,
  isDragging: Boolean,
  translationY: Float,
  credentialsAvailable: Boolean,
  onOpen: () -> Unit,
  onEdit: () -> Unit,
  onInfo: () -> Unit,
  onDelete: () -> Unit,
  onDragStart: (Float) -> Unit,
  onDrag: (Float, Float) -> Unit,
  onDragEnd: () -> Unit,
  onDragCancel: () -> Unit,
) {
  var dragDistance by remember { mutableFloatStateOf(0f) }
  var itemStride by remember { mutableFloatStateOf(0f) }
  val itemSpacing = with(LocalDensity.current) { 12.dp.toPx() }
  val haptic = LocalHapticFeedback.current
  val interactionSource = remember { MutableInteractionSource() }
  val scale by animateFloatAsState(if (isDragging) 1.02f else 1f, label = "Profile drag scale")
  val elevation by animateDpAsState(if (isDragging) 10.dp else 0.dp, label = "Profile drag elevation")
  val placementTranslation by animateFloatAsState(
    targetValue = translationY,
    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
    label = "Profile drag placement",
  )
  val currentOnDragStart by rememberUpdatedState(onDragStart)
  val currentOnDrag by rememberUpdatedState(onDrag)
  val currentOnDragEnd by rememberUpdatedState(onDragEnd)
  val currentOnDragCancel by rememberUpdatedState(onDragCancel)
  Card(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp)
      .onGloballyPositioned { itemStride = it.size.height.toFloat() + itemSpacing }
      .graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.translationY = when {
          isDragging -> translationY
          isDragActive -> placementTranslation
          else -> 0f
        }
      },
    elevation = CardDefaults.cardElevation(defaultElevation = elevation),
  ) {
    Row(
      modifier = Modifier.indication(interactionSource, ripple()).padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        modifier = Modifier.weight(1f).combinedClickable(
          interactionSource = interactionSource,
          indication = null,
          onClick = onOpen,
          onLongClick = onInfo,
        ),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        ProviderBadge(profile.provider, isAmazonS3 = profile.isAmazonS3)
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
          Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
          Text(
            stringResource(when {
              !credentialsAvailable -> R.string.credentials_unavailable
              profile.provider == StorageProvider.CLOUDFLARE_R2 -> R.string.provider_cloudflare_r2
              else -> R.string.provider_aws_s3
            }),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (credentialsAvailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        TooltipIconButton(stringResource(R.string.profile_info), onInfo) { AppIcon(R.drawable.ms_info_24, null) }
        TooltipIconButton(stringResource(R.string.edit), onEdit) { AppIcon(R.drawable.ms_edit_24, null) }
        TooltipIconButton(stringResource(R.string.delete), onDelete) { AppIcon(R.drawable.ms_delete_24, null) }
        Box(
          modifier = Modifier
            .size(48.dp)
            .pointerInput(profile.id, position, itemStride) {
              detectDragGesturesAfterLongPress(
                onDragStart = {
                  haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                  dragDistance = 0f
                  currentOnDragStart(itemStride)
                },
                onDragCancel = { dragDistance = 0f; currentOnDragCancel() },
                onDragEnd = { dragDistance = 0f; currentOnDragEnd() },
                onDrag = { change, amount ->
                  change.consume()
                  dragDistance += amount.y
                  if (itemStride > 0f) currentOnDrag(dragDistance, itemStride)
                },
              )
            },
          contentAlignment = Alignment.Center,
        ) {
          AppIcon(
            R.drawable.ms_drag_indicator_24,
            stringResource(R.string.reorder_profiles),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}
