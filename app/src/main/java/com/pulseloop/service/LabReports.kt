package com.pulseloop.service

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.pulseloop.coach.attachments.CoachImagePayload
import com.pulseloop.coach.config.CoachClientResolver
import com.pulseloop.coach.config.CoachProviderSettingsStore
import com.pulseloop.coach.openai.OpenAIRequestBuilder
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.LabReportEntity
import com.pulseloop.data.entity.LabResultEntity
import com.pulseloop.settings.ApiKeyStore
import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Lab reports (AGENTS.md, "Lab reports"): a PDF of any laboratory report is rendered page by page
 * on the phone, read by the user's own coach provider into structured markers, checked by the
 * user, and stored. Charts follow each marker from report to report by [LabResultEntity.markerKey].
 *
 * Pages go to the model as images, not as a PDF, so every provider the coach supports reads them,
 * a self-hosted one included. The PDF is sent once, when the user adds it; the coach reads the
 * stored values from then on.
 */
object LabReports {

    /** Pages read per report. Longer reports are rare, and every page is billed as an image. */
    const val MAX_PAGES = 10

    /** Longest page edge sent to the model, in pixels: sharp enough for a dense results table. */
    private const val PAGE_EDGE = 1600

    // ── Storage ──────────────────────────────────────────────────────────────

    fun directory(context: Context): File =
        File(context.applicationContext.filesDir, "lab_reports").apply { mkdirs() }

    fun pdfFile(context: Context, name: String): File = File(directory(context), name)

    /** Copies the picked PDF into the app's own storage, named after the new report's id. */
    suspend fun copyIn(context: Context, uri: Uri, reportId: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            val out = pdfFile(context, "$reportId.pdf")
            context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return@runCatching null
            out
        }.getOrNull()
    }

    suspend fun delete(context: Context, db: PulseLoopDatabase, report: LabReportEntity) {
        db.labDao().deleteReport(report.id)
        report.pdfFile?.let { runCatching { pdfFile(context, it).delete() } }
    }

    // ── Reading a report ─────────────────────────────────────────────────────

    /** Renders up to [MAX_PAGES] pages as JPEG payloads; also returns the page count. */
    private fun renderPages(file: File): Pair<List<CoachImagePayload>, Int> {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try { PdfRenderer(fd) } catch (e: Exception) { fd.close(); throw e }
        try {
            val count = renderer.pageCount
            val pages = (0 until minOf(count, MAX_PAGES)).map { i ->
                val page = renderer.openPage(i)
                try {
                    val scale = PAGE_EDGE.toFloat() / maxOf(page.width, page.height)
                    val w = (page.width * scale).toInt().coerceAtLeast(1)
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    // A PDF page is transparent where nothing is printed; JPEG would turn that black.
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    bmp.recycle()
                    val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    CoachImagePayload("data:image/jpeg;base64,$b64", b64, "image/jpeg")
                } finally {
                    page.close()
                }
            }
            return pages to count
        } finally {
            renderer.close()
            fd.close()
        }
    }

    sealed class ReadResult {
        data class Success(val draft: Draft) : ReadResult()
        data class Failure(val message: String) : ReadResult()
    }

    /** A report as read, before the user saves it. Every field is editable on the review screen. */
    data class Draft(
        val reportId: String,
        val pdfFile: String,
        val fileName: String?,
        val pageCount: Int,
        val collectedOn: LocalDate?,
        val lab: String,
        val kinds: List<String>,
        val results: List<DraftResult>,
        /** Pages beyond [MAX_PAGES] that weren't read. */
        val pagesSkipped: Int = 0,
    )

    data class DraftResult(
        val panel: String,
        val name: String,
        val key: String,
        val valueNum: Double?,
        val valueText: String,
        val unit: String,
        val refLow: Double?,
        val refHigh: Double?,
        val refText: String,
        val modelFlag: String,
    ) {
        val flag: String get() = flagFor(valueNum, refLow, refHigh, modelFlag)
    }

    suspend fun read(context: Context, file: File, reportId: String, fileName: String?): ReadResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val keyStore = ApiKeyStore(appContext)
        val providerStore = CoachProviderSettingsStore(appContext)
        val resolution = CoachClientResolver.resolve(providerStore, keyStore)
        if (!keyStore.coachEnabled || resolution.key == null) {
            return@withContext ReadResult.Failure(
                tr("Reading a report needs the coach enabled with a configured provider (Settings → AI Coach)."),
            )
        }
        val (pages, pageCount) = try {
            renderPages(file)
        } catch (e: Exception) {
            return@withContext ReadResult.Failure(tr("Couldn't open that PDF. It may be protected by a password or damaged."))
        }
        if (pages.isEmpty()) return@withContext ReadResult.Failure(tr("That PDF has no pages to read."))
        val model = CoachClientResolver.activeModel(providerStore.snapshot(), keyStore.model)
        val input = JsonArray(listOf(
            OpenAIRequestBuilder.message("system", SYSTEM_PROMPT),
            OpenAIRequestBuilder.message("user", "Read every result in this laboratory report.", pages),
        ))
        val body = JsonObject(mapOf(
            "model" to JsonPrimitive(model),
            "input" to input,
            "text" to JsonObject(mapOf("format" to textFormat())),
        ) + OpenAIRequestBuilder.reasoningParams(CoachClientResolver.resolvedReasoningEffort(providerStore.snapshot(), model), model))
        try {
            val response = resolution.client.send(body.toString().toByteArray())
            val extracted = decode(response.outputText)
                ?: return@withContext ReadResult.Failure(tr("The coach couldn't read results from that report. Try again, or try a clearer PDF."))
            if (extracted.results.isEmpty()) {
                return@withContext ReadResult.Failure(tr("No results were found in that report."))
            }
            ReadResult.Success(Draft(
                reportId = reportId,
                pdfFile = file.name,
                fileName = fileName,
                pageCount = pageCount,
                collectedOn = runCatching { LocalDate.parse(extracted.collectedOn.trim()) }.getOrNull(),
                lab = extracted.laboratory.trim(),
                kinds = extracted.kinds.map { it.lowercase() }.filter { it in KINDS }.distinct(),
                results = extracted.results.filter { it.name.isNotBlank() }.map { r ->
                    DraftResult(
                        panel = r.panel.trim(), name = r.name.trim(),
                        key = normalizeKey(r.key.ifBlank { r.name }),
                        valueNum = r.valueNumber, valueText = r.valueText.trim(), unit = r.unit.trim(),
                        refLow = r.refLow, refHigh = r.refHigh, refText = r.refText.trim(), modelFlag = r.flag,
                    )
                },
                pagesSkipped = (pageCount - MAX_PAGES).coerceAtLeast(0),
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ReadResult.Failure(trf("Reading failed: {0}", e.message ?: e.javaClass.simpleName))
        }
    }

    suspend fun save(db: PulseLoopDatabase, draft: Draft, zone: ZoneId = ZoneId.systemDefault()) {
        val day = (draft.collectedOn ?: LocalDate.now(zone)).atStartOfDay(zone).toInstant().toEpochMilli()
        db.labDao().upsertReport(LabReportEntity(
            id = draft.reportId, collectedDay = day, lab = draft.lab.ifBlank { null },
            kindsCsv = draft.kinds.joinToString(","), fileName = draft.fileName,
            pdfFile = draft.pdfFile, pageCount = draft.pageCount,
        ))
        db.labDao().upsertResults(draft.results.mapIndexed { i, r ->
            LabResultEntity(
                id = UUID.randomUUID().toString(), reportId = draft.reportId, panel = r.panel.ifBlank { "Other" },
                name = r.name, markerKey = normalizeKey(r.key.ifBlank { r.name }),
                valueNum = r.valueNum, valueText = r.valueText.ifBlank { null }, unit = r.unit.ifBlank { null },
                refLow = r.refLow, refHigh = r.refHigh, refText = r.refText.ifBlank { null },
                flag = r.flag, position = i,
            )
        })
    }

    // ── Rules ────────────────────────────────────────────────────────────────

    val KINDS = listOf("blood", "urine", "hormones", "stool", "other")

    /** One key per analyte, whatever the language or the lab: lowercase ASCII words joined by "_". */
    fun normalizeKey(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "marker" }

    /**
     * Where a result sits against the lab's own printed range. A numeric value with a range is
     * judged here, not taken from the model; without one the report's own marking (as read) stands.
     */
    fun flagFor(value: Double?, low: Double?, high: Double?, modelFlag: String): String = when {
        value != null && high != null && value > high -> "HIGH"
        value != null && low != null && value < low -> "LOW"
        value != null && (low != null || high != null) -> "NORMAL"
        else -> when (modelFlag.lowercase()) {
            "high" -> "HIGH"
            "low" -> "LOW"
            "abnormal" -> "ABNORMAL"
            "normal" -> "NORMAL"
            else -> "UNKNOWN"
        }
    }

    fun isOutside(flag: String) = flag == "HIGH" || flag == "LOW" || flag == "ABNORMAL"

    /** "148", "14.6", "0.85": no trailing zeros, at most two decimals. */
    fun formatValue(v: Double): String =
        if (v == Math.floor(v) && kotlin.math.abs(v) < 1e9) v.toLong().toString()
        else java.math.BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    fun valueLabel(num: Double?, text: String?): String =
        num?.let(::formatValue) ?: text?.takeIf { it.isNotBlank() } ?: "—"

    fun referenceLabel(low: Double?, high: Double?, text: String?, unit: String?): String? {
        val u = unit?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
        return when {
            low != null && high != null -> "${formatValue(low)}–${formatValue(high)}$u"
            high != null -> trf("below {0}", formatValue(high) + u)
            low != null -> trf("above {0}", formatValue(low) + u)
            else -> text?.takeIf { it.isNotBlank() }
        }
    }

    // ── Series, for the screens and the coach ───────────────────────────────

    data class Point(
        val day: Long, val reportId: String, val valueNum: Double?, val valueText: String?, val unit: String?,
        val refLow: Double?, val refHigh: Double?, val refText: String?, val flag: String,
    )

    data class Marker(val key: String, val name: String, val panel: String, val points: List<Point>) {
        val latest: Point get() = points.last()
        val numeric: Boolean get() = points.count { it.valueNum != null } >= 1
    }

    /** One series per marker, oldest point first, named and grouped as in its latest report. */
    fun markers(reports: List<LabReportEntity>, results: List<LabResultEntity>): List<Marker> {
        val day = reports.associate { it.id to it.collectedDay }
        return results.filter { it.reportId in day }.groupBy { it.markerKey }.map { (key, rows) ->
            val sorted = rows.sortedWith(compareBy({ day.getValue(it.reportId) }, { it.position }))
            val last = sorted.last()
            Marker(key, last.name, last.panel, sorted.map {
                Point(day.getValue(it.reportId), it.reportId, it.valueNum, it.valueText, it.unit, it.refLow, it.refHigh, it.refText, it.flag)
            })
        }.sortedWith(compareBy({ it.panel.lowercase() }, { it.name.lowercase() }))
    }

    // ── Prompt and schema ────────────────────────────────────────────────────

    private const val SYSTEM_PROMPT =
        "You read laboratory reports (blood, urine, hormones, stool or any other lab test) from page images. " +
            "Extract every result exactly as printed; never add, guess or interpret one. For each result: " +
            "panel = the section heading it is printed under; name = the analyte as printed; key = a stable " +
            "snake_case English identifier for the analyte, the same for the same analyte whatever the language " +
            "or lab (e.g. ldl_cholesterol, hdl_cholesterol, triglycerides, hemoglobin, ferritin, tsh, " +
            "vitamin_d_25oh, glucose_fasting, hba1c, creatinine, urine_protein); value_number = the numeric value " +
            "with a decimal point, or null when the result isn't a plain number (then put it in value_text, e.g. " +
            "\"Negative\", \"<5\"); value_text = the printed result; unit = as printed; ref_low/ref_high = the " +
            "printed reference limits as numbers, null when absent; ref_text = the printed reference when it isn't " +
            "a plain range; flag = high, low, abnormal or normal from the report's own marking or the printed " +
            "reference, unknown when there is neither. collected_on = the sample collection date as YYYY-MM-DD, " +
            "or empty. laboratory = the lab's name, or empty. Return only JSON matching the schema."

    private fun textFormat(): JsonObject {
        fun str() = JsonObject(mapOf("type" to JsonPrimitive("string")))
        fun numOrNull() = JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("null")))))
        fun enumOf(vararg v: String) = JsonObject(mapOf("type" to JsonPrimitive("string"), "enum" to JsonArray(v.map { JsonPrimitive(it) })))
        fun obj(props: Map<String, JsonObject>) = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(props),
            "required" to JsonArray(props.keys.map { JsonPrimitive(it) }),
            "additionalProperties" to JsonPrimitive(false),
        ))
        val result = obj(linkedMapOf(
            "panel" to str(), "name" to str(), "key" to str(),
            "value_number" to numOrNull(), "value_text" to str(), "unit" to str(),
            "ref_low" to numOrNull(), "ref_high" to numOrNull(), "ref_text" to str(),
            "flag" to enumOf("high", "low", "abnormal", "normal", "unknown"),
        ))
        val schema = obj(linkedMapOf(
            "collected_on" to str(),
            "laboratory" to str(),
            "kinds" to JsonObject(mapOf("type" to JsonPrimitive("array"), "items" to enumOf(*KINDS.toTypedArray()))),
            "results" to JsonObject(mapOf("type" to JsonPrimitive("array"), "items" to result)),
        ))
        return JsonObject(mapOf(
            "type" to JsonPrimitive("json_schema"),
            "name" to JsonPrimitive("lab_report"),
            "strict" to JsonPrimitive(true),
            "schema" to schema,
        ))
    }

    @Serializable
    data class Extracted(
        @SerialName("collected_on") val collectedOn: String = "",
        val laboratory: String = "",
        val kinds: List<String> = emptyList(),
        val results: List<ExtractedResult> = emptyList(),
    )

    @Serializable
    data class ExtractedResult(
        val panel: String = "",
        val name: String = "",
        val key: String = "",
        @SerialName("value_number") val valueNumber: Double? = null,
        @SerialName("value_text") val valueText: String = "",
        val unit: String = "",
        @SerialName("ref_low") val refLow: Double? = null,
        @SerialName("ref_high") val refHigh: Double? = null,
        @SerialName("ref_text") val refText: String = "",
        val flag: String = "unknown",
    )

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** Fence- and prose-tolerant, like the meal estimate's decode. */
    fun decode(text: String): Extracted? {
        val t = text.trim()
        if (t.startsWith("{")) runCatching { return json.decodeFromString(Extracted.serializer(), t) }
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.decodeFromString(Extracted.serializer(), t.substring(start, end + 1)) }.getOrNull()
    }
}

/** Whether the coach may use the lab results. On by default; the switch is on the Lab reports screen. */
object LabPrefs {
    @Volatile private var prefs: SharedPreferences? = null

    /** From the Application, so the coach's context builder (no Context) can read the switch. */
    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("lab_reports", Context.MODE_PRIVATE)
    }

    var coachUses: Boolean
        get() = prefs?.getBoolean("coachUses", true) ?: true
        set(v) { prefs?.edit()?.putBoolean("coachUses", v)?.apply() }
}

/**
 * The lab results as the coach sees them: each marker's latest value with its range and the two
 * before it, so it can speak to a trend. Null when there are none or the user switched it off.
 */
@Serializable
data class LabContext(
    val reports: Int,
    val latestReport: String,
    val markers: List<LabMarkerContext>,
    val note: String = "Lab results the user added from their own reports and checked themselves. You are not a " +
        "doctor: never diagnose, never name a condition the results 'mean', never say a value is fine or dangerous " +
        "beyond whether it sits inside the lab's printed range. For anything outside the range or trending badly, " +
        "say it is worth discussing with their doctor. Never suggest starting, stopping or dosing a medication or " +
        "supplement. You may suggest everyday habits (activity, sleep, food) linked to a marker in general terms, " +
        "and propose daily goals; change a goal only with set_goal and only after the user says yes to the exact numbers.",
) {
    companion object {
        suspend fun build(db: PulseLoopDatabase): LabContext? {
            if (!LabPrefs.coachUses) return null
            val reports = db.labDao().reports()
            if (reports.isEmpty()) return null
            val markers = LabReports.markers(reports, db.labDao().results())
            val zone = ZoneId.systemDefault()
            fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()
            return LabContext(
                reports = reports.size,
                latestReport = date(reports.maxOf { it.collectedDay }),
                markers = markers.take(80).map { m ->
                    val l = m.latest
                    LabMarkerContext(
                        name = m.name, panel = m.panel,
                        latest = LabReports.valueLabel(l.valueNum, l.valueText) + (l.unit?.let { " $it" } ?: ""),
                        date = date(l.day),
                        reference = LabReports.referenceLabel(l.refLow, l.refHigh, l.refText, l.unit),
                        flag = l.flag.lowercase(),
                        earlier = m.points.dropLast(1).takeLast(2).map { "${date(it.day)}: ${LabReports.valueLabel(it.valueNum, it.valueText)}" },
                    )
                },
            )
        }
    }
}

@Serializable
data class LabMarkerContext(
    val name: String,
    val panel: String,
    val latest: String,
    val date: String,
    val reference: String? = null,
    val flag: String,
    val earlier: List<String> = emptyList(),
)
