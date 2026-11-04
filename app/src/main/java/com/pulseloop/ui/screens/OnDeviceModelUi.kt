package com.pulseloop.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.coach.ondevice.ModelDownload
import com.pulseloop.coach.ondevice.ModelState
import com.pulseloop.coach.ondevice.OnDeviceModelStore
import com.pulseloop.ui.components.PulseButton
import com.pulseloop.ui.components.PulseButtonStyle
import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf
import com.pulseloop.ui.theme.PulseColors
import kotlinx.coroutines.launch

/**
 * Settings › AI Coach › "On this phone" (docs/on-device-model-and-mcp-plan.md, Part 1, "What the
 * person sees"). Shown under the provider picker when that provider is chosen.
 *
 * Flow: what it is → the size before anything moves → Download (Wi-Fi only) or "Download now on
 * mobile data" → progress with Cancel (and a switch to mobile data while it waits for Wi-Fi) →
 * Ready, with Remove. "Choose a file instead" copies a model the user already has. A failure says
 * why in plain words and offers the same actions again. Below the memory floor nothing is offered,
 * and the line says why.
 *
 * One primary action at a time, Tonal (this is a pushed screen; the Actions button is the screen's
 * only filled accent); everything else is a Text button.
 */
@Composable
fun OnDeviceModelSection() {
    val context = LocalContext.current
    val store = remember { OnDeviceModelStore.get(context) }
    val state by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { store.refresh() }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { store.import(uri) }
    }
    val openTerms = {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ModelDownload.TERMS_URL))) }
        Unit
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            tr("Answers on this phone, with no key and no connection. Nothing you ask leaves the phone. " +
                "It is slower and simpler than a cloud coach: no charts, no goal changes, no live measurements."),
            fontSize = 14.sp, color = PulseColors.textSecondary,
        )

        val current = state
        // Below the floor the model would starve the phone: say why, and offer nothing to start.
        if (!store.meetsMemoryFloor && current !is ModelState.Ready) {
            Text(
                trf(
                    "Not offered on this phone: it has {0} GB of memory, and the model needs about 4 GB to run without slowing everything down.",
                    "%.1f".format(store.totalMemoryBytes / 1_000_000_000.0),
                ),
                fontSize = 14.sp, color = PulseColors.textPrimary,
            )
        } else {
            ModelControls(store, current, onPickFile = { pickFile.launch(arrayOf("*/*")) })
        }
        PulseButton(PulseButtonStyle.Text, onClick = openTerms) { Text(tr("Gemma Terms of Use")) }
    }
}

/** The status, the progress and the one or two actions that fit [current]. */
@Composable
private fun ModelControls(store: OnDeviceModelStore, current: ModelState, onPickFile: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(statusLine(current), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = statusColor(current))

        when (current) {
            is ModelState.Getting -> {
                val p = current.progress
                if (p != null) {
                    LinearProgressIndicator(
                        progress = { p },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = PulseColors.info,
                        trackColor = PulseColors.cardSoft,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = PulseColors.info,
                        trackColor = PulseColors.cardSoft,
                    )
                }
                if (current.waitingForWifi) {
                    PulseButton(PulseButtonStyle.Tonal, onClick = { store.useMobileData() }, modifier = Modifier.fillMaxWidth()) {
                        Text(tr("Download now on mobile data"))
                    }
                }
                if (!current.copying) {
                    PulseButton(PulseButtonStyle.Text, onClick = { store.cancel() }) { Text(tr("Cancel")) }
                }
            }
            is ModelState.Ready -> {
                PulseButton(PulseButtonStyle.Text, onClick = { store.remove() }) {
                    Text(trf("Remove the model (frees {0} MB)", current.bytes / 1_000_000))
                }
            }
            ModelState.Absent, is ModelState.Failed -> {
                Text(
                    trf("{0}, about {1} MB. Downloads on Wi-Fi only, unless you choose mobile data.",
                        ModelDownload.DISPLAY_NAME, ModelDownload.APPROX_MB),
                    fontSize = 14.sp, color = PulseColors.textSecondary,
                )
                PulseButton(PulseButtonStyle.Tonal, onClick = { store.download(wifiOnly = true) }, modifier = Modifier.fillMaxWidth()) {
                    Text(trf("Download ({0} MB)", ModelDownload.APPROX_MB))
                }
                PulseButton(PulseButtonStyle.Text, onClick = { store.download(wifiOnly = false) }) {
                    Text(tr("Download now on mobile data"))
                }
                PulseButton(PulseButtonStyle.Text, onClick = onPickFile) {
                    Text(tr("Choose a file instead"))
                }
                Text(
                    tr("Downloading means you accept Google's Gemma Terms of Use."),
                    fontSize = 12.sp, color = PulseColors.textMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private fun statusLine(state: ModelState): String = when (state) {
    ModelState.Absent -> tr("Not downloaded yet")
    is ModelState.Getting -> {
        val pct = state.progress?.let { (it * 100).toInt() }
        when {
            state.copying -> if (pct != null) trf("Copying the file… {0}%", pct) else tr("Copying the file…")
            state.waitingForWifi -> tr("Waiting for Wi-Fi")
            pct == null -> tr("Starting the download…")
            pct >= 100 -> tr("Checking the download…")
            else -> trf("Downloading… {0}%", pct)
        }
    }
    is ModelState.Ready -> trf("Ready · {0}", ModelDownload.DISPLAY_NAME)
    is ModelState.Failed -> when (state.reason) {
        ModelState.Reason.NOT_A_MODEL -> tr("That file isn't a model. Choose the .task file.")
        ModelState.Reason.TOO_SMALL -> tr("That file is too small to be the model.")
        ModelState.Reason.NO_SPACE -> trf("Not enough free space. Free about {0} MB and try again.", ModelDownload.APPROX_MB * 2)
        ModelState.Reason.DOWNLOAD -> tr("The download didn't finish. Check your connection and try again.")
        ModelState.Reason.NOT_PUBLISHED -> tr("The model isn't available to download right now. Try again later.")
        ModelState.Reason.CHECK -> tr("The download didn't match its published checksum, so it was deleted. Try again.")
        ModelState.Reason.OTHER -> tr("Something went wrong. Try again.")
    }
}

private fun statusColor(state: ModelState) = when (state) {
    is ModelState.Ready -> PulseColors.success
    is ModelState.Failed -> PulseColors.danger
    else -> PulseColors.textPrimary
}
