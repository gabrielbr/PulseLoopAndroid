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

- New pref `accuracyCheck.activeSince: Long?`. `HealthConnectExportWorker.doWork` returns
  `Result.success()` with a log line while it is set. The gate goes in the worker, not at the 8
  `enqueue` call sites, so no trigger can slip past it.
- The export settings screen shows "Paused by Accuracy check (ends Oct 28)", and the user can't
  turn export back on there while the check runs. They end the check instead, so there is one
  switch and not two that disagree.
- **On resume, the default is to skip the check window.** If the watermarks were simply left
  alone, the first pass after the check would export the whole window, and the duplicates the
  pause existed to prevent would land in Health Connect anyway, just a week late. The resume
  dialog offers:
  - **Skip these days (recommended)**: move each `HealthConnectWatermarks.Key` to the check's
    end, the same way `EXPORT_NEW_ONLY` already works.
  - **Export them too**: leave the watermarks alone, for a user who will take the reference
    device off afterwards and wants a complete ring history in Health Connect.
- Records PulseLoop exported *before* the check stay in Health Connect. The comparison filters
  them out by `DataOrigin` (below), so they don't get in the way.
- Process death, reboot or an app update mid-check: everything is persisted, and the check
  picks up where it left off. A WorkManager job ends the check at `activeSince + N days` even if
  the app is never opened. It posts a "Your accuracy report is ready" notification and leaves
  export **paused until the user answers the resume dialog**. Resuming silently would make the
  choice for them.

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
5. **What a correction does *not* touch:**
   - Health Connect export sends **raw** values by default. A corrected value in Health Connect
     would be indistinguishable from a measured one, for every app reading it. That's an open
     question below, not a default.
   - Values that are persisted or derived (`hrRestingBaseline`, HR zones,
     `estimatedActiveCalories`) keep using raw values in the first version. Feeding corrected
     values into persisted figures makes a correction hard to undo, the same trap
     `DemoDataPolicy` describes for demo data.
6. **Corrections belong to one ring.** Key them by device identity (address + model). Pairing a
   different ring turns them off and prompts "Run an accuracy check for this ring?". A
   correction fitted for an R11 applied to an R10 would be invented data.
7. **Corrections go stale.** Show "fitted 3 months ago, re-check?" after ~90 days or after a
   firmware change (the firmware revision is already decoded on several families).

## Data model (Room, v36)

- `accuracy_checks`: id, deviceKey, referencePackage, referenceLabel, startedAt, plannedEndAt,
  endedAt, state (RUNNING / AWAITING_RESUME_CHOICE / DONE / CANCELLED), bandsJson.
- `accuracy_check_days`: checkId, metric, dayEpoch, ringValue, refValue, pairCount, meanDiff,
  sdDiff, mae, validDay flag. These are aggregates only; no raw samples (see above).
- `accuracy_corrections`: deviceKey, metric, context (rest/active/—), kind (SCALE/OFFSET), value,
  fittedValue, fromCheckId, enabled, createdAt.
- Archive: all three go in `PulseArchive` (they are the user's own findings). Diagnostics export:
  **none of them**, per the "export carries no database rows" rule. If a shareable summary is
  ever wanted (below), it is a separate, explicit "Share report" action.

## Phases

0. **Foundation**: a read-permission set (`AccuracyCheckPermissions`, separate from export and
   body import), manifest entries, the reference-source discovery screen. Value on its own: the
   user can see what each app actually shares with Health Connect. *Verify on real devices
   which types Fitbit and Samsung Health actually write; this decides what the report can
   cover.*
1. **Session lifecycle + export pause**: prefs/table, worker gate, export-screen state, end job,
   notification, resume dialog with watermark skip. Unit-test the watermark move.
2. **Engine + report for steps, sleep, HR**: the pure comparison module with tests built from
   synthetic series (constant bias, pure noise, missing hours, a non-wear day, a nap, a night
   that crosses 20:00/midnight), and the report UI.
3. **Remaining metrics**: resting HR, SpO₂, distance, HRV, calories.
4. **Corrections**: read-time transform, labelling everywhere a corrected value is shown,
   keyed to the ring, stale prompt.
5. *(Optional)* **Shareable summary**: a text/image of the verdicts and headline numbers, with
   model and firmware, no raw values. Useful in GitHub issues ("my SR08 undercounts steps by
   20 %") and, over time, as crowd-sourced accuracy notes per model.

## Open questions

1. **Should corrected values ever go to Health Connect?** Proposed: no in v1. Possibly a later
   opt-in with the correction recorded in the record's metadata.
2. **Should corrections feed HR zones / resting baseline / calorie estimates?** Proposed: not in
   v1 (see rule 5).
3. **Pause the export per type instead of all of it?** If the reference device only writes steps
   and sleep, PulseLoop's SpO₂/HRV would duplicate nothing and could keep exporting. More
   correct, a little more UI. Proposed: pause only the types the chosen reference actually
   writes, and say which ones on the banner.
4. **iOS**: the same feature maps onto HealthKit (`HKSource` plays the part of `DataOrigin`).
   Worth tracking as an iOS-parity item once the Android version has proved itself.
