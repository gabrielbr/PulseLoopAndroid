package com.pulseloop.mcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the MCP server back after a reboot or an app update, and only when the user left it on
 * with "Always on" chosen ([McpConfig.restartAfterBoot]). A timed session never comes back by
 * itself. Both broadcasts are protected (only the system sends them), so the receiver can't be
 * triggered by another app.
 */
class McpBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = McpPrefs(context)
        if (!McpConfig.restartAfterBoot(prefs.enabled, prefs.alwaysOn)) return
        try {
            McpService.start(context)
        } catch (e: Exception) {
            // A start the system refuses leaves it off; the switch then reads Off.
            android.util.Log.w("McpBootReceiver", "MCP restart refused: ${e.javaClass.simpleName}")
            prefs.enabled = false
        }
    }
}
