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
