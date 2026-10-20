# Drinks: tracking and cutting down on alcohol

Status: built in v2.11.23+98. Written 2026-10-20.

## 1. What competitors do

| App | Kind | What it does | What it lacks |
|---|---|---|---|
| WHOOP | Band | Journal "alcohol" answer; Behavior Impacts shows next-day Recovery effect. Says HRV can take 4–5 days to rebound | Counts, goals, any help to cut down |
| Oura | Ring | "Alcohol" tag beside the night; member data: HRV −15.6%, lowest RHR +8.2%, −35 min sleep the night after | Same |
| Sunnyside | Cut-down app (paid) | Weekly plan of drinks per day and week from current habits; text or app logging; weekly recap; coaches | Knows nothing about the body |
| Reframe | Cut-down app (paid, leans to quitting) | Baseline + goal; weekly/monthly charts; craving toolkit for the ~20-minute urge (breathing, games) | Same |
| Try Dry (Alcohol Change UK, free) | Cut-down app | Units, calories, money saved; dry-day streaks and badges; *planned drinking*; AUDIT-C every 3 months | Same |
| MyDrinkaware (free) | Cut-down app | Units/calories per drink; drink-free days; sleep beside drinking; binge warning | Same |

Evidence: the UCL *Drink Less* factorial trial (Crane et al., 2018, n = 672) found no single
component worked alone; self-monitoring with feedback plus action planning, and normative
feedback plus cognitive-bias retraining, gave modest gains. Self-monitoring with rich feedback was
the most used and best rated. So the core is **log → feedback → plan**.

**The gap PulseLoop fills:** the ring apps show the cost but don't help; the cut-down apps help
but can only guess the cost. PulseLoop has both the night's physiology and a food log.

## 2. UI/UX analysis: where it fits

### The app today

- **Five tabs** (Today, Vitals, Activity, Sleep, Coach). Tabs are body systems and the coach.
- **Today in three layers** (docs/oura-ux-analysis.md):
  1. *Glance*: score header, one-line brief, alert line.
  2. *Act*: `QuickLogRow` (Meal · Tag day · Measure · Workout) and at most **two** time-of-day
     moment cards (`TodayMoments`: last night 4–10 h, food 11–15 h, tonight from 17 h, tag day
     from 19 h).
  3. *Browse*: customisable tile grid (`DashboardCard`), with Nutrition as the only behaviour tile.
- **Behaviour inputs** live off-tab: Nutrition screen (tile, quick chip, widget, shortcut),
  Journal (Tag day sheet → "See what affects you"), My Health (from Vitals).
- **Alcohol already exists** as a journal tag, auto-ticked by `MealJournal` when a logged meal
  names a drink, and analysed by `JournalInsights` against the next night.
- Settings → Goals holds daily targets; Settings → Nutrition holds the food log's options.

### Options considered

| Placement | Verdict | Why |
|---|---|---|
| New bottom tab | **No** | Tabs are body systems; five is the Material maximum; it would put alcohol in front of every user, including those who don't drink — wrong prominence for a sensitive, opt-in topic |
| Section inside Nutrition | No | Drinks need their own goals, streaks and night impact; buried below food they'd be skipped, and a dry day is not a food event |
| Extend the Journal "Alcohol" tag only | No | A yes/no tag can't count drinks, so no dose, no weekly limit, no progress |
| Fifth Quick Log chip | No | Four equal chips already fill a phone width; a fifth crowds them for everyone |
| **Dedicated "Drinks" screen, reached from Today, opt-in** | **Yes** | Same pattern as Nutrition: a full screen for the work, a Today tile for the glance and the one-tap log, moment cards for the right time |

### The chosen design

- **Opt-in.** Off by default. Turned on from the Drinks screen's welcome, from Settings → Goals →
  Drinks, or from a one-line offer on the Journal screen next to the Alcohol tag. Turning it off
  hides every surface; data stays until deleted.
- **Today, Browse layer: a Drinks tile** (`DashboardCard.DRINKS`, only when enabled). Shows this
  week's drinks against the limit, dry days, and a **"+1"** that logs the last-used drink. Same
  shape as the Nutrition tile, so it reads as "a thing I log".
- **Today, Act layer: the Last night card gains a drinks line** on the morning after drinks
  (it shows 04–10 h, like the card itself). No new moment and no new chip: the morning-after
  question *is* a last-night question, so it belongs on that card. It says the part only
  PulseLoop can say: *"Last night, 3 drinks. HRV 18 % below your dry nights, resting heart rate
  +5 bpm, 35 min less sleep."* Tapping the line opens Drinks.
- **Drinks screen** (`drinks`), top to bottom in order of use:
  1. This week: drinks vs limit, dry days vs target, current dry streak.
  2. Log: one-tap presets (beer, wine, spirit, caipirinha, cocktail, other), a sheet for size,
     count, time and price; today's and yesterday's drinks with delete.
  3. Plan tonight (planned drinking): how many, then progress against it.
  4. What drinking does to you: last night; 1–2 vs 3+ drinks against dry nights; days for HRV to
     come back. Labelled "a pattern in your nights, not cause and effect".
  5. Progress: 8-week bars with the limit line, best streak, money and calories not spent.
  6. Help: "Craving? Ride it out" → Breathe; "Talk it through" → Coach.
  7. Check-in: AUDIT-C every 90 days, with where to get help.
  8. Settings: limit, dry days, standard drink size, typical week, price, off switch.
- **Journal:** logging a drink ticks the Alcohol tag for that evening, so the existing "What
  affects you" keeps working; the Alcohol row links to Drinks.
- **Settings → Metrics → Drinks** opens the same screen.
- **Food log:** a meal that names a drink also creates a drink entry (linked to the meal, deleted
  with it), so nobody logs twice. A drink logged on its own within 30 minutes of the same type is
  not duplicated.
- **Coach:** sees this week, the plan and last night, and can log a drink, read the summary and set
  tonight's plan. Tone rules in the prompt (below).
- **Weekly report:** one extra line when enabled ("4 drinks, 4 dry days").

### Tone and safety

- **Neutral words.** "Drinks", "dry days", never "relapse", "sober", "binge" in headlines. Heavy
  days (≥ 60 g alcohol, WHO's heavy episodic threshold) are stated as facts, not judgements.
- **No push to stop abruptly.** Stopping suddenly after heavy daily drinking can be dangerous
  (withdrawal). The screen, the AUDIT-C result and the coach prompt all say: cut down gradually,
  and talk to a doctor if you drink most days.
- **Help is one tap away**: CAPS AD (Brazil's public alcohol and drug service), Alcoólicos
  Anônimos, and CVV 188 for emotional support, plus "your doctor" for everyone.
- **Private.** On the phone only; not in Health Connect (there is no alcohol record type), not in
  diagnostics exports; included in the user's own backup archive.

## 3. Data

- `drink_entries` (DB v32): `id`, `timestamp`, `day` (local start of the *evening* day, via
  `MealJournal.eveningOf`), `type`, `volumeMl`, `abvPct`, `grams`, `kcal`, `price`, `mealId`,
  `note`, `createdAt`.
- `DrinkPrefs` (SharedPreferences): enabled, weekly limit (standard drinks), dry-days target,
  grams per standard drink (10 WHO default; 8 UK unit; 14 US), typical week (baseline), price per
  drink, enabledAt, last used preset, tonight's plan (date + count), AUDIT-C (date + score).
- Standard drinks are computed at read time from grams, so changing the standard size re-renders
  history consistently.

| Preset | Volume | ABV | kcal |
|---|---|---|---|
| Beer (can) | 350 ml | 5 % | 150 |
| Beer (600 ml bottle) | 600 ml | 5 % | 255 |
| Draft (chopp) | 300 ml | 5 % | 130 |
| Wine (glass) | 150 ml | 12 % | 125 |
| Spirit (shot) | 40 ml | 40 % | 95 |
| Caipirinha | 50 ml cachaça | 40 % | 240 |
| Cocktail | 50 ml spirit | 40 % | 200 |

Grams = ml × ABV × 0.789.

## 4. Logic (pure, unit-tested: `DrinkInsights`)

- **Week:** ISO week from Monday; total standard drinks, drinking days, dry days so far (days
  elapsed − drinking days), heavy days.
- **Streaks:** current and best run of dry days since the feature was enabled (days before
  `enabledAt` are unknown, not dry).
- **Savings:** per completed week since `enabledAt`, max(0, baseline − actual) × price and × kcal
  per drink (from the user's own average drink, or 150 kcal).
- **Morning after:** last night vs the median of dry nights in the last 60: HRV %, resting HR,
  minutes asleep. Needs ≥ 3 dry nights.
- **Dose:** nights after 1–2 and after 3+ standard drinks vs dry nights; a group needs ≥ 3 nights.
- **Recovery:** for each drinking evening followed by dry ones, days until HRV is back within 5 %
  of the dry median; average over ≥ 2 episodes.
- **AUDIT-C:** three answers scored 0–4; positive at ≥ 4 (men) or ≥ 3 (women / unspecified); ≥ 8
  "higher risk".

## 5. Build order

1. Data: entity, DAO, migration 31→32, prefs, archive.
2. `DrinkInsights` + tests.
3. Drinks screen, settings and onboarding; route; Settings → Goals entry.
4. Today: Drinks tile, morning-after line on the Last night card.
5. Food log + journal links.
6. Coach context, tools and prompt rules; weekly report line.
7. CI, merge, release.

Follow-ups (not in this change): a "drinks before this night" chip on the Sleep tab, a home-screen
widget, an evening plan reminder.

## Sources

- WHOOP: https://www.whoop.com/thelocker/alcohol-affects-body-hrv-sleep/
- Oura: https://ouraring.com/blog/how-does-alcohol-impact-oura-members/
- Sunnyside: https://healthline.com/health/sunnyside-app-review
- Reframe: https://www.choosingtherapy.com/reframe-app-review/
- Try Dry: https://alcoholchange.org.uk/blog/the-try-dry-apps-top-ten-features
- MyDrinkaware: https://drinkaware.co.uk/tools/mydrinkaware-app
- Crane et al. 2018: https://discovery.ucl.ac.uk/id/eprint/10044247/
