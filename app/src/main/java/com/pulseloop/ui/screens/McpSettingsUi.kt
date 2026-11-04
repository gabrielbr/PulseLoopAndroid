package com.pulseloop.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pulseloop.mcp.McpConfig
import com.pulseloop.mcp.McpNet
import com.pulseloop.mcp.McpPrefs
import com.pulseloop.mcp.McpService
import com.pulseloop.mcp.McpTokens
import com.pulseloop.mcp.PairedTool
import com.pulseloop.ui.components.PulseButton
import com.pulseloop.ui.components.PulseButtonStyle
import com.pulseloop.ui.components.PulseSegmented
import com.pulseloop.ui.components.switchRow
import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf
import com.pulseloop.ui.theme.PulseColors
import com.pulseloop.ui.theme.PulseType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Settings › Connected apps › AI tools on your network: the read-only MCP server
 * (docs/mcp-threat-model.md). Off by default. Summary first (the switch and where to connect),
 * then the paired tools and the one action, "Pair a tool". A token is shown once, in a dialog,
 * with the config to paste; only its hash is kept.
 */
@Composable
fun McpSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { McpPrefs(context) }
    val tokens = remember { McpTokens(prefs) }
    val status by McpService.status.collectAsState()
    var paired by remember { mutableStateOf(tokens.list()) }
    var hours by remember { mutableIntStateOf(prefs.autoOffHours) }
    var alwaysOn by remember { mutableStateOf(prefs.alwaysOn) }
    var askingAlwaysOn by remember { mutableStateOf(false) }
    // Re-read when the screen comes back from the system's battery dialog.
    var batteryExempt by remember { mutableStateOf(isBatteryExempt(context)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        batteryExempt = isBatteryExempt(context)
        onPauseOrDispose { }
    }
    // Always on that Android stopped (or refused to restart) comes back when this screen opens.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (prefs.enabled && prefs.alwaysOn && !McpService.status.value.running) McpService.start(context)
    }
    // A changed choice applies at once while it runs: start() recomputes the off time and the
    // service's type. While off it is simply kept for next time.
    fun choose(always: Boolean, h: Int) {
        alwaysOn = always; prefs.alwaysOn = always
        if (!always) { hours = h; prefs.autoOffHours = h }
        if (status.running || prefs.enabled) McpService.start(context)
    }
    var askingLabel by remember { mutableStateOf(false) }
    var justPaired by remember { mutableStateOf<Pair<String, String>?>(null) } // label to token
    var revoking by remember { mutableStateOf<PairedTool?>(null) }

    val starting = !status.running && status.offAt > 0L && !status.unreachable
    val on = status.running || starting
    // While off, the addresses it would listen on, so a config can be made before turning it on.
    val localUrls by produceState(initialValue = emptyList<String>(), status.running) {
        value = withContext(Dispatchers.IO) { McpNet.localAddresses().map { a -> McpNet.url(a) } }
    }
    val urls = if (status.running) status.urls else localUrls

    SettingsSubScreen(title = tr("AI tools on your network"), onBack = onBack) {
        Text(
            tr("Let AI tools on your computer, like Claude Desktop or Cursor, read your PulseLoop data over your Wi-Fi or Tailscale. They can only read: nothing can be changed or deleted."),
            style = PulseType.body,
            color = PulseColors.textSecondary,
        )

        McpCard {
            Row(
                Modifier.fillMaxWidth().switchRow(checked = on, onCheckedChange = { want ->
                    if (want) McpService.start(context) else McpService.stop(context)
                }),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr("Share with AI tools on this network"), style = PulseType.title, color = PulseColors.textPrimary, modifier = Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = null)
            }
            Text(
                when {
                    status.running && status.offAt == McpConfig.NEVER -> tr("On until you turn it off, including after a restart.")
                    status.running -> trf("On until {0}", clock(status.offAt))
                    starting -> tr("Starting…")
                    status.unreachable -> tr("Not on Wi-Fi or Tailscale, so nothing could reach it. Connect and try again.")
                    else -> tr("Off")
                },
                style = PulseType.body,
                color = if (status.unreachable && !on) PulseColors.warning else PulseColors.textSecondary,
            )
            Text(tr("Stays on for"), style = PulseType.eyebrow, color = PulseColors.textSecondary)
            PulseSegmented(
                options = McpPrefs.HOURS_CHOICES.map { it to trf("{0} h", it) } + (ALWAYS to tr("Always")),
                selected = if (alwaysOn) ALWAYS else hours,
                onSelect = { h -> if (h == ALWAYS) askingAlwaysOn = true else choose(false, h) },
            )
            if (alwaysOn && !batteryExempt) {
                Text(
                    tr("Android may pause it while the phone sleeps. Allow it to run in the background so your tools can always reach it."),
                    style = PulseType.label,
                    color = PulseColors.textSecondary,
                )
                PulseButton(PulseButtonStyle.Outlined, onClick = { requestBatteryExemption(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Allow background running"))
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Connect to").uppercase(), style = PulseType.eyebrow, color = PulseColors.textSecondary)
            if (urls.isEmpty()) {
                Text(tr("Connect the phone to Wi-Fi or Tailscale to get an address."), style = PulseType.body, color = PulseColors.textSecondary)
            } else {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        urls.forEach { u ->
                            Text(
                                if (u.startsWith("http://100.")) trf("{0} (Tailscale)", u) else u,
                                style = PulseType.body,
                                color = PulseColors.textPrimary,
                            )
                        }
                    }
                }
            }
            Text(
                tr("Paired tools on this network can read your health data while this is on. Nothing is sent anywhere by itself."),
                style = PulseType.body,
                color = PulseColors.textSecondary,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Paired tools").uppercase(), style = PulseType.eyebrow, color = PulseColors.textSecondary)
            if (paired.isEmpty()) {
                Text(tr("No tools paired yet."), style = PulseType.body, color = PulseColors.textSecondary)
            }
            paired.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t.label, style = PulseType.title, color = PulseColors.textPrimary)
                        Text(
                            t.lastUsedAt?.let { trf("Paired {0} · last used {1}", day(t.createdAt), day(it)) }
                                ?: trf("Paired {0} · not used yet", day(t.createdAt)),
                            style = PulseType.label,
                            color = PulseColors.textSecondary,
                        )
                    }
                    PulseButton(PulseButtonStyle.Text, onClick = { revoking = t }) { Text(tr("Revoke")) }
                }
            }
            PulseButton(PulseButtonStyle.Tonal, onClick = { askingLabel = true }, modifier = Modifier.fillMaxWidth()) {
                Text(tr("Pair a tool"))
            }
        }
    }

    if (askingAlwaysOn) {
        AlertDialog(
            onDismissRequest = { askingAlwaysOn = false },
            title = { Text(tr("Keep it always on?")) },
            text = {
                Text(
                    tr("Paired tools on this network will be able to read your health data at any time, also after the phone restarts, until you turn it off here or tap Stop in its notification. The notification stays while it's on."),
                    style = PulseType.body,
                )
            },
            confirmButton = {
                PulseButton(PulseButtonStyle.Text, onClick = { askingAlwaysOn = false; choose(true, hours) }) { Text(tr("Keep it on")) }
            },
            dismissButton = {
                PulseButton(PulseButtonStyle.Text, onClick = { askingAlwaysOn = false }) { Text(tr("Cancel")) }
            },
        )
    }

    if (askingLabel) {
        var label by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askingLabel = false },
            title = { Text(tr("Pair a tool")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Name it so you'll recognise it later, like the app and the computer."), style = PulseType.body)
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it.take(McpTokens.MAX_LABEL) },
                        label = { Text(tr("Name")) },
                        placeholder = { Text(tr("Claude Desktop on my laptop")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                PulseButton(PulseButtonStyle.Text, onClick = {
                    val (tool, token) = tokens.create(label.ifBlank { tr("AI tool") }, System.currentTimeMillis())
                    paired = tokens.list()
                    askingLabel = false
                    justPaired = tool.label to token
                }) { Text(tr("Pair")) }
            },
            dismissButton = {
                PulseButton(PulseButtonStyle.Text, onClick = { askingLabel = false }) { Text(tr("Cancel")) }
            },
        )
    }

    justPaired?.let { (label, token) ->
        val url = urls.firstOrNull() ?: "http://PHONE-ADDRESS:${McpNet.PORT}/mcp"
        val bridge = McpConfig.stdioBridge(url, token)
        val direct = McpConfig.direct(url, token)
        AlertDialog(
            onDismissRequest = { /* only Done closes it: the token is never shown again */ },
            title = { Text(trf("{0} is paired", label)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Copy this now. It won't be shown again."), style = PulseType.body, color = PulseColors.warning)
                    SelectionContainer { Text(token, style = PulseType.body, color = PulseColors.textPrimary) }
                    PulseButton(PulseButtonStyle.Text, onClick = { copySecret(context, token) }) { Text(tr("Copy token")) }

                    Text(tr("Claude Desktop or Cursor").uppercase(), style = PulseType.eyebrow, color = PulseColors.textSecondary)
                    Text(
                        tr("Add this to claude_desktop_config.json or .cursor/mcp.json, then restart the app. It needs Node.js on the computer."),
                        style = PulseType.label,
                        color = PulseColors.textSecondary,
                    )
                    SelectionContainer { Text(bridge, style = PulseType.label, color = PulseColors.textPrimary) }
                    PulseButton(PulseButtonStyle.Text, onClick = { copySecret(context, bridge) }) { Text(tr("Copy config")) }

                    Text(tr("Tools that connect over HTTP").uppercase(), style = PulseType.eyebrow, color = PulseColors.textSecondary)
                    SelectionContainer { Text(direct, style = PulseType.label, color = PulseColors.textPrimary) }
                    PulseButton(PulseButtonStyle.Text, onClick = { copySecret(context, direct) }) { Text(tr("Copy address and header")) }
                    if (urls.isEmpty()) {
                        Text(
                            tr("Replace PHONE-ADDRESS with the phone's address once it is on Wi-Fi or Tailscale."),
                            style = PulseType.label,
                            color = PulseColors.textSecondary,
                        )
                    }
                }
            },
            confirmButton = {
                PulseButton(PulseButtonStyle.Text, onClick = { justPaired = null }) { Text(tr("Done")) }
            },
        )
    }

    revoking?.let { t ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text(trf("Revoke {0}?", t.label)) },
            text = { Text(tr("It won't be able to read your data any more. Pair it again to reconnect."), style = PulseType.body) },
            confirmButton = {
                PulseButton(PulseButtonStyle.Text, onClick = {
                    tokens.revoke(t.id)
                    paired = tokens.list()
                    revoking = null
                }) { Text(tr("Revoke")) }
            },
            dismissButton = {
                PulseButton(PulseButtonStyle.Text, onClick = { revoking = null }) { Text(tr("Cancel")) }
            },
        )
    }
}

@Composable
private fun McpCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(PulseColors.card, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("d MMM")

/** The segmented control's key for "Always": no hour choice is 0. */
private const val ALWAYS = 0

private fun isBatteryExempt(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true

/** The system's own "allow in the background" dialog; the settings list if a phone lacks it. */
private fun requestBatteryExemption(context: Context) {
    val ask = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(android.net.Uri.parse("package:" + context.packageName))
    try {
        context.startActivity(ask)
    } catch (_: Exception) {
        runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

private fun clock(ts: Long): String = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(CLOCK)
private fun day(ts: Long): String = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(DAY)

/** Copies a token or a config holding one, marked sensitive so the clipboard preview hides it. */
private fun copySecret(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("PulseLoop", text)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    cm.setPrimaryClip(clip)
}
