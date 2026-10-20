# JRing (56ff) plan: estimated BP and glucose, offsets, recalibration, and the rest of the ring

Status: plan, not started. Written 2026-10-20.

The 56ff ring's combined spot measurement (`0x23` → `0x24`) returns heart rate, blood pressure,
SpO₂, fatigue, stress and blood sugar in one reply. Heart rate and SpO₂ come from the optical
sensor in the usual way. **Blood pressure and blood sugar do not**:

- **Blood pressure** is computed by the ring's firmware from the shape of the optical pulse wave
  and the user profile. A single PPG site can't time the pulse between two points, so not even
  pulse-transit time is available. No ring has passed ISO 81060-2. Calibrated cuffless devices
  (Aktiia/Hilo, Samsung) need a cuff every few weeks and are cleared for trends only.
- **Blood sugar** has no sensor behind it. Measuring glucose needs blood or interstitial fluid. No
  ring or watch is FDA-authorised to measure or estimate it, and the FDA warned against devices
  that claim to (2024). The vendor app itself applies a "Sugar Offset" app-side, which tells you
  what kind of number it is.

So this plan treats both as **estimates**: written as estimates wherever they appear, adjustable
with a user offset, checked against real readings when the user has them, and never passed on
to other apps or the coach as if they were measurements.

## What exists today

| Piece | Where | State |
|---|---|---|
| Decode BP and glucose | `RingDecoder.decodeCombinedSensor` (bytes 2, 3, 7) | Done. Glucose stored as mg/dL, `(raw / 10) × 18.016` |
| BP cuff calibration sent to ring | `RingEncoder.makeBPAdjustCommand` (`0x33`, LE u16 sys/dia), `CalibrationSettingsScreen` | Done. Stores `bpAdjustSystolic/Diastolic`, **no date** |
| Glucose offset | `ApiKeyStore.glucoseOffsetMgdl`, applied at display in `ViewModels.kt` | Done. Stored rows stay raw |
| Estimate disclaimers | `Screens.kt` detail copy and amber card | Done on the detail screen only |
| Card quality badge | `VitalCardState`: glucose ESTIMATED / NEEDS_CALIBRATION, BP likewise | Done, but no expiry |
| Health Connect export | `VitalsExporter` exports `BLOOD_SUGAR` and BP pairs | **Problem**: ring estimates go out as real `BloodGlucoseRecord` / `BloodPressureRecord`, raw, without the offset |
| Health Connect import | `HealthConnectBodyImport` (weight, body composition) | No BP or glucose |

## Phase 1 — Say "estimate" everywhere

**Goal:** no surface shows a ring BP or glucose value without saying it is an estimate.

1. **One source of the wording.** Add `EstimateLabels` (`ui/EstimateLabels.kt`) with:
   - short prefix: `Est.` (e.g. `Est. 118/76`, `Est. 96 mg/dL`)
   - card caption: `Ring estimate · not a medical reading`
   - BP detail: "Your ring estimates blood pressure from the shape of your pulse. It can't
     measure pressure, so use a cuff for anything that matters. Calibrating keeps the estimate
     near your cuff; it can't make it follow real changes."
   - glucose detail: "Your ring has no glucose sensor. This number is an estimate from your
     profile and pulse and doesn't follow what you eat. Never use it for dosing or diabetes
     decisions."
2. **Every surface uses it:**
   - Vitals card and detail screen: values, axes, tooltips
   - Today glance and tiles
   - widgets
   - spot-measurement result sheet
   - CSV/archive export column names (`bp_systolic_estimated`)
   - diagnostics labels
3. **Coach.** Where ring BP or glucose enters a context packet, the field name says it
   (`estimatedBloodPressure`, `estimatedGlucose`) and the packet `note` adds: "Ring blood pressure
   and glucose are firmware estimates, not measurements; never draw medical conclusions from
   them." Check `CoachContextBuilder`, `NotificationContextBuilder`, the Vitals tools.
4. **Health Connect export** (decision needed, recommendation first):
   - **Recommended:** stop exporting ring BP and glucose by default. Add a switch in
     Settings → Health Connect, "Share ring estimates of blood pressure and glucose", off, with
     the reason underneath. Other apps can't tell an estimate from a cuff reading, and a
     diabetes or hypertension app would trust it.
   - If exported: write the **offset-applied** value (what the user sees). Today's export sends the
     raw value, so it disagrees with the app.
   - Existing users who already exported: leave the records; the switch only governs new ones.
     Note it in the release notes.

Tests: label helpers, exporter excludes BP/glucose when the switch is off, coach packet field
names.

## Phase 2 — Offsets the user can see and change

**Goal:** both estimates have an offset the user can read, set, adjust and reset, and the app is
honest about what an offset can do.

### Blood pressure: ring calibration first, app offset second

The ring already takes a cuff value (`0x33`) and adjusts its own output. An app-side offset on top
of that would double-correct unless it is measured **after** the ring has been calibrated. So
calibration becomes one guided flow:

1. **Cuff reading.** "Sit for 5 minutes, take a cuff reading, enter it." Accept `70–250 / 40–150`,
   diastolic below systolic.
2. **Send to the ring** (`0x33`, unchanged), and store `bpCalibratedAt = now` with the cuff values.
3. **Ring reading** within 10 minutes: the screen offers "Measure with the ring now" (runs the
   combined measurement).
4. **App offset = cuff − ring reading after calibration**, per value (`bpOffsetSystolic`,
   `bpOffsetDiastolic`). If the ring already matches, the offset is about 0, which is the expected
   case.
5. **Guard rails:**
   - Offset beyond ±25 systolic or ±15 diastolic: apply it, but tell the user "Your ring's estimate
     is far from your cuff. Treat it as unreliable", and mark the card "Low agreement".
   - Skipping step 3 is allowed. The offset stays 0 and the card says "Calibrated on the ring,
     not checked".

**Manual adjustment.** The Calibration screen shows the current offsets as steppers (±1 mmHg, ±1
mg/dL), with "Reset to 0" and the date of the last calibration. A manual change counts as a
calibration only for the offset, not for the reminder below. A reminder that resets because
someone nudged a stepper defeats its purpose.

**Storage.** New `ApiKeyStore` fields: `bpOffsetSystolic`, `bpOffsetDiastolic`, `bpCalibratedAt`
(epoch ms, 0 = never), `bpCalibrationCuffSys/Dia`. Stored measurements stay **raw**. The offset is
applied at read time like glucose is, so changing the offset re-renders history consistently and
a reset is lossless. Archive export carries the fields.

**Where the offset is applied.** One function, `EstimateCalibration.apply(kind, raw)`, used by:
- the Vitals view models
- the detail charts
- the Today tiles
- the widgets
- the coach packet
- the Health Connect export (if on)

Today the glucose offset is added in three separate places in `ViewModels.kt`; fold them into this
function.

### Glucose

Keep the current flow (meter reading − latest ring reading = offset), with:
- **Freshness:** use a ring reading taken within 15 minutes of the meter reading, not "the latest
  ever", and offer "Measure with the ring now".
- **Steppers and reset**, same as BP.
- **Stated limit:** "The offset shifts every reading by the same amount. The ring's value won't
  rise after a meal, so the offset can only be right at one moment."
- `glucoseCalibratedAt` stored, for display only (no reminder; recalibrating a number that isn't a
  measurement every two weeks would suggest it is one).

Tests: offset maths, the post-calibration offset is cuff − ring, guard-rail thresholds, raw rows
untouched, export uses the applied value.

## Phase 3 — Blood pressure recalibration every 2 weeks

**Goal:** a ring BP estimate is never more than two weeks from its last cuff check without the
user being told.

- **Due rule** (`BpCalibrationReminder.due(now, calibratedAt, lastRingBpAt, lastSentAt)`), pure
  and unit-tested:
  - the active ring supports BP, and
  - there was a ring BP reading in the last 30 days (no nagging people who never use it), and
  - `now − bpCalibratedAt ≥ 14 days`, or never calibrated and the first ring BP reading is at
    least 1 day old, and
  - the reminder hasn't been sent in the last 3 days (repeat at most every 3 days until done), and
  - local time 09:00–20:59.
- **Delivery:** `GuidanceWorker` (hourly), notification ID `3008`, channel "Daily guidance". Text:
  "Time to recalibrate blood pressure — it's been 14 days. Take a cuff reading and enter it so
  your ring's estimate stays close." Tapping opens `settings/calibration` (new
  `MainActivity.ACTION_OPEN_ROUTE` extra → `LaunchRequests.route`).
- **Switch:** Settings → Daily guidance, "Blood pressure recalibration", **on by default**, shown
  only for BP-capable rings.
- **In the app, without notifications:**
  - From day 14, the BP card's quality becomes "Calibration due" (`VitalCardState`:
    `NEEDS_CALIBRATION` when `bpCalibratedAt` is older than 14 days, not only when never set).
  - The detail screen shows "Last calibrated 16 days ago · Recalibrate".
  - From day 28, BP values render greyed with "Out of date calibration".
- **Done means:** completing the guided flow's step 2 sets `bpCalibratedAt` and clears the
  reminder. A Health Connect cuff reading (Phase 4) offers a one-tap "Use this to calibrate".

Tests: the due rule across the boundaries (13/14 days, never calibrated, 3-day repeat, quiet
hours, no recent BP use), card quality transitions.

## Phase 4 — Real readings from Health Connect

**Goal:** when the user owns a cuff, glucometer or CGM, PulseLoop shows those readings and uses
them to keep the ring estimates honest.

- **Permissions:** `READ_BLOOD_PRESSURE`, `READ_BLOOD_GLUCOSE` (manifest and
  `HealthConnectPermissions`), requested from a new "Import blood pressure and glucose" switch,
  same pattern as the scale import.
- **Import:** extend `HealthConnectBodyImport` (or a sibling `HealthConnectVitalsImport`):
  - **Reading back our own export:** skip records whose `dataOrigin` is PulseLoop's own package,
    or we import our own exports back.
  - **Storage:** rows as `MeasurementEntity` with `sourceRaw = "health_connect"`, id
    `hc:<recordId>`, upserted.
  - **Glucose:** `BloodGlucoseRecord` level in mg/dL; keep `specimenSource` and `relationToMeal`
    in a note field if cheap.
  - **Blood pressure:** both rows of the pair, same as ring BP.
- **Display:**
  - Real and estimated readings sit on the same chart, distinguished: solid dots for cuff or meter,
    hollow for "Est." ring values.
  - The card's headline value prefers a real reading from the last 7 days over a ring estimate
    ("real wins", as `DemoDataPolicy` does for demo rows).
- **Agreement check:**
  - When a cuff reading and a ring estimate fall within 30 minutes of each other, the detail
    screen shows the difference.
  - Over 5+ pairs it shows the average gap and spread: "Your ring reads 6/3 mmHg low on average,
    ±11 mmHg". This is the most useful thing we can tell the user about the estimate.
- **Calibration from an import:** a new cuff reading offers "Use this to calibrate", which runs
  Phase 2 step 2 (send to the ring, set the date) and offers a ring measurement for step 3.
- **Downstream:** imported CGM/meter glucose is real data. It can feed the nutrition screens later
  (meal → glucose curve). It is out of scope here, but the import should store enough (timestamp,
  meal relation) not to block it.

Tests: import mapping, own-origin skip, idempotent re-import, real-wins selection, agreement
statistics.

## Phase 5 — The rest of the ring

Independent of BP and glucose; listed so the "everything about this ring" pass is in one place.

1. **HRV from the spot measurement.** The decoder comment says `0x24` byte 8 is HRV, and nothing
   reads it. Decode it as `HistoryMeasurement(HRV)` when > 0 and plausible (10–200 ms). It's an
   occasional spot value, not a nightly series, so it won't fill Readiness' HRV balance on its
   own. Confirm the byte against a capture first.
2. **Denser all-day heart rate.**
   - `makeAutomaticHeartRateCommand` is sent with a 30-minute cadence. Offer 10 and 5 minutes in a
     Measurement setting, shown for JRing via the `MEASUREMENT_INTERVAL` capability.
   - With 5-minute data, resting heart rate is sharper and the recovery index works.
   - Measure the battery cost on hardware before choosing a default.
3. **Heart-rate history resolution.** `decodeHrDataBlock` collapses 12 values per `0x16 A0` block
   into 2 averages stamped one minute apart. If the 12 are per-minute samples, store them all.
   This needs a capture to confirm the spacing before changing anything; a wrong guess smears
   history in time.
4. **Feature bitmask (`0x20`).** We decode `hasTemperature` (bit 10), `hasOxygenOfflineHistory`
   (81) and `hasPressureHistory` (83) and use none of them.
   - **Log it:** add the decoded bitmask to diagnostics. It already rides in the `BandFunction`
     debug JSON; make sure it reaches the export.
   - **Then:** find the history requests for offline SpO₂ and BP in `decompiled-jring-offical/`
     (`jadx --single-class` against `classes2.dex`, per AGENTS.md), and implement only what a real
     ring advertises.
   - **Caveat:** the bit order within a byte is still unconfirmed, so nothing may depend on these
     bits until a capture confirms it.
5. **Health Connect, the other direction:** if the user turns ring-estimate sharing on (Phase 1),
   export the offset-applied values. Already covered above; listed for completeness.

## Order and size

| Phase | Depends on | Size | Ships alone? |
|---|---|---|---|
| 1 Labels + export policy | — | S | Yes, and first: it fixes the export problem |
| 2 Offsets | 1 | M | Yes |
| 3 Two-week reminder | 2 (`bpCalibratedAt`) | S | With 2 |
| 4 Health Connect import | 1 | M | Yes |
| 5.1 HRV byte | capture | S | Yes |
| 5.2 HR cadence | battery test | S | Yes |
| 5.3–5.4 | capture + decompile | M | After hardware |

**Hardware needed** for 5.1, 5.3, 5.4 and the Phase 2 double-correction check: one 56ff ring, a
diagnostics export taken after a combined measurement, and one after a full history sync.

## Open questions

1. Does the ring apply `0x33` to its own output immediately? Phase 2 step 3 measures it either
   way, but a capture before and after calibration would show it directly.
2. Export ring estimates to Health Connect at all? This plan says off by default; the user decides.
3. Should the coach ever mention ring BP/glucose? This plan lets it see them as estimates, with
   an instruction never to draw medical conclusions. The alternative is to leave them out of the
   coach entirely.
4. Glucose recalibration reminder: deliberately left out (see Phase 2). Revisit only if the user
   imports real glucose and the agreement check shows the estimate tracking it.
