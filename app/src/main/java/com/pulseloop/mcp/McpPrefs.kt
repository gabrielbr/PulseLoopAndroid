package com.pulseloop.mcp

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The MCP server's own preferences file, `mcp_server`: whether it may run, when it turns itself
 * off, the auto-off length, and the paired tools (hashes only).
 *
 * **Not on `ArchivedSettings`' allowlist, on purpose.** The switch is device state and the
 * pairings are security material: a backup restored on a new phone must start with the server
 * off and nothing paired. `android:allowBackup` is off, so Android's own backup doesn't carry it
 * either.
 */
class McpPrefs(context: Context) : McpTokenStorage {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Set while the service runs; cleared whenever it stops, so it never outlives the process. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) { prefs.edit().putBoolean(KEY_ENABLED, v).apply() }

    /** When the running server turns itself off (epoch ms); 0 when off. */
    var offAt: Long
        get() = prefs.getLong(KEY_OFF_AT, 0L)
        set(v) { prefs.edit().putLong(KEY_OFF_AT, v).apply() }

    var autoOffHours: Int
        get() = prefs.getInt(KEY_HOURS, DEFAULT_HOURS).takeIf { it in HOURS_CHOICES } ?: DEFAULT_HOURS
        set(v) { prefs.edit().putInt(KEY_HOURS, v).apply() }

    /** Whether a request may be answered now: switched on and not past its auto-off time. */
    fun isOn(now: Long = System.currentTimeMillis()): Boolean = enabled && now < offAt

    override fun loadTools(): List<PairedTool> = prefs.getString(KEY_TOOLS, null)
        ?.let { runCatching { json.decodeFromString(ListSerializer(PairedTool.serializer()), it) }.getOrNull() }
        .orEmpty()

    override fun saveTools(tools: List<PairedTool>) {
        // commit(): a pairing must be on disk before its token is shown.
        prefs.edit().putString(KEY_TOOLS, json.encodeToString(ListSerializer(PairedTool.serializer()), tools)).commit()
    }

    companion object {
        const val FILE = "mcp_server"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_OFF_AT = "offAt"
        private const val KEY_HOURS = "autoOffHours"
        private const val KEY_TOOLS = "pairedTools"
        const val DEFAULT_HOURS = 8
        val HOURS_CHOICES = listOf(1, 4, 8)
        private val json = Json { ignoreUnknownKeys = true }
    }
}
