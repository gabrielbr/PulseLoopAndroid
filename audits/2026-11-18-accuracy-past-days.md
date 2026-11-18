# Audit: the accuracy check grades past days

Date: 2026-11-18 · PR: see the PR linking this file · Auditor: Claude Code.
- **impeccable:** applied to the **rendered** running card (both states) and the past-days chips.
  They were drawn on the JVM with Robolectric under the screen's own calm theme, at 1.0× and 1.3×.
  The harness is local only.
- **Device check:** owed.
- **ux-user-flows, graphic-design-fundamentals, less-is-more:** applied by hand.

## Scope

The owner asked for historical data to count: "Make sure you grab historical data. This can be
valuable." A check used to grade only from the moment it started. Now it can also grade days the
app, the second ring and Health Connect already hold.

Two controls:
- **Include past days** chips in the start dialog: None, 7, 14 or 30 days, 14 by default.
- The same choice on a running check, behind one button.

Rules: `AccuracyCheckController.historyStart`, `includePastDays`; `AccuracyCheckEntity.historyFrom`
(DB v39).

## ux-user-flows (§13 flow review checklist)

- **Entry:**
  - start a check: one more chip row, already preset to 14 days;
  - a running check: "Include past days" on its card.
- **Steps:**
  1. Pick a span, then Include.
  2. The report re-scores at once (keyed on `gradeFrom`).
- **States:**
  - **Not reaching back:** the button plus one line on why it helps.
  - **Reaching back:** "Also grading the days since [date]". The button goes, because a check only
    reaches back further by starting a new one.
  - **A ring paired recently:** grading starts at pairing, so 30 days may mean fewer. The dialog
    says "Only days since this ring was paired".
  - **Ring paired today:** nothing to reach back over; the check stays as it was.
- **Exits:** Cancel in either dialog, which changes nothing.
- **Nothing irreversible:** the export pause and the day count are unchanged, and the dialog says
  so.

## impeccable

### Audit (native, on renders)

| # | Dimension | Score | Key finding |
|---|---|---|---|
| 1 | Accessibility | 4 | Chips and buttons are standard controls with text labels. |
| 2 | Performance | 3 | 30 days of reference samples is a larger Health Connect read; the page cap rose from 40 to 150 pages. |
| 3 | Appearance & theming | 4 | Calm card, Look colours, sizes on the scale. |
| 4 | Platform conformance | 4 | FilterChip, AlertDialog, `PulseButton`. |
| 5 | Adaptivity | 4 | At 1.3× the chips wrap ("30 days" moves to a second line), and the copy wraps without clipping. |
| | **Total** | **19/20** | |

### Critique

| # | Heuristic | Score | Key issue |
|---|---|---|---|
| 1 | Visibility of status | 4 | The card says from when it grades. |
| 3 | User control | 4 | Cancel leaves things as they were; reaching back never touches the export. |
| 5 | Error prevention | 4 | Days before the ring was paired are excluded. |
| 8 | Minimalist | 4 | One chip row, one button that disappears once used. |
| 10 | Help | 4 | One line on why, one on what's unaffected. |
| | **Total (of the five that apply)** | **20/20** | |

### Polish (from the renders)

- [fixed] "Include past days" and "End now" were two outlined buttons of equal weight.
  "Include past days" is now tonal, the more useful action; "End now" stays outlined.
- [P3] The card then shows two stacked buttons. Kept: side by side they would wrap in Portuguese.

No P0 or P1.

## graphic-design-fundamentals (§13 checklist)

- **Hierarchy:** title, then status, then a 12sp explainer, then the actions.
- **Colour:** no new colours; the tonal fill is `cardRaised`.
- **Consistency:** the chips match "How long" just above them in the dialog.
- **Real size:** checked at 411dp, 1.0 and 1.3.

## less-is-more

| Element | Verdict |
|---|---|
| Past-days chips | Kept: the owner's ask, and preset so starting a check takes no extra tap |
| A date picker for any start date | Left out: four chips cover it |
| Button on a running check | Kept: the owner's check may already be running; it disappears once used |
| A "re-score" step | Left out: the report re-scores by itself |

No action gained a tap: the dialog's default already reaches back 14 days.
