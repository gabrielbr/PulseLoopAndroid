# Audit: Vitals in the new look (2.16.8)

Date: 2026-11-16 · PR: see the PR linking this file · Auditor: Claude Code. impeccable was run with
the skill loaded (context with PRODUCT.md, `audit.native.md`, `critique.md`, `craft-floor.md`,
`android.md`); ux-user-flows, graphic-design-fundamentals and less-is-more were applied by hand from
their SKILL.md files.

## Scope

Vitals, behind Debug → New look. Today's pattern (statement first, calm cards) carried over, but
the charts stay: Vitals is the tab opened for the why, and each vital's chart or gauge is that
answer. `VitalCard` takes the calm chrome (12dp radius, hairline, 20dp padding, sentence-case
title and verdict, Inter 32sp value, a quiet "Estimated" badge). The status line becomes the
screen's statement ("Last night" / "All within your usual range" or "Outside your usual: HRV").
Page colour, 20dp gutter, 720dp cap and `CalmTypography` for the tab. Measuring is unchanged.

## ux-user-flows (§13 flow review checklist)

No flow changes: the same entry (Vitals tab), the same Measure control and sheet, the same cards
opening the same detail screens, the same Customize cards. States checked: loading (statement
holds its line, no cards before the first load), empty cards keep their "No … yet" text,
measuring, failed measurement, estimated values.

## impeccable

### Audit (native, from source; no emulator in this environment)

| # | Dimension | Score | Key finding |
|---|---|---|---|
| 1 | Accessibility | 3 | Verdict now a sentence-case word beside the coloured value; device check at font scale 1.3 owed. |
| 2 | Performance | 4 | Same lists and charts; no new work. |
| 3 | Appearance & theming | 3 | Cards and statement converted; Measure button and sheet, My Health card and Customize still old (NL-4). |
| 4 | Platform conformance | 4 | Unchanged controls. |
| 5 | Adaptivity | 4 | 720dp cap added. |
| | **Total** | **18/20** | |

### Critique (design review only; detector not applicable, native platform)

| # | Heuristic | Score | Key issue |
|---|---|---|---|
| 1 | Visibility of status | 4 | The answer is the headline, not a caption. |
| 2 | Match real world | 3 | "Outside your usual: HRV" names the metric plainly. |
| 3 | User control | 3 | Customize kept. |
| 4 | Consistency | 3 | Matches Today and Symptom Radar; old Measure button sits beside the new statement (NL-4). |
| 5 | Error prevention | 3 | Unchanged. |
| 6 | Recognition | 4 | Metric colour dots kept. |
| 7 | Flexibility | 3 | Unchanged. |
| 8 | Minimalist | 3 | Caps labels and dithered chip gone; charts kept on purpose. |
| 9 | Error recovery | 3 | Measurement failure copy unchanged. |
| 10 | Help | 3 | Info texts unchanged. |
| | **Total** | **32/40** | Good |

No P0 or P1. [P2] NL-4 extended: Measure button and sheet, My Health entry card.

## graphic-design-fundamentals (§13 checklist)

| # | Question | Result |
|---|---|---|
| 1 | Brief | The answer first, each vital's chart as the evidence. |
| 3 | Hierarchy | 20sp statement → 32sp value → chart. |
| 4 | Contrast | Calm tokens (text 17.3:1, secondary 7.0:1, muted 5.3:1 on card). |
| 5–6 | Alignment, spacing | 20dp gutter and card padding; 12dp between cards. |
| 7 | Typography | Inter on the tab; sizes on the scale. |
| 8 | Colour | Zone colours with the verdict's word; amber only for estimates and warnings. |
| 9 | Consistency | Same card as Today's rows and the radar. |
| 11 | Real-size test | pt-BR "Fora do seu normal: temperatura, VFC" wraps to three lines at most. |
| 13 | Production | No new assets. |

## less-is-more

| Element | Question (1–5) | Kept / moved / merged / removed, and where |
|---|---|---|
| Caps eyebrow titles and verdicts | 4 | Sentence case. |
| Dithered ESTIMATED chip | 4 | Quiet badge; the word stays. |
| Charts and gauges | 1 (helps now) | Kept: they are the tab's reason to exist. |

Taps on common actions unchanged (Measure 1, open a vital 1).

## Evidence

pt-BR missing-key check: none missing for new strings. No device here; on-device check owed.
Design canvas: the Vitals board in the new look is owed (canvas tools not connected this session).

## Verdict

Merge (behind the switch). Carried forward: NL-4 (old pieces inside converted tabs), the canvas
boards for Today and Vitals, the on-device check.
