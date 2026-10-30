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

    fun export(context: Context): Map<String, Map<String, PrefValue>> =
        FILES.mapValues { (file, skipped) ->
            prefs(context, file).all.filterKeys { it !in skipped }.mapNotNull { (k, v) -> encode(v)?.let { k to it } }.toMap()
        }.filterValues { it.isNotEmpty() }

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
