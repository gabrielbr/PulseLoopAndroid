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
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

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
    /** The APK asset's file name, as `SHA256SUMS.txt` lists it. */
    val apkName: String = "",
    /** The release's `SHA256SUMS.txt`, or null when the release published none (then no install). */
    val sumsUrl: String? = null,
)

/** How a download ended. Only [Ready] may be handed to the installer. */
sealed class DownloadResult {
    data class Ready(val file: File) : DownloadResult()
    /** Network error, HTTP error, or a short file. */
    data object Failed : DownloadResult()
    /** The bytes didn't match the release's `SHA256SUMS.txt`, or the release published none. */
    data object ChecksumMismatch : DownloadResult()
    /** Not this app, or not signed with the installed app's key. */
    data object WrongSignature : DownloadResult()
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
        // HTTPS only, redirected to HTTPS only (design system docs/self-update.md).
        .followSslRedirects(false)
        .build()

    /** The checksum file every release publishes (`release.yml`). */
    const val SUMS_ASSET = "SHA256SUMS.txt"

    /**
     * The hash `sha256sum` wrote for [fileName] in a `SHA256SUMS.txt` body, lower-case, or null.
     * Lines are `<64 hex><space><space or *><name>`; anything else is ignored.
     */
    fun sha256For(sums: String, fileName: String): String? = sums.lineSequence()
        .map { it.trim() }
        .mapNotNull { line ->
            val m = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$").find(line) ?: return@mapNotNull null
            m.groupValues[1].lowercase() to m.groupValues[2].trim()
        }
        .firstOrNull { it.second == fileName }
        ?.first

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

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
            return@withContext UpdateCheckResult.Failed("network error")
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
                    return@withContext UpdateCheckResult.Failed("GitHub returned ${resp.code}")
                }
            }
            edit.apply()
            if (bodyText == null) return@withContext UpdateCheckResult.Failed("empty response")
            evaluate(bodyText)
        }
    }

    /** Compare a releases-API response body against the installed build. */
    private fun evaluate(bodyText: String): UpdateCheckResult {
        val release = try {
            json.decodeFromString(GithubRelease.serializer(), bodyText)
        } catch (_: Exception) {
            return UpdateCheckResult.Failed("unexpected response from GitHub")
        }
        if (release.draft || release.prerelease) return UpdateCheckResult.UpToDate

        val remoteCode = versionCodeFromTag(release.tagName)
            ?: return UpdateCheckResult.UpToDate    // tag not in the v<name>+<code> scheme
        if (remoteCode <= BuildConfig.VERSION_CODE) return UpdateCheckResult.UpToDate

        val apks = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        val apk = apks.firstOrNull { it.name.contains("universal", ignoreCase = true) }
            ?: apks.firstOrNull { a -> Build.SUPPORTED_ABIS.any { a.name.contains(it, ignoreCase = true) } }
            ?: apks.firstOrNull()
            ?: return UpdateCheckResult.Failed("release has no APK asset")

        return UpdateCheckResult.UpdateAvailable(UpdateInfo(
            versionName = versionNameFromTag(release.tagName),
            versionCode = remoteCode,
            changelog = release.body?.takeIf { it.isNotBlank() } ?: (release.name ?: "A new version is available."),
            apkUrl = apk.browserDownloadUrl,
            apkSize = apk.size,
            htmlUrl = release.htmlUrl ?: "",
            apkName = apk.name,
            sumsUrl = release.assets.firstOrNull { it.name == SUMS_ASSET }?.browserDownloadUrl,
        ))
    }

    /**
     * Download the APK to cacheDir/updates, reporting progress in 0f..1f, and check it before
     * anything may install it: HTTPS only, the whole file, its SHA-256 against the release's
     * `SHA256SUMS.txt` (no published sum, no install), then same package and same signing key.
     * Cancelling the calling coroutine stops the download and deletes the part.
     *
     * The short-file check matters on its own: a body that ends early without throwing (a
     * dropped connection on a chunked reply, a captive portal cutting in) leaves a truncated
     * APK, and the system installer reports that as "There was a problem parsing the package",
     * which reads as a broken release rather than a failed download.
     */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Float) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val sumsUrl = info.sumsUrl
        if (sumsUrl == null || !sumsUrl.startsWith("https://") || !info.apkUrl.startsWith("https://")) {
            return@withContext DownloadResult.ChecksumMismatch
        }
        val expected = try {
            downloadClient.newCall(Request.Builder().url(sumsUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext DownloadResult.Failed
                resp.body?.string()?.let { sha256For(it, info.apkName) }
            }
        } catch (_: Exception) {
            return@withContext DownloadResult.Failed
        } ?: return@withContext DownloadResult.ChecksumMismatch

        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "pulseloop-${info.versionCode}.apk")
        try {
            downloadClient.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext DownloadResult.Failed
                val body = resp.body ?: return@withContext DownloadResult.Failed
                val stream = body.byteStream()
                val total = body.contentLength().takeIf { it > 0 } ?: info.apkSize

                dir.listFiles()?.forEach { it.delete() }   // drop any stale download

                var downloaded = 0L
                out.outputStream().use { fos ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    while (stream.read(buf).also { read = it } != -1) {
                        ensureActive()                     // Cancel stops here; the catch below deletes the part
                        fos.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                    }
                    fos.flush()
                }

                if (total > 0 && downloaded != total) {
                    out.delete()
                    return@withContext DownloadResult.Failed
                }
            }
            if (sha256Hex(out) != expected) {
                out.delete()
                return@withContext DownloadResult.ChecksumMismatch
            }
            if (!ApkInstaller.matchesInstalledApp(context, out)) {
                out.delete()
                return@withContext DownloadResult.WrongSignature
            }
            DownloadResult.Ready(out)
        } catch (e: kotlinx.coroutines.CancellationException) {
            out.delete()
            throw e
        } catch (_: Exception) {
            out.delete()
            DownloadResult.Failed
        }
    }
}
