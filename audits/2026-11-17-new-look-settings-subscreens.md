# Audit: Settings sub-screens in the new look

Date: 2026-11-17 · PR: see the PR linking this file · Auditor: Claude Code.
- **impeccable:** loaded (context, `craft-floor.md`, `android.md`) and applied to **rendered**
  screens. All 18 sub-screens were drawn on the JVM with Robolectric native graphics, inside the
  app's real `PulseLoopTheme`, in the new look, and Physiology also in the old look. The harness
  used an in-memory stand-in for the Android Keystore so the screens that read encrypted settings
  could render. It is local only and not committed.
- **Device check:** owed. There is no emulator in this environment (no KVM).
- **ux-user-flows, graphic-design-fundamentals, less-is-more:** applied by hand from their
  SKILL.md files.

## Scope

Every screen behind a Settings row:
- AI Coach, Notifications, User profile, Physiology, Calibration, Goals & sleep, Measurement
  frequency, Ring & sync.
- Backup & data, About, Nutrition, Strava, Health Connect.
- Accuracy check, Use without a ring, AI tools on your network, Language.

They are converted through what they share:
- the scaffold (`SettingsSubScreen` + `CalmMaterial`);
- the card (`SubScreenCard`);
- the editors (`Editors.kt`);
- `SectionHeaderCaps` / `LookLabel`;
- `PulseSegmented`.

## ux-user-flows (§13 flow review checklist)

No flow changes: same routes, same controls, same order, same dialogs and sheets. States seen in
the renders:
- no ring paired (Ring & sync, Measurement frequency);
- Health Connect missing (Health Connect, Use without a ring, Accuracy check);
- Strava not configured;
- empty memory and no backups yet;
- every switch off and on.

## impeccable

### Audit (native, on renders)

| # | Dimension | Score | Key finding |
|---|---|---|---|
| 1 | Accessibility | 3 | Controls unchanged (48dp segments, Material switches, fields). Input outlines use `textMuted` (5.3:1) rather than the hairline, so a field's boundary passes 3:1. Selected segments carry weight and contrast, not colour alone. TalkBack on device owed. |
| 2 | Performance | 4 | No new work. |
| 3 | Appearance & theming | 3 | Neutrals, radius and type now come from the look. The coach's provider dropdowns still show mint text (P2). |
| 4 | Platform conformance | 4 | Material controls throughout; CenterAligned bar. |
| 5 | Adaptivity | 4 | 720dp cap, 20dp gutter. |
| | **Total** | **18/20** | |

### Critique

| # | Heuristic | Score | Key issue |
|---|---|---|---|
| 1 | Visibility of status | 4 | Unchanged status lines (Health Connect missing, backups). |
| 2 | Match real world | 4 | Unchanged copy. |
| 3 | User control | 4 | Unchanged. |
| 4 | Consistency | 4 | One bar, card, label and segmented style across all 18 screens and the hub. |
| 5 | Error prevention | 3 | Unchanged. |
| 6 | Recognition | 3 | Goal tiles keep their metric colour on the icon and value. |
| 7 | Flexibility | 3 | n/a |
| 8 | Minimalist | 4 | Caps kickers gone; dithered tiles replaced by one quiet tile. |
| 9 | Error recovery | 3 | Unchanged. |
| 10 | Help | 3 | Unchanged. |
| | **Total** | **35/40** | Good |

### Polish (from the renders)

- [fixed] Tonal buttons ("Pair a tool", Export, Reseed demo data) were Material's default lavender,
  because the app's colour scheme never set the tonal role. `CalmMaterial` sets it; the old look
  still shows it (P2, it goes when the old look is deleted).
- [fixed] `PulseSegmented` used the old mono font and a mint fill. Calm: raised surface, Inter.
- [fixed] Profile's Units/Sex segments filled the choice with mint. Calm: the choice is an inset
  on a raised track, in full-strength text.
- [fixed] Goals: caps section labels, monospace values and dithered icon tiles.
- [P2] The coach's provider, model and effort dropdowns keep mint text.
- [P2] Several pushed screens keep a filled mint primary (Add a ring, Save calibration, Install
  Health Connect). No Actions button shows on pushed screens, so each is still the screen's one
  filled action.

No P0 or P1.

## graphic-design-fundamentals (§13 checklist)

- **Hierarchy:** 16sp semibold card titles, 14sp body, 12sp captions, 14sp semibold group labels.
- **Contrast:** calm neutrals ≥ 5.3:1 on card; control outlines at `textMuted`.
- **Alignment and grid:** 20dp gutter, 16dp inside cards, as before.
- **Colour:** mint left to switches' on state and the one primary; metric colours only on goal
  values and sliders, where they identify the metric.
- **Consistency / real size:** checked at 411dp.

## less-is-more

| Element | Verdict |
|---|---|
| Caps labels (Units, Identity, Body metrics, Daily targets, Weekly target, Life stage, Connect to, Paired tools) | Sentence case |
| Dithered goal icon tiles | One quiet tile, metric colour kept on the icon |
| Mint fill on a selected segment | Raised surface: mint is for what to tap |
| Monospace values on goals | Inter |

No control was removed and no common action gained a tap.
