package com.jasongzy.s3pocket

import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.core.os.BundleCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.jasongzy.s3pocket.data.AppLog
import com.jasongzy.s3pocket.data.ThemeMode
import com.jasongzy.s3pocket.theme.S3PocketTheme
import com.jasongzy.s3pocket.ui.AppIcon
import dev.hossain.highlight.ui.HighlightThemeProvider
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
  private var sharedUris by mutableStateOf(emptyList<Uri>())
  private var sharedRequestId by mutableStateOf("")
  private var openSharedImmediately by mutableStateOf(false)
  private var replacementUris by mutableStateOf(emptyList<Uri>())
  private var openReplacementImmediately = false
  private var isUploadDestinationOpen = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (savedInstanceState == null) {
      acceptIncomingFiles(intent, openImmediately = false)
    } else {
      sharedUris = BundleCompat.getParcelableArrayList(savedInstanceState, SHARED_URIS_KEY, Uri::class.java).orEmpty()
      sharedRequestId = savedInstanceState.getString(SHARED_REQUEST_ID_KEY).orEmpty()
      openSharedImmediately = savedInstanceState.getBoolean(OPEN_SHARED_IMMEDIATELY_KEY)
      replacementUris = BundleCompat.getParcelableArrayList(savedInstanceState, REPLACEMENT_URIS_KEY, Uri::class.java).orEmpty()
      openReplacementImmediately = savedInstanceState.getBoolean(OPEN_REPLACEMENT_IMMEDIATELY_KEY)
    }

    enableEdgeToEdge()
    lifecycleScope.launch {
      container.transferManager.uploadedSharedFiles.collect { sources ->
        if (sources.isNotEmpty()) {
          val uploadedUris = sources.filter { it.requestId == sharedRequestId }.mapTo(mutableSetOf()) { it.uri }
          sharedUris = sharedUris.filterNot { it in uploadedUris }
          container.transferManager.consumeUploadedSharedFiles(sources)
        }
      }
    }
    lifecycleScope.launch {
      val initialSettings = container.settingsRepository.settings.first()
      AppLog.level = initialSettings.logLevel
      setContent {
        val settings = container.settingsRepository.settings.collectAsStateWithLifecycle(initialSettings).value
        SideEffect { AppLog.level = settings.logLevel }
        LaunchedEffect(settings.openWithEnabled) {
          withContext(Dispatchers.IO) {
            val component = ComponentName(this@MainActivity, "$packageName.OpenWithActivity")
            val state = if (settings.openWithEnabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
              else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (packageManager.getComponentEnabledSetting(component) != state) {
              packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
          }
        }
        val darkTheme =
          when (settings.themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
          }
        S3PocketTheme(darkTheme = darkTheme, paletteMode = settings.paletteMode) {
          HighlightThemeProvider(darkTheme = darkTheme) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
              MainNavigation(
                settings,
                sharedUris,
                sharedRequestId,
                openSharedImmediately,
                isConfirmingSharedFiles = replacementUris.isNotEmpty(),
                onUploadDestinationChanged = { isUploadDestinationOpen = it },
                onSharedUrisOpened = { requestId ->
                  if (sharedRequestId == requestId && sharedUris.isNotEmpty()) {
                    openSharedImmediately = false
                    true
                  } else false
                },
              ) {
                sharedUris = emptyList()
                openSharedImmediately = false
                Toast.makeText(this@MainActivity, R.string.upload_canceled, Toast.LENGTH_SHORT).show()
              }
              if (replacementUris.isNotEmpty()) {
                AlertDialog(
                  onDismissRequest = { replacementUris = emptyList() },
                  icon = { AppIcon(R.drawable.ms_warning_24, null, tint = MaterialTheme.colorScheme.error) },
                  title = { Text(stringResource(R.string.replace_shared_files_title)) },
                  text = {
                    Text(stringResource(R.string.replace_shared_files_description, sharedUris.size, replacementUris.size))
                  },
                  confirmButton = {
                    TextButton(
                      onClick = {
                        setSharedFiles(replacementUris, openReplacementImmediately && isUploadDestinationOpen)
                        replacementUris = emptyList()
                      },
                      colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(R.string.replace)) }
                  },
                  dismissButton = {
                    TextButton(onClick = { replacementUris = emptyList() }) { Text(stringResource(R.string.cancel)) }
                  },
                )
              }
            }
          }
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    acceptIncomingFiles(intent, openImmediately = isUploadDestinationOpen)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putParcelableArrayList(SHARED_URIS_KEY, ArrayList(sharedUris))
    outState.putString(SHARED_REQUEST_ID_KEY, sharedRequestId)
    outState.putBoolean(OPEN_SHARED_IMMEDIATELY_KEY, openSharedImmediately)
    outState.putParcelableArrayList(REPLACEMENT_URIS_KEY, ArrayList(replacementUris))
    outState.putBoolean(OPEN_REPLACEMENT_IMMEDIATELY_KEY, openReplacementImmediately)
    super.onSaveInstanceState(outState)
  }

  private fun acceptIncomingFiles(intent: Intent, openImmediately: Boolean) {
    val streams = when (intent.action) {
      Intent.ACTION_VIEW -> listOfNotNull(intent.data)
      Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
      Intent.ACTION_SEND_MULTIPLE ->
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
      else -> return
    }
    val uris = streams.ifEmpty {
      intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
    }.distinct()
    if (uris.isEmpty()) return
    val open = openImmediately && intent.action != Intent.ACTION_VIEW
    if (sharedUris.isNotEmpty() || replacementUris.isNotEmpty()) {
      replacementUris = uris
      openReplacementImmediately = open
    } else {
      setSharedFiles(uris, open)
    }
  }

  private fun setSharedFiles(uris: List<Uri>, openImmediately: Boolean) {
    sharedUris = uris
    sharedRequestId = UUID.randomUUID().toString()
    openSharedImmediately = openImmediately
  }

  private val container: AppContainer
    get() = (application as S3PocketApplication).container

  private companion object {
    const val SHARED_URIS_KEY = "shared_uris"
    const val SHARED_REQUEST_ID_KEY = "shared_request_id"
    const val OPEN_SHARED_IMMEDIATELY_KEY = "open_shared_immediately"
    const val REPLACEMENT_URIS_KEY = "replacement_uris"
    const val OPEN_REPLACEMENT_IMMEDIATELY_KEY = "open_replacement_immediately"
  }
}
