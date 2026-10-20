package com.jasongzy.s3pocket.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jasongzy.s3pocket.data.CloudflareMetrics
import com.jasongzy.s3pocket.data.CloudflareMetricsRepository
import com.jasongzy.s3pocket.data.ProfileRepository
import com.jasongzy.s3pocket.data.model.StorageProfile
import com.jasongzy.s3pocket.data.model.StorageProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileInfoUiState(
  val profile: StorageProfile? = null,
  val metrics: CloudflareMetrics? = null,
  val isProfileLoading: Boolean = true,
  val profileError: String? = null,
  val isMetricsLoading: Boolean = false,
  val metricsError: String? = null,
) {
  val canLoadMetrics: Boolean
    get() = profile?.canLoadCloudflareMetrics() == true
}

class ProfileInfoViewModel(
  private val profileId: String,
  private val profiles: ProfileRepository,
  private val metricsRepository: CloudflareMetricsRepository,
) : ViewModel() {
  private val _state = MutableStateFlow(ProfileInfoUiState())
  val state: StateFlow<ProfileInfoUiState> = _state
  private var profileJob: Job? = null
  private var metricsJob: Job? = null

  fun loadProfile() {
    profileJob?.cancel()
    metricsJob?.cancel()
    _state.update { it.copy(isProfileLoading = true, isMetricsLoading = false, profileError = null) }
    profileJob = viewModelScope.launch {
      val profile = runSuspendCatching { profiles.get(profileId) }
        .getOrElse { error ->
          _state.value = ProfileInfoUiState(isProfileLoading = false, profileError = error.userMessage())
          return@launch
        }
      _state.update {
        it.copy(
          profile = profile,
          isProfileLoading = false,
          metrics = it.metrics.takeIf { _ -> it.profile == profile },
          metricsError = null,
        )
      }
      if (profile.canLoadCloudflareMetrics()) {
        refreshMetrics()
      }
    }
  }

  fun refreshMetrics() {
    val profile = _state.value.profile?.takeIf(StorageProfile::canLoadCloudflareMetrics) ?: return
    if (_state.value.isMetricsLoading || _state.value.isProfileLoading) return
    metricsJob = viewModelScope.launch {
      _state.update { it.copy(isMetricsLoading = true, metricsError = null) }
      runSuspendCatching { metricsRepository.fetch(profile) }
        .onSuccess { metrics ->
          _state.update { it.copy(metrics = metrics, isMetricsLoading = false) }
        }
        .onFailure { error ->
          _state.update { it.copy(isMetricsLoading = false, metricsError = error.userMessage()) }
        }
    }
  }
}

private fun StorageProfile.canLoadCloudflareMetrics(): Boolean =
  provider == StorageProvider.CLOUDFLARE_R2 && managementApiToken.isNotBlank()
