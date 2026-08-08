package com.jasongzy.s3pocket.ui

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation3.ui.LocalNavAnimatedContentScope

internal val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

@Composable
internal fun Modifier.containerTransform(key: String): Modifier {
  val sharedTransitionScope = LocalSharedTransitionScope.current ?: return this
  val animatedContentScope = LocalNavAnimatedContentScope.current
  return with(sharedTransitionScope) {
    sharedBounds(
      sharedContentState = rememberSharedContentState(key),
      animatedVisibilityScope = animatedContentScope,
    )
  }
}

internal fun profileContainerKey(profileId: String?): String = "profile-editor:${profileId ?: "new"}"
