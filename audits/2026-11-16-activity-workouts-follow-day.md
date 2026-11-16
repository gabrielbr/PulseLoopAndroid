# Audit: Activity's workout list follows the shown day (2.16.12)

Date: 2026-11-16 · PR: see the PR linking this file · Auditor: Claude Code (impeccable run with the
skill loaded earlier this session; the other three applied by hand from their SKILL.md files)

## Scope

The Activity tab has day arrows, but its workout list was fixed to today ("TODAY", "No workouts
recorded today") whatever day was shown, so the owner's 12 Nov gym workout (finished, 46 min) only
appeared inside the history dialog. The list now shows the shown day's workouts; its header names
the day (TODAY, YESTERDAY, a weekday or a date) and the empty text names it too, pointing to Log
Past Activity on a past day instead of recording one now.

## ux-user-flows

Flow: Activity → ‹ previous day → that day's workouts → tap one → detail. Branches: today / a past
day; workouts / none. Errors: none new. Conventions: matches Sleep's day header. Evidence: the
owner's Developer → activity_sessions screenshot (two finished workouts not listed).

## impeccable

Native audit unchanged at the dimension level (no new components); fixes a P1 Visibility of system
status failure: the screen said "No workouts recorded today" on a day that had one. No new findings.

## graphic-design-fundamentals

No visual change: same eyebrow and empty-state styles, new words only. pt-BR "Nenhum treino
registrado em quarta-feira" fits the card.

## less-is-more

Nothing added. The history dialog keeps its place for scanning many weeks. Taps to see a past
day's workout drop from 3 (history button, find it, tap) to 2 (arrow, tap).

## Verdict

Merge.
