# New look: plan and tokens

Owner's decisions (2026-11-16): **PulseLoop only, dark only, keep mint, use Inter.** The goal is a
calmer app in the spirit of shadcn/ui and Oura: near-black surfaces, one hairline border, wide
gaps, one statement per screen, detail on tap, and a timeline for states over time (Oura's
Symptom Radar "Recently" chart).

## How it ships

- **Our own components, shadcn's style, on Material 3.** No shadcn-compose dependency: the
  primitives are small, and owning them keeps `PulseButton`, `tr()` and the type-scale test in
  force. They live in `ui/calm/`.
- **Behind a switch, screen by screen, on `main`.** `NewLook.on` (Debug → New look, off by
  default, not archived). A converted screen reads it; an unconverted one ignores it. No
  long-lived branch: each step is a normal PR and release.
- **Order:** 1 foundation + Symptom Radar (this PR) → 2 Today → 3 Vitals → 4 Sleep, Activity →
  5 Nutrition, My Health, Readiness → 6 Settings and the rest → 7 flip the default on and delete
  the old styling.

## Tokens (`ui/calm/CalmTheme.kt`)

| Token | Value | Note |
|---|---|---|
| page | `#0A0A0B` | |
| card | `#161618` | one 1dp `border` `#27272A`, no shadow |
| cardRaised | `#1E1E21` | badges, pressed rows |
| text | `#FAFAFA` | 17.3:1 on card |
| textSecondary | `#A1A1AA` | 7.0:1 on card |
| textMuted | `#8B8B94` | 5.3:1 on card, 4.9:1 on cardRaised |
| accent | `PulseColors.accent` | mint; Fairy mode still turns it pink |
| radius | 12dp | |
| gutter | 20dp | |
| section gap | 40dp | |
| type | Inter 4.1 (OFL, `assets/licenses/inter-ofl.txt`) | sizes stay on `PulseType.SCALE` |

## Components

`CalmTopBar`, `CalmCard`, `CalmSection`, `ExpandableText` ("Show more"), `UnderlineTabs`,
`LevelScale` (every state visible, the current one marked), `CalmBadge`, `CalmMetricRow`, and
`StateTimeline` (one lane per level, runs joined into bars, steps between touching runs, tap a
day for its date and level; layout in the pure, tested `StateTimelineModel`).

## Departure from the design system

This leaves the Gabriel Design System's `pulseloop` layer (Ubuntu Mono, 8dp radius, dither
wipe) on purpose. It belongs in that repo's `docs/divergences.md` with this plan; this session
couldn't reach that repo, so it is recorded here until it is moved there.

## Screens done

- **Symptom Radar** (`ui/screens/SymptomRadarScreen.kt`, route `radar`): Yesterday / Today tabs,
  the level as the headline, an expandable explanation, the three-level scale, Rest Mode, the
  biometrics under strain, and "Recently": the last 35 days as a timeline
  (`SymptomRadar.history`, computed as of each day, so a day never reads nights after it). With
  the new look on, My Health shows one row that opens it instead of the inline section.
- **Today** (`ui/screens/CalmToday.kt`, wired in `TodayScreen` behind the switch). The owner chose
  an Oura-style restructure (2026-11-16), not a restyle of the grid:
  - Readiness leads as one statement: the number (`CalmType.score`, 40sp), its band and the
    sentence that explains it. Tap opens Readiness.
  - Sleep and Activity follow as two score rows in one card (number coloured by band, the band's
    word beside it, so colour is never the only signal). Customize cards still decides whether
    each shows; while editing they join the list below so they can be hidden.
  - The coach's brief or the unusual-night alert, then the one-line pieces (detected workout,
    programs, Rest Mode) and the evening cards, as before.
  - Every other card is one full-width row: name and verdict, the value, and a small trend line
    (`CalmSparkline`, the last 60 readings, hidden from TalkBack because the value says it).
    Nutrition and Drinks keep their one-tap "+ Log meal" / "+ drink" so no common action gains
    a tap. Estimates keep their "Estimated from heart rate" note.
  - `CalmTypography` gives the whole tab Inter, including the shared pieces not yet converted.

Still in the old style inside the new Today (convert with their own screens): the program chips,
Rest Mode line, bedtime, food and tag cards, the accuracy-check banner and the Customize controls.
- **Vitals** (behind the switch in `VitalsScreen`, `VitalCard`). The pattern Today set, without
  taking the charts away: Vitals is the tab people open for the *why*, so each vital keeps its
  full chart or gauge.
  - "Last night" and the answer ("All within your usual range" / "Outside your usual: HRV") are
    the screen's statement (20sp), beside the Measure button.
  - `VitalCard` takes the calm card when the look is on: 12dp radius, hairline, 20dp padding,
    sentence-case title and verdict, the value in Inter 32sp, and a quiet "Estimated" badge
    (no dither) wherever a value is estimated.
  - Page colour, the 20dp gutter, the 720dp cap and `CalmTypography` for the whole tab.

Still old-styled inside Vitals: the Measure button and its sheet, the ring gauges' own drawing,
the My Health entry card and the Customize controls.
- **Sleep** (behind the switch in `SleepScreen`). Same structure: the night carousel, hypnogram,
  stages, records and the range views stay, because they are the answer to "why". One card
  surface for every Sleep card (`sleepCard()`), sentence-case labels (`SleepLabel`), the night's
  duration in `CalmType.score`, no dither fade on the hero, and the page colour, gutter, 720dp cap
  and `CalmTypography`.

Still old-styled inside Sleep: the range selector, the day header, the records card, the sleep
balance card and the My Health entry card.
- **Activity** (behind the switch in `ActivityScreen`). Same structure and actions. One card
  surface (`activityCard()`), metric colours on dots rather than on caps labels, Inter values,
  plain raised icon tiles on workout rows, a section title for the day's workouts, and the weekly
  goal card led by its heading. Still old-styled: the day header, the Record row's icon buttons,
  the detected-activity and cardio-load cards, the records card and the dialogs.

Every converted screen gets an impeccable pass (audit, critique, polish) before it ships; Vitals
and Sleep had theirs redone in `audits/2026-11-16-impeccable-pass-vitals-sleep-activity.md`.

