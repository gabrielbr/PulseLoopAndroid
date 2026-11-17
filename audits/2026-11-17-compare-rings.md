# Audit: Compare rings

Date: 2026-11-17 · PR: see the PR linking this file · Auditor: Claude Code. impeccable was loaded
(context, `craft-floor.md`, `android.md`) and applied to **rendered** cards. The rings card, the day
header, heart rate, totals and workouts were drawn on the JVM with Robolectric, with two rings'
worth of sample data, at font scale 1.0 and 1.3. The harness is local only. The device check is
owed (no emulator here). ux-user-flows, graphic-design-fundamentals and less-is-more were applied
by hand from their SKILL.md files.

## Scope

A new screen, Settings → My ring → Compare rings (`settings/compare`), plus its row on the hub. The
owner's request: keep using the main ring for everything, and wear a second one only to see which
is more reliable. Design and data rules: `docs/ring-comparison.md`.

## ux-user-flows (§13 flow review checklist)

- **Entry:** the Settings hub row "Compare rings", whose trailing value reads Connected / On when a
  second ring is set up.
- **Not set up:**
  1. One card: what this is, and the promise that the second ring's data stays apart.
  2. "Find a ring".
  3. The scan lists nearby rings, excluding the main ring, with a "keep it close, close the vendor
     app" hint while the list is empty.
  4. Tap a ring to connect it. The screen then switches to the comparing state when the ring
     connects.
- **Comparing:**
  1. The rings card: each ring's role, name, line style, status and battery, and "Stop comparing"
     behind a confirm dialog that says what it does and doesn't do.
  2. A day switcher.
  3. Heart rate.
  4. Totals.
  5. Workouts, if any that day.
  6. The "neither is known to be right" note, with a link to the accuracy check.
- **States:**
  - either ring disconnected (status shows it; its numbers read "—");
  - no heart rate from either ring that day;
  - no overlapping readings (the agreement sentence says so instead of a number);
  - no workouts (the card is hidden);
  - future days unreachable (the next arrow is disabled at today).
- **Exits:** Back; Stop comparing (returns to the empty state); Open the accuracy check.
- **Checklist:** every state has words; no dead end; no data-destroying action without a confirm.
  Stop comparing keeps the stored readings.

## impeccable

### Audit (native, on renders)

| # | Dimension | Score | Key finding |
|---|---|---|---|
| 1 | Accessibility | 3 | Each comparison row reads as one sentence ("Average: main 78, comparison 73 bpm"); the column swatches carry names for TalkBack; the chart has a description; rings differ by line style as well as colour. Device TalkBack pass owed. |
| 2 | Performance | 4 | One day of rows per load; the chart buckets to 5-minute means; a 30 s refresh while open. |
| 3 | Appearance & theming | 4 | Look tokens, calm cards, sizes on the scale. |
| 4 | Platform conformance | 4 | Scaffold, top bar, AlertDialog, `PulseButton` throughout. |
| 5 | Adaptivity | 4 | 1.3× renders without clipping after the polish below; 720dp cap from the scaffold. |
| | **Total** | **19/20** | |

### Critique

| # | Heuristic | Score | Key issue |
|---|---|---|---|
| 1 | Visibility of status | 4 | Both rings' connection and battery on top. |
| 2 | Match real world | 4 | "Hours with readings", "typically 3 bpm apart", not statistics jargon. |
| 3 | User control | 4 | Stop comparing, with a confirm. |
| 4 | Consistency | 4 | Same day switcher, cards and type as the other converted screens. |
| 5 | Error prevention | 4 | The main ring is excluded from the scan list. |
| 6 | Recognition | 4 | Each ring keeps one colour and one line style everywhere on the screen. |
| 7 | Flexibility | 3 | Day by day only; no week view yet. |
| 8 | Minimalist | 4 | Three cards; SpO₂/HRV left out until they're needed. |
| 9 | Error recovery | 3 | Scan hint covers the common failure (vendor app holding the ring). |
| 10 | Help | 4 | States that neither ring is ground truth and where to get one. |
| | **Total** | **37/40** | Excellent |

### Polish (from the renders)

- [fixed] At 1.3× the "Comparison" column heading truncated, and Portuguese would truncate at 1.0×.
  The column heads are now the two line swatches (named for TalkBack), matching the rings card.
- [fixed] Workouts crammed the workout's name into the first row ("Gym · 7:00 AM · avg") with
  lowercase fragments below. Each workout now has its own title line, then Average / Highest /
  Minutes with readings.
- [P3] The day average and the paired agreement can disagree in direction (a streamed workout on
  one ring raises its average but has no pair on the other). The sentence names the paired figure,
  so it stays truthful.

No P0 or P1.

## graphic-design-fundamentals (§13 checklist)

- **Hierarchy:** card titles 16sp semibold; values 16sp semibold; labels 14sp; notes 12–14sp.
- **Colour:**
  - The main ring uses the heart-rate coral, solid; the comparison ring uses the info cyan,
    dashed.
  - Mint appears only on the scan list's "Compare" action and the tonal button.
  - Status uses `success`.
- **Alignment:** a label column plus two fixed 96dp value columns, right-aligned, so numbers line up
  across cards.
- **Contrast:** calm neutrals; the line colours are drawn on the card, not used as text.
- **Real size:** checked at 411dp, 1.0 and 1.3.

## less-is-more

| Element | Verdict |
|---|---|
| Per-ring averages, coverage, agreement | Kept: the three numbers that answer "which is more reliable" |
| SpO₂ / HRV / temperature comparison | Left out for now; stored, shown when asked |
| A week view | Left out; day switcher covers the first use |
| Column names in each table | Replaced by the line swatches the rings card already names |

No existing action gained a tap: the hub gained one row in My ring.
