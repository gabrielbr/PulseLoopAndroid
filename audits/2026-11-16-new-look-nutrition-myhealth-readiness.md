# Audit: Nutrition, My Health and Readiness in the new look (2.16.14)

Date: 2026-11-16 · PR: see the PR linking this file · Auditor: Claude Code, with impeccable loaded
for this pass (`impeccable context` with PRODUCT.md, `audit.native.md`, `critique-rubric.md`,
`craft-floor.md`, `polish.md`, `android.md`). Native: design review only, detector not applicable.
No emulator here: findings from source; the on-device check is owed. ux-user-flows,
graphic-design-fundamentals and less-is-more applied by hand from their SKILL.md files.

## Scope

All behind Debug → New look; the current look is unchanged (every shared change keeps the old
look's values, `lookCard(oldBorder = false)` where an old card had no hairline).

- **Shared, so several screens move at once:** `Look` (surface and text roles of whichever look is
  on) and `lookCard()`. My Health's `Section` (calm card, sentence-case heading instead of a caps
  eyebrow), `Fact`, the My Health entry card (also at the end of Vitals, Sleep and Activity: one
  NL-4 item closed), and `ContributorsCard` (Readiness, Sleep, Activity: a section title and a calm
  card with inset dividers).
- **Readiness:** calm top bar, page, gutter, 720dp cap, Inter; the band word in `sectionTitle`;
  the coverage note a plain raised block instead of a dithered one; the no-score and
  data-behind cards on the calm surface.
- **My Health:** calm top bar (share kept), page, gutter, cap, Inter; health-area titles in Inter;
  the Symptom Radar row a calm card.
- **Nutrition:** calm bar (pushed) or none (tab), page, gutter, cap, Inter; the day's total in
  `CalmType.score`; meal type headings; meal rows on the calm surface; the next-meal card led by
  "Next meal idea · Lunch" instead of a caps kicker.

## impeccable

### Findings and fixes
| Screen | Sev | Finding | Fix |
|---|---|---|---|
| Readiness | P1 | "Not enough data yet: hrv balance": acronyms lower-cased | Acronyms kept |
| Nutrition | P1 | "NEXT MEAL IDEA · LUNCH" kicker over the card's content | Heading in sentence case |
| Nutrition | P2 | A red bin on every meal row shouted about a rare action (quieter) | Muted in the new look; the confirm dialog keeps the red |
| My Health | P2 | Section names were caps eyebrows above their own content | Sentence-case headings |
| Readiness | P2 | The coverage note's dither was the old look's | Plain raised block |
| All | P2 | Still old-styled: Nutrition's Log meal button and macro bars, My Health's trend charts, breathing sheet and habits, the Readiness ring's drawing (NL-4) | Carried forward |
| Readiness | P3 | The score is centred while Today leads left-aligned | Kept: a detail screen's hero; revisit when the default flips |

### Scores after the fixes
| Screen | Audit (A11y · Perf · Theme · Conform · Adapt) | Critique /40 | Cognitive load failures |
|---|---|---|---|
| Readiness | 3 · 4 · 3 · 4 · 4 = 18/20 | 32 | 0 |
| My Health | 3 · 4 · 3 · 4 · 4 = 18/20 | 30 (many sections; folded ones help) | 1 (sections compete below the fold) |
| Nutrition | 3 · 4 · 3 · 4 · 4 = 18/20 | 32 | 0 |

No P0 or P1 left.

## ux-user-flows
No flow changes: same entries, logging paths (Log meal, photo, describe, next-meal idea), deletes
with confirm, Rest Mode, Share my week, contributor rows that expand. States unchanged (loading
holds height, empty day says so).

## graphic-design-fundamentals
Hierarchy: one statement per screen (readiness score, the day's kcal). Contrast: calm tokens.
Typography: Inter, sizes on the scale. Colour: band colours with their words; red only for the
destructive confirmation. Consistency: one card surface, shared by all converted tabs.

## less-is-more
Removed in the new look: caps eyebrows and kickers, a dithered note, red bins. Nothing added.
Taps on common actions unchanged (log a meal 1, open readiness 1, open a section 1).

## Evidence
pt-BR: "{0} kcal", "Next meal idea · {0}" added; caption updated. Canvas boards owed (tools not
connected). No device here.

## Verdict
Merge (behind the switch). Carried forward: NL-4 remainder, canvas boards, on-device check.
