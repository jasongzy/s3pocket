package com.jasongzy.s3pocket

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import android.net.Uri
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.jasongzy.s3pocket.ui.BucketsScreen
import com.jasongzy.s3pocket.ui.ObjectDetailsScreen
import com.jasongzy.s3pocket.ui.ObjectsScreen
import com.jasongzy.s3pocket.ui.ProfileEditorScreen
import com.jasongzy.s3pocket.ui.ProfilesScreen
import com.jasongzy.s3pocket.ui.ProfilesViewModel
import com.jasongzy.s3pocket.ui.SettingsScreen
import com.jasongzy.s3pocket.ui.ProfileInfoScreen
import com.jasongzy.s3pocket.data.model.FavoriteKind
import com.jasongzy.s3pocket.data.AppSettingsSnapshot

@Composable
fun MainNavigation(
  settings: AppSettingsSnapshot,
  sharedUris: List<Uri>,
  openSharedImmediately: Boolean,
  onUploadDestinationChanged: (Boolean) -> Unit,
  onSharedUrisConsumed: (List<Uri>) -> Boolean,
) {
  val backStack = rememberNavBackStack(ProfilesRoute)
  val application = LocalContext.current.applicationContext as S3PocketApplication
  val container = application.container
  val profilesViewModel: ProfilesViewModel = viewModel { ProfilesViewModel(container.profileRepository) }
  val editorOffset = with(LocalDensity.current) { 48.dp.roundToPx() } *
    if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
  SideEffect { onUploadDestinationChanged(backStack.lastOrNull() is ObjectsRoute) }

  NavDisplay(
    backStack = backStack,
    modifier = Modifier.clipToBounds(),
    onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
    transitionSpec = { pageTransition(forward = true) },
    popTransitionSpec = { pageTransition(forward = false) },
    predictivePopTransitionSpec = { _ ->
      if (settings.predictiveBack) pageTransition(forward = false)
      else androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
    },
    entryDecorators =
      listOf(
        rememberSaveableStateHolderNavEntryDecorator(),
        rememberViewModelStoreNavEntryDecorator(),
      ),
    entryProvider =
      entryProvider {
        entry<ProfilesRoute> {
          ProfilesScreen(
            viewModel = profilesViewModel,
            pendingSharedCount = sharedUris.size.takeIf { !openSharedImmediately } ?: 0,
            onAdd = { backStack.add(ProfileEditorRoute()) },
            onEdit = { backStack.add(ProfileEditorRoute(it)) },
            onInfo = { backStack.add(ProfileInfoRoute(it)) },
            onOpen = { backStack.add(BucketsRoute(it)) },
            onOpenFavorite = { favorite ->
              when (favorite.kind) {
                FavoriteKind.BUCKET -> backStack.add(ObjectsRoute(favorite.profileId, favorite.bucket))
                FavoriteKind.FOLDER -> backStack.add(ObjectsRoute(favorite.profileId, favorite.bucket, favorite.key))
                FavoriteKind.FILE -> backStack.add(ObjectDetailsRoute(favorite.profileId, favorite.bucket, favorite.key))
              }
            },
            onSettings = { backStack.add(SettingsRoute) },
          )
        }
        entry<SettingsRoute> { route ->
          SettingsScreen(
            repository = container.settingsRepository,
            profiles = container.profileRepository,
            onBack = { backStack.popIfCurrent(route) },
          )
        }
        entry<ProfileEditorRoute>(
          metadata = NavDisplay.transitionSpec { editorTransition(editorOffset) } +
            NavDisplay.popTransitionSpec { editorTransition(-editorOffset) } +
            NavDisplay.predictivePopTransitionSpec { _ ->
              if (settings.predictiveBack) editorTransition(-editorOffset) else null
            },
        ) { route ->
          ProfileEditorScreen(
            profileId = route.profileId,
            repository = container.profileRepository,
            storageFactory = container.storageFactory,
            onBack = { backStack.popIfCurrent(route) },
            onSaved = { profileId ->
              if (backStack.popIfCurrent(route)) backStack.add(BucketsRoute(profileId))
            },
          )
        }
        entry<ProfileInfoRoute> { route ->
          ProfileInfoScreen(
            profileId = route.profileId,
            profiles = profilesViewModel.state,
            metricsRepository = container.cloudflareMetricsRepository,
            onBack = { backStack.popIfCurrent(route) },
            onEdit = { backStack.add(ProfileEditorRoute(route.profileId)) },
          )
        }
        entry<BucketsRoute> { route ->
          BucketsScreen(
            profileId = route.profileId,
            repository = container.profileRepository,
            storageFactory = container.storageFactory,
            onBack = { backStack.popIfCurrent(route) },
            onOpenBucket = { backStack.add(ObjectsRoute(route.profileId, it)) },
          )
        }
        entry<ObjectsRoute> { route ->
          ObjectsScreen(
            profileId = route.profileId,
            bucket = route.bucket,
            prefix = route.prefix,
            repository = container.profileRepository,
            storageFactory = container.storageFactory,
            settingsRepository = container.settingsRepository,
            settings = settings,
            transferManager = container.transferManager,
            thumbnailUrls = container.thumbnailUrls,
            onBack = { backStack.popIfCurrent(route) },
            onOpenFolder = { backStack.add(ObjectsRoute(route.profileId, route.bucket, it)) },
            onOpenObject = { backStack.add(ObjectDetailsRoute(route.profileId, route.bucket, it)) },
            onNavigateToPrefix = { targetPrefix ->
              val ancestorIndex = backStack.indexOfLast { candidate ->
                candidate is ObjectsRoute && candidate.profileId == route.profileId &&
                  candidate.bucket == route.bucket && candidate.prefix == targetPrefix
              }
              if (ancestorIndex >= 0) {
                repeat(backStack.lastIndex - ancestorIndex) { backStack.removeLastOrNull() }
              } else {
                backStack.removeLastOrNull()
                backStack.add(ObjectsRoute(route.profileId, route.bucket, targetPrefix))
              }
            },
            sharedUris = sharedUris,
            openSharedImmediately = openSharedImmediately && backStack.lastOrNull() == route,
            onSharedUrisConsumed = { onSharedUrisConsumed(sharedUris) },
          )
        }
        entry<ObjectDetailsRoute> { route ->
          ObjectDetailsScreen(
            profileId = route.profileId,
            bucket = route.bucket,
            key = route.key,
            repository = container.profileRepository,
            settingsRepository = container.settingsRepository,
            storageFactory = container.storageFactory,
            transferManager = container.transferManager,
            onBack = { backStack.popIfCurrent(route) },
            onRenamed = { newKey ->
              if (backStack.popIfCurrent(route)) {
                backStack.add(ObjectDetailsRoute(route.profileId, route.bucket, newKey))
              }
            },
          )
        }
      },
  )
}

internal fun MutableList<NavKey>.popIfCurrent(route: NavKey): Boolean {
  if (size <= 1 || last() != route) return false
  removeAt(lastIndex)
  return true
}

private fun editorTransition(offset: Int): ContentTransform =
  (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { offset } +
    fadeIn(tween(180, delayMillis = 70))) togetherWith
    (slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { -offset } +
      fadeOut(tween(90)))

private fun pageTransition(forward: Boolean): ContentTransform {
  val direction = if (forward) 1 else -1
  return slideInHorizontally(
    animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
    initialOffsetX = { it * direction },
  ) togetherWith slideOutHorizontally(
    animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
    targetOffsetX = { -it * direction },
  )
}
