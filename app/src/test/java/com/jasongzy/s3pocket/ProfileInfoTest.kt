package com.jasongzy.s3pocket

import androidx.lifecycle.ViewModelStore
import com.jasongzy.s3pocket.data.CloudflareMetrics
import com.jasongzy.s3pocket.data.ProfileStoreState
import com.jasongzy.s3pocket.data.model.StorageProfile
import com.jasongzy.s3pocket.data.model.StorageProvider
import com.jasongzy.s3pocket.ui.ProfileInfoViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileInfoTest {
  private val store = ViewModelStore()
  private val profile = StorageProfile("profile", "Storage", StorageProvider.AWS_S3, accessKeyId = "key", secretAccessKey = "secret")

  @Before
  fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

  @After
  fun tearDown() {
    store.clear()
    Dispatchers.resetMain()
  }

  @Test
  fun cachedS3InformationIsAvailableBeforePageStart() = runTest {
    val profiles = MutableStateFlow<ProfileStoreState?>(ProfileStoreState(listOf(profile), emptySet()))
    val model = ProfileInfoViewModel(profile.id, profiles) { error("S3 information must stay local") }
    store.put("info", model)

    assertEquals(profile, model.state.value.profile)
    assertFalse(model.state.value.isProfileLoading)
    model.loadProfile()
    model.refreshMetrics()
    assertFalse(model.state.value.isMetricsLoading)
    assertNull(model.state.value.metricsError)
    profiles.value = ProfileStoreState(listOf(profile.copy(provider = StorageProvider.CLOUDFLARE_R2)), emptySet())
    assertFalse(model.state.value.canLoadMetrics)
    assertNull(model.state.value.metricsError)
  }

  @Test
  fun restoredPageTracksLocalEditsAndRemoval() = runTest {
    val profiles = MutableStateFlow<ProfileStoreState?>(null)
    val model = ProfileInfoViewModel(profile.id, profiles) { error("S3 information must stay local") }
    store.put("info", model)
    model.loadProfile()
    assertTrue(model.state.value.isProfileLoading)

    profiles.value = ProfileStoreState(listOf(profile), emptySet())
    assertEquals(profile, model.state.value.profile)
    assertFalse(model.state.value.isProfileLoading)
    val edited = profile.copy(name = "Updated", endpointUrl = "https://storage.example.com")
    profiles.value = ProfileStoreState(listOf(edited), emptySet())
    assertEquals(edited, model.state.value.profile)

    model.stopLoading()
    profiles.value = ProfileStoreState(emptyList(), emptySet())
    model.loadProfile()
    assertNull(model.state.value.profile)
    assertFalse(model.state.value.isProfileLoading)
  }

  @Test
  fun r2MetricsLoadAndFailIndependentlyFromLocalInformation() = runTest {
    val r2 = profile.copy(provider = StorageProvider.CLOUDFLARE_R2, managementApiToken = "token")
    val profiles = MutableStateFlow<ProfileStoreState?>(ProfileStoreState(listOf(r2), emptySet()))
    var response = CompletableDeferred<CloudflareMetrics>()
    var requests = 0
    val model = ProfileInfoViewModel(r2.id, profiles) {
      requests++
      response.await()
    }
    store.put("info", model)
    assertEquals(r2, model.state.value.profile)
    model.loadProfile()
    model.refreshMetrics()
    assertEquals(1, requests)
    assertTrue(model.state.value.isMetricsLoading)
    assertFalse(model.state.value.isProfileLoading)

    response.completeExceptionally(IllegalStateException("API unavailable"))
    assertEquals(r2, model.state.value.profile)
    assertNotNull(model.state.value.metricsError)
    response = CompletableDeferred()
    model.refreshMetrics()
    val metrics = CloudflareMetrics(100, 2, 3, 4)
    response.complete(metrics)
    assertEquals(metrics, model.state.value.metrics)

    response = CompletableDeferred()
    model.refreshMetrics()
    model.stopLoading()
    response.complete(CloudflareMetrics(999, 9, 9, 9))
    assertFalse(model.state.value.isMetricsLoading)
    assertEquals(metrics, model.state.value.metrics)
  }
}
