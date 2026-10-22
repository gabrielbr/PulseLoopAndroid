package com.pulseloop.ui.i18n

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.util.Locale

/** The languages PulseLoop speaks. [SYSTEM] follows the phone. */
enum class AppLanguage(val key: String, val label: String) {
    SYSTEM("system", "System default"),
    ENGLISH("en", "English"),
    PORTUGUESE("pt-BR", "Português (Brasil)"),
}

/**
 * The app's language (Settings → Language). The UI is written in English, and every displayed
 * literal goes through [tr], which looks the English text up in the language's catalog
 * (`assets/i18n/pt-BR.json`) and falls back to the English when a string has no entry yet. The
 * English text *is* the key, so a reworded string simply shows in English until it is translated
 * again.
 *
 * Snapshot state: [tr] reads [language], so a composition re-reads every string when it changes.
 * The default [Locale] follows the language too, so dates and numbers format to match.
 */
object L10n {

    var language by mutableStateOf(AppLanguage.SYSTEM)
        private set

    @Volatile private var catalog: Map<String, String> = emptyMap()
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

    fun locale(): Locale = when (language) {
        AppLanguage.ENGLISH -> Locale.ENGLISH
        AppLanguage.PORTUGUESE -> Locale("pt", "BR")
        AppLanguage.SYSTEM -> systemLocale
    }

    private fun apply() {
        val locale = locale()
        Locale.setDefault(locale)
        catalog = if (locale.language == "pt") load("i18n/pt-BR.json") else emptyMap()
    }

    private fun load(asset: String): Map<String, String> = runCatching {
        val json = appContext!!.assets.open(asset).bufferedReader().use { it.readText() }
        val obj = JSONObject(json)
        buildMap { obj.keys().forEach { k -> put(k, obj.getString(k)) } }
    }.getOrDefault(emptyMap())

    fun translate(en: String): String {
        @Suppress("UNUSED_VARIABLE") val l = language // read, so composition follows a change
        return catalog[en] ?: en
    }

    /** Appended to the coach's prompts: the coach answers in the app's language. */
    fun coachInstruction(): String =
        if (isPortuguese) "\n\nThe user's app is in Brazilian Portuguese. Always reply in Brazilian Portuguese (pt-BR), " +
            "naturally and warmly, keeping metric names the app shows (HRV, SpO2) as they are."
        else ""

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
