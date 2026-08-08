package com.jasongzy.s3pocket.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jasongzy.s3pocket.data.ProfileRepository
import com.jasongzy.s3pocket.data.model.StorageProfile
import com.jasongzy.s3pocket.data.model.StorageProvider
import com.jasongzy.s3pocket.data.model.FavoriteItem
import com.jasongzy.s3pocket.data.model.FavoriteKind
import com.jasongzy.s3pocket.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
  repository: ProfileRepository,
  onAdd: () -> Unit,
  onEdit: (String) -> Unit,
  onInfo: (String) -> Unit,
  onOpen: (String) -> Unit,
  onOpenFavorite: (FavoriteItem) -> Unit,
  onSettings: () -> Unit,
  viewModel: ProfilesViewModel = viewModel { ProfilesViewModel(repository) },
) {
  val profiles by viewModel.profiles.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val favorites by viewModel.favorites.collectAsStateWithLifecycle()
  var deleting by remember { mutableStateOf<StorageProfile?>(null) }
  val orderedProfiles = remember { mutableStateListOf<StorageProfile>() }
  var dragState by remember { mutableStateOf<ItemDragState?>(null) }
  var pendingOrder by remember { mutableStateOf<List<String>?>(null) }
  val orderedFavorites = remember { mutableStateListOf<FavoriteItem>() }
  var favoriteDragState by remember { mutableStateOf<ItemDragState?>(null) }
  var pendingFavoriteOrder by remember { mutableStateOf<List<String>?>(null) }
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
    if (favoriteDragState == null && (pendingFavoriteOrder == null || pendingFavoriteOrder == persistedOrder)) {
      orderedFavorites.clear()
      orderedFavorites.addAll(favorites)
      pendingFavoriteOrder = null
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.labelMedium)
          }
        },
        actions = {
          TooltipIconButton(stringResource(R.string.settings), onSettings) {
            AppIcon(R.drawable.ms_settings_24, null)
          }
        },
      )
    },
    floatingActionButton = {
      FloatingActionButton(
        onClick = onAdd,
        modifier = Modifier.containerTransform(profileContainerKey(null)),
      ) {
        AppIcon(R.drawable.ms_add_24, stringResource(R.string.add_profile))
      }
    },
  ) { padding ->
    if (profiles.isEmpty()) {
      MessagePane(
        title = stringResource(R.string.connect_storage),
        message = stringResource(R.string.connect_storage_description),
        padding = padding,
        action = stringResource(R.string.add_r2_profile),
        onAction = onAdd,
      )
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item { Text(stringResource(R.string.profiles), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
        itemsIndexed(orderedProfiles, key = { _, profile -> profile.id }) { index, profile ->
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
            onOpen = { onOpen(profile.id) },
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
              isDragActive = activeDrag != null,
              isDragging = isDragging,
              translationY = activeDrag?.translationFor(index) ?: 0f,
              onOpen = { onOpenFavorite(favorite) },
              onRemove = { viewModel.removeFavorite(favorite) { context.showFavoriteToast(false) } },
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
}

@Composable
private fun FavoriteCard(
  modifier: Modifier,
  position: Int,
  favorite: FavoriteItem,
  profileName: String,
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
      }
      .clickable(onClick = onOpen),
    elevation = CardDefaults.cardElevation(defaultElevation = elevation),
  ) {
    Row(modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfileCard(
  modifier: Modifier,
  position: Int,
  profile: StorageProfile,
  isDragActive: Boolean,
  isDragging: Boolean,
  translationY: Float,
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
      .containerTransform(profileContainerKey(profile.id))
      .onGloballyPositioned { itemStride = it.size.height.toFloat() + itemSpacing }
      .graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.translationY = when {
          isDragging -> translationY
          isDragActive -> placementTranslation
          else -> 0f
        }
      }
      .combinedClickable(
        onClick = onOpen,
        onLongClick = {
          haptic.performHapticFeedback(HapticFeedbackType.LongPress)
          onInfo()
        },
      ),
    elevation = CardDefaults.cardElevation(defaultElevation = elevation),
  ) {
    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
      ProviderBadge(profile.provider)
      Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
        Text(profile.name, style = MaterialTheme.typography.titleMedium)
        Text(
          stringResource(if (profile.provider == StorageProvider.CLOUDFLARE_R2) R.string.provider_cloudflare_r2 else R.string.provider_aws_s3),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
