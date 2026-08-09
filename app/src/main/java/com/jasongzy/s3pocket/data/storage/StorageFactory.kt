package com.jasongzy.s3pocket.data.storage

import com.jasongzy.s3pocket.data.model.StorageProfile
import okhttp3.OkHttpClient

class StorageFactory(private val httpClient: OkHttpClient = OkHttpClient()) {
  private val regionResolver = BucketRegionResolver(httpClient)
  private val capabilities = StorageCapabilityCache()

  fun create(profile: StorageProfile): ObjectStorage = S3ObjectStorage(profile, httpClient, regionResolver, capabilities)
}
