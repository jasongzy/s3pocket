package com.jasongzy.s3pocket

import android.app.Application
import com.jasongzy.s3pocket.data.ProfileRepository
import com.jasongzy.s3pocket.data.SettingsRepository
import com.jasongzy.s3pocket.data.CloudflareMetricsRepository
import com.jasongzy.s3pocket.data.storage.StorageFactory

class S3PocketApplication : Application() {
  val container by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
  val profileRepository = ProfileRepository(application)
  val settingsRepository = SettingsRepository(application)
  val storageFactory = StorageFactory()
  val cloudflareMetricsRepository = CloudflareMetricsRepository()
}
