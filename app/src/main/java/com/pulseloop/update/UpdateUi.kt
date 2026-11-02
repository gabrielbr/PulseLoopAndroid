package com.pulseloop.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pulseloop.BuildConfig
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.pulseloop.ui.components.MarkdownLite
import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * "Update available" dialog (Gabriel Design System, docs/self-update.md): the changelog, then
 * Update · size downloads the APK with a progress bar, checks it, and hands it to the system
 * installer. While it downloads, Later becomes Cancel and the dialog can't be dismissed by accident.
 * Reused by the foreground check and the About screen's button.
 */
@Composable
fun UpdateDialog(info: UpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    val downloading = job != null
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text(trf("Update available · {0}", info.versionName)) },
        text = {
            Column {
                if (downloading) {
                    Text(
                        trf("Downloading… {0}%", (progress * 100).toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    MarkdownLite(
                        tr(info.changelog),   // GitHub's notes stay as written; the fallback line translates
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        // Theme colors — the dialog surface follows light/dark, unlike the
                        // coach's fixed dark cards, so the fixed PulseColors defaults would
                        // be near-invisible on a light dialog.
                        headingColor = MaterialTheme.colorScheme.onSurface,
                        bodyColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        bulletColor = MaterialTheme.colorScheme.primary,
                        boldColor = MaterialTheme.colorScheme.onSurface,
                    )
                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = {
                    // Require the per-app "install unknown apps" grant first.
                    if (!ApkInstaller.canInstall(context)) {
                        error = tr("Allow installs from this app, then tap Update again.")
                        context.startActivity(ApkInstaller.installPermissionIntent(context))
                        return@TextButton
                    }
                    error = null
                    progress = 0f
                    job = scope.launch {
                        val result = UpdateChecker.download(context, info) { progress = it }
                        job = null
                        when (result) {
                            is DownloadResult.Ready -> {
                                ApkInstaller.install(context, result.file)
                                onDismiss()
                            }
                            is DownloadResult.Failed -> error = tr(result.reason)
                        }
                    }
                },
            ) {
                Text(
                    if (info.apkSize > 0) trf("Update · {0}", UpdateChecker.sizeLabel(info.apkSize))
                    else tr("Update"),
                )
            }
        },
        dismissButton = {
            if (downloading) {
                TextButton(onClick = { job?.cancel(); job = null }) { Text(tr("Cancel")) }
            } else {
                TextButton(onClick = onDismiss) { Text(tr("Later")) }
            }
        },
    )
}

/**
 * Self-contained "Check for updates" control for the About screen: a button that runs a forced
 * check and shows the result (up to date / couldn't check / the update dialog). A debug or
 * non-release build can't update itself, so it says that instead.
 */
@Composable
fun CheckForUpdatesButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }

    Column(modifier) {
        if (!UpdateChecker.isSupported()) {
            Text(
                trf("Self-update works in the release build only ({0} / {1}).", BuildConfig.VERSION_NAME, BuildConfig.APPLICATION_ID),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        OutlinedButton(
            enabled = !checking,
            onClick = {
                checking = true
                status = null
                scope.launch {
                    when (val result = UpdateChecker.check(context, force = true)) {
                        is UpdateCheckResult.UpdateAvailable -> update = result.info
                        is UpdateCheckResult.Failed ->
                            status = trf("Couldn't check for updates ({0}). Try again.", tr(result.reason))
                        else -> status = trf("You're on the latest version ({0}).", BuildConfig.VERSION_NAME)
                    }
                    checking = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (checking) tr("Checking…") else tr("Check for updates")) }
        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }

    update?.let { UpdateDialog(it) { update = null } }
}
