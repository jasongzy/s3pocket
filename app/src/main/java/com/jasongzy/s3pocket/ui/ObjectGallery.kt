package com.jasongzy.s3pocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import com.jasongzy.s3pocket.R
import com.jasongzy.s3pocket.data.GalleryImageScale
import com.jasongzy.s3pocket.data.GalleryThumbnailSize
import com.jasongzy.s3pocket.data.model.ObjectItem
import java.time.Instant

@Composable
internal fun GalleryOptionsDialog(
  thumbnailSize: GalleryThumbnailSize,
  imageScale: GalleryImageScale,
  onThumbnailSizeChange: (GalleryThumbnailSize) -> Unit,
  onImageScaleChange: (GalleryImageScale) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.gallery_options)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.thumbnail_size), style = MaterialTheme.typography.titleSmall)
        GalleryThumbnailSize.entries.forEach { option ->
          Row(
            modifier = Modifier.fillMaxWidth().clickable { onThumbnailSizeChange(option) },
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(selected = thumbnailSize == option, onClick = null)
            Text(stringResource(when (option) {
              GalleryThumbnailSize.LARGE -> R.string.thumbnail_size_large
              GalleryThumbnailSize.MEDIUM -> R.string.thumbnail_size_medium
              GalleryThumbnailSize.SMALL -> R.string.thumbnail_size_small
            }))
          }
        }
        Text(stringResource(R.string.image_scaling), style = MaterialTheme.typography.titleSmall)
        GalleryImageScale.entries.forEach { option ->
          Row(
            modifier = Modifier.fillMaxWidth().clickable { onImageScaleChange(option) },
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(selected = imageScale == option, onClick = null)
            Column {
              Text(stringResource(if (option == GalleryImageScale.CROP) R.string.image_scale_crop else R.string.image_scale_fit))
              Text(
                stringResource(
                  if (option == GalleryImageScale.CROP) R.string.image_scale_crop_description
                  else R.string.image_scale_fit_description,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
  )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun GalleryObject(
  item: ObjectItem,
  selected: Boolean,
  thumbnailUrl: suspend () -> String,
  imageScale: GalleryImageScale,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
) {
  val previewUrl by produceState<String?>(null, item) {
    value = null
    if (item.isPreviewImage()) value = runSuspendCatching { thumbnailUrl() }.getOrNull()
  }
  var imageLoaded by remember(item, previewUrl) { mutableStateOf(false) }
  Column(
    modifier = Modifier
      .background(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        MaterialTheme.shapes.medium,
      )
      .combinedClickable(onClick = onClick, onLongClick = onLongClick)
      .padding(8.dp),
  ) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
      if (previewUrl != null) {
        AsyncImage(
          model = rememberObjectImageRequest(requireNotNull(previewUrl), item.etag, item.lastModified),
          contentDescription = null,
          modifier = Modifier.fillMaxSize(),
          contentScale = if (imageScale == GalleryImageScale.CROP) ContentScale.Crop else ContentScale.Fit,
          onState = { imageLoaded = it is AsyncImagePainter.State.Success },
        )
      }
      if (!imageLoaded) {
        AppIcon(item.iconResource(), null, Modifier.size(42.dp), MaterialTheme.colorScheme.primary)
      }
    }
    Text(item.name, maxLines = 2, modifier = Modifier.padding(top = 8.dp))
    if (!item.isFolder) {
      Text(formatBytes(item.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
internal fun rememberObjectImageRequest(url: String, etag: String?, lastModified: Instant?): ImageRequest {
  val context = LocalContext.current
  return remember(context, url, etag, lastModified) {
    val cacheKey = "$url\n$etag\n$lastModified"
    ImageRequest.Builder(context).data(url).memoryCacheKey(cacheKey).diskCacheKey(cacheKey).build()
  }
}
