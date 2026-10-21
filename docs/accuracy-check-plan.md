# Accuracy check — compare the ring against another device via Health Connect

Status: **plan, nothing implemented.** Date: 2026-10-21.

## The idea

People online often say cheap rings aren't reliable. PulseLoop can answer that for the user's
own ring rather than in general. The user wears the ring **and** a device they trust (Fitbit,
Pixel Watch, Galaxy Watch, Garmin, anything that writes to Health Connect) for a few days.
PulseLoop reads that device's records back out of Health Connect, lines them up against its own,
and reports where the ring agrees, where it falls short, and by how much. Where the gap is a
**consistent bias** rather than noise, the user can turn on a correction.

While the check runs, PulseLoop **stops writing to Health Connect**, so the two devices don't
double-count the same steps, sleep and heart rate in every other app reading Health Connect.

### Naming: not "Diagnose"

Settings already has a *Diagnostics* area: the debug console, `DiagnosticsExporter`, raw packet
capture. A second "Diagnose" entry next to it would be mistaken for it, and bug reports would
mix the two up. Working name: **Settings → Accuracy check** (subtitle "Compare your ring with
another device"). The code name in this doc is `AccuracyCheck`.

## What already exists and gets reused

| Need | Existing piece | Note |
| --- | --- | --- |
| Health Connect client, availability | `HealthConnectSdk`, `HealthConnectAvailability` | as-is |
| A *read* path with its own opt-in and permissions | `HealthConnectBodyImport` | the model for this feature: separate permission set, separate prefs, never mixed with the export's `HealthConnectPermissions.all` |
| Pausing the export | `HealthConnectExportWorker.doWork` already returns early on `!prefs.enabled` / `NOT_ASKED` | add one more gate |
| Skip a window on resume | watermarks + `effectiveWatermark(stored, newOnlyConsentAt)` | the "export new only" mechanism already does exactly this |
| Ring data | `measurements`, `activity_daily`, `activity_buckets`, `sleep_sessions` + `sleep_stage_blocks` | read through the `*Real` DAO queries, never demo rows (`DemoDataPolicy`) |
| Sleep definitions | `asleepMinutes(blocks)`, `TimeUtil.wakingDayLocal` | compare on the same definition the app displays |
| Process-wide services | `AppServices` | the session controller lives here, not in a composable (issue #113) |

## User flow

1. **Settings → Accuracy check → Start.** Explainer: wear both devices for N days, ring on a
   finger, the other device on the wrist as usual. Health Connect export pauses while it runs.
2. **Read permission sheet** for the comparison types only (steps, heart rate, sleep, resting
   HR, SpO₂, HRV, distance, calories). Partial grants are fine: a type the user denies is left
   out of the report.
3. **Pick the reference source.** PulseLoop lists the apps that wrote to Health Connect over the
   last 7 days (`DataOrigin` package names, mapped to app labels) and which types each one wrote,
   e.g. *Fitbit: steps, heart rate, sleep. Samsung Health: steps.* PulseLoop's own package is
   never offered. The user picks one source. Types that source never writes are shown greyed
   out with "Fitbit doesn't share this with Health Connect", so nobody waits a week for a
   comparison that can't happen.
4. **Duration**: 3, 5, 7 (default) or 14 days. At least 3 nights, because one night of sleep
   data says almost nothing.
5. **While running**: a small banner in Settings and Today, "Accuracy check · day 3 of 7 ·
   Health Connect export paused". There's a live "so far" view, labelled preliminary until
   enough data has been paired.
6. **End**, automatically after N days or early by hand → the **report** → the
   **resume-export choice** (below) → optional **corrections**.

## Export pause and resume

- **Only the types the reference writes are paused** (decided). The pause set is a list of export
  rows (`HealthConnectPermissions.DataTypeRow`), not one switch. With a Health Sync/Huawei
  reference that writes steps, sleep and HR, PulseLoop keeps exporting SpO₂, HRV, temperature
  and the rest, because those would duplicate nothing. The gate lives in
  `HealthConnectExporter.run`, next to the existing per-kind permission check: a row in the pause
  set is skipped **and its watermark isn't advanced**, so the resume choice below still applies to
  it. `HealthConnectExportWorker` itself keeps running.
- **The pause set comes from the reference device, never from the ring.** It's what the
  reference was seen writing in Health Connect, re-read once a day during the check. It does
  **not** come from the current ring's capability list. That way a ring change can't create a
  duplicate: if the user switches to a ring that measures SpO₂ and the reference writes SpO₂ too,
  SpO₂ was paused already. A type the reference *starts* writing mid-check (the user switches
  on a new sync category in Health Sync) joins the pause set from that day on, and its watermark
  is held from that day.
- The export settings screen marks each paused row "Paused by Accuracy check (ends Oct 28)", and
  the user can't switch those rows back on while the check runs. They end the check instead, so there is one
  switch and not two that disagree.
- **On resume, the default is to skip the check window.** If the watermarks were simply left
  alone, the first pass after the check would export the whole window, and the duplicates the
  pause existed to prevent would land in Health Connect anyway, just a week late. The resume
  dialog offers:
  - **Skip these days (recommended)**: move the watermark of every *paused* group to the check's
    end, the same way `EXPORT_NEW_ONLY` already works. Groups that weren't paused have exported
    all along and are left alone. Watermarks are per group (`VITALS` covers HR, SpO₂, HRV…), so
    the skip has to be per kind within `VITALS`, not per watermark key, or skipping HR would also
    skip the SpO₂ that never paused. That makes it a small exporter change, not just a prefs
    write, and it needs its own test.
  - **Export them too**: leave the watermarks alone, for a user who will take the reference
    device off afterwards and wants a complete ring history in Health Connect.
- Records PulseLoop exported *before* the check stay in Health Connect. The comparison filters
  them out by `DataOrigin` (below), so they don't get in the way.
- Process death, reboot or an app update mid-check: everything is persisted, and the check
  picks up where it left off. A WorkManager job ends the check at `activeSince + N days` even if
  the app is never opened. It posts a "Your accuracy report is ready" notification and leaves
  export **paused until the user answers the resume dialog**. Resuming silently would make the
  choice for them.

## Reference via Health Sync (the Huawei case)

The first real user's reference is a Huawei device whose data reaches Health Connect through
**Health Sync** (a third-party bridge app), not from Huawei directly. What that changes:

- **The `DataOrigin` is Health Sync's package, not Huawei's.** Discovery lists "Health Sync", and
  filtering on it is correct. Health Connect has no record of the original source, so the screen
  should show the label as "Health Sync" and offer to name it ("Health Sync (Huawei Watch GT 4)").
  If `Metadata.device` (manufacturer/model) is filled in on Health Sync's records, show that
  instead. *Unverified whether Health Sync fills it in; check in Phase 0.*
- **A bridge can relay more than one source.** Health Sync may be copying from Huawei Health
  *and* from somewhere else (Google Fit, Samsung Health). The origin filter can't separate those.
  If `Metadata.device` is present and varies, offer a device filter on top of the origin filter.
  If not, the explainer says to set Health Sync to sync from the watch only during the check.
- **Echo risk: the ring's own data coming back as the "reference".** Health Sync can also *read*
  Health Connect and write elsewhere. If it is set up two-way (Health Connect → Huawei Health →
  Health Connect), PulseLoop's earlier exports could come back under Health Sync's origin, and the
  report would say the ring agrees perfectly with itself. The origin filter doesn't catch this.
  Guard: an **echo check** in the engine. If a large share of reference samples match ring
  samples exactly (same value, timestamp within a second), stop and show "The reference data
  looks like PulseLoop's own — check Health Sync's sync direction". The per-type pause also
  helps from the start of the check on, but it does nothing about history that echoed earlier.
- **Lag.** Health Sync syncs on its own schedule (periodically, not live), and Huawei Health syncs
  from the watch on its own schedule before that. So the most recent day is usually incomplete.
  The engine only scores a day once reference data covers it to the end, or 24 h have passed.
  When the check ends, the report waits for the last day ("Waiting for Health Sync to deliver
  Oct 28 — open Health Sync to sync now") before scoring it. Without this, the last day would
  always read as "ring overcounts".
- **Granularity.** Whatever Huawei exports is what we get. HR may arrive as sparser samples than
  the ring's, and sleep stages may be mapped by Health Sync to Health Connect's stage set. The
  pairing windows above (±60 s HR, ±5 min SpO₂) may need widening per source. Phase 0 measures
  the actual cadence on this setup before the engine fixes those numbers.

## Changing rings

A user can swap rings, mid-check or between checks, and the new ring may measure things the old
one didn't. The rules:

- **A check belongs to one ring** (`deviceKey`). If a different ring connects mid-check, the check
  for the old ring **ends early**: its days so far are kept and reported as a partial check for
  that ring. The user is offered a new check for the new ring. Data from two rings is never
  scored as one ring's. The export pause stays in place across the switch (it follows the
  reference, see above) until the user answers the resume dialog or starts the new check.
- **What gets compared = what both devices produce.** This is decided at scoring time, not when
  the check starts. A new ring with HRV, SpO₂ or temperature gets those metrics in its report
  as long as the reference writes them. A metric the new ring has and the reference doesn't
  shows "Your reference device doesn't share this", which is a different case from "not enough
  data".
- **Corrections are per ring.** A new ring starts with none, and the old ring's are kept, so
  they come back if that ring is paired again. The new ring's Settings entry says "No accuracy
  check for this ring yet".

## Reading the reference data

- Every read is filtered with `dataOriginFilter = setOf(DataOrigin(referencePackage))`. This is
  the most important correctness rule in the feature. Without it, PulseLoop's own records from
  before the check, or a third app re-syncing the same data, end up compared against themselves
  and the report reads "perfect agreement".
- Prefer the `aggregate` / `aggregateGroupByDuration` APIs for steps, distance and calories.
  They return the reference's per-hour totals without paging through raw records, and they
  respect the origin filter.
- Reading history: Health Connect only lets an app read 30 days before its first grant unless
  it holds `READ_HEALTH_DATA_HISTORY`. The check window lies after the grant, so this doesn't
  apply. Don't ask for that permission.
- Background reads have tighter rate limits than foreground ones. Do the heavy read when the
  report is opened (foreground), plus at most one small incremental read per day for the live
  view.
- **No raw reference samples are stored.** For each day and metric, the engine reduces the
  reference data to the figures the report needs (totals, paired-error statistics). Only those
  are persisted, in `accuracy_check_days`. Another device's raw health data has no business in
  PulseLoop's archive, and it would be the first foreign health data the app keeps.
  Trade-off: if the reference app later revises a day, the stored result doesn't follow. That's
  acceptable, and "Recompute" on the report re-reads while the data is still in Health Connect.

## Comparison engine

A pure Kotlin module (`AccuracyComparison`) with no Health Connect or Room imports. It takes two
plain time series and returns statistics, so it can be unit-tested the way
`HealthConnectTypeMappings` is.

### Two ideas behind every metric

1. **Agreement band ("buffer")**: how close is close enough. Each metric has a default, and the
   user can change it. A day or a pair inside the band counts as agreeing.
2. **Bias vs noise.** For every metric, report the **mean difference (bias)** and the **spread
   of the differences (limits of agreement, ±1.96 SD, Bland-Altman)** separately. They lead to
   different advice:
   - large bias, small spread → the ring is consistently off, and a correction *will* help;
   - small bias, large spread → the ring is erratic, and no correction can fix that. Say so
     plainly instead of offering a slider that only moves the average.

### Per metric

| Metric | Pairing | Headline figures | Default band |
| --- | --- | --- | --- |
| **Steps** | hourly buckets (`activity_buckets` vs reference `StepsRecord` aggregated per hour) + daily totals | daily % difference, bias, hours where the ring missed activity entirely | ±10 % per day |
| **Distance** | daily totals | % difference | ±15 % |
| **Sleep: duration** | per waking day (`wakingDayLocal`) | asleep minutes (ring's `asleepMinutes` vs reference stages except AWAKE / `SLEEPING`), bias | ±30 min |
| **Sleep: timing** | per night | onset and final-wake difference | ±20 min |
| **Sleep: stages** | minute by minute over the overlap, only if the reference reports stages | % of minutes on the same stage, a deep/light/REM/awake confusion table | report only |
| **Heart rate** | each ring sample against the reference's nearest sample within ±60 s | mean absolute error, bias, limits of agreement, split **at rest** (asleep, or <50 steps in that hour) vs **active** | ±5 bpm |
| **Resting HR** | daily `RestingHeartRateRecord` vs the app's own resting HR | daily difference | ±3 bpm |
| **SpO₂** | nearest sample within ±5 min, mostly overnight | bias, % of pairs in band | ±2 points |
| **HRV (RMSSD)** | nightly value only | bias, and whether the two move up and down together (rank correlation) | report only |
| **Calories** | daily | % difference | report only |

Notes on the choices above:

- **HR is split by context** because the cheap-ring complaint is mostly about exercise: optical
  sensors on a finger lose the signal when the hand moves. One blended figure would hide a ring
  that is excellent at rest and poor during a run, which is the most useful thing the report
  can tell the user.
- **HRV and calories are report-only.** Vendors compute HRV in different ways (different
  windows, different times of night). Calories are an estimate on both sides, and the ring's
  figure may be the app's own `DailyCalorieEstimator` estimate. So a difference here doesn't
  show which device is wrong, and a correction would only paper over it.
- **Temperature is left out.** Fitbit and most watches publish skin-temperature *variation*,
  not an absolute value, so there's nothing to pair against.
- **A minimum sample size per metric** (e.g. 3 nights, 500 HR pairs, 3 full days of steps).
  Below it the metric shows "Not enough data yet" instead of a number. Same rule as
  `DerivedStress` returning null below twelve readings: a number the data doesn't support,
  shown on a health screen, gets read as a measurement.
- **Days where either device was clearly not worn don't count.** For example, a reference day
  with < 500 steps or under 12 hours of HR coverage, or a ring day with a long data gap. A day
  the watch spent charging is not a day the ring overcounted.

### The reference is not ground truth

Fitbit and the other watches are consumer devices too. The report should say "differs from
Fitbit by" and not "is wrong by", and the explainer should say once, briefly, that this is
agreement with the device the user chose, not a lab measurement. The corrections below assume
the reference is the better device because that is what the user told us. The app shouldn't
claim it for them.

## The report

- One card per metric. Each card has a verdict chip (**Agrees** / **Consistently high/low** /
  **Inconsistent** / **Not enough data**), the headline number, and a one-line plain-language
  reading. Example: *"Your ring counts about 18 % fewer steps than Fitbit, consistently, every
  day. A correction would help."*
- Tapping a card opens detail: a day-by-day bar chart of ring vs reference, a Bland-Altman
  scatter for HR and SpO₂, and the stage confusion table for sleep.
- The summary at the top lists the metrics outside their band, worst first. This is the "point
  where the ring is falling short" part of the request.
- Bands are editable from the report, and the verdicts recompute at once. This needs no new
  read, since the stored per-day statistics are enough.

## Corrections ("make the ring more accurate")

This part needs the most care, because several hard-won rules in `AGENTS.md` bear on it:
*"disagreeing with the ring is not a better number"* (issue #59/#60), *"a derived metric must
say it is derived"* (#67), and the ring re-supplying its own history on every sync.

**Rules:**

1. **Stored data is never rewritten.** Rows in `measurements`, `activity_*` and `sleep_*` stay
   exactly as the ring sent them. The ring re-sends history on every sync and `upsert` would
   overwrite a corrected row anyway. A correction is a **read-time transform** applied where
   values are shown, in one place (`AccuracyCorrections.apply(kind, value, context)`), so it can
   be switched off and everything goes back to raw.
2. **Only offered where the report found a consistent bias.** The "Inconsistent" verdict gets no
   correction button, just the explanation. This stops the feature from implying it can repair
   a ring that is simply noisy.
3. **Simple, explainable forms only:**
   - steps and distance: a **scale factor** (e.g. ×1.18), fitted as total reference ÷ total ring
     over the valid days;
   - heart rate: a **bpm offset**, kept separately for *rest* and *active*, since the two biases
     often differ;
   - sleep: an **onset/wake time offset** and a **duration offset**. Sleep *stages* are never
     corrected; the report only describes the confusion.
   - SpO₂ and resting HR: an offset.
   The fitted value is a suggestion. The user can accept it, adjust it with a slider, or decline.
4. **Labelled wherever it's shown.** A corrected figure carries a small "adjusted" marker, the
   same way `VitalsState.stressIsDerived` drives the derived-stress caption. Detail screens say
   "Adjusted from ring reading 7,412 (×1.18, from your Oct 21–28 accuracy check)".
5. **Corrections never reach Health Connect** (decided). The export always sends raw ring values.
   A corrected value in Health Connect would look like a measurement to every app reading it.
6. **Corrections do feed the app's own derived figures** (decided): resting-HR baseline, HR zones,
   calorie estimates. These two decisions meet at two values that are *both* derived *and*
   exported, so each of those needs a raw copy and an adjusted copy:
   - **Resting-HR baseline.** `RestingHRBaselineService` keeps computing `hrRestingBaseline` from
     raw HR; that's the value `RestingHeartRateExporter` sends. The adjusted baseline isn't
     stored. The baseline is a percentile (p10) and the HR correction at rest is an offset, and
     an offset shifts a percentile by exactly itself, so adjusted = raw + rest offset, computed
     at read time. `HeartRateZones` / `VitalsZoneModel` read the adjusted value.
   - **Estimated active calories.** `DailyCalorieEstimator.estimateNetActive` isn't linear in its
     inputs (HR thresholds, step buckets), so there's no shortcut. Add a second column,
     `activity_daily.estimatedActiveCaloriesAdjusted`. The estimator writes both from the same
     buckets (raw inputs and corrected inputs); the activity exporter keeps reading the raw
     column and the UI reads the adjusted one.
   - **Undo must be exact.** Switching a correction off, changing its value, or a ring change that
     turns corrections off: each one re-runs the estimator over the days the correction covered
     and recomputes the zones. The figures go back to exactly what they were. Since only the
     adjusted copies are ever touched, the raw ones are always there to recompute from.
   - The device-reported calories path (`deviceReportedCalories`) is left alone. A correction
     derived from steps doesn't apply to a figure the ring computed with its own formula.
7. **Corrections belong to one ring.** Key them by device identity (address + model). Pairing a
   different ring turns them off (see "Changing rings") and prompts "Run an accuracy check for this ring?". A
   correction fitted for an R11 applied to an R10 would be invented data.
8. **Corrections go stale.** Show "fitted 3 months ago, re-check?" after ~90 days or after a
   firmware change (the firmware revision is already decoded on several families).

## Data model (Room, v36)

- `accuracy_checks`: id, deviceKey, referencePackage, referenceLabel, startedAt, plannedEndAt,
  endedAt, state (RUNNING / AWAITING_RESUME_CHOICE / DONE / CANCELLED), bandsJson.
- `accuracy_check_days`: checkId, metric, dayEpoch, ringValue, refValue, pairCount, meanDiff,
  sdDiff, mae, validDay flag. These are aggregates only; no raw samples (see above).
- `accuracy_check_paused_rows`: checkId, row, pausedFrom (the day the reference was first seen
  writing it, which is what the skip-on-resume moves the watermark past).
- `activity_daily.estimatedActiveCaloriesAdjusted: Double?` (null = no correction applied).
- `accuracy_corrections`: deviceKey, metric, context (rest/active/—), kind (SCALE/OFFSET), value,
  fittedValue, fromCheckId, enabled, createdAt.
- Archive: all three go in `PulseArchive` (they are the user's own findings). Diagnostics export:
  **none of them**, per the "export carries no database rows" rule. If a shareable summary is
  ever wanted (below), it is a separate, explicit "Share report" action.

## Phases

0. **Foundation**: a read-permission set (`AccuracyCheckPermissions`, separate from export and
   body import), manifest entries, the reference-source discovery screen (with per-type sample
   cadence and `Metadata.device`, if any, so the Health Sync questions get answered here). Value on its own: the
   user can see what each app actually shares with Health Connect. *Verify on real devices
   which types Fitbit and Samsung Health actually write; this decides what the report can
   cover.*
1. **Session lifecycle + per-type export pause**: table, the exporter's per-row pause gate that
   holds watermarks, daily refresh of the pause set, export-screen state, end job, notification,
   resume dialog with the per-kind skip, early end on a ring change. Unit-test the skip and that
   an unpaused kind inside `VITALS` keeps exporting.
2. **Engine + report for steps, sleep, HR**: the pure comparison module with tests built from
   synthetic series (constant bias, pure noise, missing hours, a non-wear day, a nap, a night
   that crosses 20:00/midnight, an echoed series, a last day the reference hasn't delivered
   yet), and the report UI.
3. **Remaining metrics**: resting HR, SpO₂, distance, HRV, calories.
4. **Corrections**: read-time transform, labelling everywhere a corrected value is shown,
   keyed to the ring, stale prompt; the adjusted baseline, zones and
   `estimatedActiveCaloriesAdjusted` column (v37 or folded into v36), with recompute on every
   correction change. Test that switching a correction off restores every derived figure exactly
   and that the export payload is the same with corrections on and off.
5. *(Optional)* **Shareable summary**: a text/image of the verdicts and headline numbers, with
   model and firmware, no raw values. Useful in GitHub issues ("my SR08 undercounts steps by
   20 %") and, over time, as crowd-sourced accuracy notes per model.

## Decisions (2026-10-21)

1. **Corrected values never go to Health Connect.** Export is raw, always (Corrections rule 5).
2. **Corrections feed resting-HR baseline, HR zones and calorie estimates**, with raw and
   adjusted copies kept apart where a figure is also exported (rule 6).
3. **The pause covers only the types the reference device writes**, decided from the reference
   and not the ring, so a ring change can't open a duplication gap (Export pause; Changing
   rings).

## Open questions

1. **iOS**: the same feature maps onto HealthKit (`HKSource` plays the part of `DataOrigin`).
   Worth tracking as an iOS-parity item once the Android version has proved itself.
2. **Health Sync specifics** to confirm on the real Huawei setup in Phase 0: whether
   `Metadata.device` is filled in, HR sample cadence, how sleep stages are mapped, and typical
   sync lag.
