# Audit: Sleep in the new look (2.16.10)

Date: 2026-11-16 · PR: see the PR linking this file · Auditor: Claude Code. impeccable was run with
the skill loaded (context with PRODUCT.md, `audit.native.md`, `critique.md`, `craft-floor.md`,
`android.md`); ux-user-flows, graphic-design-fundamentals and less-is-more were applied by hand.

## Scope

Sleep, behind Debug → New look. The structure stays (carousel, hypnogram, stages, records,
range views): Sleep is the tab opened for the why. Every Sleep card shares one surface
(`sleepCard()`: calm card, 12dp radius, hairline), labels are sentence case (`SleepLabel`), the
night's duration is `CalmType.score` (40sp Inter), the hero loses its dither fade, and the tab gets
the page colour, 20dp gutter, 720dp cap and `CalmTypography`.

## ux-user-flows (§13 flow review checklist)

No flow changes: same range selector, day navigation, carousel, record editing and detail. States
unchanged: loading renders nothing until the first rebuild, no-data hero, a split night's records.

## impeccable

| # | Dimension | Score | Key finding |
|---|---|---|---|
| 1 | Accessibility | 3 | Labels now full words, not tracked caps; device check owed. |
| 2 | Performance | 4 | No new work. |
| 3 | Appearance & theming | 3 | Cards converted; range selector, day header, records and balance cards still old (NL-4). |
| 4 | Platform conformance | 4 | Unchanged controls. |
| 5 | Adaptivity | 4 | 720dp cap added. |
| | **Total** | **18/20** | |

Critique (design review only; native): 31/40. Strong: the duration as the statement, the stage
colours unchanged so the hypnogram reads as before. Weak: Consistency 2 (old pieces between new
cards, NL-4). No P0 or P1.

## graphic-design-fundamentals (§13 checklist)

Hierarchy: 40sp duration → hypnogram → stage cards. Contrast: calm tokens. Typography: Inter,
sizes on the scale. Colour: the stage colours carry the data; the hero's purple wash is gone, so
the night's colour lives in the chart only. Consistency with Today, Vitals and the radar: same
card. Production: no new assets.

## less-is-more

| Element | Question (1–5) | Kept / moved / merged / removed |
|---|---|---|
| Hero dither fade | 4 | Removed in the new look. |
| Caps eyebrows | 4 | Sentence case. |
| Hypnogram, stages, records | 1 | Kept. |

Taps on common actions unchanged.

## Evidence

pt-BR: no new strings. No device here; on-device check owed. Canvas: Sleep board in the new look owed.

## Verdict

Merge (behind the switch). Carried forward: NL-4, canvas boards, on-device check.
