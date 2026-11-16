# Audit: Settings in the new look

Date: 2026-11-16 · PR: see the PR linking this file · Auditor: Claude Code. impeccable was loaded
(context, `craft-floor.md`, `android.md`) and applied to **rendered** screens: the hub's ring card
and groups were drawn on the JVM with Robolectric native graphics, connected and disconnected, at
font scale 1.0 and 1.3, and in the old look. That harness is local only; no emulator here (no
KVM), so the device check is owed. ux-user-flows, graphic-design-fundamentals and less-is-more
were applied by hand from their SKILL.md files.

## Scope

The Settings hub (`SettingsScreen`) behind Debug → New look, and the shared pieces it is made of:
`SettingsSection`, `SettingsRow`, `DeviceHeroCard` and the Health Connect data-source card. The
sub-screens (`SettingsSubScreens.kt`, about 4,100 lines) are the next step.

## ux-user-flows (§13 flow review checklist)

No flow changes. Entry: the header's gear. Same groups, same rows, same routes, same order, same
conditional rows (measurement frequency, calibration, developer). States checked: loading (the
placeholder keeps the card's height so the rows don't jump: 176dp in the new look, 160dp in the
old), connected (card opens Ring & sync, no button), disconnected / never paired / reconnecting
(one button: Connect, Set up or Disconnect, disabled while a connect is in flight), Health Connect
as the data source (its card replaces the ring's). Exits: Back, and every row's screen.

## impeccable

### Audit (native, on renders)

| # | Dimension | Score | Key finding |
|---|---|---|---|
| 1 | Accessibility | 3 | Rows 56dp, button 52dp, icons decorative (`null`), titles carry the meaning. Device TalkBack pass owed. |
| 2 | Performance | 4 | No new work; same state reads. |
| 3 | Appearance & theming | 4 | Look tokens only; 12dp radius, one hairline, Inter. |
| 4 | Platform conformance | 4 | CenterAligned top bar, system Back. |
| 5 | Adaptivity | 4 | 720dp cap; 1.3× renders without clipping (ring name, "Português (Brasil)" trailing value ellipsises at 140dp). |
| | **Total** | **19/20** | |

### Critique

| # | Heuristic | Score | Key issue |
|---|---|---|---|
| 1 | Visibility of status | 4 | Ring state, sync time and battery in the card; trailing values on rows. |
| 2 | Match real world | 4 | Groups named as jobs, unchanged. |
| 3 | User control | 4 | Unchanged. |
| 4 | Consistency | 4 | Same bar, page and card as the converted tabs. |
| 5 | Error prevention | 3 | Button disabled while connecting. |
| 6 | Recognition | 3 | Icons kept, now monochrome: recognition rests on the titles. |
| 7 | Flexibility | 3 | n/a |
| 8 | Minimalist | 4 | Caps kickers and the duplicated single-row headers are gone. |
| 9 | Error recovery | 3 | Unchanged. |
| 10 | Help | 3 | n/a |
| | **Total** | **35/40** | Good |

### Polish (from the renders)

- [fixed] The first build used a tonal button for Connect, which took Material's default tonal
  colour (lavender) — a colour in neither look. It is now the calm outlined button.
- [fixed] Craft-floor ban, kicker over a heading: the group caps labels and the data source's
  "DATA SOURCE" kicker are gone in the new look.
- [P3] The ring art keeps its dithered band: AGENTS.md "Rings are drawn" holds for every look.

No P0 or P1.

## graphic-design-fundamentals (§13 checklist)

- **Hierarchy:** ring card first (20sp name, status in its state colour), then groups: 14sp
  semibold group name, 16sp row titles, 14sp trailing values.
- **Contrast:** text on card from `CalmColors` (≥ 5.3:1); the battery and status colours are the
  existing semantic ones.
- **Alignment and grid:** 20dp gutter, dividers start under the title column (64dp).
- **Spacing:** 28dp between groups, 8dp between a group name and its card.
- **Colour:** no per-row hues; colour is left to status (connected, battery level).
- **Consistency / real size:** checked at 411dp and 1.3×.

## less-is-more

| Element | Helps now? | Duplicate? | Verdict |
|---|---|---|---|
| Caps group labels | Yes, as names | — | Sentence case |
| "Notifications" header over "Notifications" row | No | Yes | Dropped in the new look |
| "AI Coach" header over "AI Coach" row | No | Yes | Dropped in the new look |
| Per-row icon colours | No (meant nothing) | — | One quiet tile |
| Dithered battery pill | The colour helps; the dither doesn't | — | Plain raised pill |
| Connect pill in the card corner | Yes, but small and off to the side | — | Full-width button |

No common action gained a tap.
