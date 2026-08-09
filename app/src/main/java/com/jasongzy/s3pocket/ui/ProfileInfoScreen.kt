package com.jasongzy.s3pocket.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jasongzy.s3pocket.R
import com.jasongzy.s3pocket.data.CloudflareMetricsRepository
import com.jasongzy.s3pocket.data.ProfileRepository
import com.jasongzy.s3pocket.data.model.StorageProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileInfoScreen(
  profileId: String,
  profiles: ProfileRepository,
  metricsRepository: CloudflareMetricsRepository,
  onBack: () -> Unit,
  onEdit: () -> Unit,
  viewModel: ProfileInfoViewModel = viewModel { ProfileInfoViewModel(profileId, profiles, metricsRepository) },
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.profile_info)) },
        navigationIcon = { BackButton(onBack) },
        actions = {
          state.profile?.takeIf { it.managementApiToken.isNotBlank() }?.let {
            RefreshButton(viewModel::refresh)
          }
        },
      )
    }
  ) { padding ->
    when {
      state.isLoading -> LoadingPane(padding)
      state.profile == null -> MessagePane(stringResource(R.string.profile_info), state.error.orEmpty(), padding)
      else -> Column(
        modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        val profile = state.profile!!
        Card(modifier = Modifier.fillMaxWidth()) {
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              ProviderBadge(profile.provider, isAmazonS3 = profile.isAmazonS3)
              Column {
                Text(profile.name, style = MaterialTheme.typography.titleLarge)
                Text(stringResource(if (profile.provider == StorageProvider.CLOUDFLARE_R2) R.string.provider_cloudflare_r2 else R.string.provider_aws_s3))
              }
            }
            Text(stringResource(R.string.endpoint_url), style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(profile.endpoint, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { context.copyProfileEndpoint(profile.endpoint) }) {
              AppIcon(R.drawable.ms_content_copy_24, null)
              Text(stringResource(R.string.copy_link))
            }
            if (profile.publicBaseUrl.isNotBlank()) {
              Text(stringResource(R.string.public_base_url), style = MaterialTheme.typography.labelLarge)
              SelectionContainer { Text(profile.publicBaseUrl, style = MaterialTheme.typography.bodySmall) }
            }
          }
        }
        if (profile.provider == StorageProvider.CLOUDFLARE_R2 && profile.managementApiToken.isBlank()) {
          MessageCard(stringResource(R.string.management_token_missing))
        }
        state.error?.let { MessageCard(it, error = true) }
        state.metrics?.let { metrics ->
          Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
              Text(stringResource(R.string.account_metrics), style = MaterialTheme.typography.titleMedium)
              MetricRow(stringResource(R.string.storage_used), formatBytes(metrics.storageBytes))
              MetricRow(stringResource(R.string.object_count), metrics.objectCount.toString())
              MetricRow(stringResource(R.string.class_a_operations), metrics.classAOperations.toString())
              MetricRow(stringResource(R.string.class_b_operations), metrics.classBOperations.toString())
              Text(stringResource(R.string.metrics_delay), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
        Button(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.edit_profile)) }
      }
    }
  }
}

@Composable
private fun MessageCard(message: String, error: Boolean = false) {
  Card(modifier = Modifier.fillMaxWidth()) {
    SelectionContainer {
      Text(message, modifier = Modifier.padding(16.dp), color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun MetricRow(label: String, value: String) {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value)
  }
}

private fun Context.copyProfileEndpoint(value: String) {
  val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  clipboard.setPrimaryClip(ClipData.newPlainText("S3 endpoint", value))
}
