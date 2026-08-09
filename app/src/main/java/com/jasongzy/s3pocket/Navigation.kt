package com.jasongzy.s3pocket

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import android.net.Uri
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.jasongzy.s3pocket.ui.BucketsScreen
import com.jasongzy.s3pocket.ui.ObjectDetailsScreen
import com.jasongzy.s3pocket.ui.ObjectsScreen
import com.jasongzy.s3pocket.ui.ProfileEditorScreen
import com.jasongzy.s3pocket.ui.ProfilesScreen
import com.jasongzy.s3pocket.ui.SettingsScreen
import com.jasongzy.s3pocket.ui.ProfileInfoScreen
import com.jasongzy.s3pocket.ui.LocalSharedTransitionScope
import com.jasongzy.s3pocket.data.model.FavoriteKind

@Composable
fun MainNavigation(
  predictiveBackEnabled: Boolean,
  sharedUris: List<Uri>,
  openSharedImmediately: Boolean,
  onSharedUrisConsumed: () -> Unit,
) {
  val backStack = rememberNavBackStack(ProfilesRoute)
  val application = LocalContext.current.applicationContext as S3PocketApplication
  val container = application.container

  SharedTransitionLayout {
    CompositionLocalProvider(LocalSharedTransitionScope provides this) {
      NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        transitionSpec = { fadeIn(tween(90)) togetherWith fadeOut(tween(90)) },
        popTransitionSpec = { fadeIn(tween(90)) togetherWith fadeOut(tween(90)) },
        predictivePopTransitionSpec = { _ ->
          if (predictiveBackEnabled) fadeIn(tween(90)) togetherWith fadeOut(tween(90))
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
            repository = container.profileRepository,
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
        entry<SettingsRoute> {
          SettingsScreen(
            repository = container.settingsRepository,
            profiles = container.profileRepository,
            onBack = { backStack.removeLastOrNull() },
          )
        }
        entry<ProfileEditorRoute> { route ->
          ProfileEditorScreen(
            profileId = route.profileId,
            repository = container.profileRepository,
            storageFactory = container.storageFactory,
            onBack = { backStack.removeLastOrNull() },
            onSaved = { profileId ->
              backStack.removeLastOrNull()
              backStack.add(BucketsRoute(profileId))
            },
          )
        }
        entry<ProfileInfoRoute> { route ->
          ProfileInfoScreen(
            profileId = route.profileId,
            profiles = container.profileRepository,
            metricsRepository = container.cloudflareMetricsRepository,
            onBack = { backStack.removeLastOrNull() },
            onEdit = { backStack.add(ProfileEditorRoute(route.profileId)) },
          )
        }
        entry<BucketsRoute> { route ->
          BucketsScreen(
            profileId = route.profileId,
            repository = container.profileRepository,
            storageFactory = container.storageFactory,
            onBack = { backStack.removeLastOrNull() },
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
            transferManager = container.transferManager,
            thumbnailUrls = container.thumbnailUrls,
            onBack = { backStack.removeLastOrNull() },
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
            openSharedImmediately = openSharedImmediately,
            onSharedUrisConsumed = onSharedUrisConsumed,
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
            onBack = { backStack.removeLastOrNull() },
            onRenamed = { newKey ->
              backStack.removeLastOrNull()
              backStack.add(ObjectDetailsRoute(route.profileId, route.bucket, newKey))
            },
          )
        }
          },
      )
    }
  }
}
