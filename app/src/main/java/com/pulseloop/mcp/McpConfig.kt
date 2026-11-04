package com.pulseloop.mcp

/**
 * The text a person pastes into their AI tool after pairing. Plain Kotlin, JVM-tested.
 *
 * Claude Desktop and Cursor start MCP servers over stdio, so the ready-made entry runs
 * [mcp-remote](https://www.npmjs.com/package/mcp-remote) as a bridge. The bearer header goes
 * through an environment variable, as mcp-remote's README advises, because a space inside an
 * `args` entry is mangled by some clients on Windows. `--allow-http` is needed because the
 * phone serves plain HTTP on the local network.
 */
object McpConfig {
    const val SERVER_NAME = "pulseloop"

    fun stdioBridge(url: String, token: String): String = """
        {
          "mcpServers": {
            "$SERVER_NAME": {
              "command": "npx",
              "args": ["-y", "mcp-remote", "$url", "--allow-http", "--header", "Authorization:${'$'}{PULSELOOP_AUTH}"],
              "env": { "PULSELOOP_AUTH": "Bearer $token" }
            }
          }
        }
    """.trimIndent()

    /** For clients that speak streamable HTTP themselves. */
    fun direct(url: String, token: String): String = """
        URL: $url
        Header: Authorization: Bearer $token
    """.trimIndent()

    /**
     * Android 15 (API 35) gives a `dataSync` foreground service about six hours in every 24, then
     * calls `onTimeout` (which [McpService] honours by stopping). A longer auto-off can't be kept
     * there, so it isn't offered, and a longer stored choice reads as the cap.
     */
    const val DATA_SYNC_LIMIT_SDK = 35
    const val DATA_SYNC_LIMIT_HOURS = 6

    /** The auto-off lengths offered on a device running [sdkInt]; the last is the default. */
    fun autoOffChoices(sdkInt: Int): List<Int> =
        if (sdkInt >= DATA_SYNC_LIMIT_SDK) listOf(1, 4, DATA_SYNC_LIMIT_HOURS) else listOf(1, 4, 8)

    fun defaultAutoOffHours(sdkInt: Int): Int = autoOffChoices(sdkInt).last()

    /**
     * A stored auto-off length as this device can honour it: capped at [DATA_SYNC_LIMIT_HOURS] on
     * Android 15 and later, and the default when it isn't one of the choices offered here.
     */
    fun autoOffHours(stored: Int, sdkInt: Int): Int {
        val capped = if (sdkInt >= DATA_SYNC_LIMIT_SDK) stored.coerceAtMost(DATA_SYNC_LIMIT_HOURS) else stored
        return capped.takeIf { it in autoOffChoices(sdkInt) } ?: defaultAutoOffHours(sdkInt)
    }
}
