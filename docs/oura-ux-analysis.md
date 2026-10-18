# Oura's app UX, and how PulseLoop mirrors it

Goal: make PulseLoop read like a consumer health app, not a sensor dashboard, without
claiming more than a budget ring measures. Oura is the reference because it solved the same
problem first: a ring produces noisy vitals, and people want one honest answer to *"how am I
today, and what should I do about it?"*

Sources are listed at the end. Where a point comes from general familiarity with the app
rather than a cited source, it is marked *(observed)*: verify it against the current app
before copying a detail exactly.

---

## 1. What Oura actually does

### 1.1 Information architecture

The October 2025 redesign settled on three tabs [1][2]:

| Tab | Job | What lives there |
|---|---|---|
| **Today** | "How am I right now, what matters today" | Readiness, Sleep and Activity scores at the top, then one focus card ("One Big Thing") chosen from the most relevant signal. |
| **Vitals** | "Show me the numbers" | Each vital with day / week / month / year views. Cards are tinted by status so problem areas stand out. |
| **My Health** | "Long-term picture" | Trends, Cumulative Stress, Habits & Routines, and the AI Advisor. |

The point of the split: **the first screen answers a question, the second shows evidence,
the third shows change over time.** Raw numbers are never removed ("raw numbers remain
available for users who want them" [1]), but they are not the first thing you see.

### 1.2 Score anatomy (the core pattern)

All three scores share one structure [3][4][5]:

1. **One number, 0–100**, with a word label. Bands are the same everywhere:
   85–100 *Optimal*, 70–84 *Good*, 60–69 *Fair*, 0–59 *Pay attention* [3].
2. **One sentence** of plain-language interpretation under the number *(observed)*,
   e.g. "You're well recovered, a good day to push." Never a diagnosis.
3. **Contributors**: 6–9 rows, each with a name, a short status, and a progress bar.
   A red bar plus "Pay attention" marks what pulled the score down [4].
4. **Tap a contributor** for the explanation: what it measures, your value against your own
   baseline, and what you can do about it.

**Every number is relative to you.** Readiness compares last night against your own
baselines: two months for resting HR, 14 days against three months for HRV, two weeks for
sleep balance [3]. Population norms appear only as a sanity range (RHR 40–100, sleep 7–9 h).

### 1.3 The three scores' contributors

**Readiness** (nine contributors [3]):

| Contributor | Oura's rule |
|---|---|
| Resting heart rate | Night's lowest HR vs your ~2-month average. Drops when 3–5 bpm above, or 10–15 below, usual. |
| HRV balance | 14-day HRV average, recent nights weighted more, vs your 3-month average. |
| Body temperature | Night temperature vs your long-term night average. |
| Recovery index | Time slept after your heart rate hit its nightly low. Optimal: 6 h or more. |
| Sleep | Total sleep in the past 24 h (including naps) vs your baseline. |
| Sleep balance | Two weeks of sleep vs your need (7–9 h for adults). Captures sleep debt. |
| Sleep regularity | Consistency of bed and wake times over two weeks. |
| Previous day activity | Yesterday's activity and inactivity vs your averages. A very heavy day lowers it too. |
| Activity balance | 14-day activity, weighted recent, vs your 2-month level. Flags over- and under-training. |

**Sleep** (seven contributors [4]): total sleep (7–9 h), efficiency (about 85% optimal),
restfulness, REM (around 90 min; 20–25% of sleep), deep (about 90 min young adults, less with
age), latency (15–20 min ideal; under 5 suggests overtiredness), timing (midpoint between
midnight and 3 am).

**Activity** (six contributors [5]): stay active (sedentary time), move every hour, meet
daily goals (3+ days a week, aim for 5), training frequency (medium/high intensity 3+ times
a week), training volume, recovery time.

### 1.4 Trust and honesty patterns

These are what make a cheap signal feel trustworthy. They matter more than the visuals.

- **A learning period, stated up front.** "It can take up to two weeks for the Oura App to
  learn your average values" [3]. Until then contributors show a learning state, not a
  made-up number.
- **Deviation from your normal, not absolute values.** "3 bpm above your usual" is
  understandable and hard to misread. "61 bpm" alone is not.
- **The score is explained by its parts.** No black box: every point lost is visible in a
  contributor.
- **Non-judgemental tone.** Low scores say "prioritise rest and recovery", not "bad night".
  High scores say "ready to take on new challenges" [3].
- **Missing data is shown as missing.** A night the ring didn't capture is a gap, not a zero
  *(observed)*.
- **Colour encodes status, sparingly.** The redesign tints parts of the app by your
  biometrics for at-a-glance status [1]. Colour is reserved for meaning, not decoration.

### 1.5 Visual language *(observed)*

- Dark, calm backgrounds, generous spacing, few borders. The redesign moved away from boxed
  cards to a more open layout [2].
- Scores drawn as large rings or arcs. The number is the biggest type on screen.
- One accent per score (Readiness, Sleep, Activity) used consistently on every surface.
- Charts carry a shaded band for "your usual range", so a point outside it reads at a glance.

---

## 2. Where PulseLoop stands

From a codebase survey (paths under `app/src/main/java/com/pulseloop/`):

| Area | Today | Gap against Oura |
|---|---|---|
| Information architecture | Five tabs: Today, Vitals, Activity, Sleep, Coach. | Close enough; Activity and Sleep can stay as tabs. Today lacks the score header. |
| Readiness | None. `PulseColors.readiness` exists but is unused. | The headline score is missing entirely. |
| Sleep score | `SleepScore.calculate` (`service/SleepInsights.kt`): duration, deep%, light%, awake%. There is also a second, cruder `sleepStageScore` stored on `SleepSessionEntity.score`. | Two inconsistent scores, and no contributor breakdown in the UI. |
| Activity score | None. Goal rings and a step-delta heuristic (`deriveHero` in `TodayScreen.kt`). | No score; rings are fine as the visual. |
| Baselines | Resting HR: 10th percentile of all-day HR over 30 days (`RestingHRBaselineService`). HRV: computed per screen, never stored. | Baselines come from all-day data, which mixes in daytime noise. Oura uses the sleep window only. |
| Data quality | Plausibility clamps at ingest (`RingEventBridge.isPlausible`). Wear state is not persisted. `SourceQuality.MOTION_ARTIFACT` exists but nothing sets it. | Spikes reach every minimum and mean. Nothing tells the user how much of the night the ring covered. |
| Honesty rules | Strong: "a derived metric must say it is derived", null over defaults, real data wins over demo (AGENTS.md). | Already aligned with Oura's trust patterns. Readiness must follow them. |
| Coach / AI | Summary and notification packets carry steps, latest HR/SpO₂, sleep totals. | No readiness or baseline context, so the coach can't say "your HRV is down". |

**What the hardware rules out:** body temperature (the R06 has no sensor), so the Body
temperature contributor is dropped, as is Cumulative Stress's temperature-regulation input.
**Recovery index** needs a dense overnight HR curve; with a 5-minute HR grid it is feasible but
noisy, so it is deferred until the overnight data is checked against a reference.

---

## 3. The plan

### Phase 1: scoring engine (done in this change)

Plain Kotlin, no UI, unit-tested (`service/Readiness.kt`, `service/ReadinessRepository.kt`,
`ReadinessTest`).

1. **Spike filter** (`ReadingQuality.dropSpikes`): a two-sided Hampel filter. A sample is
   dropped only when it disagrees with its neighbours on both sides, so isolated optical
   spikes go and genuine changes stay.
2. **Nightly vitals** (`NightlyVitals.summarize`): resting HR is the lowest 3-sample rolling
   mean *inside the sleep window* (a sustained low, not one dropped beat), HRV is the night's
   median, and **coverage** is the share of half-hour slots with any HR sample. Too few
   samples gives null, not a guess.
3. **Readiness** (`Readiness.compute`): seven of Oura's nine contributors, each 0–100 against
   the user's own baseline, combined by weight (RHR 20%, HRV 20%, sleep 20%, sleep balance
   15%, regularity 5%, previous-day activity 10%, activity balance 10%). Unavailable
   contributors are listed as missing and the rest are renormalised. Three explicit
   non-score states:
   - **NoSleep**: no night recorded, nothing to be ready from.
   - **Learning(n of 7 nights)**: baseline still forming, shown as progress.
   - Learning also when last night has neither HR nor HRV: a sleep-only number would be a
     sleep score wearing a readiness label.
   Every score carries `derived = true` and the night's coverage.

### Phase 2: Today header and Readiness screen (done, v2.11.3)

- **Today, top**: three score rings (Readiness, Sleep, Activity) in their own accents, with
  the readiness sentence under them. While learning: "Learning your baseline: 4 of 7
  nights" with a progress bar.
- **Readiness detail**: big score, band, one sentence, then contributor rows (name, status
  word, bar, one-line detail like "7 bpm above your usual 55"), each tappable for an
  explanation. A footer line: "Estimated by PulseLoop from your ring's data. Last night's
  coverage: 92%." Low coverage (under 60%) shows a visible caution.
- **Copy rules**: plain words, deviation from *your* normal, no diagnoses, low scores
  suggest rest rather than blame.

### Phase 3: one sleep score, with contributors (done)

`SleepScore.calculate` is now Oura-shaped: total sleep vs the user's goal (35%), efficiency
(15%), deep (20%), REM (10%), restfulness (10%) and timing (10%), weights renormalised over
what the ring can see. REM only counts when the ring reports REM, efficiency and restfulness
only when it reports wakefulness. Latency is left out: the ring doesn't record when the wearer
got into bed. Every surface that showed a sleep score now shows this one, and the Sleep tab
lists the contributors with the same rows as Readiness (`ContributorsCard`). The old
`SleepSessionEntity.score` column is left alone for history.

### Phase 4: activity score (done)

`service/ActivityScore.kt`: daily goal progress (25%), meet daily goals over the last week
(20%), move every hour from 15-minute step buckets between 08:00 and 21:00 (15%), training
frequency (15%) and volume (15%) from logged workouts, and recovery days (10%). Oura's "stay
active" needs minute-level intensity the ring doesn't give, so today's goal progress stands in.
The Today header's Activity ring shows this score and opens an Activity score screen with the
same contributor rows.

### Phase 5: night-only baselines, chart bands and the coach (done)

- `RestingHRBaselineService` now uses the median nightly resting HR (sleep window only, spikes
  filtered) once 7 nights exist, and falls back to the all-day 10th percentile before that.
  That baseline drives the "auto" HR zones and the Health Connect resting-HR export.
- `ScoresContext` puts readiness, its contributors, coverage and the activity score into both
  the coach summary packet and the check-in notification packet, and the check-in prompt tells
  the model to treat them as estimates and not to quote readiness while it is still learning.
- "Your usual range" bands (`service/UsualRange.kt`): the 25th–75th percentile of the user's
  own real readings over 30 days, once there are 20+ readings across 7+ days. Drawn on the
  Today and Vitals chart cards for HR, SpO₂, HRV, stress and temperature (replacing the
  population band once established) and on each vital's detail chart, captioned "Shaded: your
  usual range over the last 30 days". Derived stress gets none.

### Not planned (hardware)

Body temperature, Cumulative Stress's temperature input, cycle insights. Revisit only if a
supported ring reports temperature.

---

## Sources

1. 9to5Google, "Oura is redesigning its app weeks after Ceramic Ring launch", 2025-10-20: https://9to5google.com/2025/10/20/oura-app-redesign/
2. Android Police, "Oura's redesigned app brings big new health tracking powers": https://www.androidpolice.com/oura-redesigned-app-brings-new-health-tracking-powers/
3. Oura Help, "Readiness Contributors": https://support.ouraring.com/hc/en-us/articles/360057791533
4. Oura, "Sleep Score": https://ouraring.com/blog/sleep-score/
5. Oura, "Activity Score": https://ouraring.com/blog/activity-score/
