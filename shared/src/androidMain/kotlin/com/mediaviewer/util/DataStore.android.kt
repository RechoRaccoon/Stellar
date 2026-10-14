package com.mediaviewer.util

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.util.settings.SettingsStore
import com.mediaviewer.util.settings.asSettingsStore

val PlatformContext.dataStore: DataStore<Preferences> by preferencesDataStore(name = "media_viewer_prefs")

/** Shared code's view of the same file (see util/settings). */
actual val PlatformContext.appSettings: SettingsStore get() = dataStore.asSettingsStore()
