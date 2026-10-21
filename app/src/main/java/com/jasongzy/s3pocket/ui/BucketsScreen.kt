package com.jasongzy.s3pocket.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jasongzy.s3pocket.data.ProfileRepository
import com.jasongzy.s3pocket.data.model.BucketItem
import com.jasongzy.s3pocket.data.model.FavoriteItem
import com.jasongzy.s3pocket.data.model.FavoriteKind
import com.jasongzy.s3pocket.data.storage.StorageFactory
import com.jasongzy.s3pocket.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BucketsScreen(
  profileId: String,
  repository: ProfileRepository,
  storageFactory: StorageFactory,
  onBack: () -> Unit,
  onOpenBucket: (String) -> Unit,
  viewModel: BucketsViewModel = viewModel { BucketsViewModel(profileId, repository, storageFactory) },
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val favorites by viewModel.favorites.collectAsStateWithLifecycle()
  var favoriteTarget by remember { mutableStateOf<BucketItem?>(null) }
  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(stringResource(R.string.buckets))
            if (state.profileName.isNotEmpty()) Text(state.profileName, style = MaterialTheme.typography.labelMedium)
          }
        },
        navigationIcon = { BackButton(onBack) },
        actions = { RefreshButton(viewModel::refresh) },
      )
    }
  ) { padding ->
    PullToRefreshBox(
      isRefreshing = state.isLoading && state.buckets.isNotEmpty(),
      onRefresh = viewModel::refresh,
      modifier = Modifier.fillMaxSize().padding(padding),
    ) {
      when {
        state.isLoading && state.buckets.isEmpty() -> LoadingPane(PaddingValues())
        state.error != null -> MessagePane(
          stringResource(R.string.load_buckets_error),
          listOfNotNull(state.errorHintRes?.let { stringResource(it) }, state.error).joinToString("\n\n"),
          PaddingValues(),
          stringResource(R.string.try_again),
          viewModel::refresh,
        )
        state.buckets.isEmpty() -> MessagePane(
          stringResource(R.string.no_buckets),
          stringResource(R.string.no_buckets_description),
          PaddingValues(),
        )
        else -> LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(vertical = 10.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          items(state.buckets, key = { it.name }) { bucket ->
            BucketCard(
              bucket,
              onClick = { onOpenBucket(bucket.name) },
              onLongClick = { favoriteTarget = bucket },
            )
          }
        }
      }
    }
  }
  favoriteTarget?.let { bucket ->
    val favorite = FavoriteItem(profileId, bucket.name, kind = FavoriteKind.BUCKET)
    val isFavorite = favorites.any { it.id == favorite.id }
    AlertDialog(
      onDismissRequest = { favoriteTarget = null },
      icon = { AppIcon(if (isFavorite) R.drawable.ms_star_24 else R.drawable.ms_star_outline_24, null) },
      title = { Text(bucket.name) },
      confirmButton = {
        TextButton(onClick = {
          viewModel.setFavorite(favorite, !isFavorite) { context.showFavoriteToast(!isFavorite) }
          favoriteTarget = null
        }) {
          Text(stringResource(if (isFavorite) R.string.remove_from_favorites else R.string.add_to_favorites))
        }
      },
      dismissButton = { TextButton(onClick = { favoriteTarget = null }) { Text(stringResource(R.string.cancel)) } },
    )
  }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BucketCard(bucket: BucketItem, onClick: () -> Unit, onLongClick: () -> Unit) {
  Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(18.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      AppIcon(
        R.drawable.ms_database_24,
        null,
        Modifier.size(28.dp),
        MaterialTheme.colorScheme.primary,
      )
      Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
        Text(bucket.name, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.created_at, formatInstant(bucket.createdAt)), style = MaterialTheme.typography.bodySmall)
      }
      AppIcon(
        R.drawable.ms_chevron_right_24,
        stringResource(R.string.open),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
