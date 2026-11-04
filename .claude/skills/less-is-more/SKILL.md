---
name: less-is-more
description: Use when designing, reviewing or changing any screen, flow, sheet, notification or widget in an app built on the Gabriel Design System, or when asked to reduce clutter. A checklist for removing what doesn't earn its place, for someone using the phone one-handed and in a hurry. Pairs with the impeccable and graphic-design-fundamentals audits; all three run on every screen and flow.
---

# Less is more

Every element on a screen costs attention. The people using the owner's apps are usually doing
something else at the same time: holding a baby, walking, between two meetings. This skill decides
what earns its place, what moves one tap away, and what goes. Run it while you design a new screen
or flow, and again in the audit. Put the result in the change's audit file.

The design system's `AGENTS.md` requires it, with **impeccable** and
**graphic-design-fundamentals**, on every screen and flow of every app built on this boilerplate.
A product can keep its own copy with its own examples (Cochilo does), but it keeps the five
questions and the output below.

## Where the rules come from

- **Calm technology** (Weiser and Brown, Xerox PARC, 1995; Amber Case, *Calm Technology*, 2015).
  Technology should stay in the background and speak only when it helps. It should give people
  what they need to solve their problem, and nothing more. Judge a product by how well it served
  the person, not by how much attention it took.
  ([overview](https://en.wikipedia.org/wiki/Calm_technology), [Case's principles](https://caseorganic.com/post/principles-of-calm-technology/))
- **Progressive disclosure** (credited to Nielsen; IBM's Carroll and Rosson). Show what most
  people need now, and keep the rest one step away. Never hide what's required, what's legal, or
  what a destructive action will do.
  ([summary](https://thedecisionlab.com/fr-CA/reference-guide/design/progressive-disclosure))
- **Hick–Hyman law.** The more equal choices there are, the longer a decision takes. The effect is
  strongest on unfamiliar lists; people scan familiar ones. So cut options where they're
  unfamiliar or equal, and order the rest by how often they're used.
  ([IxDF on Hick–Hyman and its limits](https://ixdf.org/literature/article/the-hick-hyman-law-an-argument-against-complexity-in-user-interface-design))
- **Reduce load, don't strip features.** Clear the mental clutter (duplicates, chunking,
  defaults) rather than deleting what people use.
  ([Webdesigner Depot](https://webdesignerdepot.com/10-ways-to-reduce-cognitive-load-without-turning-your-ui-into-a-minimalist-ghost-town/))
- **The system's own rules** (AGENTS.md, DESIGN.md):
  - one filled accent action per screen;
  - states, not logs;
  - short, plain copy;
  - 48dp targets;
  - motion only where it means something.

## The test for every element

Ask these questions in order of every label, number, line, card, button, chip, row, "i" and hint
on the screen:

1. **Does it help the next thing the person does, or a decision they make today?** If yes, it
   stays visible.
2. **Is it shown elsewhere on the same screen, or already one tap away?** Then show it once. Keep
   the copy nearest its action and remove the other.
3. **Is it needed every time, or only to understand once?** Explanations, sources, the numbers
   behind an estimate and how-to hints go one tap away ("Why?", "How it's worked out"), or show
   until the thing has been used once.
4. **Does it serve the app's main goal?** The numbers for the goal lead. Others are grouped or
   folded.
5. **Is it an action taken weekly or less?** Then it lives in Settings or a sheet, never on the
   home screen.

| Answer | Do |
|---|---|
| Helps now | Keep it, ordered by how often it's used |
| Duplicate | Keep one, nearest its action |
| Understand once | One tap away, or shown until first use |
| Weak for the goal | Group it into one row, or move it to a secondary screen |
| Rare | Settings or a sheet |
| None of these | Remove it |

## Screen rules

- **One subject per screen, and one filled accent action.**
- **A number only with its meaning.** "Sleep: 10h 05m" beats a bare "10h 05m". Never show two
  numbers that say the same thing, like a header and a card both saying "Awake · 40m".
- **Every meta-role line has a cost.** One meta line per card at most; a second one has to earn
  its place.
- **No permanent hints.** A gesture hint shows until it has been used, then goes.
- **Few "i" buttons.** At most one per screen, on the thing people misread, and not on the main
  card.
- **Quiet when the person is tired.** At night, or in a hurry, show the state and its actions,
  nothing to read.
- **Defaults over questions.** Guess, show the guess, and allow a one-tap correction. Ask
  afterwards, never before.
- **No duplicate paths on one screen.** If the header opens something, the page doesn't need a row
  for it too.

## Flow rules

- **Count the taps.** Removing clutter must never add a tap to a common action.
- **A flow ends where it started.** No dead ends, and no "Done" screens.
- **Undo instead of confirm.** Confirm only when an action is destructive and can't be undone.

## Output

In the audit, list each removed, moved or merged element, the question (1–5) that decided it, and
where it went. Then score:

| Dimension | 0–4 |
|---|---|
| Elements per screen, compared with before | |
| Duplicates left | |
| Text to read when tired | |
| Taps on the common actions (must not rise) | |
