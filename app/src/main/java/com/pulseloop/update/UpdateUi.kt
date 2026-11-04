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
import com.pulseloop.ui.components.MarkdownLite
import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf
import android.text.format.Formatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * "Update available" dialog: shows the changelog, then downloads the APK with a progress bar
 * and hands it to the system installer. Reused by the on-launch check and the Settings button.
 */
@Composable
fun UpdateDialog(info: UpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    val downloading = job != null
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    // The size on the button, so nobody spends mobile data unknowingly (docs/self-update.md).
    val size = remember(info.apkSize) {
        if (info.apkSize > 0) Formatter.formatShortFileSize(context, info.apkSize) else null
    }

    AlertDialog(
        // While downloading, tapping outside or Back does nothing; Cancel is the way out.
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text(trf("Update available · {0}", info.versionName)) },
        text = {
            Column {
                if (downloading) {
                    Text(trf("Downloading… {0}%", (progress * 100).toInt()), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    MarkdownLite(
                        info.changelog,
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
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
                    // One download at a time: the button is disabled while [job] runs.
                    job = scope.launch {
                        try {
                            when (val result = UpdateChecker.download(context, info) { progress = it }) {
                                is DownloadResult.Ready -> {
                                    ApkInstaller.install(context, result.file)
                                    onDismiss()
                                }
                                DownloadResult.Failed -> error = tr("Download failed. Try again.")
                                DownloadResult.ChecksumMismatch -> error =
                                    tr("The download didn't match the release's checksum, so it wasn't installed.")
                                DownloadResult.WrongSignature -> error =
                                    tr("The download isn't signed like this app, so it wasn't installed.")
                            }
                        } finally {
                            job = null
                        }
                    }
                },
            ) {
                Text(
                    when {
                        downloading -> tr("Downloading…")
                        size != null -> trf("Update · {0}", size)
                        else -> tr("Update")
                    }
                )
            }
        },
        dismissButton = {
            if (downloading) {
                // Cancel stops the download and deletes the part (UpdateChecker.download).
                TextButton(onClick = { job?.cancel(); job = null }) { Text(tr("Cancel")) }
            } else {
                TextButton(onClick = onDismiss) { Text(tr("Later")) }
            }
        },
    )
}

/**
 * Self-contained "Check for updates" control for the Settings screen: a button that runs a
 * forced check and shows the result (up-to-date / error / the update dialog). On a debug or
 * non-release build, self-update isn't possible, so it shows a short explanation instead.
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
                            status = trf("Couldn't check for updates ({0}). Try again.", result.reason)
                        else -> status = trf("You're on the latest version ({0}).", BuildConfig.VERSION_NAME)
                    }
                    checking = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (checking) tr("Checking…") else tr("Check for updates")) }
        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    update?.let { UpdateDialog(it) { update = null } }
}
