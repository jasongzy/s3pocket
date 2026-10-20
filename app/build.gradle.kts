import java.io.File
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

abstract class ValidateReleaseSigningTask : DefaultTask() {
  @get:Input
  abstract val configured: Property<Boolean>

  @get:Input
  abstract val storePath: Property<String>

  @TaskAction
  fun validate() {
    require(configured.get()) { "Release signing properties are incomplete" }
    require(File(storePath.get()).isFile) { "Release keystore does not exist" }
  }
}

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.isFile) {
  keystorePropertiesFile.inputStream().use(keystoreProperties::load)
}

fun signingProperty(localName: String, gradleName: String): String? =
  keystoreProperties.getProperty(localName)?.takeIf { it.isNotBlank() }
    ?: providers.gradleProperty(gradleName).orNull

val releaseStoreFile = signingProperty("storeFile", "RELEASE_STORE_FILE")
val releaseStorePassword = signingProperty("storePassword", "RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingProperty("keyAlias", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingProperty("keyPassword", "RELEASE_KEY_PASSWORD")
val hasReleaseSigning =
  listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }
val validateReleaseSigning = tasks.register<ValidateReleaseSigningTask>("validateReleaseSigning") {
  configured.set(hasReleaseSigning)
  storePath.set(releaseStoreFile?.let { file(it).absolutePath }.orEmpty())
}

android {
    namespace = "com.jasongzy.s3pocket"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "com.jasongzy.s3pocket"
        minSdk = 24
        targetSdk = 37
        versionCode = providers.gradleProperty("VERSION_CODE").get().toInt()
        versionName = providers.gradleProperty("VERSION_NAME").get()
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }
    androidResources {
      generateLocaleConfig = true
      localeFilters += listOf("en", "zh-rCN")
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  coreLibraryDesugaring(libs.desugar.jdk.libs)

  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.appcompat)

  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.kotlinx.serialization.json)

  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.coil.compose)
  implementation(libs.compose.highlight)
  implementation(libs.coil.network.okhttp)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.ui)

  implementation(libs.aws.s3)
  implementation(libs.okhttp)
  debugImplementation(libs.androidx.compose.ui.tooling)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}

tasks.configureEach {
  if (name == "preReleaseBuild") dependsOn(validateReleaseSigning)
}
