# UI audit — October 2026

A code-level audit of the Compose UI, run with two design skills: **impeccable** (`audit` for
native Android, scored against Material 3) and **ui-ux-pro-max** (its priority rules and the
Jetpack Compose stack guidance). Three parallel passes covered the whole `ui/` tree; every finding
was checked against the code before it was acted on.

The GMind brand is treated as the brief, not as a finding: dark-only, Ubuntu Mono throughout, one
mint accent with ink-green on it, and the 4dp mint frame.

## Score (before the fixes in this branch)

| # | Dimension | Score | Key finding |
|---|-----------|-------|-------------|
| 1 | Accessibility | 1 | ~24 switch rows had no spoken label ("off, switch"); no `semantics`, `selectable` or `liveRegion` anywhere in `ui/`; icon-only Back/Edit/Delete read as "unlabeled button" |
| 2 | Performance | 3 | `runBlocking` on the UI thread for workout pause/resume/finish; a Room Flow rebuilt on every recomposition |
| 3 | Appearance & theming | 3 | Tokenised throughout (`PulseColors`), but `textMuted` failed 4.5:1 on every card surface (182 uses) |
| 4 | Platform conformance | 2 | No `BackHandler` in the app: Back abandoned onboarding, edit mode, a live workout and breathing sessions; permission prompts re-opened on every resume |
| 5 | Adaptivity | 2 | Landscape handled; no window-size awareness, so tablets get stretched phone tiles |
| | **Total** | **11/20** | **Acceptable — significant work needed** |

## Systemic fixes

- **`textMuted` #7C7B82 → #8E8D94.** One token change lifts all 182 uses to 5.2:1 on `card` and
  4.75:1 on `cardSoft`, while staying a visible step below `textSecondary`.
- **Switch rows are one control** (`Modifier.switchRow`, `ui/components/A11y.kt`): the whole row
  toggles with `Role.Switch` and the Switch is only the indicator. TalkBack now reads
  "Athlete mode, off, switch", and the label is tappable.
- **"Remove animations" is honoured** (`rememberReduceMotion()`): the connection pill and the
  breathing circle hold still when the system animator scale is 0.

## Fixed in this branch

See the commit for the full list. In short: Back handling (onboarding steps, dashboard edit mode,
live workout, breathing session save), the permission loop and the missing coarse-location
request, confirmations for Forget Ring / meal / drink / Strava / memory deletes, IME insets on
settings sub-screens, 48dp touch targets on the dashboard edit badges, coach composer, period
chevrons, chips and segmented controls, selection semantics on chips and segmented controls,
spoken summaries for charts, gauges, rings and the hypnogram, the DEEP stage label's contrast,
9sp labels raised, bottom-nav labels no longer read twice, tile grid keys and a 3-column grid at
600dp+, and `runBlocking` removed from the record screen.

Removed as dead code: the landscape "status-bar glass strip" in `PulseLoopApp`. `MainActivity`
pads and consumes the system-bar insets around the whole app, so `WindowInsets.statusBars` inside
resolved to zero and the strip had no height; content already stops below the status bar.

Workout pause/resume/finish run on `LiveWorkoutManager`'s own scope (`*FromUi`), not the record
screen's: finishing pops that screen, and a screen-scoped launch would cancel the Strava upload.
A second tap while one is in flight is dropped, so Finish can't credit a workout twice.

## Open (not done here)

- **Location is still requested at launch.** Material guidance is to ask just before the first
  GPS workout; that needs a rationale screen in the record flow.
- **Charts are summarised, not navigable.** TalkBack hears a one-line summary; per-point
  exploration would need custom accessibility actions.
- **Window size classes.** Only the Today grid adapts; a navigation rail on expanded width and
  wider content gutters are the next step.
- **Sleep times are always 12-hour** (`SleepFormat.clockTime`), while Today now follows the
  phone's 12/24-hour setting.
- **Toasts** remain in `SleepScreen`; a `SnackbarHost` at the app scaffold would let them move.
