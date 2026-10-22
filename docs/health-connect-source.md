# Health Connect as the data source (no ring)

For people who already wear an Oura, a Galaxy Ring, a Fitbit or a watch: PulseLoop reads that
device's app from Health Connect instead of pairing a ring, and every screen, the coach and the
insights run on that data.

## How a user gets there

- Onboarding, welcome step: **"I wear an Oura or another device"**. The ring step then shows the
  source picker instead of pairing.
- Settings → Resources → **Use without a ring** (`settings/hc-source`).

The screen asks for Health Connect read access, lists the apps that wrote health data in the last
week (the accuracy check's `ReferenceSourceScanner`, so PulseLoop's own exports and bridge-app
echoes are already filtered out), and the user picks one.

## What is imported

Each pass reads only the chosen package (`DataOrigin` filter) and turns records into the same
`PulseEvent`s a ring's history produces. Those go through `EventPersistenceSubscriber.ingest`, so
ids, tombstones, the sleep reconcile and the bucket sums all behave exactly as for a ring.

| Health Connect | Becomes |
| --- | --- |
| Heart rate samples | `HistoryMeasurement(HEART_RATE)` |
| HRV (RMSSD) | `HistoryMeasurement(HRV)` |
| Oxygen saturation | `HistoryMeasurement(SPO2)` |
| Respiratory rate | `HistoryMeasurement(RESPIRATORY_RATE)` |
| Sleep session + stages | `SleepTimeline`, one stage per minute, `completeSession = true` |
| Steps + distance | 15-minute aggregates → `ActivityBucket` |
| Total (else active) calories | the day's `activity_daily.calories`, `source = "health_connect"` |
| Exercise sessions | finished `activity_sessions`, id `hc-<record id>`, imported once |
| Menstruation periods | `periods`, unless one is logged within 2 days of the start |

The first pass reads 30 days, the most Health Connect serves before the first grant. Later passes
re-read from 3 days before the last one, because source apps write late (a night lands in the
morning). Passes run hourly in the background (with the background-read permission), and whenever
the app comes to the foreground.

## Rules

- **One source app, by package.** Without the filter, PulseLoop's own earlier exports would come
  back in, and two devices' steps would land in the same 15-minute slots.
- **Exclusive with a paired ring.** The screen refuses while a ring is paired, and a ring being
  identified switches the mode off (`EventPersistenceSubscriber`, `DeviceIdentified`).
- **Nothing goes back.** While the mode is on, `HealthConnectSource.exportExclusions` holds back
  every `ExportUnit` for all time, through the same `ExportPause` filter the accuracy check uses,
  so watermarks still advance. Imported workouts are skipped by the workout export by id prefix.
  Meals and workouts recorded in PulseLoop still export.
- **A deleted import stays deleted.** Measurements and sleep use the ring's tombstones. Workouts are
  imported once (the seen-id list), so deleting one doesn't bring it back.

## Known limits

- Health Connect has no skin temperature from most of these apps yet, so temperature cards stay
  empty, and stress is derived from HRV as for rings without a stress sensor.
- Whatever the source app's own scores are (readiness, sleep score), they don't reach Health
  Connect. PulseLoop computes its own from the raw data.
