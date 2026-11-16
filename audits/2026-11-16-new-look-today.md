# Audit: Today in the new look (2.16.7)

Date: 2026-11-16 · PR: see the PR linking this file · Auditor: Claude Code. impeccable was run with
the skill loaded (context, `audit.native.md`, `critique.md`, `craft-floor.md`, `android.md`,
`init.md`, `new-work.md`); ux-user-flows, graphic-design-fundamentals and less-is-more were applied
by hand from their SKILL.md files.

## Scope

Today, behind Debug → New look (off by default). The owner chose an Oura-style restructure over a
restyle (2026-11-16): readiness as the lead statement, Sleep and Activity as two score rows under
it, and every other card as a single-column row with a small trend line. Context work done first:
`PRODUCT.md` written with the owner (impeccable init: users, positioning, constraints).

## ux-user-flows (§13 flow review checklist)

Flow: open app → Today: read readiness (statement + sentence) → [why?] Sleep / Activity row →
detail screen; or scan the rows → tap one → its detail; log a meal / drink from its row; Customize
cards → edit the list → Done.

| # | Question | Result |
|---|---|---|
| 1 | Job | "Am I ready today, and is anything off?" in one glance, then the why. |
| 2 | Entries | App launch and the Today tab; unchanged. |
| 3 | Happy path | The answer is the first thing read, no tap. |
| 4 | Branches | Score / learning / no sleep / not measured / loading; coach on or off; alert or not; edit mode. |
| 5 | States | Loading holds the hero's height and shows no rows until vitals and sleep have loaded (no "No data" flash); empty rows say why ("No reading", "Nothing logged yet"). |
| 6 | Errors | Readiness failure reads "No data"; rows fall back to their own empty text. |
| 7 | Control | Pull to refresh, Customize cards, and every row opens its detail. |
| 8 | Progress and resume | Learning shows "Learning: n of 14 nights". |
| 9 | Memory | Card order and hidden cards from Customize, unchanged. |
| 10 | Exits | Tabs and the header, unchanged. |
| 11 | Conventions | Rows match Symptom Radar's cards; Oura's order the owner asked for. |
| 12 | Speed | Same loads as before; no new queries. |
| 13 | Accessibility | Rows are buttons; value and unit merge; sparkline hidden from TalkBack; band word beside every coloured number. |
| 14 | Ethics | Estimates keep their label (#103, #67); readiness copy unchanged. |
| 15 | Evidence | The owner's choice in this session and the Oura screenshot that started the redesign. |

Issues: polish — while editing, Sleep and Activity move from the top card into the list so they
can be hidden; the preview differs from the result for those two (NL-5). Their position in the
Customize order no longer applies outside editing.

## impeccable

### Audit (native, from source; no emulator in this environment)

| # | Dimension | Score | Key finding |
|---|---|---|---|
| 1 | Accessibility | 3 | Targets ≥48dp, colour never alone. Device check at font scale 1.3 and TalkBack is owed. |
| 2 | Performance | 4 | LazyColumn with stable keys; sparklines capped at 60 points; no extra reads. |
| 3 | Appearance & theming | 3 | New tokens throughout the new rows; reused pieces still old-styled (NL-4). |
| 4 | Platform conformance | 4 | Pull-to-refresh, system Back in edit mode, Material buttons underneath. |
| 5 | Adaptivity | 4 | Single column caps at 720dp, centred; no grid to stretch. |
| | **Total** | **18/20** | |

### Critique (design review only; detector not applicable, native platform)

| # | Heuristic | Score | Key issue |
|---|---|---|---|
| 1 | Visibility of status | 4 | Readiness, its band and its reason lead the screen. |
| 2 | Match real world | 3 | "Learning: 3 of 14 nights" says what the app waits for. |
| 3 | User control | 3 | Customize cards kept; Sleep/Activity order fixed at the top. |
| 4 | Consistency | 2 | Reused old-styled pieces sit between new rows (NL-4). |
| 5 | Error prevention | 3 | Loading never shows "No data" for data still being read. |
| 6 | Recognition | 4 | Each row keeps its metric's colour dot and name. |
| 7 | Flexibility | 3 | One-tap logging stays on its rows. |
| 8 | Minimalist | 3 | Score rings and tile chrome gone; the list is longer to scroll (owner's trade). |
| 9 | Error recovery | 3 | Empty rows name what's missing. |
| 10 | Help | 3 | The readiness sentence explains the number. |
| | **Total** | **31/40** | Good |

Design specificity: the lead is this product's own answer (readiness built from sleep and
activity, shown in that order), not a generic dashboard. Priority issues: [P2] NL-4 old-styled
pieces inside the new Today, to convert with their own screens; [P3] NL-5 edit-mode preview.
No P0 or P1.

## graphic-design-fundamentals (§13 checklist)

| # | Question | Result |
|---|---|---|
| 1 | Brief | One statement first, its causes second, everything else as rows. |
| 2 | Concept | Oura's order in the new look: number, word, reason. |
| 3 | Hierarchy | 40sp number → 20sp band word → 16sp sentence → 28sp row values. |
| 4 | Contrast | Same tokens as Symptom Radar (text 17.3:1, secondary 7.0:1, muted 5.3:1 on card). |
| 5 | Alignment and grid | One 20dp gutter; every row left-aligned to it. |
| 6 | Spacing | 12dp between rows; rows pad 20dp. |
| 7 | Typography | Inter only on the tab (`CalmTypography`); sizes on the scale. |
| 8 | Colour | Mint only on the in-row log actions; band and zone colours for meaning, with words. |
| 9 | Consistency | Rows reuse Symptom Radar's card. Fails for the old-styled pieces (NL-4). |
| 10 | Simplicity | Three rings and nine tile designs become one row pattern. |
| 11 | Real-size test | pt-BR "Aprendendo: 3 de 14 noites" wraps under the 32sp style; row names ellipsise. |
| 12 | Accessibility and ergonomics | 48dp targets; the lead needs no tap. |
| 13 | Production | No new assets; one 40sp style added to the calm scale. |

## less-is-more

| Element | Question (1–5) | Kept / moved / merged / removed, and where |
|---|---|---|
| Three score rings | 4 (weak for the goal) | Merged: readiness is the statement, the other two are rows. |
| Sleep and Activity tiles | 2 (duplicate) | Merged into the score rows; not repeated in the list. |
| Last night card | 2 (duplicate) | Only when it adds last night's drinks, as before. |
| Tile eyebrows (caps labels) | 4 | Removed: sentence-case names. |
| "+ Log meal" / "+ drink" | 1 (helps now) | Kept on their rows. |

| Dimension | 0–4 |
|---|---|
| Elements per screen, compared with before | Fewer kinds of element; more rows to scroll |
| Duplicates left | 0 |
| Text to read when tired | 4 |
| Taps on the common actions (must not rise) | Unchanged: log meal 1, add drink 1, readiness 1, sleep 1 |

## Evidence

- pt-BR missing-key check: none missing.
- No device or emulator here: on-device check (dark, font scale 1.3, TalkBack, pt-BR) owed on the
  release build.
- Design canvas: not reachable from this session (the canvas tools disconnected); see the PR.

## Verdict

Merge (behind the switch). Carried forward: NL-3 tab ↔ timeline selection (radar), NL-4 old-styled
pieces inside the new Today, NL-5 edit-mode preview of Sleep/Activity, the Today board on the
canvas, the on-device check.
