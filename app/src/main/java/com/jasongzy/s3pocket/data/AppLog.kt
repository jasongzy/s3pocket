package com.jasongzy.s3pocket.data

import android.util.Log

@kotlinx.serialization.Serializable
enum class LogLevel {
  OFF,
  ERRORS,
  DEBUG,
}

object AppLog {
  @Volatile
  var level: LogLevel = LogLevel.ERRORS

  inline fun debug(tag: String, message: () -> String) {
    if (level == LogLevel.DEBUG) Log.d(tag, message())
  }

  inline fun error(tag: String, throwable: Throwable, message: () -> String) {
    if (level != LogLevel.OFF) Log.e(tag, message(), throwable)
  }
}
