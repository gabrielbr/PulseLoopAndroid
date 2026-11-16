# Audit: new look foundation and Symptom Radar screen (2.16.5)

Date: 2026-11-16 · PR: see the PR linking this file · Auditor: Claude Code (ux-user-flows, graphic-design-fundamentals and less-is-more applied by hand from their SKILL.md files; impeccable run with the skill loaded)

## Scope

- **New look foundation, behind Debug → New look (off by default).** Tokens in `ui/calm/CalmTheme.kt`:
  page `#0A0A0B`, card `#161618`, one `#27272A` hairline border, text `#FAFAFA` / `#A1A1AA` /
  `#8B8B94`, mint accent from `PulseColors`, 12dp radius, 20dp gutter, 40dp section gap, Inter 4.1
  (OFL). Components: `CalmTopBar`, `CalmCard`, `CalmSection`, `ExpandableText`, `UnderlineTabs`,
  `LevelScale`, `CalmBadge`, `CalmMetricRow`, `StateTimeline`.
- **Symptom Radar gets its own screen** (route `radar`). With the new look on, My Health shows
  one row ("Symptom Radar · Minor signs") that opens it, in place of the inline section. With it
  off, nothing changes.
- **"Recently" timeline:** the last 35 days, each computed as of that day (`SymptomRadar.history`).
- Departure from the Gabriel Design System (font, radius, no dither) is deliberate and recorded in
  `docs/new-look-plan.md`; it moves to the design-system repo's `docs/divergences.md`.

## ux-user-flows (§13 flow review checklist)

Flow: My Health → Symptom Radar row → Radar screen (Today tab) → [Yesterday tab] → [Show more] →
[Turn on Rest Mode] → [tap a day on Recently] → Back.

| # | Question | Result |
|---|---|---|
| 1 | Job | "Is something straining my body, and has it been building?" |
| 2 | Entries | One: the My Health row. The header's radar line already opens What changed; unchanged. |
| 3 | Happy path | Open → read the headline. One tap from My Health. |
| 4 | Branches | Today / Yesterday; no night that day; baseline under 14 nights; no flagged signal. |
| 5 | States | Loading (line after 400 ms), load failed (retry), no night, learning (n of 14), result, Rest Mode on/off. |
| 6 | Errors | Load failure says so and offers Try again. |
| 7 | Control | Back at any time; Rest Mode turns off from the same button. |
| 8 | Progress and resume | Tab survives rotation (`rememberSaveable`). |
| 9 | Memory | Rest Mode state is the existing `RestMode` preference. |
| 10 | Exits | Back to My Health. |
| 11 | Conventions | Underline tabs and the timeline follow Oura's pattern the owner pointed to. |
| 12 | Speed | Same single repository read My Health already does. |
| 13 | Accessibility | Tabs carry `Role.Tab`; the timeline has a content description summarising counts per level and the selected day. |
| 14 | Ethics | "Not a diagnosis…" stays on the screen, unchanged wording. |
| 15 | Evidence | The owner's Oura screenshot and request (2026-11-16). |

Issues: important — a day with no recorded night first read as "Learning your normal: 0 nights".
Fixed: it now says "No night to read" and why.

## graphic-design-fundamentals (§13 checklist)

| # | Question | Result |
|---|---|---|
| 1 | Brief | One statement, the scale it sits on, the cause, the history; nothing else. |
| 2 | Concept | Calm: near-black, one border, wide gaps, a single type family. |
| 3 | Hierarchy | 32sp headline → 16sp explanation (2 lines, expandable) → scale → 20sp section titles. |
| 4 | Contrast | Text 17.3:1, secondary 7.0:1, muted 5.3:1 on card (4.9:1 on cardRaised). |
| 5 | Alignment and grid | One 20dp gutter; every block left-aligned to it. |
| 6 | Spacing | 40dp between sections, 16dp inside; the timeline gets its own section. |
| 7 | Typography | Inter in four weights; sizes 12/14/16/20/28/32 only, all on `PulseType.SCALE`. |
| 8 | Colour | Mint only on Fairy/accent paths; levels use info / warning / danger, the existing semantic colours. |
| 9 | Consistency | The scale, the timeline lanes and the headline name the levels with the same labels. |
| 10 | Simplicity | Five "Normal" rows are gone: only flagged signals are listed. |
| 11 | Real-size test | pt-BR "Sinais fortes" fits the 96dp lane-label column at 12sp; the headline wraps. |
| 12 | Accessibility and ergonomics | Tabs, "Show more" and the Rest Mode button are at least 48dp tall; the button 52dp. |
| 13 | Production | Inter 4.1 static TTFs (four files), licence shipped in `assets/licenses/`. |

## impeccable (audit, native / Android)

Run with the vendored skill loaded (`impeccable context`, `audit.native.md`, `critique.md`, `polish.md`,
`craft-floor.md`, `android.md`). No PRODUCT.md or DESIGN.md exists, so the code and
`docs/new-look-plan.md` were the context. No Android SDK or emulator in this environment: the pass is
from source and the canvas mock, not device screenshots. The device check (dark, font scale 1.3,
TalkBack) is owed on the release build.

### Audit, before → after the polish pass

| # | Dimension | Before | After | Key finding |
|---|---|---|---|---|
| 1 | Accessibility | 2 | 3 | Timeline days were tap-only and ~7dp wide; label column fixed at 96dp clipped at large font scale. Now: drag to scrub, TalkBack "Previous day / Next day" actions with a polite live region, column measured from the labels. |
| 2 | Performance | 4 | 4 | One repository read, 35 pure computes, one Canvas. |
| 3 | Appearance & theming | 3 | 4 | Rest Mode was Material's pill with the mint outline, off the new look. Now `CalmOutlinedButton` (12dp radius, hairline border, still a `PulseButton`). |
| 4 | Platform conformance | 4 | 4 | M3 top bar, system Back, Material button underneath. |
| 5 | Adaptivity | 2 | 3 | No width cap: on a tablet the text ran 1000dp+ wide. Now capped at 720dp and centred like the tabs. |
| | **Total** | **15/20** | **18/20** | |

### Critique (design review only; detector not applicable, native platform)

| # | Heuristic | Score | Key issue |
|---|---|---|---|
| 1 | Visibility of status | 3 | Headline and scale say the level; the timeline's selected day is shown. |
| 2 | Match real world | 3 | "Inactivity · Lower" read as *less* inactive: fixed to "Fewer steps". |
| 3 | User control | 3 | Rest Mode is reversible from the same button. |
| 4 | Consistency | 3 | Signal detail was English-only beside translated text: now `trf`, pt-BR added. |
| 5 | Error prevention | 3 | No destructive action on the screen. |
| 6 | Recognition | 4 | Every level visible on the scale and the lanes. |
| 7 | Flexibility | 2 | Tabs and timeline don't talk: picking Yesterday doesn't select it on the chart (P3, NL-3). |
| 8 | Minimalist | 3 | Only flagged signals; explanation folded to two lines. |
| 9 | Error recovery | 3 | Load failure says so and retries. |
| 10 | Help | 3 | Explanation and the not-a-diagnosis line are on the screen. |
| | **Total** | **30/40** | Good |

Design specificity: the lanes-and-steps timeline and the three-dot scale are the radar's own
shapes, not generic cards. The rest is deliberately quiet (Operate mode).

Priority issues found and fixed: [P1] timeline unusable with TalkBack and hard to aim;
[P1] "Inactivity · Lower" contradicts itself; [P2] untranslated signal detail; [P2] off-look pill
button; [P2] no width cap. Left: [P3] NL-3 tab ↔ timeline selection; [P3] temperature shown in °C
regardless of unit setting (pre-existing, the radar's own model).

## less-is-more

| Element | Question (1–5) | Kept / moved / merged / removed, and where |
|---|---|---|
| Five per-metric "Normal" rows | 4 (can it be cut?) | Removed; "Nothing is outside your usual range." when none is flagged. |
| Rest Mode switch row | 2 (right form?) | Now one outlined button with its state in the label. |
| Long explanation | 3 (does it need to show now?) | Two lines, "Show more" for the rest. |
| Timeline | 1 (does it earn its place?) | Kept: it is the feature the owner asked for, and it replaces guessing from today alone. |

| Dimension | 0–4 |
|---|---|
| Elements per screen, compared with before | My Health: a section becomes one row (−) |
| Duplicates left | 0 |
| Text to read when tired | 4 |
| Taps on the common actions (must not rise) | Reading the radar unchanged (one tap, My Health). Rest Mode from My Health rises 1→2 with the new look on; its main door, Today's Rest Mode line, is unchanged at 1. |

## Evidence

- No change to the design-system tokens (`GTokens.kt`), so `drift.py` doesn't apply; the new
  tokens are this app's, recorded in `docs/new-look-plan.md`.
- Contrast computed with the WCAG formula (values above).
- Tests: `StateTimelineModelTest`; `HealthInsightsTest` radar history case.
- pt-BR missing-key check: no new key missing.
- Design canvas: see the PR description.

## Verdict

Merge. NL-1 (per-day TalkBack) and NL-2 (width cap) were fixed in the impeccable polish pass. Carried
forward: NL-3 tab ↔ timeline selection; the on-device check; the divergence to be recorded in the
design-system repo.
