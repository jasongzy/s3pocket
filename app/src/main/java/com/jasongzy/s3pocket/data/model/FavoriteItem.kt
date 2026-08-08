package com.jasongzy.s3pocket.data.model

import kotlinx.serialization.Serializable

@Serializable
data class FavoriteItem(
  val profileId: String,
  val bucket: String,
  val key: String = "",
  val kind: FavoriteKind,
) {
  val id: String
    get() = "$profileId\n$bucket\n$key\n$kind"

  val name: String
    get() = when (kind) {
      FavoriteKind.BUCKET -> bucket
      FavoriteKind.FOLDER -> key.removeSuffix("/").substringAfterLast('/')
      FavoriteKind.FILE -> key.substringAfterLast('/')
    }
}

@Serializable
enum class FavoriteKind {
  BUCKET,
  FOLDER,
  FILE,
}
