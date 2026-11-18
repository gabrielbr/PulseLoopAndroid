# Audit: the accuracy check grades both rings

Date: 2026-11-18 · PR: see the PR linking this file · Auditor: Claude Code.
- **impeccable:** loaded and applied to the **rendered** card. It was drawn on the JVM with
  Robolectric, using five days of sample results for both rings, at 1.0× and 1.3×. The harness is
  local only.
- **Device check:** owed.
- **ux-user-flows, graphic-design-fundamentals, less-is-more:** applied by hand.

## Scope

The owner wears a JRing (main) and a Colmi R06 (comparison) with a Huawei watch, and asked for the
accuracy check to grade both rings against the watch. Scoring: `AccuracyScorer.scoreComparisonRing`,
`ComparisonRingScoring`. UI: one new card at the top of the check's report, "Both rings against
[reference]". Nothing else on the screen changes.

## ux-user-flows (§13 flow review checklist)

- **Entry:** unchanged. Start a check as before; open its report.
- **Steps:** none added. The second ring is graded automatically when one was worn during the
  check.
- **States:**
  - no second ring, or none with valid rows: the card is absent and the report is as before;
  - not enough days from both: "Not enough days from both rings yet…";
  - a measure only one ring has: "—" for the other;
  - a running check: the card fills as days arrive, like the rest.
- **Exits:** unchanged.

## impeccable

### Audit (native, on renders)

| # | Dimension | Score | Key finding |
|---|---|---|---|
| 1 | Accessibility | 3 | Each row reads as one sentence naming both gaps and which ring is closer; the closer value is bold as well as brighter, so colour is never the only cue. TalkBack on device owed. |
| 2 | Performance | 4 | The summaries are computed from rows already loaded for the report. |
| 3 | Appearance & theming | 4 | `AccuracyCard`, Look tokens, sizes on the scale. |
| 4 | Platform conformance | 4 | Plain rows, no new controls. |
| 5 | Adaptivity | 4 | Fixed 104dp value columns, single-line values; the labels wrap. Checked at 1.3×. |
| | **Total** | **19/20** | |

### Critique

| # | Heuristic | Score | Key issue |
|---|---|---|---|
| 1 | Visibility of status | 4 | The tally answers the question asked. |
| 2 | Match real world | 4 | "Typical gap", "lower is closer". |
| 4 | Consistency | 4 | Same metric names and units as the cards below. |
| 8 | Minimalist | 4 | One card; the detailed cards stay the main ring's. |
| 10 | Help | 3 | Says what the numbers are; the report's own subtitle covers "not a lab measurement". |
| | **Total (of the five that apply)** | **19/20** | |

### Polish (from the renders)

- [fixed] At 1.3× "22.0 bpm" wrapped inside an 88dp column. The columns are 104dp now and the
  values stay on one line.
- [P3] The column heads say "Main" and "Second" rather than ring names; the names live on Compare
  rings. Fine for one owner with two rings.

No P0 or P1.

## graphic-design-fundamentals (§13 checklist)

- **Hierarchy:** title 16sp bold, then a 12sp explainer, the 14sp rows, and a 14sp tally.
- **Alignment:** two right-aligned numeric columns, matching across rows.
- **Colour:** no new colours; the closer value uses full-strength text, the other the secondary
  colour.
- **Real size:** checked at 411dp, 1.0 and 1.3.

## less-is-more

| Element | Verdict |
|---|---|
| Second ring's per-measure cards and day detail | Left out: the card answers "which is closer"; detail stays on the main ring's cards |
| Second ring's corrections | Left out on purpose: its numbers aren't used anywhere |
| A winner per measure | Shown as bold, plus one tally line, not badges |

No action gained a tap.
