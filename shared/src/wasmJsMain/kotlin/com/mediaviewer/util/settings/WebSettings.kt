package com.mediaviewer.util.settings

import com.mediaviewer.platform.WebPaths
import com.mediaviewer.platform.readLocalFile
import com.mediaviewer.platform.writeLocalFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The web version's settings file ("media_viewer_prefs"): one JSON file in
 * the browser's storage (WebFiles), every value with its type.
 */
internal object WebSettings : SettingsStore {
    private const val PATH = "${WebPaths.FILES}/media_viewer_prefs.json"
    private val mutex = Mutex()
    private val state = MutableStateFlow<Map<PreferenceKey<*>, Any>>(emptyMap())
    private var loaded = false

    override val data: Flow<Preferences>
        get() {
            ensureLoaded()
            return MapFlow
        }

    /** [state] as [Preferences] snapshots. */
    private object MapFlow : Flow<Preferences> {
        override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<Preferences>) {
            state.collect { collector.emit(MapPreferences(it)) }
        }
    }

    override suspend fun edit(transform: suspend (MutablePreferences) -> Unit): Preferences = mutex.withLock {
        ensureLoaded()
        val m = MutableMapPreferences(LinkedHashMap(state.value))
        transform(m)
        val now: Map<PreferenceKey<*>, Any> = m.map
        if (now != state.value) {
            state.value = now
            save(now)
        }
        MapPreferences(now)
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val text = readLocalFile(PATH)?.decodeToString() ?: return
        val root = runCatching { com.mediaviewer.json.StellarJson.default.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val out = LinkedHashMap<PreferenceKey<*>, Any>()
        for ((name, el) in root) {
            val o = el as? JsonObject ?: continue
            val t = o["t"]?.jsonPrimitive?.content ?: continue
            val v = o["v"] ?: continue
            runCatching {
                when (t) {
                    "s" -> out[stringPreferencesKey(name)] = v.jsonPrimitive.content
                    "b" -> v.jsonPrimitive.booleanOrNull?.let { out[booleanPreferencesKey(name)] = it }
                    "i" -> out[intPreferencesKey(name)] = v.jsonPrimitive.content.toInt()
                    "l" -> out[longPreferencesKey(name)] = v.jsonPrimitive.content.toLong()
                    "f" -> out[floatPreferencesKey(name)] = v.jsonPrimitive.content.toFloat()
                    "d" -> out[doublePreferencesKey(name)] = v.jsonPrimitive.content.toDouble()
                    "set" -> out[stringSetPreferencesKey(name)] = v.jsonArray.map { it.jsonPrimitive.content }.toSet()
                }
            }
        }
        state.value = out
    }

    private fun save(values: Map<PreferenceKey<*>, Any>) {
        val root = buildJsonObject {
            for ((k, v) in values) {
                put(k.name, buildJsonObject {
                    when (k.type) {
                        PreferenceType.STRING -> { put("t", "s"); put("v", v as String) }
                        PreferenceType.BOOLEAN -> { put("t", "b"); put("v", v as Boolean) }
                        PreferenceType.INT -> { put("t", "i"); put("v", v as Int) }
                        PreferenceType.LONG -> { put("t", "l"); put("v", v as Long) }
                        PreferenceType.FLOAT -> { put("t", "f"); put("v", v as Float) }
                        PreferenceType.DOUBLE -> { put("t", "d"); put("v", v as Double) }
                        PreferenceType.STRING_SET -> { put("t", "set"); put("v", JsonArray((v as Set<*>).map { JsonPrimitive(it.toString()) })) }
                    }
                })
            }
        }
        writeLocalFile(PATH, root.toString().encodeToByteArray())
    }
}

private open class MapPreferences(open val map: Map<PreferenceKey<*>, Any>) : Preferences {
    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: PreferenceKey<T>): T? = map[key] as T?
    override fun contains(key: PreferenceKey<*>): Boolean = map.containsKey(key)
    override fun asMap(): Map<PreferenceKey<*>, Any> = map.toMap()
}

private class MutableMapPreferences(override val map: LinkedHashMap<PreferenceKey<*>, Any>) : MapPreferences(map), MutablePreferences {
    override fun <T> set(key: PreferenceKey<T>, value: T) {
        // (Sets are copied, as DataStore does: a later change to the
        // caller's set doesn't reach the settings.)
        map[key] = (if (value is Set<*>) value.toSet() else value) as Any
    }
    @Suppress("UNCHECKED_CAST")
    override fun <T> remove(key: PreferenceKey<T>): T? = map.remove(key) as T?
    override fun clear() = map.clear()
}
