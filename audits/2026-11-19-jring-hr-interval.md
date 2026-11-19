# Audit: JRing heart-rate interval

Date: 2026-11-19 · PR: see the PR linking this file · Auditor: Claude Code.
- **impeccable:** applied to the **rendered** Measurement frequency screen for a JRing. It was
  drawn on the JVM with Robolectric, under the app theme with the new look on, at 1.0× and 1.3×.
  The harness is local only.
- **Device check:** owed.
- **ux-user-flows, graphic-design-fundamentals, less-is-more:** applied by hand.

## Scope

The owner asked for a 15-minute option for the JRing's background heart rate. Settings → My ring →
Measurement frequency now appears for a JRing whose capability reply sets bit 18 (the vendor app's
own condition). For a JRing the screen shows:
- the All-day heart rate switch;
- a slider with the vendor's four choices, 15, 30, 45 and 60 minutes;
- one line on what a reading is.

Nothing changes for other rings.

## ux-user-flows (§13 flow review checklist)

- **Entry:** Settings → My ring → Measurement frequency. The row was absent for every JRing until
  now.
- **Steps:** pick an interval, then Save. "Saved & sent to ring ✓" when connected, otherwise "Saved —
  will sync on next connect" (existing states).
- **States:**
  - **Never saved:** shows 30 min, the vendor default and what the app always sent.
  - **Saved:** the saved value, snapped to the nearest stop.
  - **Switched off:** the slider hides; the ring is told background heart rate is off.
  - **A ring without bit 18:** no row; the app keeps sending 30.
- **Exits:** Back without saving changes nothing.

## impeccable

### Audit (native, on renders)

| # | Dimension | Score | Key finding |
|---|---|---|---|
| 1 | Accessibility | 4 | Standard Slider and Switch; the value is also text ("Every 30 min"). |
| 2 | Performance | 4 | No new reads. |
| 3 | Appearance & theming | 4 | Existing card and controls; one 12sp note. |
| 4 | Platform conformance | 4 | Four discrete slider stops, matching the four choices. |
| 5 | Adaptivity | 4 | At 1.3× the note wraps to three lines; nothing clips. |
| | **Total** | **20/20** | |

### Critique

| # | Heuristic | Score | Key issue |
|---|---|---|---|
| 1 | Visibility of status | 4 | Value shown above the slider; save confirmation unchanged. |
| 2 | Match real world | 4 | Minutes, as the vendor app shows them. |
| 5 | Error prevention | 4 | Only values the vendor offers can be chosen. |
| 8 | Minimalist | 4 | Switches the ring can't act on are hidden. |
| 10 | Help | 4 | One line: what a reading is, and that workouts measure continuously. |
| | **Total (of the five that apply)** | **20/20** | |

### Polish

- [P3] The 5-min Colmi steps and the 15-min JRing steps share one slider component; tick spacing
  differs by ring, which is right for each ring.

No P0 or P1.

## graphic-design-fundamentals (§13 checklist)

- **Hierarchy:** title, explainer, switch, value, slider, note, Save. Unchanged order.
- **Colour:** no new colours.
- **Real size:** checked at 411dp, 1.0 and 1.3.

## less-is-more

| Element | Verdict |
|---|---|
| SpO₂ / stress / HRV / temperature switches for a JRing | Cut: the ring has no command for them |
| Intervals under 15 min | Left out: the vendor never offers them |
| The note | Kept: answers "why only every 15 min?" and "what about workouts?" |

No action gained a tap.
