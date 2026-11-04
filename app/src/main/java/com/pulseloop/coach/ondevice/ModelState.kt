package com.pulseloop.coach.ondevice

/**
 * Where the on-device coach's model is, as Settings and the coach see it
 * (docs/on-device-model-and-mcp-plan.md, Part 1). Adapted from Cochilo's `ModelState`. Pure, so
 * the download and file checks that produce it are unit-tested.
 */
sealed interface ModelState {
    /** Not on the phone yet. */
    data object Absent : ModelState

    /**
     * On its way: [progress] 0..1, or null when unknown. [waitingForWifi]: queued until Wi-Fi.
     * [copying]: a file the user chose is being copied in (not a download).
     */
    data class Getting(
        val progress: Float?,
        val waitingForWifi: Boolean = false,
        val copying: Boolean = false,
    ) : ModelState

    /** On the phone, checked, ready to load. */
    data class Ready(val bytes: Long) : ModelState

    /** The last try failed; [reason] is shown in plain words. */
    data class Failed(val reason: Reason) : ModelState

    /**
     * [NOT_PUBLISHED]: the model's checksum isn't on the release. [CHECK]: the file arrived, but
     * its SHA-256 didn't match the published one.
     */
    enum class Reason { NOT_A_MODEL, TOO_SMALL, NO_SPACE, DOWNLOAD, NOT_PUBLISHED, CHECK, OTHER }
}
