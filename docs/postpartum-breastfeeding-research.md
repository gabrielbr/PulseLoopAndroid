# Postpartum & breastfeeding: what a ring app can (and can't) offer

Research note, 2026-10-22. Question: are there health metrics or tracking features specific to women
with a newborn who are breastfeeding, and which could PulseLoop realistically offer with what it
already has (sleep stages, HR, HRV, SpO2, skin temperature on some rings, steps, workouts, nutrition,
drinks with standard drinks, medications, cycle tracking, journal tags, AI coach, Health Connect)?

**Short answer:** yes, but the ring's own signals are mostly *context*, not diagnostics. The
evidence supports showing postpartum recovery trends (RHR/HRV returning toward baseline, sleep
fragmentation improving) against the user's *own* pre-pregnancy baseline, and supports a set of
well-established, guideline-backed logging aids (alcohol-to-feed timing, calorie/fluid targets,
medication-in-lactation lookups, EPDS screen, warning signs). Nothing in the literature supports a
ring detecting mastitis, return of ovulation during breastfeeding, milk supply, or postpartum
depression on its own, and the app must not claim it.

Confidence labels used below: **Strong** = official guideline or multiple consistent studies;
**Moderate** = a few cohort/observational studies, consistent direction; **Weak** = single small
studies, vendor material, or extrapolation from non-postpartum populations.

---

## 1. What existing apps and wearables do

| Product | Postpartum / breastfeeding features (concrete) | Notes |
| --- | --- | --- |
| **Oura** | Pregnancy Insights (Gen3+): gestational-age tracking, weekly education on how sleep/HR change, pregnancy symptom tags. Postpartum is served by generic Sleep/Readiness, tags, and Explore meditations/breathwork; the company's own postpartum stories are about going to bed earlier to offset night feeds and using a low Readiness score as a "take it easy" cue. | No dedicated breastfeeding/feeding log, no published postpartum mode or temperature handling after birth found. |
| **WHOOP** | Journal toggles and insights for trying to conceive, pregnancy, **postpartum**, perimenopause, postmenopause; WHOOP's own perinatal study (18 women) showed HRV rising / RHR falling from ~7 weeks before birth into postpartum. | "Coaching" specific to postpartum was not confirmed; it is journal-plus-insights. |
| **Garmin** | Pregnancy tracking (built on its cycle tracking); integration with Natural Cycles, where overnight wrist skin temperature syncs each morning. | Postpartum specifics not documented in sources found. Garmin itself states its watches are wellness devices, not medical devices. |
| **Natural Cycles** | **NC° Postpartum** mode (for users who log a pregnancy ending after 28 weeks): check-ins on mental and physical health, postpartum bleeding, sleep, pain, skin/hair changes; education on when fertility returns; shareable with clinicians/partners. While breastfeeding the birth-control mode shows only "Red Days" until the first post-birth ovulation is detected. | The most conservative and clinically framed of the group: it does not try to predict fertility under lactational suppression. |
| **Huckleberry** | One-touch logging of feeds, **breastfeeding timer per side**, pumping, diapers, sleep, growth, medicine; caregiver sync; paid "SweetSpot" nap prediction. | Baby-centric. |
| **Glow Baby** | Breastfeeding, diaper, sleep trackers; **Pump Log** (sessions, supply, storage); AI insights; export to the baby's clinician. | Baby-centric. |
| **Ovia Parenting** | Feeds (breast/bottle), diapers, sleep, milestones, family photo sharing. | Baby-centric. |
| **Elvie / Willow pump apps** | (Not researched in depth this pass.) Smart pumps log session time and expressed volume per breast automatically. | Volume data would only reach PulseLoop through manual entry or a future integration. |

**Gap PulseLoop could fill:** baby apps track the *baby* and ignore the mother's physiology;
wearable apps track the *mother's* physiology and ignore feeds. Nobody in this list joins
"feed at 02:40" with "HRV, sleep fragmentation and wake bouts tonight", or the drinks log with
"next safe feed". PulseLoop already has both halves.

---

## 2. What changes physiologically, and what a ring can measure

### Resting heart rate and HRV — **Moderate**
- **Sarhaddi et al., 2022, JMIR mHealth uHealth** (58 women, Samsung Gear Sport PPG, pregnancy
  through 3 months postpartum): HR rose and HRV fell through pregnancy; in the third trimester the
  trend began to reverse; values **returned to normal after delivery** within the 12-week window.
- **Scripps / PowerMom, 2025, eBioMedicine** (Quer, Àjàyí et al.; >5,600 enrolled, 108 with
  continuous data from 3 months pre-pregnancy to 6 months postpartum; Apple Watch, Fitbit, Garmin):
  RHR peaked ~9.4 bpm above baseline before delivery, then **dropped below pre-pregnancy levels,
  reaching a minimum ~7 weeks postpartum**, and took roughly **6 months** to settle back to baseline.
- **WHOOP perinatal cohort, 2022, BMC Pregnancy and Childbirth** ("Monitoring one heart to help
  two", 18 active women): from ~49 days before birth, daily HRV rose and RHR fell regardless of
  activity level, continuing into postpartum (modelled to 8 weeks).
- **Byfield et al., 2025, Biological Research for Nursing** (scoping review of wearable HRV in
  perinatal people): HRV moves back toward pre-pregnancy levels postpartum, but individual recovery
  patterns vary and measurement methods are not standardised.

**Implication:** a "postpartum recovery" view comparing current RHR/HRV with the user's own
pre-pregnancy baseline is well grounded — *if* the app has that baseline (most PulseLoop users who
wore a ring before pregnancy will). Population norms are not; individual variation is large.
Note the RHR undershoot: a postpartum RHR *below* baseline at 6–8 weeks is expected, not a problem.

### HRV and postpartum depression — **Weak (research only)**
- **Shinba et al., 2024** (45 EPDS-positive women, wearable ECG, 1–4 weeks postpartum): women with
  PPD showed poor HRV regulation under task load and high resting LF/HF, distinguishing them from
  adjustment disorder. Research-grade ECG protocol, not passive consumer PPG.
- **Eriksson et al., 2024**: lower HRV at gestational week 38 associated with depressive/anxious
  symptoms at 6 weeks postpartum; HRV alone was **not predictive**, only in a combined model.
- A narrative review (2025, PMC11773373) and the scoping review both call the evidence inconsistent.

**Implication:** never present HRV as a depression signal. Use the validated questionnaire (EPDS)
for screening; HRV can at most be shown beside it as context.

### Sleep — **Strong for the pattern, mixed for feeding effects**
- **Montgomery-Downs et al., 2010, AJOG** (actigraphy, weeks 2–16): nocturnal sleep ~**7.2 h** and
  stable across the 16 weeks, but highly fragmented: efficiency rose from ~**80% at week 2 to ~90%
  at week 16** as fragmentation fell (21.7 → 12.8). "Total sleep is not the problem; continuity is."
- **Srimoragot et al., 2023, J Sleep Res** (systematic review/meta-analysis): breastfeeding mothers
  had somewhat longer night-time sleep, and exclusively breastfeeding mothers had **more** actigraphic
  wake after sleep onset.
- **Doan et al., 2014, J Clin Sleep Med** (first-time mothers, 1 month): exclusive breastfeeding
  ≈ 30 min more nocturnal sleep, no WASO difference. Other studies (incl. a mixed-methods study of
  women at risk of PPD, PMC9665349) found **no difference** by feeding type.

**Implication:** the metric that matters postpartum is **fragmentation / number of wake bouts /
longest uninterrupted stretch**, not total sleep or a sleep score built for adults without infants.
A standard score will read "poor" for months and demoralise. Also: ring sleep staging in highly
fragmented sleep is less reliable than in consolidated sleep, so stage breakdowns should be
de-emphasised. Daytime naps ("sleep when the baby sleeps") should count.

### Skin temperature — **Weak for postpartum use**
- Wearable skin temperature tracks the menstrual cycle in non-postpartum women (Zurich wrist study:
  sustained 3-day shift in 82% of cycles; Gombert-Labedens et al., 2024). The wrist-vs-BBT validation
  study (PMC8238491) **excluded actively breastfeeding women**.
- In older BBT work in breastfeeding women (PubMed 10899480 and related), the first bleeding during
  lactation was often **anovulatory** (none of 8 women who bled before weaning had an adequate thermal
  shift), and in the first cycles after lactational amenorrhoea BBT-defined ovulation lagged the LH
  peak by ~4 days, converging over three cycles.
- No peer-reviewed study was found that validates ring/wrist temperature for detecting the *first*
  postpartum ovulation while breastfeeding. Night feeds, room temperature, bedding and finger
  perfusion all disturb finger temperature.
- Fever: CDC's urgent warning sign is **≥ 38.0 °C (100.4 °F)**. A ring measures *skin* temperature
  deviation, not core temperature, so it cannot confirm fever; at most it can prompt "your
  temperature deviation is unusually high — if you feel unwell, take your temperature".

**Implication:** keep cycle predictions **off** while breastfeeding (as Natural Cycles does) and
show temperature only as raw trend. A large deviation can trigger a "check with a thermometer"
nudge, never "possible mastitis".

### SpO2 — **no postpartum-specific evidence found**
Nothing found suggesting SpO2 changes meaningfully during lactation. Shortness of breath and chest
pain are warning signs (CDC), but those are symptoms to act on, not ring readings to wait for.

### Activity — **Strong (guideline)**
ACOG Committee Opinion 804 (2020): resuming or starting exercise postpartum is beneficial and may
help prevent postpartum depressive symptoms; avoid routine restriction; individualise. Practical
tip in the same guidance: breastfeed or express before exercise to avoid engorgement discomfort.
Adult target (WHO): 150–300 min/week moderate activity. Timing of return (vaginal vs caesarean,
pelvic floor) is a clinician decision; the app should not set a date.

---

## 3. Breastfeeding-specific logging that matters clinically

| Item | Guidance | Strength |
| --- | --- | --- |
| **Feed timing / side / duration** | Standard in every baby app; LAM requires feeds "at least 6–10 times a day including night feeds". Mainly useful to the mother for alternation and to correlate with her night wakings. | Practice standard |
| **Pumping volume** | Pump apps log per-session, per-breast volume. Useful for working mothers and supply worries; not something the ring can infer. | Practice standard |
| **Diapers as intake proxy** | Used clinically for the *baby's* intake in the first weeks. Out of PulseLoop's scope (baby app territory) — mention, don't build. | — |
| **Extra energy** | **Dietary Guidelines for Americans 2020–2025**: ~**+330 kcal/day** in months 0–6 (500 for milk minus 170 from weight loss) and **+400 kcal/day** in months 7–12, for a healthy pre-pregnancy weight; individualise. Lactation textbooks quote 400–500 kcal. Very low intakes (below ~1,500–1,800 kcal) are commonly cited as a supply risk. | Strong |
| **Fluids** | No fixed extra-water rule; the US IOM reports median fluid intake of breastfeeding women ~**3.1 L/day**. "Drink to thirst, keep water by the feeding chair" is the consistent advice. | Moderate |
| **Alcohol** | **CDC**: not drinking is safest; up to 1 standard drink/day (14 g alcohol) is not known to harm the infant. Wait **at least 2 h per drink** before nursing; alcohol is detectable in milk ~2–3 h per drink (2 drinks ≈ 4–5 h, 3 drinks ≈ 6–8 h). Pumping and dumping does **not** speed clearance. Caring for an infant while intoxicated is unsafe. | Strong |
| **Caffeine** | Commonly quoted limit ~200–300 mg/day; could not confirm the CDC's own page in this pass, so quote it as a range from secondary sources. | Moderate |
| **Medication safety** | **LactMed** (NIH/NLM, free, peer-reviewed, per-drug levels in milk/infant and alternatives) and **e-lactancia.org** (APILAM, Spanish/English/Portuguese, tiers: compatible → limited compatibility → unsafe, with alternatives; ratings revised as evidence changes, e.g. lisinopril re-rated Dec 2025). Both say they don't replace a clinician. | Strong (as references) |
| **Supplements** | DHA ~200 mg/day if eating fish less than twice a week; iodine supplementation recommended in several guidelines (German network: 100 µg/day + iodised salt; US RDA for lactation is 290 µg — background knowledge, not re-verified this pass); infant vitamin D drops 400 IU (10 µg) for breastfed babies. Brazil iodises table salt, which changes the calculus. Many commercial lactation supplements don't meet iodine/DHA targets (Ernährungs Umschau 2023). | Moderate |
| **Return of menstruation / contraception (LAM)** | LAM is ≥98% effective in the first 6 months **only while all three hold**: baby < 6 months, no return of menses (bleeding after day 56), fully or nearly fully breastfeeding (no long gaps, no supplements). If any one lapses, another method is needed. No STI protection. | Strong |
| **Postpartum depression screen** | **EPDS**, 10 items, 0–30. Validation cut-offs range 9–13 (higher = more specific). Validated in Brazil (Pelotas cohort, Cad Saúde Pública 2007). **Item 10 (self-harm) must be acted on regardless of total score.** | Strong |
| **Pelvic floor** | Pelvic floor muscle training is standard postpartum advice; specifics weren't confirmed in ACOG 804's summaries found. A reminder/log is low-risk. | Moderate (not re-verified) |
| **Sleep debt** | Fragmentation, not duration, is the postpartum signature (Montgomery-Downs 2010). | Moderate |

---

## 4. Safety and wording

### What PulseLoop must not claim
- That the ring **detects** mastitis, infection, fever, postpartum depression, preeclampsia,
  haemorrhage, milk supply, or fertility/ovulation return during breastfeeding.
- That an alcohol timer makes milk "safe" or "alcohol-free" — it is an estimate based on CDC's
  per-drink rule; metabolism varies. Say "CDC suggests waiting at least 2 h per drink; estimate
  ready around 23:40".
- That LAM status is contraception advice. Show the three criteria and "if any changes, talk to
  your clinician about another method".
- That a medication is safe — link to LactMed/e-lactancia and the clinician; don't render our own
  verdict.
- That a calorie deficit is fine to chase; avoid weight-loss framing in early postpartum.
- That HRV/Readiness scores mean the same as before pregnancy; label postpartum baselines.

### Red flags that should prompt care (show plainly, not buried)
From **CDC Hear Her urgent maternal warning signs** (apply up to a year after birth):
headache that won't go away or worsens; dizziness or fainting; vision changes; fever ≥ 38.0 °C
(100.4 °F); extreme swelling of hands or face; thoughts of harming yourself or the baby; trouble
breathing; chest pain or fast-beating heart; severe nausea/vomiting; severe belly pain; vaginal
bleeding or discharge after pregnancy; severe swelling, redness or pain in a leg or arm (to 6 weeks);
overwhelming tiredness.

Additional specifics:
- **Postpartum preeclampsia** can appear up to 6 weeks after delivery (typically days 7–10): any
  BP ≥ 160/110 is an emergency; ≥ 140/90 with headache, vision change, or upper-abdominal pain needs
  urgent assessment. Relevant because PulseLoop has a BP vital on some rings — but **ring BP is not
  validated**, so a high ring BP must say "confirm with a cuff", and a normal one must never reassure.
- **Mastitis** (NHS; ABM Protocol #36, 2022, which reframes it as a spectrum from engorgement to
  abscess): hot, painful, red/swollen area or wedge-shaped lump, burning pain, flu-like symptoms with
  fever/chills. See a clinician if no improvement in 12–24 h of home care or 48 h after antibiotics,
  or immediately if systemically unwell.
- **EPDS** high score → contact a health professional; **item 10 endorsed → show crisis resources
  immediately**, whatever the total.

### Brazil-specific resources
- **CVV — 188**: free, 24 h, anonymous, nationwide (also chat/e-mail with narrower hours).
- **SAMU — 192** for emergencies (background knowledge).
- **Rede Brasileira de Bancos de Leite Humano (rBLH-BR, Fiocruz/Ministério da Saúde)**: ~220+
  milk banks (counts vary by date), help with breastfeeding difficulties and milk donation; home
  collection in some places; Disque Saúde **136** to find the nearest bank. In 2024, 193k donors
  supplied ~245.7k litres to >219k babies.
- **SUS**: postpartum visit at the UBS (puerpério) and Caderneta da Gestante / da Criança
  (background knowledge, not re-verified).
- **Guia Alimentar para Crianças Brasileiras Menores de 2 Anos** (Ministério da Saúde, 2019):
  exclusive breastfeeding to 6 months, continued to 2 years or more. Its specific alcohol wording
  for the mother was not found in this pass; use CDC's rule and note "Ministério da Saúde advises
  avoiding alcohol" only after verifying.
- **e-lactancia.org** has a Portuguese interface and is widely used by Brazilian lactation
  consultants.

---

## 5. Recommendations for PulseLoop (prioritised)

All of these assume an opt-in **"Postpartum / breastfeeding" life-stage mode** (date of birth of the
baby, feeding type: exclusive / mixed / not breastfeeding). The mode's first job is to *change
defaults* — and several items below are mostly defaults.

| # | Feature | What it shows | Data it needs | Evidence |
| --- | --- | --- | --- | --- |
| 1 | **Postpartum mode that changes defaults** | Pauses cycle/fertility predictions and period-late alerts while breastfeeding (Natural Cycles' approach); relabels baselines "postpartum"; suppresses "your RHR is unusually low" and readiness penalties built for non-postpartum adults; counts naps toward daily sleep. | Baby DOB, feeding type; existing cycle and readiness code. | Strong (avoids known false signals) |
| 2 | **Alcohol → next-feed estimate** | After a drink is logged, "CDC suggests waiting ≥ 2 h per standard drink; estimated clear for feeding around HH:MM", with "pumping and dumping doesn't speed this up". Daily guidance ≤ 1 drink. | Existing drinks tracker (already computes standard drinks) + mode flag. | Strong |
| 3 | **Warning-signs card + EPDS check-in** | Always-reachable CDC warning-sign list; optional EPDS at ~2 weeks, 6 weeks, 3 and 6 months; score shown with "talk to a professional" above cut-off; **item 10 → CVV 188 / SAMU 192 immediately**. Answers stay local, never in diagnostics exports. | Questionnaire storage; pt-BR translation (EPDS pt-BR validated). | Strong |
| 4 | **Night-feed / sleep fragmentation view** | Per night: wake bouts, longest uninterrupted sleep, total sleep including naps, with optional feed markers (one-tap "feed" with side, or imported). Trend vs week 2 (Montgomery-Downs: efficiency ~80 → 90% by week 16) framed as "this is normal and improves". | Existing sleep sessions/blocks + new feed log entries (journal tag or dedicated table). | Moderate–Strong |
| 5 | **Recovery-to-baseline trend** | RHR and HRV vs the user's own pre-pregnancy baseline, with expected shape annotated (RHR dips below baseline around week 7, ~6 months to settle; HRV rises back). No score, no colour-coded "bad". | Existing measurements; baseline from data ≥ 10 months before baby DOB if present. | Moderate |
| 6 | **Lactation-aware nutrition & fluid targets** | Calorie target +330 kcal (0–6 mo) / +400 (7–12 mo) for exclusive breastfeeding, scaled down for mixed feeding; floor warning if logged intake is persistently very low; fluid nudge (~3 L/day median, "drink to thirst"); DHA/iodine/infant vit D reminders as education. No weight-loss goal in the first months by default. | Existing nutrition/calorie log + mode. | Strong (calories), Moderate (fluids/supplements) |
| 7 | **Medication log: lactation lookup** | On each logged medication, a "Check in LactMed / e-lactancia" link (deep link by drug name); no in-app verdict. | Existing medications log. | Strong (as reference) |
| 8 | **LAM status helper** | Shows the three criteria, days until baby is 6 months, and flips to "LAM no longer applies" when a period is logged or feeding type changes to mixed. Explicitly informational. | Baby DOB, cycle log (bleed after day 56), feeding type. | Strong |
| 9 | **Temperature-deviation nudge** | If nightly skin-temp deviation is unusually high, "If you feel unwell or have breast pain, take your temperature; ≥ 38 °C after birth is a reason to call your clinician". Never "possible mastitis". | Existing skin temp (rings that have it). | Weak (signal), Strong (the fever threshold) |
| 10 | **Coach + Brazil resources** | Coach prompt gets the life-stage context (postpartum week, feeding type) and the do-not-claim rules above; resources screen with CVV 188, SAMU 192, Disque Saúde 136 / rBLH, e-lactancia. | Existing coach; static content via `tr()`. | — |

**Deliberately not recommended:** detecting first postpartum ovulation from ring temperature (no
validation in breastfeeding women); inferring milk supply or feed events from HR/motion; HRV-based
depression risk; a full baby tracker (diapers, growth, milestones) — that is Huckleberry/Glow
territory, and Health Connect has no baby data types to import it from.

**Privacy note:** postpartum mood answers, EPDS scores and feed logs are sensitive. Per the
existing diagnostics rule in `AGENTS.md`, none of them should appear in the diagnostics export.
Health Connect has no record type for EPDS or feeds, so nothing new would be exported there.

---

## Sources

- [Sarhaddi et al. 2022 — Trends in HR and HRV during pregnancy and the 3-month postpartum period (JMIR mHealth uHealth)](https://mhealth.jmir.org/2022/6/e33458/) · [DOAJ record](https://doaj.org/article/f6ff721b9ac14fba97ef6ecaa7f697b2)
- [Association between wearable sensor signals and expected hormonal changes in pregnancy (eBioMedicine 2025)](https://www.thelancet.com/journals/ebiom/article/PIIS2352-3964(25)00332-9/fulltext) · [Scripps press release](https://www.scripps.edu/news-and-events/press-room/2025/20250911-quer-wearables.html)
- [Monitoring one heart to help two: HRV and RHR with wearables across the perinatal period (BMC Pregnancy Childbirth 2022)](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC9710029/)
- [Byfield et al. 2025 — Scoping review of wearable HRV in pregnant and postpartum people](https://journals.sagepub.com/doi/10.1177/10998004251325212)
- [Shinba et al. 2024 — HRV as a point-of-care tool for screening postpartum depression](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC10934039/)
- [Eriksson et al. 2024 — Pregnancy HRV as predictor of postpartum depression and anxiety](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC11094065/)
- [Depressive symptoms and HRV in perinatal women: narrative review](https://pmc.ncbi.nlm.nih.gov/articles/PMC11773373)
- [Montgomery-Downs et al. 2010 — Normative longitudinal maternal sleep, first 4 postpartum months (AJOG)](https://pubmed.ncbi.nlm.nih.gov/20719289/)
- [Srimoragot et al. 2023 — Infant feeding type and maternal sleep: systematic review and meta-analysis (J Sleep Res)](https://onlinelibrary.wiley.com/doi/full/10.1111/jsr.13625)
- [Doan et al. 2014 — Nighttime breastfeeding and nocturnal sleep at one month (JCSM)](https://jcsm.aasm.org/doi/full/10.5664/jcsm.3538)
- [Mixed-methods study of perinatal sleep and breastfeeding in women at risk for PPD](https://pmc.ncbi.nlm.nih.gov/articles/PMC9665349/)
- [Using complete breastfeeding and lactational amenorrhoea as birth spacing methods (PubMed)](https://pubmed.ncbi.nlm.nih.gov/10899480/)
- [Wrist wearables capture menstrual-cycle temperature changes](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC6265623/)
- [Accuracy of wrist skin temperature vs BBT for ovulation (excludes breastfeeding women)](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC8238491/)
- [Gombert-Labedens et al. 2024 — Wearable skin temperature and the menstrual cycle](https://pmc.ncbi.nlm.nih.gov/articles/PMC11294004/)
- [Contraceptive efficacy of lactational amenorrhoea (Lancet 1992, PubMed)](https://pubmed.ncbi.nlm.nih.gov/1346183/) · [IRH LAM FAQs](https://www.irh.org/wp-content/uploads/2013/04/LAM_FAQs.pdf)
- [CDC — Alcohol and breastfeeding](https://www.cdc.gov/breastfeeding-special-circumstances/hcp/vaccine-medication-drugs/alcohol.html)
- [CDC Hear Her — Urgent maternal warning signs](https://www.cdc.gov/hearher/maternal-warning-signs/index.html)
- [health.gov — Nutrition during breastfeeding (DGA 2020–2025 energy needs)](https://health.gov/news/202108/nutrition-during-breastfeeding-how-health-professionals-can-support-healthy-moms-and-babies)
- [KellyMom — Do breastfeeding mothers need extra calories / fluids](https://kellymom.com/nutrition/mothers-diet/mom-calories-fluids/)
- [Ernährungs Umschau 2023 — Supplements during lactation](https://www.ernaehrungs-umschau.de/english-articles/25-10-2023-supplements-during-lactation)
- [LactMed introduction (PubMed)](https://pubmed.ncbi.nlm.nih.gov/17210549/)
- [e-lactancia — drug compatibility explainer (LactApp)](https://blog.lactapp.es/en/drug-compatibility-and-breastfeeding/) · [e-lactancia example record](https://e-lactancia.org/breastfeeding/apasmo/tradename/)
- [ABM Protocol #36 commentary (Int Breastfeed J 2023)](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC10481477/) · [LactApp summary of ABM #36](https://blog.lactapp.es/en/the-abm-protocol-on-mastitis/)
- [NHS — Mastitis](https://www.nhs.uk/conditions/mastitis/)
- [ACOG District II — Postpartum preeclampsia in the ED checklist](https://www.scdhhs.gov/sites/default/files/ACOG-District-II-Checklist-Postpartum-Preeclampsia-in-the-ED.pdf) · [JUCM — Postpartum presentations: headache](https://www.jucm.com/postpartum-presentations-when-risk-arises-after-delivery-headache/)
- [ACOG Committee Opinion 804 summary (OPQIC)](https://opqic.org/acog-committee-opinion-no-804-physical-activity-and-exercise-during-pregnancy-and-the-postpartum-period/)
- [EPDS validation in a Brazilian general population (PMC4203969)](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC4203969/) · [EPDS validation, Pelotas postpartum cohort (Cad Saúde Pública 2007)](https://scielosp.org/pdf/csp/2007.v23n11/2577-2588/en) · [EPDS scoring and item 10 guidance](https://help.blueprint.ai/en/articles/3945631-edinburgh-postnatal-depression-scale-epds)
- [Oura — supporting members during pregnancy and beyond](https://ouraring.com/blog/oura-supports-members-during-pregnancy-and-beyond/) · [TechRadar — Oura Pregnancy Insights](https://www.techradar.com/health-fitness/fitness-trackers/ouras-new-pregnancy-insights-feature-is-a-step-in-the-right-direction-for-womens-health-monitoring)
- [WHOOP — reproductive health through all life stages](https://www.whoop.com/de/en/thelocker/whoop-features-support-reproductive-health-through-all-life-stages)
- [Natural Cycles — How NC° Postpartum works](https://help.naturalcycles.com/hc/en-us/articles/18589315952285-How-NC-Postpartum-works) · [Using the app while breastfeeding](https://help.naturalcycles.com/hc/en-us/articles/360003291094-How-to-use-the-app-while-breastfeeding)
- [Garmin — Natural Cycles integration](https://www.garmin.com/en-NZ/blog/natural-cycles-integration-unlocks-non-hormonal-birth-control-app-for-users-with-select-garmin-smartwatches/) · [MobiHealthNews — Garmin adds pregnancy tracking](https://www.mobihealthnews.com/news/garmin-adds-pregnancy-tracking-capabilities-smartwatches)
- [Huckleberry (App Store)](https://apps.apple.com/us/app/huckleberry-baby-tracker/id1169136078) · [Glow Baby (App Store)](https://apps.apple.com/us/app/-/id1077177456) · [Ovia Parenting (App Store)](https://apps.apple.com/app/id1106614359)
- [RingConn — postpartum sleep and recovery trends (finger temperature caveats)](https://ringconn.com/blogs/guides/postpartum-recovery)
- [CONASS — CVV 188 free nationwide](https://www.conass.org.br/?p=14185) · [CVV quarterly report Q4 2025](https://cvv.org.br/wp-content/uploads/2026/02/RelatorioCVV_4Tri_2025.pdf)
- [Ministério da Saúde campaign on milk donation (Itatiaia)](https://www.itatiaia.com.br/brasil/leite-materno-ministerio-da-saude-lanca-campanha-nacional-e-incentiva-a-doacao/) · [SBP — importance of human milk donation](https://www.sbp.com.br/importancia-da-doacao-de-leite-humano-e-ressaltada-em-novo-documento-da-sbp/)
- [Guia Alimentar para Crianças Brasileiras Menores de 2 Anos (SES-MG PDF)](https://www.saude.mg.gov.br/wp-content/uploads/2018/03/23-03-guia_alimentar_2anos-85b.pdf)

Items marked "background knowledge" or "not re-verified" (US iodine RDA 290 µg, SAMU 192, SUS
puerpério visit, Ministério da Saúde's alcohol wording, pelvic-floor specifics) should be checked
against the primary source before they reach UI copy.
