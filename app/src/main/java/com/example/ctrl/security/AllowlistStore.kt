package com.example.ctrl.security

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.allowlistDataStore by preferencesDataStore(name = "ctrl_allowlist")

data class AllowlistConfig(
    val enabled: Boolean,
    val entries: List<String>,
    val lastBlockedIp: String?,
) {
    fun compiledRules(): List<AllowRule> = entries.mapNotNull { raw ->
        runCatching { Allowlist.parseEntry(raw) }.getOrNull()
    }
}

class AllowlistStore(private val context: Context) {
    private object Keys {
        val enabled: Preferences.Key<Boolean> = booleanPreferencesKey("enabled")
        val entries: Preferences.Key<String> = stringPreferencesKey("entries")
        val lastBlockedIp: Preferences.Key<String> = stringPreferencesKey("last_blocked_ip")
    }

    val configFlow: Flow<AllowlistConfig> = context.allowlistDataStore.data.map { prefs ->
        val enabled = prefs[Keys.enabled] ?: true
        val entries = decodeEntries(prefs[Keys.entries])
        val lastBlocked = prefs[Keys.lastBlockedIp]
        AllowlistConfig(enabled = enabled, entries = entries, lastBlockedIp = lastBlocked)
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.allowlistDataStore.edit { it[Keys.enabled] = enabled }
    }

    suspend fun addEntry(raw: String) {
        val normalized = Allowlist.normalizeEntry(raw)
        context.allowlistDataStore.edit { prefs ->
            val current = decodeEntries(prefs[Keys.entries]).toMutableList()
            if (!current.contains(normalized)) {
                current.add(normalized)
            }
            prefs[Keys.entries] = encodeEntries(current)
        }
    }

    suspend fun removeEntry(entry: String) {
        val normalized = Allowlist.normalizeEntry(entry)
        context.allowlistDataStore.edit { prefs ->
            val current = decodeEntries(prefs[Keys.entries]).toMutableList()
            current.removeAll { it == normalized }
            prefs[Keys.entries] = encodeEntries(current)
        }
    }

    suspend fun clearEntries() {
        context.allowlistDataStore.edit { prefs ->
            prefs[Keys.entries] = ""
        }
    }

    suspend fun setLastBlockedIp(ip: String?) {
        context.allowlistDataStore.edit { prefs ->
            if (ip == null) prefs.remove(Keys.lastBlockedIp) else prefs[Keys.lastBlockedIp] = ip
        }
    }

    private fun decodeEntries(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun encodeEntries(entries: List<String>): String =
        entries.joinToString("\n")
}
