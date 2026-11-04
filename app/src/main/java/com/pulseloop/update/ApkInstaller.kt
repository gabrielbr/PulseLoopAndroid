package com.pulseloop.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a downloaded APK to the system package installer.
 *
 * Uses an ACTION_VIEW install intent with a FileProvider content URI, which reliably shows
 * the standard "install update?" confirmation. Requires REQUEST_INSTALL_PACKAGES plus the
 * user having granted "install unknown apps" for PulseLoop — gate the call on [canInstall]
 * and send the user to settings via [installPermissionIntent] when needed.
 *
 * The APK must be signed with the same key as the installed app, or the system rejects it
 * with INSTALL_FAILED_UPDATE_INCOMPATIBLE (so this only works for the signed release build).
 */
object ApkInstaller {
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Intent to the per-app "install unknown apps" screen. */
    fun installPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * True when [apk] is this app's package and is signed with the installed app's key. Android's
     * installer refuses a mismatch too, but only after its own confirmation dialog; checking
     * first lets the update sheet say why instead (design system docs/self-update.md, check 4).
     */
    fun matchesInstalledApp(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        val archive = archiveInfo(pm, apk) ?: return false
        if (archive.packageName != context.packageName) return false
        val installed = try {
            installedInfo(pm, context.packageName)
        } catch (_: Exception) {
            return false
        }
        val a = signatures(archive)
        val b = signatures(installed)
        // The new APK may carry a rotated key whose history includes ours, so: ours ⊆ theirs.
        return b.isNotEmpty() && a.containsAll(b)
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)
        }

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager, pkg: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
        }

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): List<String> {
        val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { si ->
                if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            }
        } else {
            info.signatures
        }
        return sigs?.map { it.toCharsString() } ?: emptyList()
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
