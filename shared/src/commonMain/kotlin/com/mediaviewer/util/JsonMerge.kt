package com.mediaviewer.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Private Sync's three-way merge of JSON: [base] is what both sides had at
 * the last sync, [local] and [remote] what each has now. Whatever only one
 * side changed is taken from it; objects merge key by key; lists of
 * entries with ids (drafts, notes, folders, events…) merge entry by entry,
 * so an entry added, edited or removed on either side stays that way;
 * lists of plain values merge like sets. Only the very same value changed
 * on both sides is a real conflict, settled by [localWins]. An absent value
 * is null, so an edit outlives a delete.
 *
 * (Tested by tools/vault-crypto-test.)
 */
object JsonMerge {
    fun merge3(base: JsonElement?, local: JsonElement?, remote: JsonElement?, localWins: Boolean): JsonElement? {
        val l = local?.takeUnless { it is JsonNull }
        val r = remote?.takeUnless { it is JsonNull }
        val b = base?.takeUnless { it is JsonNull }
        if (l == r) return l
        if (l == b) return r
        if (r == b) return l
        // Changed on both sides.
        if (l == null) return r // (an edit outlives a delete)
        if (r == null) return l
        if (l is JsonObject && r is JsonObject) {
            val bo = b as? JsonObject
            val out = linkedMapOf<String, JsonElement>()
            for (key in LinkedHashSet(l.keys + r.keys)) merge3(bo?.get(key), l[key], r[key], localWins)?.let { out[key] = it }
            return JsonObject(out)
        }
        if (l is JsonArray && r is JsonArray) {
            val ba = b as? JsonArray
            val il = ids(l); val ir = ids(r); val ib = if (ba == null) emptyMap() else ids(ba)
            if (il != null && ir != null && ib != null) {
                val out = linkedMapOf<String, JsonElement>()
                // This side's order, with the other side's new ones where
                // they stood there.
                for ((id, v) in il) merge3(ib[id], v, ir[id], localWins)?.let { out[id] = it }
                val list = out.entries.map { it.key to it.value }.toMutableList()
                ir.keys.forEachIndexed { pos, id ->
                    if (id in il) return@forEachIndexed
                    val merged = merge3(ib[id], null, ir[id], localWins) ?: return@forEachIndexed
                    list.add(pos.coerceAtMost(list.size), id to merged)
                }
                return JsonArray(list.map { it.second })
            }
            val pl = prims(l); val pr = prims(r)
            if (pl != null && pr != null) {
                val pb = (ba?.let { prims(it) }).orEmpty().toSet()
                val keep = pl.filter { it in pr || it !in pb } + pr.filter { it !in pl && it !in pb }
                return JsonArray(keep)
            }
        }
        return if (localWins) l else r
    }

    /** id → element when every element is an object with its own id. */
    private fun ids(a: JsonArray): Map<String, JsonElement>? {
        val out = linkedMapOf<String, JsonElement>()
        for (e in a) {
            val o = e as? JsonObject ?: return null
            fun f(name: String) = (o[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            val id = f("id") ?: f("rkey")?.let { (f("did") ?: "") + "/" + it } ?: f("uri") ?: f("did")
                ?: f("createdAt")?.let { it + "|" + (f("text") ?: "") } ?: return null
            if (id in out) return null
            out[id] = e
        }
        return out
    }

    /** The elements when they're all distinct plain values. */
    private fun prims(a: JsonArray): List<JsonElement>? =
        if (a.all { it is JsonPrimitive } && a.distinct().size == a.size) a.toList() else null
}
