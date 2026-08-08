package com.jasongzy.s3pocket

import com.jasongzy.s3pocket.data.model.StorageProfile
import com.jasongzy.s3pocket.data.model.StorageProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class StorageProfileTest {
  @Test
  fun cloudflareProfile_derivesR2EndpointAndRegion() {
    val profile =
      StorageProfile(
        id = "profile",
        name = "R2",
        provider = StorageProvider.CLOUDFLARE_R2,
        accountId = "0123456789abcdef0123456789abcdef",
        accessKeyId = "access",
        secretAccessKey = "secret",
      )

    assertEquals("https://0123456789abcdef0123456789abcdef.r2.cloudflarestorage.com", profile.endpoint)
    assertEquals("auto", profile.region)
  }

  @Test
  fun awsProfile_derivesStandardEndpointAndUsesCustomEndpointWhenProvided() {
    val profile =
      StorageProfile(
        id = "aws",
        name = "AWS",
        provider = StorageProvider.AWS_S3,
        accessKeyId = "access",
        secretAccessKey = "secret",
        regionName = "ap-southeast-1",
      )
    assertEquals("https://s3.ap-southeast-1.amazonaws.com", profile.endpoint)
    assertEquals("ap-southeast-1", profile.region)
    assertEquals("https://minio.example.com", profile.copy(endpointUrl = "https://minio.example.com").endpoint)
  }
}
