package com.pulseloop.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable

/** One SharedPreferences value, typed so a restore writes it back as what it was. */
@Serializable
data class PrefValue(
    val type: String,
    val value: String? = null,
    val set: List<String>? = null,
)

/**
 * The user's own data and choices that live in SharedPreferences rather than Room, carried in the
 * archive so a new phone gets them back.
 *
 * Drinks is the case that made this necessary: its entries were in the archive, but its on/off
 * switch was a preference that defaults to off, so after moving phones every drinks surface hid
 * itself and the restored history looked lost. The plan (weekly limit, dry days, standard-drink
 * size), its start date and the AUDIT-C result were lost outright.
 *
 * An allowlist, not a sweep of every file: device state (ring pairing, Health Connect grants and
 * watermarks, notification scheduling, algorithm and scoring versions) must stay with the phone it
 * describes, and some data stays on the phone on purpose.
 */
object ArchivedSettings {

    /** File name to the keys never carried. An empty set carries the whole file. */
    private val FILES: Map<String, Set<String>> = mapOf(
        // Drinks: on/off, tracked-since, the plan, the AUDIT-C result.
        "drinks" to emptySet(),
        // Life stage. The EPDS answers stay on the phone (AGENTS.md, postpartum mode).
        "postpartum" to setOf("epds"),
        // Breathing sessions the user did.
        "mindful_sessions" to emptySet(),
        // The phone-step calibration and its daily counts. The raw step counter is this phone's.
        "step_calibration" to setOf("lastCounter"),
        // Detected workouts and naps already answered, so they aren't asked again.
        "activity_detection" to emptySet(),
        // Rest Mode, while it lasts.
        "rest_mode" to emptySet(),
    )

    /**
     * The encrypted store (`pulseloop_secure`, shared by `ApiKeyStore` and
     * `CoachProviderSettingsStore`). It mixes the user's choices with this phone's own state, so
     * only these keys are carried, and a restore writes only these keys.
     *
     * The coach's setup is here at the owner's request, **its API keys included**: the provider,
     * every provider's key and model, and the custom (local / own server) API's address, key,
     * model and options. The archive is plain JSON, so those keys are readable by anyone holding
     * the backup file; the Backup screen says so.
     */
    private const val SECURE_FILE = "pulseloop_secure"
    private val SECURE_KEYS: Set<String> = setOf(
        // AI coach: switches, provider, keys, models, the custom API.
        "coach_enabled", "coach_model", "openai_api_key", "coach_provider_mode",
        "gemini_api_key", "gemini_model", "openrouter_api_key", "openrouter_model",
        "minimax_api_key", "minimax_model", "openrouter_privacy_routing", "openrouter_provider_sort",
        "coach_reasoning_effort", "coach_image_input_enabled", "web_search_enabled",
        "write_tools_enabled", "environment_context_enabled",
        "local_llm_base_url", "local_llm_api_key", "local_llm_model", "local_llm_tool_calling",
        "local_llm_structured_output", "local_llm_max_tokens", "local_llm_timeout_seconds",
        // Coach check-ins and their hours.
        "notifications_enabled", "morning_hour", "evening_hour",
        // Calibration and physiology change how stored readings are read.
        "bp_adjust_systolic", "bp_adjust_diastolic", "glucose_offset_mgdl", "glucose_ref_mgdl",
        "athlete_mode", "altitude_meters", "uses_beta_blockers", "has_known_lung_condition",
        // Units and the other switches the user set.
        "unit_system", "preferred_glucose_unit", "battery_alerts_enabled", "live_measurements_enabled",
    )

    fun export(context: Context): Map<String, Map<String, PrefValue>> =
        (FILES.mapValues { (file, skipped) ->
            prefs(context, file).all.filterKeys { it !in skipped }.mapNotNull { (k, v) -> encode(v)?.let { k to it } }.toMap()
        } + (SECURE_FILE to secureValues(context))).filterValues { it.isNotEmpty() }

    private fun secureValues(context: Context): Map<String, PrefValue> = runCatching {
        secure(context).all.filterKeys { it in SECURE_KEYS }.mapNotNull { (k, v) -> encode(v)?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())

    private fun secure(context: Context): SharedPreferences = androidx.security.crypto.EncryptedSharedPreferences.create(
        SECURE_FILE,
        androidx.security.crypto.MasterKeys.getOrCreate(androidx.security.crypto.MasterKeys.AES256_GCM_SPEC),
        context.applicationContext,
        androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /**
     * Writes the archive's values back. Only files on the allowlist are touched, and each is
     * replaced as a whole except the keys it never carries, so a restore can't mix two phones'
     * plans together.
     */
    fun restore(context: Context, settings: Map<String, Map<String, PrefValue>>) {
        FILES.forEach { (file, skipped) ->
            val values = settings[file] ?: return@forEach
            val p = prefs(context, file)
            val e = p.edit()
            p.all.keys.filter { it !in skipped }.forEach { e.remove(it) }
            values.filterKeys { it !in skipped }.forEach { (k, v) -> put(e, k, v) }
            e.commit()
        }
        // The secure file keeps this phone's own keys: only the carried ones are replaced.
        settings[SECURE_FILE]?.let { values ->
            runCatching {
                val p = secure(context)
                val e = p.edit()
                SECURE_KEYS.filter { it in p.all && it !in values }.forEach { e.remove(it) }
                values.filterKeys { it in SECURE_KEYS }.forEach { (k, v) -> put(e, k, v) }
                e.commit()
            }
        }
    }

    private fun prefs(context: Context, file: String): SharedPreferences =
        context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE)

    private fun encode(v: Any?): PrefValue? = when (v) {
        is Boolean -> PrefValue("boolean", v.toString())
        is Int -> PrefValue("int", v.toString())
        is Long -> PrefValue("long", v.toString())
        is Float -> PrefValue("float", v.toString())
        is String -> PrefValue("string", v)
        is Set<*> -> PrefValue("set", set = v.filterIsInstance<String>())
        else -> null
    }

    private fun put(e: SharedPreferences.Editor, k: String, v: PrefValue) {
        runCatching {
            when (v.type) {
                "boolean" -> e.putBoolean(k, v.value!!.toBoolean())
                "int" -> e.putInt(k, v.value!!.toInt())
                "long" -> e.putLong(k, v.value!!.toLong())
                "float" -> e.putFloat(k, v.value!!.toFloat())
                "string" -> e.putString(k, v.value)
                "set" -> e.putStringSet(k, v.set.orEmpty().toSet())
            }
        }
    }
}
