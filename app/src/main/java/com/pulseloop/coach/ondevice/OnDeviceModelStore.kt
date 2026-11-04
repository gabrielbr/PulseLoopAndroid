package com.pulseloop.coach.ondevice

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.pulseloop.BuildConfig
import com.pulseloop.coach.openai.ResponsesError
import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where the on-device coach's model lives and how it gets there (docs/on-device-model-and-mcp-plan.md,
 * Part 1), adapted from Cochilo's github-flavour `ModelSources`.
 *
 * - **The file lives in `noBackupFilesDir`**, so neither Android's backup nor PulseLoop's own
 *   archive (which reads Room and an allowlist of preferences) ever carries 600 MB of
 *   re-downloadable weights. It is never in git or the APK either.
 * - **One tap downloads it** through Android's download manager, so it keeps going with the app
 *   closed, shows its progress in the system notification, resumes after a dropped connection and
 *   waits for Wi-Fi unless the user picks mobile data. The download manager can only write to
 *   the app's external files folder, so the file lands there and moves into place once its
 *   SHA-256 matches the published `.sha256`.
 * - **A file the user already has** can be chosen instead ([import]); it is checked for size and
 *   that it isn't text, and the runtime refuses anything else it can't load.
 * - **One engine is kept loaded** ([use]) and closed when the provider changes or the model is
 *   removed ([closeEngine], [remove]). Calls are serialized: one MediaPipe engine, one answer at a time.
 *
 * The requests carry nothing about the user: a GET of a public file.
 */
class OnDeviceModelStore private constructor(private val context: Context) : OnDeviceEngine {

    /** The model, once it is on the phone and checked. */
    val modelFile: File = File(context.noBackupFilesDir, "models/${ModelDownload.FILE}")

    private val downloadDir: File? get() = context.getExternalFilesDir(null)?.let { File(it, "models") }
    private val partial: File? get() = downloadDir?.let { File(it, PARTIAL_NAME) }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dm: DownloadManager? = context.getSystemService(DownloadManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watching: Job? = null

    private val _state = MutableStateFlow<ModelState>(ModelState.Absent)
    val state: StateFlow<ModelState> = _state

    /** The phone's total memory, for the memory floor. 0 when Android won't say. */
    val totalMemoryBytes: Long = runCatching {
        ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem
    }.getOrDefault(0L)

    val meetsMemoryFloor: Boolean get() = ModelDownload.meetsMemoryFloor(totalMemoryBytes)

    /** Serializes the engine: one load, one answer, one close at a time. */
    private val lock = Mutex()
    private var loaded: LocalModel? = null
    private var lastFailure = ModelState.Reason.DOWNLOAD

    init { look() }

    /** Whether the model is on the phone: a cheap file check, safe on any thread. */
    fun isReady(): Boolean = modelFile.exists() && modelFile.length() >= ModelFile.MIN_BYTES

    /** Looks again at what's on the phone (the Settings sheet calls it when it opens). */
    fun refresh() = look()

    private fun look() {
        if (_state.value is ModelState.Getting) return
        when {
            isReady() -> _state.value = ModelState.Ready(modelFile.length())
            prefs.getLong(KEY_ID, -1L) >= 0 -> watch()
            _state.value !is ModelState.Failed -> _state.value = ModelState.Absent
        }
    }

    /** Starts the download: Wi-Fi only unless [wifiOnly] is false. */
    fun download(wifiOnly: Boolean = true) {
        if (_state.value is ModelState.Getting) return
        start(wifiOnly)
    }

    /** A download waiting for Wi-Fi goes now, on any network. The download manager can't change a
     *  request's networks, so it starts again. */
    fun useMobileData() {
        cancelDownload()
        _state.value = ModelState.Absent
        start(wifiOnly = false)
    }

    /** Stops a download in progress. */
    fun cancel() {
        cancelDownload()
        _state.value = if (isReady()) ModelState.Ready(modelFile.length()) else ModelState.Absent
    }

    private fun start(wifiOnly: Boolean) {
        _state.value = ModelState.Getting(null, waitingForWifi = false)
        scope.launch {
            val dir = downloadDir
            val manager = dm
            if (dir == null || manager == null) { _state.value = ModelState.Failed(ModelState.Reason.OTHER); return@launch }
            val sha = fetchSum() ?: run { _state.value = ModelState.Failed(lastFailure); return@launch }
            dir.mkdirs()
            modelFile.parentFile?.mkdirs()
            // The download and the moved copy are on disk together for a moment.
            val usable = minOf(dir.usableSpace, context.noBackupFilesDir.usableSpace)
            if (!ModelFile.hasSpaceFor(ModelDownload.APPROX_BYTES * 2, usable)) {
                _state.value = ModelState.Failed(ModelState.Reason.NO_SPACE); return@launch
            }
            partial?.delete()
            val request = DownloadManager.Request(Uri.parse(ModelDownload.modelUrl()))
                .setTitle(tr("PulseLoop coach model"))
                .setDescription(trf("{0}, about {1} MB", ModelDownload.DISPLAY_NAME, ModelDownload.APPROX_MB))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, null, "models/$PARTIAL_NAME")
                .setAllowedOverMetered(!wifiOnly)
                .setAllowedOverRoaming(false)
                .addRequestHeader("User-Agent", "PulseLoop/${BuildConfig.VERSION_NAME}")
            val id = runCatching { manager.enqueue(request) }.getOrNull()
            if (id == null) { _state.value = ModelState.Failed(ModelState.Reason.DOWNLOAD); return@launch }
            prefs.edit().putLong(KEY_ID, id).putString(KEY_SHA, sha).apply()
            watch()
        }
    }

    /** The published checksum, or null (and why, in [lastFailure]) when it can't be read. */
    private fun fetchSum(): String? = runCatching {
        val conn = (URL(ModelDownload.sumUrl()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 15_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PulseLoop/${BuildConfig.VERSION_NAME}")
        }
        try {
            when (conn.responseCode) {
                200 -> ModelDownload.parseSum(conn.inputStream.bufferedReader().use { it.readText().take(512) })
                    .also { if (it == null) lastFailure = ModelState.Reason.NOT_PUBLISHED }
                404 -> { lastFailure = ModelState.Reason.NOT_PUBLISHED; null }
                else -> { lastFailure = ModelState.Reason.DOWNLOAD; null }
            }
        } finally { conn.disconnect() }
    }.getOrElse { lastFailure = ModelState.Reason.DOWNLOAD; null }

    /** Follows the download manager's progress until the download ends. */
    private fun watch() {
        if (watching?.isActive == true) return
        val manager = dm ?: return
        watching = scope.launch {
            while (isActive) {
                val id = prefs.getLong(KEY_ID, -1L)
                if (id < 0) return@launch
                val row = runCatching {
                    manager.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                        if (!c.moveToFirst()) null else Triple(
                            c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                            c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                            c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)) to
                                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                        )
                    }
                }.getOrNull()
                // Gone: cancelled from the notification.
                if (row == null) { forget(); _state.value = ModelState.Absent; return@launch }
                val (status, reason, sizes) = row
                val progress = sizes.second.takeIf { it > 0 }?.let { (sizes.first.toFloat() / it).coerceIn(0f, 1f) }
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> { finish(); return@launch }
                    DownloadManager.STATUS_FAILED -> {
                        forget(); partial?.delete()
                        _state.value = ModelState.Failed(
                            if (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) ModelState.Reason.NO_SPACE
                            else ModelState.Reason.DOWNLOAD,
                        )
                        return@launch
                    }
                    DownloadManager.STATUS_PAUSED -> _state.value = ModelState.Getting(
                        progress, waitingForWifi = reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI,
                    )
                    else -> _state.value = ModelState.Getting(progress)
                }
                delay(1_000)
            }
        }
    }

    /** Checks the finished download against the published SHA-256, then moves it into place. */
    private suspend fun finish() {
        val sha = prefs.getString(KEY_SHA, null)
        forget()
        val file = partial
        _state.value = ModelState.Getting(1f)
        val problem = if (sha == null || file == null || !file.exists()) ModelState.Reason.DOWNLOAD
            else runCatching { ModelDownload.verify(file, sha) }.getOrElse { ModelState.Reason.OTHER }
        if (problem != null) {
            file?.delete()
            _state.value = ModelState.Failed(problem)
            return
        }
        val placed = lock.withLock {
            closeLoaded()
            runCatching {
                if (modelFile.exists()) modelFile.delete()
                modelFile.parentFile?.mkdirs()
                // Different volumes: a rename usually fails, so copy and delete.
                if (!file!!.renameTo(modelFile)) {
                    file.inputStream().use { ModelFile.copy(it, modelFile) { } }
                    file.delete()
                }
            }.isSuccess
        }
        _state.value = if (placed && isReady()) ModelState.Ready(modelFile.length())
            else { file?.delete(); ModelState.Failed(ModelState.Reason.NO_SPACE) }
    }

    private fun forget() = prefs.edit().remove(KEY_ID).apply()

    private fun cancelDownload() {
        val id = prefs.getLong(KEY_ID, -1L)
        if (id >= 0) runCatching { dm?.remove(id) }
        forget()
        watching?.cancel()
        partial?.delete()
    }

    /** Copies the model from [uri], a file the user chose (Storage Access Framework). */
    suspend fun import(uri: Uri) = withContext(Dispatchers.IO) {
        cancelDownload()
        val resolver = context.contentResolver
        val size = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()?.takeIf { it > 0 }
        val head = runCatching { resolver.openInputStream(uri)?.use { s -> ByteArray(16).also { s.read(it) } } }.getOrNull() ?: ByteArray(0)
        ModelFile.check(head, size ?: Long.MAX_VALUE)?.let { _state.value = ModelState.Failed(it); return@withContext }
        if (size != null && !ModelFile.hasSpaceFor(size, context.noBackupFilesDir.usableSpace)) {
            _state.value = ModelState.Failed(ModelState.Reason.NO_SPACE); return@withContext
        }
        _state.value = ModelState.Getting(if (size != null) 0f else null, copying = true)
        val copied = lock.withLock {
            closeLoaded()
            runCatching {
                resolver.openInputStream(uri)!!.use { input ->
                    ModelFile.copy(input, modelFile) { done ->
                        if (size != null) _state.value = ModelState.Getting((done.toFloat() / size).coerceIn(0f, 1f), copying = true)
                    }
                }
            }
        }
        _state.value = copied.fold(
            onSuccess = { bytes ->
                if (bytes < ModelFile.MIN_BYTES) { modelFile.delete(); ModelState.Failed(ModelState.Reason.TOO_SMALL) }
                else ModelState.Ready(bytes)
            },
            onFailure = { ModelState.Failed(ModelState.Reason.OTHER) },
        )
    }

    /** Deletes the model from the phone, closing the engine first. */
    fun remove() {
        cancelDownload()
        _state.value = ModelState.Absent
        scope.launch {
            lock.withLock {
                closeLoaded()
                modelFile.delete()
                File(modelFile.parentFile, modelFile.name + ".part").delete()
            }
            _state.value = ModelState.Absent
        }
    }

    // ── The engine ──────────────────────────────────────────────────────

    override suspend fun <T> use(block: (LocalModel) -> T): T = lock.withLock {
        val model = loaded ?: run {
            if (!isReady()) throw ResponsesError.Decoding(NOT_READY)
            MediaPipeModel(context, modelFile).also { loaded = it }
        }
        block(model)
    }

    /** Frees the loaded engine (about a gigabyte of memory) once any answer in progress ends. */
    fun closeEngine() {
        scope.launch { lock.withLock { closeLoaded() } }
    }

    /** Only with [lock] held. */
    private fun closeLoaded() {
        loaded?.let { runCatching { it.close() } }
        loaded = null
    }

    /** The coach client that answers with this phone's model. */
    fun client(): OnDeviceResponsesClient = OnDeviceResponsesClient(engine = this)

    companion object {
        private const val PREFS = "on_device_model"
        private const val KEY_ID = "download_id"
        private const val KEY_SHA = "sha256"
        private const val PARTIAL_NAME = "${ModelDownload.FILE}.download"

        const val NOT_READY = "The model on this phone isn't downloaded yet. Get it in Settings → AI Coach → On this phone."

        @Volatile private var instance: OnDeviceModelStore? = null

        fun get(context: Context): OnDeviceModelStore =
            instance ?: synchronized(this) {
                instance ?: OnDeviceModelStore(context.applicationContext).also { instance = it }
            }
    }
}
