package com.jasongzy.s3pocket.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.annotation.StringRes
import com.jasongzy.s3pocket.R
import com.jasongzy.s3pocket.data.ProfileRepository
import com.jasongzy.s3pocket.data.model.StorageProfile
import com.jasongzy.s3pocket.data.model.StorageProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileFormState(
  val id: String = "",
  val name: String = "",
  val accountId: String = "",
  val provider: StorageProvider = StorageProvider.AWS_S3,
  val accessKeyId: String = "",
  val secretAccessKey: String = "",
  val managementApiToken: String = "",
  val region: String = "",
  val endpoint: String = "",
  val publicBaseUrl: String = "",
  val isLoading: Boolean = false,
  @param:StringRes val errorRes: Int? = null,
  val errorDetail: String? = null,
)

class ProfileEditorViewModel(
  private val profileId: String?,
  private val repository: ProfileRepository,
) : ViewModel() {
  private val _state = MutableStateFlow(ProfileFormState())
  val state: StateFlow<ProfileFormState> = _state

  init {
    if (profileId != null) {
      viewModelScope.launch {
        val profile = repository.find(profileId) ?: return@launch
        _state.value =
          ProfileFormState(
            id = profile.id,
            name = profile.name,
            accountId = profile.accountId,
            provider = profile.provider,
            accessKeyId = profile.accessKeyId,
            secretAccessKey = profile.secretAccessKey,
            managementApiToken = profile.managementApiToken,
            region = profile.regionName,
            endpoint = profile.endpointUrl,
            publicBaseUrl = profile.publicBaseUrl,
          )
      }
    }
  }

  fun updateName(value: String) = _state.update { it.copy(name = value, errorRes = null, errorDetail = null) }

  fun updateProvider(value: StorageProvider) = _state.update { it.copy(provider = value, errorRes = null, errorDetail = null) }

  fun updateAccountId(value: String) = _state.update { it.copy(accountId = value.trim(), errorRes = null, errorDetail = null) }

  fun updateAccessKeyId(value: String) = _state.update { it.copy(accessKeyId = value.trim(), errorRes = null, errorDetail = null) }

  fun updateSecretAccessKey(value: String) = _state.update { it.copy(secretAccessKey = value.trim(), errorRes = null, errorDetail = null) }

  fun updateManagementApiToken(value: String) = _state.update { it.copy(managementApiToken = value.trim(), errorRes = null, errorDetail = null) }

  fun updateRegion(value: String) = _state.update { it.copy(region = value.trim(), errorRes = null, errorDetail = null) }

  fun updateEndpoint(value: String) = _state.update { it.copy(endpoint = value.trim(), errorRes = null, errorDetail = null) }

  fun updatePublicBaseUrl(value: String) =
    _state.update { it.copy(publicBaseUrl = value.trim(), errorRes = null, errorDetail = null) }

  fun save(onSaved: (String) -> Unit) {
    val form = state.value
    val errorRes = validate(form)
    if (errorRes != null) {
      _state.update { it.copy(errorRes = errorRes) }
      return
    }
    viewModelScope.launch {
      _state.update { it.copy(isLoading = true, errorRes = null, errorDetail = null) }
      runSuspendCatching {
          repository.save(
            StorageProfile(
              id = form.id,
              name = form.name.trim(),
              provider = form.provider,
              accountId = form.accountId,
              accessKeyId = form.accessKeyId,
              secretAccessKey = form.secretAccessKey,
              managementApiToken = form.managementApiToken,
              regionName = form.region,
              endpointUrl = form.endpoint,
              publicBaseUrl = form.publicBaseUrl,
            )
          )
        }
        .onSuccess { onSaved(it.id) }
        .onFailure { error -> _state.update { it.copy(isLoading = false, errorDetail = error.userMessage()) } }
    }
  }

  @StringRes
  private fun validate(form: ProfileFormState): Int? = when {
    form.name.isBlank() -> R.string.error_profile_name
    form.provider == StorageProvider.CLOUDFLARE_R2 && !form.accountId.matches(Regex("[a-fA-F0-9]{32}")) -> R.string.error_account_id
    form.provider == StorageProvider.AWS_S3 && form.region.isBlank() -> R.string.error_region
    form.accessKeyId.isBlank() -> R.string.error_access_key
    form.secretAccessKey.isBlank() -> R.string.error_secret_key
    form.provider == StorageProvider.AWS_S3 && form.secretAccessKey.length != 40 -> R.string.error_aws_secret_key_length
    else -> null
  }
}
