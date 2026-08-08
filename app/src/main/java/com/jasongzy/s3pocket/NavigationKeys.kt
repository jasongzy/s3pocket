package com.jasongzy.s3pocket

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object ProfilesRoute : NavKey

@Serializable
data object SettingsRoute : NavKey

@Serializable
data class ProfileEditorRoute(val profileId: String? = null) : NavKey

@Serializable
data class ProfileInfoRoute(val profileId: String) : NavKey

@Serializable
data class BucketsRoute(val profileId: String) : NavKey

@Serializable
data class ObjectsRoute(val profileId: String, val bucket: String, val prefix: String = "") : NavKey

@Serializable
data class ObjectDetailsRoute(val profileId: String, val bucket: String, val key: String) : NavKey
