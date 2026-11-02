package com.pulseloop.update

import android.content.Context
import android.os.Build
import com.pulseloop.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0,
)

/** A newer release than what's installed, resolved to an installable APK asset. */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val changelog: String,
    val apkUrl: String,
    val apkSize: Long,
    val htmlUrl: String,
    val apkName: String = "",
    /** Where the release's SHA256SUMS.txt is, if it has one. */
    val sumsUrl: String? = null,
    /** The APK's SHA-256 from that file (lowercase hex). No sum, no update. */
    val sha256: String = "",
)

/** Outcome of [UpdateChecker.download]: the verified file, or why there is none (an English key for `tr`). */
sealed class DownloadResult {
    data class Ready(val file: File) : DownloadResult()
    data class Failed(val reason: String) : DownloadResult()
}

/**
 * Outcome of an update check. Failures are distinct from "no update" so the UI never
 * tells an offline or rate-limited user they're on the latest version.
 */
sealed class UpdateCheckResult {
    data class UpdateAvailable(val info: UpdateInfo) : UpdateCheckResult()
    data object UpToDate : UpdateCheckResult()
    /** Unsupported build (debug / non-release id), or inside the auto-check throttle window. */
    data object Skipped : UpdateCheckResult()
    /** [reason] is a fixed English phrase, translated where it's shown. */
    data class Failed(val reason: String) : UpdateCheckResult()
}

/**
 * Polls the GitHub "latest release" endpoint and compares it to the installed build.
 *
 * Versioning contract: releases are tagged `v{versionName}+{versionCode}` (e.g. `v1.0.0+5`).
 * The integer after `+` is the Android versionCode — the only value Android uses to decide
 * "is this newer" — so that's what we compare. versionName is display-only.
 *
 * Self-update only works for the signed release build under the real applicationId: a debug
 * build (different signing key / `.debug` id) can't install the release APK over itself.
 */
object UpdateChecker {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient()

    /**
     * Separate client for the APK body. The check client's 10 s OkHttp defaults are fine for a
     * small JSON reply but tight for a release asset that grew past 30 MB — a stall on mobile
     * data would otherwise abort the download mid-file.
     */
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val PREFS = "self_update"
    private const val KEY_ETAG = "etag"
    private const val KEY_CACHED_RELEASE = "cachedReleaseJson"
    private const val KEY_LAST_CHECK = "lastCheckAt"

    /**
     * Minimum gap between automatic checks. Auto-checks fire on every background→foreground
     * transition (plus cold start), so this is what actually bounds GitHub traffic: at most
     * 4 requests/hour, and repeat checks send If-None-Match, whose 304 replies don't count
     * against GitHub's rate limit at all. 15 min keeps a long-running app noticing a new
     * release quickly without the old force-close-and-relaunch (previously this was 24 h,
     * which made foreground checks pointless).
     */
    private const val AUTO_CHECK_INTERVAL_MS = 15 * 60_000L

    fun isSupported(): Boolean =
        !BuildConfig.DEBUG && BuildConfig.APPLICATION_ID == "com.pulseloop"

    fun versionCodeFromTag(tag: String): Int? = tag.substringAfter('+', "").toIntOrNull()
    fun versionNameFromTag(tag: String): String = tag.removePrefix("v").substringBefore('+')

    /**
     * Checks the GitHub latest release against the installed build. [force] bypasses the
     * auto-check throttle and the ETag cache (used by the Settings "Check for updates" button).
     *
     * A 304 Not Modified still re-evaluates the release cached alongside the ETag — the
     * throttle is the only thing that limits how often the result surfaces. Otherwise a
     * pending update the user dismissed once would never be offered again while that
     * release's ETag stays current. (Nag control for the frequent foreground checks lives
     * at the call site, which remembers the dismissed versionCode for the session.)
     */
    suspend fun check(context: Context, force: Boolean = false): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext UpdateCheckResult.Skipped
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force) {
            val last = prefs.getLong(KEY_LAST_CHECK, 0L)
            if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS) return@withContext UpdateCheckResult.Skipped
        }
        // Only send If-None-Match when a cached release body exists to evaluate on 304.
        val cached = prefs.getString(KEY_CACHED_RELEASE, null)
        val etag = if (!force && cached != null) prefs.getString(KEY_ETAG, null) else null

        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .apply { etag?.let { header("If-None-Match", it) } }
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (_: Exception) {
            return@withContext UpdateCheckResult.Failed(REASON_OFFLINE)
        }

        response.use { resp ->
            val edit = prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            val bodyText = when {
                resp.code == 304 -> cached          // Not Modified — re-evaluate the cached release
                resp.isSuccessful -> resp.body?.string()?.also {
                    edit.putString(KEY_ETAG, resp.header("ETag")).putString(KEY_CACHED_RELEASE, it)
                }
                else -> {
                    edit.apply()
                    return@withContext UpdateCheckResult.Failed(
                        if (resp.code == 403 || resp.code == 429) REASON_RATE_LIMITED else REASON_NO_ANSWER,
                    )
                }
            }
            edit.apply()
            if (bodyText == null) return@withContext UpdateCheckResult.Failed(REASON_BAD_REPLY)
            withChecksum(evaluate(bodyText))
        }
    }

    /**
     * Fills in the APK's SHA-256 from the release's SHA256SUMS.txt (Gabriel Design System,
     * docs/self-update.md: no published sum, no install). A release without the file, or without a
     * line for this APK, is not offered: the check fails with a reason a manual check shows.
     */
    private fun withChecksum(result: UpdateCheckResult): UpdateCheckResult {
        if (result !is UpdateCheckResult.UpdateAvailable) return result
        val info = result.info
        val sumsUrl = info.sumsUrl ?: return UpdateCheckResult.Failed(REASON_NO_CHECKSUM)
        val text = try {
            client.newCall(Request.Builder().url(sumsUrl).build()).execute().use { r ->
                if (!r.isSuccessful || !r.request.url.isHttps) null else r.body?.string()
            }
        } catch (_: Exception) {
            return UpdateCheckResult.Failed(REASON_OFFLINE)
        } ?: return UpdateCheckResult.Failed(REASON_NO_CHECKSUM)
        val sum = parseSums(text)[info.apkName] ?: return UpdateCheckResult.Failed(REASON_NO_CHECKSUM)
        return UpdateCheckResult.UpdateAvailable(info.copy(sha256 = sum))
    }

    /** Compare a releases-API response body against the installed build. */
    private fun evaluate(bodyText: String): UpdateCheckResult {
        val release = try {
            json.decodeFromString(GithubRelease.serializer(), bodyText)
        } catch (_: Exception) {
            return UpdateCheckResult.Failed(REASON_BAD_REPLY)
        }
        if (release.draft || release.prerelease) return UpdateCheckResult.UpToDate

        val remoteCode = versionCodeFromTag(release.tagName)
            ?: return UpdateCheckResult.UpToDate    // tag not in the v<name>+<code> scheme
        if (remoteCode <= BuildConfig.VERSION_CODE) return UpdateCheckResult.UpToDate

        val apk = chooseApk(release.assets, Build.SUPPORTED_ABIS.toList())
            ?: return UpdateCheckResult.UpToDate    // nothing this phone can install: not an error to show
        val sums = release.assets.firstOrNull { it.name == SUMS_FILE }

        return UpdateCheckResult.UpdateAvailable(UpdateInfo(
            versionName = versionNameFromTag(release.tagName),
            versionCode = remoteCode,
            changelog = release.body?.takeIf { it.isNotBlank() } ?: (release.name ?: "A new version is available."),
            apkUrl = apk.browserDownloadUrl,
            apkSize = apk.size,
            htmlUrl = release.htmlUrl ?: "",
            apkName = apk.name,
            sumsUrl = sums?.browserDownloadUrl?.takeIf { it.startsWith("https://") },
        ))
    }

    const val SUMS_FILE = "SHA256SUMS.txt"
    const val REASON_OFFLINE = "no connection"
    const val REASON_RATE_LIMITED = "GitHub is busy, try again in an hour"
    const val REASON_NO_ANSWER = "GitHub didn't answer"
    const val REASON_BAD_REPLY = "unexpected reply from GitHub"
    const val REASON_NO_CHECKSUM = "the release has no checksum"
    const val DOWNLOAD_FAILED = "Download failed. Try again."
    const val CHECKSUM_MISMATCH = "The download didn't match the release's checksum, so it wasn't installed."
    const val NOT_THIS_APP = "The download isn't signed like this app, so it wasn't installed."

    /** Universal APK first, then one for this phone's ABI, then any APK; HTTPS links only. */
    fun chooseApk(assets: List<GithubAsset>, abis: List<String>): GithubAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) && it.browserDownloadUrl.startsWith("https://") }
        return apks.firstOrNull { it.name.contains("universal", ignoreCase = true) }
            ?: apks.firstOrNull { a -> abis.any { a.name.contains(it, ignoreCase = true) } }
            ?: apks.firstOrNull()
    }

    /** `sha256sum` output ("<64 hex>  name" or "<64 hex> *name") → file name → lowercase hex. */
    fun parseSums(text: String): Map<String, String> = text.lineSequence().mapNotNull { line ->
        val m = SUM_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
        m.groupValues[2].trim() to m.groupValues[1].lowercase(Locale.ROOT)
    }.toMap()

    private val SUM_LINE = Regex("([0-9a-fA-F]{64})\\s+\\*?(.+)")

    /** "38 MB" for the Update button. */
    fun sizeLabel(bytes: Long): String =
        if (bytes >= 1_000_000_000L) String.format(Locale.ROOT, "%.1f GB", bytes / 1e9)
        else "${maxOf(1L, Math.round(bytes / 1e6))} MB"

    /**
     * Downloads the APK to cacheDir/updates, reporting progress in 0f..1f, and checks it before
     * anything is installed (Gabriel Design System, docs/self-update.md): HTTPS only, the whole file,
     * its SHA-256 against the release's SHA256SUMS.txt, and the same package and signing key as this
     * app. Cancelling the calling coroutine stops the download and deletes the part.
     *
     * The short-file check matters: a body that ends early without throwing (a dropped connection on
     * a chunked reply, a captive portal cutting in) leaves a truncated APK, and the system installer
     * reports that as "There was a problem parsing the package", which reads as a broken release.
     */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Float) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        if (info.sha256.isEmpty()) return@withContext DownloadResult.Failed(CHECKSUM_MISMATCH)
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "pulseloop-${info.versionCode}.apk")
        try {
            downloadClient.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful || !resp.request.url.isHttps) return@withContext DownloadResult.Failed(DOWNLOAD_FAILED)
                val body = resp.body ?: return@withContext DownloadResult.Failed(DOWNLOAD_FAILED)
                val stream = body.byteStream()
                val total = body.contentLength().takeIf { it > 0 } ?: info.apkSize

                dir.listFiles()?.forEach { it.delete() }   // drop any stale download

                val sha = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                out.outputStream().use { fos ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    while (stream.read(buf).also { read = it } != -1) {
                        coroutineContext.ensureActive()        // Cancel in the dialog
                        fos.write(buf, 0, read)
                        sha.update(buf, 0, read)
                        downloaded += read
                        if (total > 0) onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                    }
                    fos.flush()
                }

                if (total > 0 && downloaded != total) {
                    out.delete()
                    return@withContext DownloadResult.Failed(DOWNLOAD_FAILED)
                }
                if (hex(sha.digest()) != info.sha256) {
                    out.delete()
                    return@withContext DownloadResult.Failed(CHECKSUM_MISMATCH)
                }
                if (!ApkInstaller.sameAppAndKey(context, out)) {
                    out.delete()
                    return@withContext DownloadResult.Failed(NOT_THIS_APP)
                }
                DownloadResult.Ready(out)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            out.delete()
            throw cancelled
        } catch (_: Exception) {
            out.delete()
            DownloadResult.Failed(DOWNLOAD_FAILED)
        }
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
}
