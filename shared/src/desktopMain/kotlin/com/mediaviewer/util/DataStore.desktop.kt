package com.mediaviewer.util

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.mediaviewer.platform.DesktopPaths
import com.mediaviewer.platform.PlatformContext
import okio.Path.Companion.toOkioPath

/** The same "media_viewer_prefs" settings file as Android and iOS, in
 *  Stellar's data folder. */
private val desktopDataStore: DataStore<Preferences> by lazy {
    PreferenceDataStoreFactory.createWithPath(
        produceFile = { java.io.File(DesktopPaths.filesDir, "media_viewer_prefs.preferences_pb").toOkioPath() }
    )
}

actual val PlatformContext.dataStore: DataStore<Preferences> get() = desktopDataStore
