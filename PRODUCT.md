# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

Mainly the owner and his family, each wearing an inexpensive smart ring (Colmi, R100, SR08,
Ale-Hop and similar). Public users on GitHub are welcome but secondary: they report rings and
bugs, and their reports have shaped much of the ring code.

They check the app in a hurry, one-handed: in the morning to see how the night went and whether
to push or rest, and in the evening to log the day. The answer has to be on the first screen.

## Product Purpose

Give a budget ring Oura-level insight: readiness, sleep, vitals, symptom radar, trends, and a
coach that reads the data. Success is a morning glance that says something true and useful about
the person, without the vendor's app and without a subscription.

## Positioning

Oura-level insight from a cheap ring. It works with many inexpensive rings by speaking each
vendor's own protocol, needs no subscription and no account, keeps the data on the phone, is open
source, and says plainly what the ring measured and what the app estimated.

## Operating Context

- The ring syncs over Bluetooth about every 30 minutes in the background, and on opening the app.
- Data can also come from Health Connect, and goes out to it and to Strava.
- A coach (the user's own API key, or an on-device model) reads the data and answers questions.
- The owner reviews screens on a Claude Design canvas and in released APKs on his phone.

## Capabilities and Constraints

- **English and Brazilian Portuguese** for every string; layouts must survive the longer
  Portuguese (owner's confirmation, 2026-11-16).
- **One-handed and glanceable**: the important answer first, detail on tap (owner's
  confirmation, 2026-11-16).
- **Never a diagnosis**: estimates are labelled as estimates, nothing names a condition, and
  safety text keeps its plain wording (AGENTS.md: lab reports, postpartum, derived stress).
- Ring hardware differs: a metric the app shows must say when this ring doesn't measure it.
- Undecided: whether public users ever become a primary audience.

## Brand Commitments

- The name PulseLoop, the mint accent, and Fairy mode (the brand turns pink for Tatá's profile,
  never announced).
- The visual system is recorded in `docs/new-look-plan.md` (new look, in progress) and the Gabriel
  Design System's `pulseloop` layer (current look).

## Evidence on Hand

- Real ring captures and reports in the GitHub issues (#29, #58, #59, #63, #68, #73 and others).
- No testimonials, customer counts or benchmarks exist; don't invent any.

## Product Principles

1. The ring's own data, shown honestly: measured and estimated are never confused.
2. The morning answer first; detail on tap.
3. Nothing leaves the phone unless the person sends it.
4. Works with the ring people already own, by matching each vendor's app.

## Accessibility & Inclusion

Two languages, TalkBack, and large font scales: no clipped text at 1.3×. 48dp targets.
