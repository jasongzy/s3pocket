package com.jasongzy.s3pocket.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jasongzy.s3pocket.data.ProfileRepository
import com.jasongzy.s3pocket.data.model.StorageProvider
import com.jasongzy.s3pocket.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
  profileId: String?,
  repository: ProfileRepository,
  onBack: () -> Unit,
  onSaved: (String) -> Unit,
  viewModel: ProfileEditorViewModel = viewModel { ProfileEditorViewModel(profileId, repository) },
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  var showSecret by remember { mutableStateOf(false) }
  var showManagementToken by remember { mutableStateOf(false) }
  val uriHandler = LocalUriHandler.current

  Scaffold(
    modifier = Modifier.containerTransform(profileContainerKey(profileId)),
    topBar = {
      TopAppBar(
        title = { Text(stringResource(if (profileId == null) R.string.add_profile else R.string.edit_profile)) },
        navigationIcon = { BackButton(onBack) },
      )
    }
  ) { padding ->
    Column(
      modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
          selected = state.provider == StorageProvider.AWS_S3,
          onClick = { viewModel.updateProvider(StorageProvider.AWS_S3) },
          label = { Text(stringResource(R.string.provider_aws_s3)) },
        )
        FilterChip(
          selected = state.provider == StorageProvider.CLOUDFLARE_R2,
          onClick = { viewModel.updateProvider(StorageProvider.CLOUDFLARE_R2) },
          label = { Text(stringResource(R.string.provider_cloudflare_r2)) },
        )
      }
      Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          ProviderBadge(state.provider)
          Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              stringResource(if (state.provider == StorageProvider.CLOUDFLARE_R2) R.string.provider_cloudflare_r2 else R.string.provider_aws_s3),
              style = MaterialTheme.typography.titleMedium,
            )
            Text(
              stringResource(if (state.provider == StorageProvider.CLOUDFLARE_R2) R.string.r2_profile_description else R.string.aws_profile_description),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.provider == StorageProvider.CLOUDFLARE_R2) {
              TextButton(onClick = { uriHandler.openUri(R2_DASHBOARD_URL) }) {
                AppIcon(R.drawable.ms_open_in_new_24, null)
                Text(stringResource(R.string.open_r2_dashboard))
              }
            }
          }
        }
      }
      OutlinedTextField(
        value = state.name,
        onValueChange = viewModel::updateName,
        label = { RequiredLabel(stringResource(R.string.profile_name)) },
        placeholder = { Text(stringResource(R.string.profile_name_example)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      if (state.provider == StorageProvider.CLOUDFLARE_R2) {
        OutlinedTextField(
          value = state.accountId,
          onValueChange = viewModel::updateAccountId,
          label = { RequiredLabel(stringResource(R.string.cloudflare_account_id)) },
          supportingText = { Text(stringResource(R.string.account_id_hint)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = state.managementApiToken,
          onValueChange = viewModel::updateManagementApiToken,
          label = { Text(stringResource(R.string.cloudflare_management_token)) },
          supportingText = { Text(stringResource(R.string.management_token_hint)) },
          singleLine = true,
          visualTransformation = if (showManagementToken) VisualTransformation.None else PasswordVisualTransformation(),
          trailingIcon = {
            TooltipIconButton(
              label = stringResource(if (showManagementToken) R.string.hide else R.string.show),
              onClick = { showManagementToken = !showManagementToken },
            ) {
              AppIcon(if (showManagementToken) R.drawable.ms_visibility_off_24 else R.drawable.ms_visibility_24, null)
            }
          },
          modifier = Modifier.fillMaxWidth(),
        )
      } else {
        OutlinedTextField(
          value = state.region,
          onValueChange = viewModel::updateRegion,
          label = { RequiredLabel(stringResource(R.string.aws_region)) },
          placeholder = { Text("us-east-1") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = state.endpoint,
          onValueChange = viewModel::updateEndpoint,
          label = { Text(stringResource(R.string.custom_endpoint)) },
          supportingText = { Text(stringResource(R.string.custom_endpoint_hint)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
      OutlinedTextField(
        value = state.publicBaseUrl,
        onValueChange = viewModel::updatePublicBaseUrl,
        label = { Text(stringResource(R.string.public_base_url)) },
        supportingText = { Text(stringResource(R.string.public_base_url_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        value = state.accessKeyId,
        onValueChange = viewModel::updateAccessKeyId,
        label = { RequiredLabel(stringResource(R.string.access_key_id)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        value = state.secretAccessKey,
        onValueChange = viewModel::updateSecretAccessKey,
        label = { RequiredLabel(stringResource(R.string.secret_access_key)) },
        singleLine = true,
        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
          TooltipIconButton(label = stringResource(if (showSecret) R.string.hide else R.string.show), onClick = { showSecret = !showSecret }) {
            AppIcon(
              if (showSecret) R.drawable.ms_visibility_off_24 else R.drawable.ms_visibility_24,
              null,
            )
          }
        },
        modifier = Modifier.fillMaxWidth(),
      )
      Text(
        stringResource(R.string.credential_security_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      state.errorRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
      state.errorDetail?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = { viewModel.save(onSaved) }, enabled = !state.isLoading) {
          Text(stringResource(if (state.isLoading) R.string.saving else R.string.save_and_connect))
        }
      }
    }
  }
}

@Composable
private fun RequiredLabel(text: String) {
  Row {
    Text(text)
    Text(" *", color = MaterialTheme.colorScheme.error)
  }
}

private const val R2_DASHBOARD_URL = "https://dash.cloudflare.com/?to=/:account/r2/overview"
