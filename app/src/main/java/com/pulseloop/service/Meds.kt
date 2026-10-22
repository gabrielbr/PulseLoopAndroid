package com.pulseloop.service

import com.pulseloop.ui.i18n.tr

import com.pulseloop.data.entity.MedDoseEntity
import com.pulseloop.data.entity.MedEntity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Medications and supplements: a daily schedule and one answer per dose (OpenStrap Edge's model).
 * Only a past dose that was neither taken nor skipped counts as missed, so a skipped dose the
 * user decided on is never reported as forgotten. No interaction checking, on purpose: that
 * belongs with a pharmacist, not an app that can't see the whole picture.
 */
object Meds {
    enum class State(private val labelEn: String) { TAKEN("Taken"), SKIPPED("Skipped"), DUE("Due"), MISSED("Missed"), LATER("Later");
        val label: String get() = tr(labelEn)
    }

    data class Dose(val med: MedEntity, val day: LocalDate, val time: String, val state: State) {
        val id: String get() = MedDoseEntity.idFor(med.id, day.toEpochDay(), time)
    }

    /** A dose counts as missed this long after its time with no answer. */
    const val MISSED_AFTER_MIN = 120L

    fun times(m: MedEntity): List<String> =
        m.timesCsv.split(',').map { it.trim() }.filter { runCatching { LocalTime.parse(it) }.isSuccess }.distinct().sorted()

    fun dosesOn(day: LocalDate, meds: List<MedEntity>, answers: List<MedDoseEntity>, now: LocalDateTime): List<Dose> {
        val byId = answers.associateBy { it.id }
        return meds.filter { it.active }.flatMap { m ->
            times(m).map { t ->
                val at = day.atTime(LocalTime.parse(t))
                val answer = byId[MedDoseEntity.idFor(m.id, day.toEpochDay(), t)]?.status
                val state = when {
                    answer == "taken" -> State.TAKEN
                    answer == "skipped" -> State.SKIPPED
                    now.isBefore(at.minusMinutes(60)) -> State.LATER
                    now.isAfter(at.plusMinutes(MISSED_AFTER_MIN)) -> State.MISSED
                    else -> State.DUE
                }
                Dose(m, day, t, state)
            }
        }.sortedBy { it.time }
    }

    /** Share of scheduled doses taken over the [days] completed days before [today], or null with none scheduled. */
    fun adherence(m: MedEntity, answers: List<MedDoseEntity>, today: LocalDate, days: Int = 7): Int? {
        val since = maxOf(today.minusDays(days.toLong()).toEpochDay(), java.time.Instant.ofEpochMilli(m.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay())
        val scheduled = (since until today.toEpochDay()).sumOf { times(m).size.toLong() }
        if (scheduled <= 0) return null
        val taken = answers.count { it.medId == m.id && it.status == "taken" && it.day in since until today.toEpochDay() }
        return (taken * 100 / scheduled).toInt()
    }
}
