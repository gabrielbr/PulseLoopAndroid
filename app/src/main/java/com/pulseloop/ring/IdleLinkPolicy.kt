package com.pulseloop.ring

/**
 * What to do when a Colmi ring drops the link after it has gone quiet.
 *
 * **The ring hangs up on purpose.** Colmi firmware ends a connection after roughly 20 s with no
 * command on its UART channel, and PulseLoop deliberately sends no keepalive to Colmi rings (the
 * official QRing app sends none either — see `RingBLEClient.startKeepalive`). A diagnostics export
 * from an R06 (`R06_1.00.06_240921`, Pixel 9a, Android 17) shows it on every connection: the sync
 * completes, and 18.7–20.3 s after the app's last UART write the link ends with GATT status 147
 * (`GATT_CONNECTION_TIMEOUT`). That is the ring idling, not a fault.
 *
 * Two things went wrong with treating it as a fault:
 * - It was surfaced as `"Ring disconnected (GATT 147)"` and a red "Disconnected" after every sync.
 * - The watchdog reconnected 3–8 s later. The ring had only just hung up, so the attempt failed
 *   with 147 again, and the retries spent the whole reconnect budget in about 2.5 minutes. Once
 *   spent, nothing reconnected until the app was reopened (3, 7 and 21 minutes offline in that
 *   one export) — and each successful reconnect paid for a full sync, every few minutes.
 *
 * So a quiet drop after a completed sync is shown as idle, and the watchdog leaves the ring alone
 * for [REST_MS] before trying again. Foreground, user and background-worker reconnects are not
 * held back by the rest; only the unattended watchdog is.
 */
object IdleLinkPolicy {

    /** How long the watchdog waits after an idle drop before reconnecting on its own. Shorter
     *  than the ~30-minute background sync, so a missed worker run still gets the ring back. */
    const val REST_MS = 15 * 60_000L

    /** After the reconnect budget runs out, how long before unattended retries may start again.
     *  Without this the budget stayed spent until the user next opened the app. */
    const val BACKOFF_COOLDOWN_MS = 15 * 60_000L

    /** `BluetoothGatt.GATT_CONNECTION_TIMEOUT` (API 35). Hard-coded so the check compiles and
     *  behaves the same on older SDKs, where the stack reports the same value. */
    const val STATUS_CONNECTION_TIMEOUT = 147

    /** HCI connection timeout (0x08), how older stacks report a supervision timeout. */
    const val STATUS_HCI_CONNECTION_TIMEOUT = 8

    /** HCI remote user terminated (0x13): the ring closed the link itself. */
    const val STATUS_PEER_TERMINATED = 19

    private val idleStatuses = setOf(
        STATUS_CONNECTION_TIMEOUT,
        STATUS_HCI_CONNECTION_TIMEOUT,
        STATUS_PEER_TERMINATED,
    )

    /**
     * Whether a disconnect is the ring idling out rather than a failure.
     *
     * Scoped to the Colmi family on purpose: it is the one whose idle hang-up is measured, and the
     * one that runs without a keepalive. Jring keeps its link alive with pings, so a drop there
     * means something. The sync must have finished on this link — a drop mid-sync is a real
     * failure and keeps its error — and the status must be one a quiet hang-up produces.
     */
    fun isIdleDrop(
        deviceType: RingDeviceType?,
        syncCompletedOnLink: Boolean,
        status: Int,
        /** The app hung up itself after the sync ([RingBLEClient.settleIdleLink]): an idle drop
         *  whatever status the stack reports for a locally initiated disconnect. */
        requestedByApp: Boolean = false,
    ): Boolean =
        deviceType == RingDeviceType.COLMI_R02 &&
            syncCompletedOnLink &&
            (status in idleStatuses || requestedByApp)

    /** After a Colmi sync, how long the link must be quiet before the app hangs up itself. */
    const val SETTLE_QUIET_MS = 4_000L

    /** Stop waiting for quiet after this long: the ring hangs up on its own at ~20 s anyway. */
    const val SETTLE_MAX_MS = 15_000L

    /** Whether the watchdog should still leave an idle ring alone at [now]. */
    fun isResting(restUntil: Long, now: Long): Boolean = now < restUntil

    /**
     * Whether a spent reconnect budget may be refilled at [now]. `exhaustedAt` is 0 while the
     * budget is not spent.
     */
    fun budgetCooledDown(exhaustedAt: Long, now: Long): Boolean =
        exhaustedAt > 0 && now - exhaustedAt >= BACKOFF_COOLDOWN_MS
}
