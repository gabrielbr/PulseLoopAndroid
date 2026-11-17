# Comparing two rings

The owner wears two rings (a Colmi and a JRing) to see which is more reliable. The owner's choice
(2026-11-17): **compare only**. The main ring keeps feeding the whole app; the second ring is shown
only on Settings → My ring → Compare rings.

## How it is kept apart ✅

- **Its own client:** `RingBLEClient(role = RingRole.COMPARISON)`, built once in `AppServices`
  (`ComparisonRing`). It has:
  - its own connection slot (`comparisonConnectionOwner`), so the two rings never block each other;
  - its own saved ring (`ring_ble_compare` prefs), so it never overwrites the main ring's.
- **Its own event stream: `ComparisonBus`, never `PulseEventBus`.** Everything on the app's bus
  belongs to the main ring:
  - persistence and the device row;
  - the accuracy watcher;
  - Health Connect and Strava;
  - the sync coordinator.

  So the comparison client publishes there only its diagnostics, marked `[comparison ring]`.
- **Driver publishes go through the writer.** Drivers that publish on their own (YCBT history,
  LuckRing history, Colmi's sync "done", jring's log) use `RingCommandWriter.publish`, which the
  comparison client overrides. A new driver that publishes must do the same, or a second ring of
  that family leaks into the app's data.
- **Its own table: `comparison_readings`** (v38). It holds:
  - heart rate, SpO₂ and HRV;
  - steps (buckets, plus the ring's running daily total);
  - sleep, one row per minute with the stage.

  Ids are `<address>:<kind>:<timestamp>`, so a re-sync updates rather than duplicates. Nothing else
  reads it: no score, no Health Connect, no Strava, and it is not in the backup archive.

## What it does ✅

- **On every connect:** the second ring runs its own startup and history sync, so its log for the
  day arrives like the main ring's.
- **During a recorded workout:** it streams heart rate too (`LiveWorkoutManager` →
  `ComparisonRing.startWorkout`), and a reconnect restarts that stream.
- **"Stop comparing":** disconnects the ring and forgets it without the vendor unbind, so it is
  still paired to its own app. Readings already kept stay in the table.

## The screen ✅

Per local day:
- **Heart rate:** both rings' curves (main solid, comparison dashed, each in its own colour), the
  time-weighted average, the hours with readings (5-minute slots), and how close the two were when
  both measured within 3 minutes (`RingComparison.agreement`).
- **Totals:** steps, and sleep for the night before.
- **Workouts:** each workout's average, highest and minutes with readings, per ring.

It says plainly that neither ring is known to be right, and links the accuracy check for a watch.

## Not yet ⬜

- The background sync worker syncs only the main ring. The second ring catches up whenever the app
  is open or a workout is recording.
- No SpO₂, HRV or temperature comparison on screen yet (the rows are stored).
