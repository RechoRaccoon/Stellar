package com.mediaviewer.json

import kotlinx.serialization.json.JsonArray as KxArray
import kotlinx.serialization.json.JsonElement as KxElement
import kotlinx.serialization.json.JsonNull as KxNull
import kotlinx.serialization.json.JsonObject as KxObject
import kotlinx.serialization.json.JsonPrimitive as KxPrimitive

// The org.json classes, under their own names. JSONObject/JSONArray can't
// be real classes here: they'd sit beside GsonCompat's JsonObject/JsonArray
// in this package, and on Windows and macOS (where file names ignore case)
// building the app writes JSONArray.class over JsonArray.class — and
// whichever one is lost breaks everything that uses it.
actual typealias JSONException = OrgJSONException
actual typealias JSONObject = OrgJSONObject
actual typealias JSONArray = OrgJSONArray

open class OrgJSONException(message: String?) : Exception(message)

/** org.json values: String, Boolean, Int/Long/Double, JSONObject, JSONArray, or null. */
private fun fromKx(el: KxElement): Any? = when (el) {
    is KxNull -> null
    is KxPrimitive -> when {
        el.isString -> el.content
        el.content == "true" -> true
        el.content == "false" -> false
        else -> el.content.toIntOrNull() ?: el.content.toLongOrNull() ?: el.content.toDoubleOrNull() ?: el.content
    }
    is KxObject -> JSONObject(el)
    is KxArray -> JSONArray(el)
}

private fun toKx(v: Any?): KxElement = when (v) {
    null -> KxNull
    is JSONObject -> v.toKx()
    is JSONArray -> v.toKx()
    else -> anyToKxJson(v)
}

private fun asNumber(v: Any?): Double? = when (v) {
    is Number -> v.toDouble()
    is String -> v.trim().toDoubleOrNull()
    else -> null
}

private fun asBool(v: Any?): Boolean? = when (v) {
    is Boolean -> v
    is String -> when (v.lowercase()) { "true" -> true; "false" -> false; else -> null }
    else -> null
}

private fun asStr(v: Any?): String? = when (v) {
    null -> null
    is Double -> if (v == kotlin.math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
    else -> v.toString()
}

open class OrgJSONObject {
    private val map = LinkedHashMap<String, Any?>()

    constructor()
    constructor(json: String) {
        val el = try { StellarJson.default.parseToJsonElement(json) } catch (e: Exception) { throw JSONException(e.message) }
        if (el !is KxObject) throw JSONException("Value is not a JSONObject")
        for ((k, v) in el) map[k] = fromKx(v)
    }
    internal constructor(el: KxObject) { for ((k, v) in el) map[k] = fromKx(v) }

    internal fun toKx(): KxObject = KxObject(map.mapValues { (_, v) -> toKx(v) })

    fun length(): Int = map.size
    fun has(name: String): Boolean = map.containsKey(name)
    fun isNull(name: String): Boolean = map[name] == null
    fun keys(): Iterator<String> = map.keys.toList().iterator()
    fun opt(name: String): Any? = map[name]
    fun remove(name: String): Any? = map.remove(name)
    fun put(name: String, value: Any?): JSONObject {
        if (value == null) map.remove(name) else map[name] = value
        return this
    }

    fun optString(name: String): String = optString(name, "")
    fun optString(name: String, fallback: String): String = asStr(map[name]) ?: fallback
    fun optInt(name: String): Int = optInt(name, 0)
    fun optInt(name: String, fallback: Int): Int = asNumber(map[name])?.toInt() ?: fallback
    fun optLong(name: String): Long = optLong(name, 0L)
    fun optLong(name: String, fallback: Long): Long = (map[name] as? Long) ?: asNumber(map[name])?.toLong() ?: fallback
    fun optDouble(name: String): Double = optDouble(name, Double.NaN)
    fun optDouble(name: String, fallback: Double): Double = asNumber(map[name]) ?: fallback
    fun optBoolean(name: String): Boolean = optBoolean(name, false)
    fun optBoolean(name: String, fallback: Boolean): Boolean = asBool(map[name]) ?: fallback
    fun optJSONObject(name: String): JSONObject? = map[name] as? JSONObject
    fun optJSONArray(name: String): JSONArray? = map[name] as? JSONArray

    private fun req(name: String): Any = map[name] ?: throw JSONException("No value for $name")
    fun getString(name: String): String = asStr(req(name))!!
    fun getInt(name: String): Int = asNumber(req(name))?.toInt() ?: throw JSONException("Value at $name is not an int")
    fun getLong(name: String): Long = (map[name] as? Long) ?: asNumber(req(name))?.toLong() ?: throw JSONException("Value at $name is not a long")
    fun getDouble(name: String): Double = asNumber(req(name)) ?: throw JSONException("Value at $name is not a double")
    fun getBoolean(name: String): Boolean = asBool(req(name)) ?: throw JSONException("Value at $name is not a boolean")
    fun getJSONObject(name: String): JSONObject = req(name) as? JSONObject ?: throw JSONException("Value at $name is not a JSONObject")
    fun getJSONArray(name: String): JSONArray = req(name) as? JSONArray ?: throw JSONException("Value at $name is not a JSONArray")

    override fun toString(): String = toKx().toString()
    fun toString(indentSpaces: Int): String = PrettyJson.print(toKx(), indentSpaces)
}

open class OrgJSONArray {
    private val list = ArrayList<Any?>()

    constructor()
    constructor(json: String) {
        val el = try { StellarJson.default.parseToJsonElement(json) } catch (e: Exception) { throw JSONException(e.message) }
        if (el !is KxArray) throw JSONException("Value is not a JSONArray")
        for (v in el) list.add(fromKx(v))
    }
    internal constructor(el: KxArray) { for (v in el) list.add(fromKx(v)) }

    internal fun toKx(): KxArray = KxArray(list.map { toKx(it) })

    fun length(): Int = list.size
    fun opt(index: Int): Any? = list.getOrNull(index)
    fun put(value: Any?): JSONArray { list.add(value); return this }

    fun optString(index: Int): String = optString(index, "")
    fun optString(index: Int, fallback: String): String = asStr(opt(index)) ?: fallback
    fun optInt(index: Int): Int = optInt(index, 0)
    fun optInt(index: Int, fallback: Int): Int = asNumber(opt(index))?.toInt() ?: fallback
    fun optLong(index: Int): Long = (opt(index) as? Long) ?: asNumber(opt(index))?.toLong() ?: 0L
    fun optDouble(index: Int): Double = optDouble(index, Double.NaN)
    fun optDouble(index: Int, fallback: Double): Double = asNumber(opt(index)) ?: fallback
    fun optBoolean(index: Int): Boolean = asBool(opt(index)) ?: false
    fun optJSONObject(index: Int): JSONObject? = opt(index) as? JSONObject
    fun optJSONArray(index: Int): JSONArray? = opt(index) as? JSONArray

    private fun req(index: Int): Any = opt(index) ?: throw JSONException("Value at $index is null.")
    fun getString(index: Int): String = asStr(req(index))!!
    fun getInt(index: Int): Int = asNumber(req(index))?.toInt() ?: throw JSONException("Value at $index is not an int")
    fun getLong(index: Int): Long = (opt(index) as? Long) ?: asNumber(req(index))?.toLong() ?: throw JSONException("Value at $index is not a long")
    fun getDouble(index: Int): Double = asNumber(req(index)) ?: throw JSONException("Value at $index is not a double")
    fun getBoolean(index: Int): Boolean = asBool(req(index)) ?: throw JSONException("Value at $index is not a boolean")
    fun getJSONObject(index: Int): JSONObject = req(index) as? JSONObject ?: throw JSONException("Value at $index is not a JSONObject")
    fun getJSONArray(index: Int): JSONArray = req(index) as? JSONArray ?: throw JSONException("Value at $index is not a JSONArray")

    override fun toString(): String = toKx().toString()
    fun toString(indentSpaces: Int): String = PrettyJson.print(toKx(), indentSpaces)
}

private object PrettyJson {
    fun print(el: KxElement, indent: Int): String {
        val sb = StringBuilder(); write(el, indent, 0, sb); return sb.toString()
    }
    private fun write(el: KxElement, indent: Int, level: Int, sb: StringBuilder) {
        val pad = " ".repeat(indent * (level + 1)); val end = " ".repeat(indent * level)
        when (el) {
            is KxObject -> {
                if (el.isEmpty()) { sb.append("{}"); return }
                sb.append("{\n")
                el.entries.forEachIndexed { i, (k, v) ->
                    sb.append(pad).append(KxPrimitive(k).toString()).append(": ")
                    write(v, indent, level + 1, sb)
                    if (i < el.size - 1) sb.append(',')
                    sb.append('\n')
                }
                sb.append(end).append('}')
            }
            is KxArray -> {
                if (el.isEmpty()) { sb.append("[]"); return }
                sb.append("[\n")
                el.forEachIndexed { i, v ->
                    sb.append(pad); write(v, indent, level + 1, sb)
                    if (i < el.size - 1) sb.append(',')
                    sb.append('\n')
                }
                sb.append(end).append(']')
            }
            else -> sb.append(el.toString())
        }
    }
}
