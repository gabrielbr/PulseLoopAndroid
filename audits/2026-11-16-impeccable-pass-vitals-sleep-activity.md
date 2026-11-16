# Audit: impeccable pass on Vitals and Sleep (redone), and Activity in the new look (2.16.13)

Date: 2026-11-16 · PR: see the PR linking this file · Auditor: Claude Code, with impeccable loaded
for this pass (`impeccable context` with PRODUCT.md, `audit.native.md`, `critique-rubric.md`,
`craft-floor.md`, `polish.md`, `android.md`). Native platform: design review only, detector not
applicable. No emulator here: every finding is from source; the on-device check is still owed.
ux-user-flows, graphic-design-fundamentals and less-is-more applied by hand from their SKILL.md files.

Why this file exists: the Vitals and Sleep audits were scored from source without a fresh
impeccable pass on those screens. The owner asked for a pass after every step, and for the
screens that missed one. This is that pass, plus Activity's.

## Vitals

### Findings and fixes
| Sev | Finding | Fix |
|---|---|---|
| P1 | The statement and the Today alert lower-cased every signal name, so "HRV" read "hrv" (Match real world) | `OffSignal.inSentence` keeps acronyms; used in Vitals (both looks) and Today's alert (both looks) |
| P2 | Card title and verdict were the same 16sp semibold, so they competed (hierarchy) | Verdict is `CalmType.bodySmall` semibold, coloured, a step under the title |
| P2 | The gauge's centre verdict stayed in tracked caps in the new look | Sentence case, no tracking, when the look is on |
| P2 | Measure button and sheet still old-styled (NL-4) | Carried forward |

### Scores after the fixes
Audit: Accessibility 3 · Performance 4 · Theming 3 · Conformance 4 · Adaptivity 4 = **18/20**.
Critique: Status 4 · Real world 4 · Control 3 · Consistency 3 · Errors 3 · Recognition 4 ·
Flexibility 3 · Minimalist 3 · Recovery 3 · Help 3 = **33/40**.
Cognitive load: 1 failure (the Measure sheet can offer up to six legs at once).

## Sleep

### Findings and fixes
| Sev | Finding | Fix |
|---|---|---|
| P1 | Visualization cards put a label ("Stages") over their heading ("Sleep architecture"): a kicker, which the craft floor bans | New look leads with the heading and puts the category under it |
| P2 | Range selector, day header, records and balance cards still old-styled (NL-4) | Carried forward |
| P3 | The hypnogram legend is 12sp and wraps under the title in pt-BR | Acceptable; it wraps rather than clips |

### Scores after the fixes
Audit: 3 · 4 · 3 · 4 · 4 = **18/20**. Critique: 4 · 3 · 3 · 3 · 3 · 4 · 3 · 3 · 3 · 3 = **32/40**.
Cognitive load: 0 failures.

## Activity (new in this PR, behind Debug → New look)

### What changed
Same structure and actions (Record, history, Log Past Activity, the day's workouts, cardio load,
weekly goal, records). In the new look: one card surface (`activityCard()`); summary metrics show
the metric's colour on a dot and the name as text, values in Inter 32sp; workout rows lose the
dithered icon tile for a plain raised tile, type name at 16sp; the day's list is headed by a 20sp
section title ("Today", "Yesterday", a date) instead of tracked caps; the weekly goal card's
heading is "Weekly goal" with the count under it (no kicker); page colour, gutter, 720dp cap,
`CalmTypography`.

### Findings
| Sev | Finding | Status |
|---|---|---|
| P1 | "WEEKLY GOAL" kicker over the count | Fixed: heading then count |
| P2 | Summary labels were coloured caps text: colour carried the label | Fixed: colour on a dot, name in secondary text |
| P2 | Day header, Record row's icon buttons, detected-activity card, cardio load, records card, history and type-picker dialogs still old-styled | NL-4, carried forward |
| P3 | The rings beside the summary repeat the three numbers as progress | Kept: progress against goal is new information |

### Scores
Audit: Accessibility 3 · Performance 4 · Theming 3 · Conformance 4 · Adaptivity 4 = **18/20**.
Critique: 4 · 3 · 3 · 3 · 3 · 4 · 3 · 3 · 3 · 3 = **32/40**. Cognitive load: 1 failure (the Record row
offers three actions side by side; kept, all three are frequent).

## ux-user-flows
No flow changes on any of the three: same entries, steps, states and exits. Activity's day-list
fix (previous PR) already covered its states.

## graphic-design-fundamentals
Hierarchy: one statement per screen, then cards; no kickers over headings left in converted cards.
Contrast: calm tokens (17.3 / 7.0 / 5.3:1 on card). Typography: Inter, sizes on the scale.
Colour: metric colours on dots and charts, not on labels. Consistency: one card surface per tab.

## less-is-more
Removed in the new look: tracked-caps eyebrows, dithered icon tiles, the kicker labels. Nothing
added. Taps on common actions unchanged.

## Evidence
pt-BR: "Weekly goal" → "Meta semanal" added; the caption lists Activity. No device here.
Canvas boards for Today, Vitals, Sleep and Activity in the new look: owed (canvas tools not
connected this session).

## Verdict
Merge (behind the switch). Carried forward: NL-4, canvas boards, on-device check.
