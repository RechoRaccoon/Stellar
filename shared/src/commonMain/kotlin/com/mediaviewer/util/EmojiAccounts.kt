package com.mediaviewer.util

import androidx.compose.runtime.mutableStateMapOf
import com.mediaviewer.json.StellarJson
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.SharedPreferences
import com.mediaviewer.platform.sharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * The accounts saved in the emoji menu's top row (each one's emoji are a
 * tap away), per signed-in account, in the order they were put in. Kept on
 * this device; also part of the private settings that sync between your
 * devices (see PrivateSync).
 */
object EmojiAccounts {
    @Serializable
    data class Account(val did: String, val handle: String = "", val displayName: String = "", val avatarUrl: String? = null)

    private var prefs: SharedPreferences? = null
    private val lists = mutableStateMapOf<String, List<Account>>()

    fun init(context: PlatformContext) {
        if (prefs == null) prefs = context.sharedPreferences("emoji_accounts")
    }

    private fun key(owner: String) = "saved_$owner"

    /** Forgets what's been read (Private Sync just wrote new lists). */
    fun reload() { lists.clear() }

    /** [owner]'s saved accounts (read by Compose: changes recompose). */
    fun saved(owner: String): List<Account> {
        if (owner.isBlank()) return emptyList()
        lists[owner]?.let { return it }
        val read = prefs?.getString(key(owner), null)?.let { raw ->
            runCatching { StellarJson.default.decodeFromString(ListSerializer(Account.serializer()), raw) }.getOrNull()
        }.orEmpty()
        lists[owner] = read
        return read
    }

    private fun put(owner: String, list: List<Account>) {
        lists[owner] = list
        prefs?.edit()?.putString(key(owner), StellarJson.default.encodeToString(ListSerializer(Account.serializer()), list))?.apply()
        onChanged?.invoke(owner)
    }

    fun isSaved(owner: String, did: String) = saved(owner).any { it.did == did }

    /** Adds [account] at the end (or refreshes its name and picture). */
    fun save(owner: String, account: Account) {
        if (owner.isBlank() || account.did.isBlank() || account.did == owner) return
        val cur = saved(owner)
        put(owner, if (cur.any { it.did == account.did }) cur.map { if (it.did == account.did) account else it } else cur + account)
    }

    fun remove(owner: String, did: String) {
        val cur = saved(owner)
        if (cur.none { it.did == did }) return
        put(owner, cur.filterNot { it.did == did })
    }

    fun move(owner: String, from: Int, to: Int) {
        val cur = saved(owner).toMutableList()
        if (from !in cur.indices) return
        val t = to.coerceIn(0, cur.lastIndex)
        if (t == from) return
        cur.add(t, cur.removeAt(from))
        put(owner, cur)
    }

    /** The whole list, as the private sync carries it. */
    fun export(owner: String): List<Account> = saved(owner)

    /** Takes a list that came from another device. */
    fun import(owner: String, list: List<Account>) {
        if (owner.isBlank()) return
        lists[owner] = list
        prefs?.edit()?.putString(key(owner), StellarJson.default.encodeToString(ListSerializer(Account.serializer()), list))?.apply()
    }

    /** Called after a change made here (the private sync listens). */
    var onChanged: ((String) -> Unit)? = null
}
