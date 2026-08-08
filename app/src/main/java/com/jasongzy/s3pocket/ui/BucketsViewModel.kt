package com.jasongzy.s3pocket.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jasongzy.s3pocket.data.ProfileRepository
import com.jasongzy.s3pocket.data.AppLog
import com.jasongzy.s3pocket.data.model.BucketItem
import com.jasongzy.s3pocket.data.model.FavoriteItem
import com.jasongzy.s3pocket.data.storage.StorageFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

data class BucketsUiState(
  val profileName: String = "",
  val buckets: List<BucketItem> = emptyList(),
  val isLoading: Boolean = false,
  val error: String? = null,
  val errorHintRes: Int? = null,
)

class BucketsViewModel(
  private val profileId: String,
  private val profiles: ProfileRepository,
  private val storageFactory: StorageFactory,
) : ViewModel() {
  private val _state = MutableStateFlow(BucketsUiState(isLoading = true))
  val state: StateFlow<BucketsUiState> = _state
  val favorites: StateFlow<List<FavoriteItem>> =
    profiles.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  init {
    refresh()
  }

  fun refresh() {
    viewModelScope.launch {
      val startedAt = SystemClock.elapsedRealtime()
      AppLog.debug(TAG) { "ListBuckets started" }
      _state.update { it.copy(isLoading = true, error = null, errorHintRes = null) }
      runSuspendCatching {
          val profile = profiles.get(profileId)
          profile.name to storageFactory.create(profile).listBuckets()
        }
        .onSuccess { (name, buckets) ->
          AppLog.debug(TAG) { "ListBuckets succeeded in ${SystemClock.elapsedRealtime() - startedAt} ms" }
          _state.value =
            BucketsUiState(
              profileName = name,
              buckets = buckets.sortedBy { it.name },
              isLoading = false,
            )
        }
        .onFailure { error ->
          AppLog.error(TAG, error) { "ListBuckets failed in ${SystemClock.elapsedRealtime() - startedAt} ms" }
          _state.update {
            it.copy(
              isLoading = false,
              error = error.userMessage(),
              errorHintRes = if (error.message?.contains("SignatureDoesNotMatch") == true) {
                com.jasongzy.s3pocket.R.string.aws_signature_mismatch_hint
              } else null,
            )
          }
        }
    }
  }

  fun setFavorite(favorite: FavoriteItem, enabled: Boolean, onSaved: () -> Unit) {
    viewModelScope.launch {
      profiles.setFavorite(favorite, enabled)
      onSaved()
    }
  }

  private companion object {
    const val TAG = "S3Pocket.Buckets"
  }
}
