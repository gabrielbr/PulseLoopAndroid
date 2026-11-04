# AI tools on your network (MCP): threat model

PulseLoop can answer AI tools on the user's own network, such as Claude Desktop or Cursor on a
laptop, over the Model Context Protocol (streamable HTTP, `POST /mcp`, JSON-RPC 2.0, protocol
`2025-06-18`). This note covers what that exposes and what limits it. It is a port of GMind's
design (`nottheomiaiapp/docs/MCP-THREAT-MODEL.md`), adapted to health data.

Code: `app/src/main/java/com/pulseloop/mcp/` (`McpHttp`, `McpNet`, `McpTokens`, `McpProtocol`,
`McpServer`, `McpCoachTools`, `McpSummary`, `McpService`, `McpPrefs`), the screen in
`ui/screens/McpSettingsUi.kt` (Settings › Connected apps › AI tools on your network), and the
tests in `app/src/test/java/com/pulseloop/mcp/`.

## What it exposes

The coach's own read tools, listed from the same `CoachToolDef` schemas, plus `summary`:

- ring data: `get_profile_context`, `get_daily_summary`, `get_range_summary`, `get_metric_series`,
  `get_activity_sessions`, `summarize_activity_session`, `get_sync_status`,
  `get_data_availability`, `get_sleep_summary`, `get_sleep_trends`, `get_goal_progress`,
  `get_recent_anomalies`;
- arithmetic over numbers the caller sends: `analyze_trend`, `compare_periods`,
  `compute_correlation`, `detect_outliers`, `summarize_distribution`;
- logged data: `get_nutrition_log`, `get_goal`, `suggest_next_meal`, `get_food_preferences`,
  `get_drinks` (refuses while Drinks is off);
- `summary`: today, last night, the latest heart rate and SpO2, the last 7 days, the ring's state
  and the coach's data-quality notes, as text.

Those can return the user's health data: steps, heart rate, SpO2, sleep, workouts, meals, drinks,
goals, profile name and age. **Anyone holding a valid token and able to reach the phone can read
that while the server is on.** That is the asset to protect.

**Never offered**, by an explicit allowlist (`McpCoachTools.ALLOWLIST`), not by flags alone: every
tool that writes, logs, sets a goal, deletes, queues a confirmation, starts a live ring
measurement or searches the web (`log_meal`, `set_goal`, `save_food_preference`, `log_drink`,
`set_drink_plan`, `save_memory`, `log_user_note`, the activity and meal edit/delete tools,
`trigger_measurement`, `search_food_database`). There is no tool at all for the coach's API keys,
raw ring packets, diagnostics, lab reports or their PDFs, or the EPDS answers. `summary` reuses
the coach's context packet but drops its labs, postpartum, drinks and goal-plan blocks.

## What limits it

| Threat | Mitigation |
|---|---|
| Left on by accident | Off by default. Never started on boot; Android doesn't restart it (`START_NOT_STICKY`). While it runs an ongoing notification says "Sharing your health data with paired AI tools on this network. Tap to stop.", and a tap stops it. It turns itself off after 1, 4 or 8 hours (default 8; Android 15 ends a `dataSync` service sooner, at its daily limit, through `onTimeout`). Stopping for any reason clears the switch. |
| Reached from the internet or over mobile data | Listens only on Wi-Fi, Ethernet, hotspot and Tailscale (`tun`) interfaces, never `rmnet`/`ccmni`. Binds only addresses `McpNet.allowedPeer` accepts: loopback, 10/8, 172.16/12, 192.168/16, link-local, 100.64/10 (Tailscale/CGNAT), IPv6 ULA. Each connection's peer is checked again and closed before anything is read. |
| Someone else on the same Wi-Fi | Every request needs `Authorization: Bearer <token>`. Each paired tool has its own token: 32 random bytes, base64url, shown once. Only its SHA-256 is stored, compared in constant time against every pairing. Tokens are revoked one by one. |
| A web page in a browser on the network (CSRF, DNS rebinding) | Any request with an `Origin` header is refused (403) before the token is even looked at. MCP clients don't send one; browsers do on cross-origin POSTs. The token is required anyway. |
| Changing or deleting data | Only allowlisted read tools are listed or callable, and they run with `CoachFeatureFlags(writeToolsEnabled = false, liveMeasurementsEnabled = false, webSearchEnabled = false)` and `coordinator = null`, so even a misrouted call has no ring to talk to. `McpAllowlistTest` fails if a write tool's name reaches the allowlist, or if a new coach tool appears that nobody has classified. |
| Exhausting the phone | At most 4 connections at once, a 15 s socket timeout, 8 KB of headers, a 256 KB body, no chunked bodies, one request per connection, one JSON-RPC message per request (no batches), one tool call at a time (a 20 s wait, then "busy"), replies cut at 200 KB. |
| Another app on the phone starting it | `McpService` is `exported="false"`; only PulseLoop's settings screen starts it. Its loopback address is still reachable by other apps on the phone, but they need a token too. |
| A restore carrying a pairing to another phone | The switch and the pairings live in their own preferences file, `mcp_server`, which is not on `ArchivedSettings`' allowlist; `android:allowBackup` is off. A restore starts with the server off and nothing paired. |
| A token leaking through the clipboard | Copies are marked sensitive (`android.content.extra.IS_SENSITIVE`), which hides the clipboard preview on Android 13+. |
| Plain HTTP on the LAN | The traffic is readable on the local network. Use Tailscale (encrypted) on networks you don't trust, or keep it off. The token gates access; it doesn't hide the replies. |
| The agent over-reading the numbers | The `initialize` result's `instructions` say this is the user's own data from a consumer ring, read-only, not a diagnostic tool, that the numbers are estimates, and to send anything worrying to a doctor. |

## Residual risks

- **A leaked token** gives read access from the network while the server is on, until it is
  revoked in Settings › Connected apps › AI tools on your network.
- **Untrusted Wi-Fi** (cafés, hotels): other people there can read the plain-HTTP traffic,
  including the token. Turn it off there, or use Tailscale.
- **The paired tool** sees what it asks for, and its own data policy then applies to that health
  data. A cloud-backed assistant sends it to its provider.
- **The address can change.** The server binds the addresses it found at start; after a Wi-Fi
  change it must be turned off and on again.
- **The notification can be hidden.** If notifications are denied, Android 13+ still runs the
  service but may not show its card; the switch on the settings screen still shows the truth.
- **Readings may be demo rows.** The tools read what the screens read (real rows win once the
  ring has synced; before that, demo data). `summary` carries the coach's "this is demo data"
  note when that applies.
