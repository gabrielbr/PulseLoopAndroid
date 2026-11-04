# Activity recognition: what the ring saw you doing

**Goal.** After a sync, and when the app opens, the app says what the user was probably doing,
so it can be logged with a tap. Examples: "Looks like strength training · 45 min at 18:10", or
"Looks like a ride". The model is Oura's automatic activity detection: a stretch of movement is
noticed, a type is guessed, and the user confirms, changes the type, or dismisses it.

## What we have to work with

The rings PulseLoop supports don't expose raw accelerometer data. Detection has to work from
what every family syncs:

| Signal | Resolution | Source |
|---|---|---|
| Steps | buckets of 1–15 min (per family) | `activity_buckets` |
| Heart rate | all-day grid, usually 5 min, denser during spot measures | `measurements` (HEART_RATE, real rows) |
| Quiet HR / HRmax | per user | `CardioLoad.quietLevel`, `CardioLoad.hrMax(age)` |
| Nights | per night | sleep sessions (excluded from detection) |

What that can and can't tell apart:

- **Walking vs running:** cadence. Reliable.
- **Cycling:** heart rate held up for a long stretch, with few steps and a steady line. Usually
  right; a rowing machine or an elliptical looks the same.
- **Strength training:** heart rate up but uneven (sets and rests), few steps, often short bursts
  of steps between sets. The 5-minute HR grid blurs the sets, so on its own this is the weakest
  call.
- **High-intensity intervals:** high peaks with deep drops between them.
- **Everything else** (sport, dance, yoga) can't be told apart from these signals. The user
  chooses, and the app learns.

That is why the app always asks and never logs on its own. It is also why the user's own answers
are the strongest signal it has.

## Phase 1: shipped first

1. **Guess the type** (`service/ActivityClassifier.kt`).
   - **Features of a stretch:** minutes, cadence (steps per minute), how bursty the steps are,
     mean and peak heart rate as a share of heart-rate reserve, and how uneven the heart rate is
     (coefficient of variation).
   - **Rule scores** for walk, run, cycle, gym (strength), high-intensity and other. Every type
     is offered, best first.
   - **Learning:** each confirmed suggestion stores its features with the type the user chose
     (`activity_detection` prefs, `learned`, newest 200, carried in the backup). Once there are
     a few, the nearest earlier answers vote, and an answer close to this stretch outweighs the
     rules. Someone who always lifts at 18:00 gets "Gym" first, even when the rules lean
     towards cycling.
2. **One candidate per stretch.** Step stretches and raised-heart-rate stretches merge into one
   window, so a run is not also offered as "cardio".
3. **After every sync, a notification.** `ActivityDetectionTrigger` listens for the sync's
   `done` event, runs detection, and posts one notification per new suggestion. It only
   notifies for activity that ended within the last 12 hours, and only once per suggestion.
   Tapping it opens the Activity tab. The trigger has its own channel and its own switch
   (Settings → Notifications → Detected workouts).
4. **When the app opens, a line on Today.** It names the newest suggestion ("Looks like strength
   training · 18:10 · 45 min") and opens the Activity tab. There it is the same "Detected" card
   as before, with the guessed type selected and the other types offered in order of
   likelihood.
5. **Rules kept from the first version:** nothing is ever logged without a tap, "Not this" is
   remembered, and a stretch that overlaps a recorded workout or a night is never offered.

## Phase 2: workouts the ring recorded itself

Several families keep their own exercise records when the user starts a sport on the ring, or
when the ring auto-detects one: Colmi's sport records (`0x77`/`0x78` sessions), YCBT sport
history, CRP movement records. Their type comes from the ring, so it beats any guess. Import them
as suggestions that carry the ring's type and its own HR and distance figures. Match the vendor
app's decode for each family; per AGENTS.md, don't guess opcodes.

## Phase 3: sharper heart rate during a stretch

When a stretch looks active, request the ring's finer HR history for that window where a family
offers one, or temporarily raise the all-day measuring interval. Sets and rests then become
visible, and the strength call stops depending on a 5-minute grid. This needs per-family protocol
work and hardware validation with the ring owners.

## Not planned

- **Logging automatically.** A wrong workout is worse than a missed one: it lands in Health
  Connect and Strava.
- **A population-trained model.** We have no labelled dataset, and the user's own answers are
  the better one.
