package com.jasongzy.s3pocket

import com.jasongzy.s3pocket.data.model.StorageProfile
import com.jasongzy.s3pocket.data.model.StorageProvider
import com.jasongzy.s3pocket.data.model.ProfileError
import com.jasongzy.s3pocket.ui.ProfileFormState
import com.jasongzy.s3pocket.ui.PublicUrlEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageProfileTest {
  @Test
  fun profileFormsUseOnlyTheSelectedProvidersFields() {
    val form = ProfileFormState(
      name = "Storage", accessKeyId = "key", secretAccessKey = "secret",
      accountId = "0123456789abcdef0123456789abcdef", managementApiToken = "token",
      endpoint = "unfinished", region = "us-east-1", forcePathStyle = true,
    )
    val r2 = form.copy(provider = StorageProvider.CLOUDFLARE_R2)
    assertNull(r2.validationError())
    assertEquals("", r2.toProfile().endpointUrl)
    assertEquals("", r2.toProfile().regionName)
    assertEquals(false, r2.toProfile().forcePathStyle)
    assertEquals("token", r2.toProfile().managementApiToken)
    val s3 = form.copy(endpoint = "https://storage.example.com").toProfile()
    assertNull(s3.validationError())
    assertEquals("", s3.accountId)
    assertEquals("", s3.managementApiToken)
    assertEquals("https://storage.example.com", s3.endpointUrl)
    assertEquals("us-east-1", s3.regionName)
    assertEquals(true, s3.forcePathStyle)
  }

  @Test
  fun publicUrlsMatchTheirBucketsAndPreserveBasePaths() {
    val profile = StorageProfile(
      "id", "name", StorageProvider.AWS_S3, accessKeyId = "key", secretAccessKey = "secret",
      publicBaseUrls = mapOf(
        "public" to "https://cdn.example.com/",
        "photos" to "https://images.example.com/archive/",
      ),
    )
    assertEquals("https://cdn.example.com/a%20b/photo.jpg", profile.publicObjectUrl("public", "a b/photo.jpg"))
    assertNull(profile.publicObjectUrl("private", "a b/photo.jpg"))
    assertEquals(
      "https://images.example.com/archive/a%20b/photo%23%25.jpg",
      profile.publicObjectUrl("photos", "a b/photo#%.jpg"),
    )
    assertNull(profile.copy(publicBaseUrls = emptyMap()).publicObjectUrl("public", "a b/photo.jpg"))
  }

  @Test
  fun publicUrlRowsMustBeCompleteAndHaveUniqueBuckets() {
    val form = ProfileFormState(
      name = "Storage", accessKeyId = "key", secretAccessKey = "secret", region = "us-east-1",
      publicUrls = listOf(PublicUrlEntry("public", "https://cdn.example.com")),
    )
    assertNull(form.validationError())
    assertEquals(
      ProfileError.DUPLICATE_PUBLIC_BUCKET,
      form.copy(publicUrls = form.publicUrls + PublicUrlEntry("public", "https://other.example.com")).validationError(),
    )
    assertEquals(ProfileError.PUBLIC_BUCKET, form.copy(publicUrls = listOf(PublicUrlEntry())).validationError())
    listOf("", "http://cdn.example.com", "file:///tmp", "https://cdn.example.com?token=value", "https://user:pass@cdn.example.com").forEach { url ->
      assertEquals(ProfileError.PUBLIC_URL, form.copy(publicUrls = listOf(PublicUrlEntry("bucket", url))).validationError())
    }
    assertNull(form.copy(publicUrls = emptyList()).validationError())
    assertEquals(ProfileError.ENDPOINT, form.copy(endpoint = "http://storage.example.com").validationError())
  }

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
