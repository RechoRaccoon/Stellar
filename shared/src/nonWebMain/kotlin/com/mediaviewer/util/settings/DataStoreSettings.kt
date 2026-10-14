package com.mediaviewer.util.settings

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.Preferences as DsPreferences
import androidx.datastore.preferences.core.MutablePreferences as DsMutablePreferences
import androidx.datastore.preferences.core.edit as dsEdit

/** Android, iOS and desktop: the settings are androidx DataStore's
 *  "media_viewer_prefs" file, exactly as before; this only translates. */
private class DataStoreSettings(private val store: DataStore<DsPreferences>) : SettingsStore {
    override val data: Flow<Preferences> = store.data.map { DsSnapshot(it) }

    override suspend fun edit(transform: suspend (MutablePreferences) -> Unit): Preferences =
        DsSnapshot(store.dsEdit { transform(DsMutable(it)) })
}

@Suppress("UNCHECKED_CAST")
private fun <T> PreferenceKey<T>.native(): DsPreferences.Key<T> = when (type) {
    PreferenceType.STRING -> androidx.datastore.preferences.core.stringPreferencesKey(name)
    PreferenceType.BOOLEAN -> androidx.datastore.preferences.core.booleanPreferencesKey(name)
    PreferenceType.INT -> androidx.datastore.preferences.core.intPreferencesKey(name)
    PreferenceType.LONG -> androidx.datastore.preferences.core.longPreferencesKey(name)
    PreferenceType.FLOAT -> androidx.datastore.preferences.core.floatPreferencesKey(name)
    PreferenceType.DOUBLE -> androidx.datastore.preferences.core.doublePreferencesKey(name)
    PreferenceType.STRING_SET -> androidx.datastore.preferences.core.stringSetPreferencesKey(name)
} as DsPreferences.Key<T>

private open class DsSnapshot(private val prefs: DsPreferences) : Preferences {
    override fun <T> get(key: PreferenceKey<T>): T? = prefs[key.native()]
    override fun contains(key: PreferenceKey<*>): Boolean = prefs.contains(key.native())
    override fun asMap(): Map<PreferenceKey<*>, Any> = buildMap {
        for ((k, v) in prefs.asMap()) {
            val t = preferenceTypeOf(v) ?: continue
            put(PreferenceKey<Any>(k.name, t), v)
        }
    }
}

private class DsMutable(private val prefs: DsMutablePreferences) : DsSnapshot(prefs), MutablePreferences {
    override fun <T> set(key: PreferenceKey<T>, value: T) { prefs[key.native()] = value }
    override fun <T> remove(key: PreferenceKey<T>): T? = prefs.remove(key.native())
    override fun clear() = prefs.clear()
}

private var wrapped: Pair<DataStore<DsPreferences>, SettingsStore>? = null

/** The [SettingsStore] view of this DataStore (one per store). */
fun DataStore<DsPreferences>.asSettingsStore(): SettingsStore =
    wrapped?.takeIf { it.first === this }?.second
        ?: DataStoreSettings(this).also { wrapped = this to it }
