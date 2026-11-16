# Audit: workout detail in two tabs, and the ring's workout heart rate

Date: 2026-11-16 · PR: see the PR linking this file · Auditor: Claude Code. impeccable was run with
the skill loaded (`impeccable context --target WorkoutSummaryScreen.kt`, `craft-floor.md`,
`android.md`), on **rendered** screens: both tabs were drawn on the JVM with Robolectric native
graphics, in both looks and at font scale 1.3. That harness was local and is not committed. No
emulator in this environment (no KVM), so the device check is still owed. ux-user-flows,
graphic-design-fundamentals and less-is-more were applied by hand from their SKILL.md files.

## What prompted it

The owner compared one gym session (12 Nov) against a Huawei watch:

| | Watch | PulseLoop before |
|---|---|---|
| Average HR | 116 | 76 |
| Max HR | 149 | 97 |
| Calories | 315 active / 379 total | 297 |
| Training load | 34 | none |

Cause, from the vendor app (`docs/jring-56ff-plan.md`, "Workout heart rate"): the workout started
the ring's 180-second spot check, so only the warm-up streamed. Fixed by sending the vendor's own
continuous sport-mode start. The summary also averaged per row, so a 1 Hz stream outvoted the
ring's log; it is now weighted by time (`WorkoutHeart`).

## Scope

`activity_detail/{id}` (Workout). Before: a centred icon and title, a three-figure band, a grid of
small tiles (Calories, Avg/Max/Min HR, SpO₂), then the map and splits.

After: one header line (icon, type, date and time), then two tabs.
- **Summary**: total calories as the one large number, with active and resting under it. Then a
  list: duration, average heart rate, and training load marked "Estimated from your heart rate".
  GPS workouts keep distance with pace or speed, the map and the splits here.
- **Heart rate**: average and maximum, the session's curve with a labelled dashed average, a note
  when readings cover under 60% of the session, then minutes in each zone with each zone's bpm
  range. An empty state says the ring sent nothing and what to do next time.

Styled through `Look`, so it follows whichever look is on, with `CalmTypography` in the new one.

## ux-user-flows (§13 flow review checklist)

- **Entry:** Activity tab → a workout row, or straight after finishing or logging one. Unchanged.
- **Steps:** one view with two tabs. Summary is the default because "how much did it burn / how
  hard was it" is the question asked on arrival. The tab is kept across rotation (`rememberSaveable`).
- **States:** loading (blank page, as before, no flash of the empty state), missing workout
  (unchanged), no heart rate (Heart rate tab says so and why), partial heart rate (coverage note),
  no profile (active/resting line hidden; load and zones use age 30 and resting 60), GPS / not GPS,
  accuracy corrections on (caption at the bottom of both tabs).
- **Exits:** Back, Edit, Delete, Share GPX. Unchanged, still in the top bar.
- **Checklist:** every state reachable and named; no dead end; no new decisions asked of the user;
  taps to the common answer unchanged (0 for calories, 1 for zones, which was not shown before).

## impeccable

### Audit (native, on renders)

| # | Dimension | Score | Key finding |
|---|---|---|---|
| 1 | Accessibility | 3 | Tabs are `Role.Tab` and 48dp; each zone row reads as one sentence; the chart has a description. Colour is never alone: every zone carries its name and range. Device TalkBack pass owed. |
| 2 | Performance | 4 | The curve is bucketed to 15 s, so a 1 Hz stream draws ~180 points, not thousands. Figures computed once per load (`remember`). |
| 3 | Appearance & theming | 4 | Look tokens only; sizes on `PulseType.SCALE`; 12/8dp radii per look. |
| 4 | Platform conformance | 4 | Material tab semantics, top-app-bar actions kept, system Back unchanged. |
| 5 | Adaptivity | 3 | 1.3× renders without clipping. Landscape keeps the single column (no rail change needed). |
| | **Total** | **18/20** | |

### Critique

| # | Heuristic | Score | Key issue |
|---|---|---|---|
| 1 | Visibility of status | 4 | Coverage note says when the heart rate is partial instead of presenting it as whole. |
| 2 | Match real world | 4 | Same split as the watch the owner compares with: total, active, resting, load. |
| 3 | User control | 3 | Unchanged actions. |
| 4 | Consistency | 3 | Zones reuse `HeartRateZones`, as the live workout screen does. Top bar is still the old `PulseTopBar` (NL-4). |
| 5 | Error prevention | 3 | n/a |
| 6 | Recognition | 4 | Values labelled in words, not caps tiles. |
| 7 | Flexibility | 3 | n/a |
| 8 | Minimalist | 4 | Min HR and SpO₂ dropped (see less-is-more). |
| 9 | Error recovery | 3 | The empty state names the cause and the next step. |
| 10 | Help | 3 | Load and zones each say what they are estimated from. |
| | **Total** | **34/40** | Good |

### Polish (from the renders)

- [fixed] The dashed average line was unlabelled; it now carries "avg 111" at its right end.
- [fixed] The craft-floor ban on an eyebrow over a heading: the old CAPS tile labels are gone, and
  "Time in each zone" is a plain section title.
- [P2] `PulseTopBar` is the old look's bar; it moves with NL-4 like the other pushed screens.

No P0 or P1.

## graphic-design-fundamentals (§13 checklist)

- **Hierarchy:** one dominant figure per tab (40sp kcal; 28sp average and maximum), then 20sp
  values in rows, then 12sp notes.
- **Contrast:** text on card uses `text`/`textSecondary`/`textMuted`, all ≥ 4.5:1 per the tokens.
- **Alignment and grid:** 16dp gutters; labels left, values right in one column.
- **Spacing:** 16dp between cards, 12–14dp inside; more above the zone title than below.
- **Colour:** zone colours are the data palette (`zoneGood`, `zoneBorderline`, `zoneElevated`,
  `zoneHigh`), not mint; heart rate line in the heart-rate metric colour.
- **Consistency / simplicity / real size:** checked at 411dp and 1.3×.

## less-is-more

| Element | Helps now? | Duplicate? | Understood once? | Weak? | Rare? | Verdict |
|---|---|---|---|---|---|---|
| Big centred icon (72dp) | No | Type name says it | Yes | Yes | No | Shrunk to 48dp beside the title |
| Total kcal | Yes | — | Yes | — | — | Lead figure |
| Active · resting | Yes | — | Yes | — | — | One line |
| Duration, average HR, load | Yes | — | Yes | — | — | Rows |
| Min HR | No | — | Misleading in a workout (a rest between sets) | Yes | — | Cut |
| SpO₂ | No | — | Not measured mid-workout any more | Yes | — | Cut |
| Zone minutes | Yes | — | Yes | — | — | Heart rate tab |

Taps on the common action did not rise: calories and duration are on arrival as before.
