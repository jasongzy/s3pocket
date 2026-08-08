package com.jasongzy.s3pocket.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jasongzy.s3pocket.data.CloudflareMetrics
import com.jasongzy.s3pocket.data.CloudflareMetricsRepository
import com.jasongzy.s3pocket.data.ProfileRepository
import com.jasongzy.s3pocket.data.model.StorageProfile
import com.jasongzy.s3pocket.data.model.StorageProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileInfoUiState(
  val profile: StorageProfile? = null,
  val metrics: CloudflareMetrics? = null,
  val isLoading: Boolean = true,
  val error: String? = null,
)

class ProfileInfoViewModel(
  private val profileId: String,
  private val profiles: ProfileRepository,
  private val metricsRepository: CloudflareMetricsRepository,
) : ViewModel() {
  private val _state = MutableStateFlow(ProfileInfoUiState())
  val state: StateFlow<ProfileInfoUiState> = _state

  init {
    refresh()
  }

  fun refresh() {
    viewModelScope.launch {
      _state.update { it.copy(isLoading = true, error = null) }
      val profile = runSuspendCatching { profiles.get(profileId) }
        .getOrElse { error ->
          _state.update { it.copy(isLoading = false, error = error.userMessage()) }
          return@launch
        }
      _state.update { it.copy(profile = profile) }
      if (profile.provider != StorageProvider.CLOUDFLARE_R2 || profile.managementApiToken.isBlank()) {
        _state.update { it.copy(isLoading = false, metrics = null) }
        return@launch
      }
      runSuspendCatching { metricsRepository.fetch(profile) }
        .onSuccess { metrics -> _state.value = ProfileInfoUiState(profile, metrics, isLoading = false) }
        .onFailure { error -> _state.update { it.copy(isLoading = false, error = error.userMessage()) } }
    }
  }
}
