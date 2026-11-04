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
}
