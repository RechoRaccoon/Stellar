package com.mediaviewer.util

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.mediaviewer.platform.IosPaths
import com.mediaviewer.platform.PlatformContext
import okio.Path.Companion.toPath

private val iosDataStore: DataStore<Preferences> by lazy {
    PreferenceDataStoreFactory.createWithPath(
        produceFile = { (IosPaths.filesDir() + "/media_viewer_prefs.preferences_pb").toPath() }
    )
}

actual val PlatformContext.dataStore: DataStore<Preferences> get() = iosDataStore

/**
 * The settings file's share of [IosPaths.rehome]: any saved text that
 * names a file in an older copy of the app's folder is pointed at the
 * current one. Run once at launch, before anything reads the settings;
 * when nothing needs changing (every launch but the first after an
 * update) nothing is written.
 */
fun healMovedAppFolderPaths() {
    runCatching {
        kotlinx.coroutines.runBlocking {
            iosDataStore.updateData { prefs ->
                var changed: androidx.datastore.preferences.core.MutablePreferences? = null
                for ((key, value) in prefs.asMap()) {
                    when (value) {
                        is String -> {
                            val healed = IosPaths.rehome(value)
                            if (healed != value) {
                                val m = changed ?: prefs.toMutablePreferences().also { changed = it }
                                m[androidx.datastore.preferences.core.stringPreferencesKey(key.name)] = healed
                            }
                        }
                        is Set<*> -> {
                            val before = value.filterIsInstance<String>().toSet()
                            val healed = before.map { IosPaths.rehome(it) }.toSet()
                            if (healed != before) {
                                val m = changed ?: prefs.toMutablePreferences().also { changed = it }
                                m[androidx.datastore.preferences.core.stringSetPreferencesKey(key.name)] = healed
                            }
                        }
                        else -> {}
                    }
                }
                changed ?: prefs
            }
        }
    }
}
