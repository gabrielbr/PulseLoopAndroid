package com.pulseloop.ui.i18n

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.util.Locale

/** How the app talks: plainly, or like F*cking Health (sarcastic, grumbling, censored swearing). */
enum class Voice { PLAIN, FUNNY }

/**
 * The languages PulseLoop speaks, each a locale, a stack of catalogs and a [Voice]. A lookup takes
 * the first catalog that has the English key, then falls back to the English itself, so a voice
 * layers on top of its language: F*cking Health in Portuguese would be
 * `listOf("i18n/pt-BR-FH.json", "i18n/pt-BR.json")`, and any string it doesn't rewrite reads as
 * plain Portuguese. [SYSTEM] follows the phone.
 */
enum class AppLanguage(
    val key: String,
    val label: String,
    val localeTag: String?,
    val catalogs: List<String>,
    val voice: Voice = Voice.PLAIN,
    /** Shown under the option in the picker. */
    val caption: String? = null,
) {
    SYSTEM("system", "System default", null, emptyList()),
    ENGLISH("en", "English", "en", emptyList()),
    PORTUGUESE("pt-BR", "Português (Brasil)", "pt-BR", listOf("i18n/pt-BR.json")),
    FUCKING_HEALTH_EN(
        "fh-en", "F*cking Health", "en", listOf("i18n/en-FH.json"), Voice.FUNNY,
        caption = "English, with attitude. Strong language.",
    ),
}

/**
 * The app's language (Settings → Language). The UI is written in English, and every displayed
 * literal goes through [tr], which looks the English text up in the language's catalogs
 * (`assets/i18n/*.json`) and falls back to the English when a string has no entry yet. The
 * English text *is* the key, so a reworded string simply shows in English until it is translated
 * again.
 *
 * Snapshot state: [tr] reads [language], so a composition re-reads every string when it changes.
 * The default [Locale] follows the language too, so dates and numbers format to match.
 */
object L10n {

    var language by mutableStateOf(AppLanguage.SYSTEM)
        private set

    @Volatile private var catalogs: List<Map<String, String>> = emptyList()
    private var appContext: Context? = null
    private var systemLocale: Locale = Locale.getDefault()

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        systemLocale = Resources.getSystem().configuration.locales.get(0) ?: Locale.getDefault()
        val saved = prefs(context).getString("language", AppLanguage.SYSTEM.key)
        language = AppLanguage.entries.firstOrNull { it.key == saved } ?: AppLanguage.SYSTEM
        apply()
    }

    fun set(context: Context, value: AppLanguage) {
        prefs(context).edit().putString("language", value.key).apply()
        language = value
        apply()
    }

    /** Whether the UI is currently in Portuguese (chosen, or the phone's language under [AppLanguage.SYSTEM]). */
    val isPortuguese: Boolean get() = locale().language == "pt"

    val voice: Voice get() = language.voice

    fun locale(): Locale = language.localeTag?.let { Locale.forLanguageTag(it) } ?: systemLocale

    /** The catalogs in force: the language's own, or under [AppLanguage.SYSTEM] the phone's language's. */
    private fun catalogPaths(): List<String> = when {
        language != AppLanguage.SYSTEM -> language.catalogs
        systemLocale.language == "pt" -> AppLanguage.PORTUGUESE.catalogs
        else -> emptyList()
    }

    private fun apply() {
        Locale.setDefault(locale())
        catalogs = catalogPaths().map(::load)
    }

    private fun load(asset: String): Map<String, String> = runCatching {
        val json = appContext!!.assets.open(asset).bufferedReader().use { it.readText() }
        val obj = JSONObject(json)
        buildMap { obj.keys().forEach { k -> put(k, obj.getString(k)) } }
    }.getOrDefault(emptyMap())

    fun translate(en: String): String {
        @Suppress("UNUSED_VARIABLE") val l = language // read, so composition follows a change
        for (c in catalogs) c[en]?.let { return it }
        return en
    }

    /** Appended to the coach's prompts: the coach answers in the app's language. */
    fun coachInstruction(): String = buildString {
        if (isPortuguese) append(
            "\n\nThe user's app is in Brazilian Portuguese. Always reply in Brazilian Portuguese (pt-BR), " +
                "naturally and warmly, keeping metric names the app shows (HRV, SpO2) as they are.",
        )
        if (voice == Voice.FUNNY) append(FUNNY_VOICE)
    }

    /**
     * The F*cking Health voice for the coach and its notifications. The joke rides on top of the
     * data; it never replaces it, and it stops at anything that could hurt someone.
     */
    private const val FUNNY_VOICE =
        "\n\nVOICE: The user picked \"F*cking Health\", the app's sarcastic, foul-mouthed persona. Talk like a " +
            "grumpy best friend who happens to know physiology: blunt, funny, a bit theatrical, complaining about " +
            "the numbers even when they're good. Swear, but censor it with asterisks the way the app does " +
            "(f*cking, sh*t, WTF, damn). Short punchy sentences, rhetorical questions, the odd exclamation. " +
            "The facts, numbers and advice must stay exactly as accurate and useful as in plain mode; the " +
            "attitude is the wrapping, not the content. Roast the habits, never the person: no jokes about " +
            "weight, body shape, age, gender, illness or appearance. Drop the act completely and be calm, kind " +
            "and clear for anything serious: chest pain, fainting, shortness of breath, very high or low " +
            "readings, pregnancy or postpartum health, medications, mental health or self-harm, eating " +
            "disorders, alcohol dependence. If the user seems upset, stressed or asks you to stop, go plain."

    /** The Activity's context in the chosen language, so Android resources and system UI follow it. */
    fun wrap(base: Context): Context {
        init(base)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale())
        return base.createConfigurationContext(config)
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("language", Context.MODE_PRIVATE)
}

/** The current language's text for an English UI string. */
fun tr(en: String): String = L10n.translate(en)

/**
 * A string with values in it: the key holds `{0}`, `{1}`… where the values go, so the translation
 * can move them (`"{0} steps today"` → `"{0} passos hoje"`). Each value is written as a template
 * would write it (`toString()`), so the English result is exactly what the template produced.
 */
fun trf(en: String, vararg args: Any?): String {
    var s = L10n.translate(en)
    args.forEachIndexed { i, a -> s = s.replace("{$i}", a.toString()) }
    return s
}
