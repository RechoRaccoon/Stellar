package com.mediaviewer.util.settings

import kotlinx.coroutines.flow.Flow

/*
 * The app's settings ("media_viewer_prefs"), as shared code sees them. The
 * names follow androidx DataStore's (stringPreferencesKey, edit { }, …) so
 * PreferencesManager reads the same as it always has. On Android, iOS and
 * the desktop app this is a thin wrapper over the very same DataStore file
 * as before (nonWebMain: DataStoreSettings); the web version keeps it in
 * the browser's storage.
 */

/** What a setting holds. */
enum class PreferenceType { STRING, BOOLEAN, INT, LONG, FLOAT, DOUBLE, STRING_SET }

/** One setting's name and type (DataStore's Preferences.Key). */
class PreferenceKey<T> internal constructor(val name: String, val type: PreferenceType) {
    override fun equals(other: Any?): Boolean = other is PreferenceKey<*> && other.name == name && other.type == type
    override fun hashCode(): Int = name.hashCode() * 31 + type.hashCode()
    override fun toString(): String = name
}

fun stringPreferencesKey(name: String): PreferenceKey<String> = PreferenceKey(name, PreferenceType.STRING)
fun booleanPreferencesKey(name: String): PreferenceKey<Boolean> = PreferenceKey(name, PreferenceType.BOOLEAN)
fun intPreferencesKey(name: String): PreferenceKey<Int> = PreferenceKey(name, PreferenceType.INT)
fun longPreferencesKey(name: String): PreferenceKey<Long> = PreferenceKey(name, PreferenceType.LONG)
fun floatPreferencesKey(name: String): PreferenceKey<Float> = PreferenceKey(name, PreferenceType.FLOAT)
fun doublePreferencesKey(name: String): PreferenceKey<Double> = PreferenceKey(name, PreferenceType.DOUBLE)
fun stringSetPreferencesKey(name: String): PreferenceKey<Set<String>> = PreferenceKey(name, PreferenceType.STRING_SET)

/** A snapshot of every setting. */
interface Preferences {
    operator fun <T> get(key: PreferenceKey<T>): T?
    operator fun contains(key: PreferenceKey<*>): Boolean
    fun asMap(): Map<PreferenceKey<*>, Any>
}

/** The settings inside [SettingsStore.edit]. */
interface MutablePreferences : Preferences {
    operator fun <T> set(key: PreferenceKey<T>, value: T)
    fun <T> remove(key: PreferenceKey<T>): T?
    fun clear()
}

/** The settings file: read as a Flow, changed with [edit]. */
interface SettingsStore {
    val data: Flow<Preferences>

    /** Applies [transform] atomically and saves; returns the new settings. */
    suspend fun edit(transform: suspend (MutablePreferences) -> Unit): Preferences
}

/** The type a stored value has (for snapshots and backups). */
fun preferenceTypeOf(value: Any): PreferenceType? = when (value) {
    is String -> PreferenceType.STRING
    is Boolean -> PreferenceType.BOOLEAN
    is Int -> PreferenceType.INT
    is Long -> PreferenceType.LONG
    is Float -> PreferenceType.FLOAT
    is Double -> PreferenceType.DOUBLE
    is Set<*> -> PreferenceType.STRING_SET
    else -> null
}
