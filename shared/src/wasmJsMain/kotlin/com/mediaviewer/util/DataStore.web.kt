package com.mediaviewer.util

import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.util.settings.SettingsStore
import com.mediaviewer.util.settings.WebSettings

/** The settings file, kept in the browser (see WebSettings). */
actual val PlatformContext.appSettings: SettingsStore get() = WebSettings
