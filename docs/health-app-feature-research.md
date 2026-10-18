# What other health apps offer, and what PulseLoop took from them

Research from 2026-10 into the features that make WHOOP, Garmin, Samsung Health, Apple Health,
Fitbit and Ultrahuman valuable to an everyday user, filtered by what PulseLoop already has
(readiness, sleep and activity scores, AI coach, check-ins, usual-range chart bands) and by what a
budget ring like the Colmi R06 can measure (HR, HRV, SpO₂, steps, sleep stages; no temperature).

## Built (daily guidance)

| Feature | Inspired by | Where |
|---|---|---|
| **Unusual signals alert**: last night's resting HR, HRV, SpO₂ and sleep duration against the 10th–90th percentile of the user's own nights; notify only when two or more are outside it | Apple Vitals | `service/DailyGuidance.kt` `VitalsCheck`; Today card; `GuidanceWorker` once a day |
| **Bedtime guidance**: usual wake time minus the sleep goal stretched by the user's own sleep efficiency, ±15 min; optional wind-down reminder | Samsung Bedtime Guidance, Oura | `BedtimeGuidance`; Today "Tonight" card; `GuidanceWorker` (off by default) |
| **Caffeine cutoff**: 8 hours before the suggested bedtime | Ultrahuman caffeine window | `BedtimeGuidance.caffeineCutoff`; Today "Tonight" card |
| **Weekly report**: Sunday from 18:00, this week vs last week (sleep, resting HR, HRV, steps) and the one area that moved most the wrong way | WHOOP Weekly Plan, Oura | `WeeklyReport`; `GuidanceWorker` |

All are switchable under Settings → Coach Check-Ins → Daily guidance, and work without an AI key.

## Next candidates

- **Tags and journal**, then "what affects you" correlations (WHOOP Journal, Oura tags). Medium effort.
- **Breathing session with live heart rate** (WHOOP stress monitor). Medium effort.
- **Training load**: 7-day vs 28-day workout load (Apple, WHOOP Strain, Garmin). Only logged workouts have HR.

## Not planned on this hardware

- Skin temperature, cycle insights, illness prediction: need a temperature sensor.
- Daytime energy (Garmin Body Battery): needs continuous HR; a 5-minute grid would be guesswork.
- Healthspan, biological or cardio age: would overclaim on a budget ring's data.

## Sources

- WHOOP, App Store listing: https://apps.apple.com/app/id933944389
- Garmin, HRV Status and Training Readiness: https://www.garmin.com/en-MY/blog/fitness/new-features-on-garmin-smartwatches-help-to-maximize-your-training/
- Samsung Health: https://www.samsung.com/uk/apps/samsung-health/
- Apple Watch User Guide, Vitals: https://support.apple.com/guide/watch/vitals-apd15aa7ed96/26/watchos
- Google, Fitbit personal health coach: https://blog.google/products/fitbit/fitbit-ai-personal-health-coach-preview/
- NBC News, Ultrahuman review: https://www.nbcnews.com/select/amp/rcna202789
