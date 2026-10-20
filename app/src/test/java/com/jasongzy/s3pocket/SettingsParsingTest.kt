package com.jasongzy.s3pocket

import com.jasongzy.s3pocket.data.ThemeMode
import com.jasongzy.s3pocket.data.enumOrDefault
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsParsingTest {
  @Test
  fun unknownEnumFallsBackToDefault() {
    assertEquals(ThemeMode.SYSTEM, "FUTURE_MODE".enumOrDefault(ThemeMode.SYSTEM))
    assertEquals(ThemeMode.DARK, "DARK".enumOrDefault(ThemeMode.SYSTEM))
  }
}
