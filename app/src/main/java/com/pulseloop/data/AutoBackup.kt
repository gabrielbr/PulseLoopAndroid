package com.pulseloop.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Scheduled local backups into a folder the user picked (the Storage Access Framework, so it
 * can be a folder synced by Drive, Nextcloud or a USB stick). Reuses the manual export's archive,
 * keeps the newest [keep] files and deletes older ones it wrote. If the folder grant was revoked
 * the backup fails and says so; it never quietly writes somewhere else (OpenStrap Edge's rule).
 */
object AutoBackup {
    private const val PREFS = "auto_backup"
    private const val WORK = "auto_backup"
    const val PREFIX = "pulseloop-backup-"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun folder(c: Context): Uri? = prefs(c).getString("folder", null)?.let(Uri::parse)
    fun intervalDays(c: Context) = prefs(c).getInt("intervalDays", 1)
    fun keep(c: Context) = prefs(c).getInt("keep", 5)
    fun lastAt(c: Context) = prefs(c).getLong("lastAt", 0L)
    fun lastError(c: Context): String? = prefs(c).getString("lastError", null)

    fun setEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("enabled", on).apply(); schedule(c) }
    fun setIntervalDays(c: Context, days: Int) = prefs(c).edit().putInt("intervalDays", days).apply()
    fun setKeep(c: Context, n: Int) = prefs(c).edit().putInt("keep", n.coerceIn(1, 30)).apply()

    /** Remember a folder from ACTION_OPEN_DOCUMENT_TREE, keeping read and write access across restarts. */
    fun setFolder(c: Context, tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { c.contentResolver.takePersistableUriPermission(tree, flags) }
        prefs(c).edit().putString("folder", tree.toString()).remove("lastError").apply()
    }

    /** The folder's name as the file picker shows it, or null. */
    fun folderName(c: Context): String? {
        val tree = folder(c) ?: return null
        return runCatching {
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            c.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
    }

    /** Whether a backup is due at [now]. */
    fun isDue(lastAt: Long, intervalDays: Int, now: Long): Boolean =
        lastAt <= 0L || now - lastAt >= intervalDays * 24L * 3_600_000L - 30 * 60_000L

    /** Of the backup files in a folder, the ones to delete so only the newest [keep] remain. */
    fun toPrune(names: List<String>, keep: Int): List<String> =
        names.filter { it.startsWith(PREFIX) && it.endsWith(".json") }.sortedDescending().drop(keep)

    /** Write one backup now. Returns null on success, or the reason it failed. */
    suspend fun runNow(c: Context): String? {
        val ctx = c.applicationContext
        val tree = folder(ctx) ?: return fail(ctx, "Choose a folder first")
        val granted = ctx.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
        if (!granted) return fail(ctx, "PulseLoop lost access to the backup folder. Choose it again.")
        return try {
            val resolver = ctx.contentResolver
            val parentId = DocumentsContract.getTreeDocumentId(tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
            val name = PREFIX + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date()) + ".json"
            val doc = DocumentsContract.createDocument(resolver, parent, "application/json", name)
                ?: return fail(ctx, "Couldn't create a file in the backup folder")
            val text = DataArchiveService.exportJson(PulseLoopDatabase.getInstance(ctx), ctx)
            resolver.openOutputStream(doc, "w")?.use { it.write(text.toByteArray()) }
                ?: return fail(ctx, "Couldn't write to the backup folder")
            prune(ctx, tree, parentId)
            prefs(ctx).edit().putLong("lastAt", System.currentTimeMillis()).remove("lastError").apply()
            null
        } catch (e: Exception) {
            fail(ctx, "Backup failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun prune(c: Context, tree: Uri, parentId: String) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val files = mutableMapOf<String, String>()
        c.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { cur -> while (cur.moveToNext()) files[cur.getString(1)] = cur.getString(0) }
        toPrune(files.keys.toList(), keep(c)).forEach { name ->
            runCatching { DocumentsContract.deleteDocument(c.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, files.getValue(name))) }
        }
    }

    private fun fail(c: Context, reason: String): String {
        prefs(c).edit().putString("lastError", reason).apply()
        return reason
    }

    /** A daily check; the worker itself decides whether a backup is due. */
    fun schedule(c: Context) {
        val wm = WorkManager.getInstance(c.applicationContext)
        if (!isEnabled(c)) { wm.cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<AutoBackupWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        if (!AutoBackup.isEnabled(c)) return Result.success()
        if (!AutoBackup.isDue(AutoBackup.lastAt(c), AutoBackup.intervalDays(c), System.currentTimeMillis())) return Result.success()
        AutoBackup.runNow(c)
        // A failure is recorded for the settings screen; retrying a revoked folder won't help.
        return Result.success()
    }
}
