# What is wrong with the models, and what would fix it

Written 2026-09-01, the afternoon before the draft, at Justin's request after a
day that felt like circles. The circles were real and this says where they came
from. Every number here is from a committed tool; the tool is named beside it.

## The one-paragraph version

**The model's recommendation is robust and the measurement apparatus around it
was not.** The pick-7 answer is RB, separated from every other position by more
than two paired standard errors, under all four projection feeds. That has not
moved through a single change made today. What moved, repeatedly, were the
*numbers I used to judge the model* - and most of today's "findings" were
faults in those measurements, not in the model. The room model is fitted on
857 selections (2021-2025, sixteen rounds; `TrainingRows`) and is at or near
the accuracy floor that much data allows;
tuning it further by adding features is fitting noise, and the nine-round gate
proved that twice.

## The components, and what is established about each

The system is eight pieces. For each: what it does, what is measured, what is
wrong, and how confident the measurement is.

### 1. Projections - the value curve

`LiveBoard.thisYear` builds each position's rank-by-rank curve from Sleeper's
2026 projections, keepers removed.

| measured | tool | confidence |
|---|---|---|
| pick-7 verdict is RB under sleeper, espn, cbs and a blend | `BoardSourceCheck` | high - four feeds, one answer |
| preseason projections predict the season at spearman 0.56-0.59 | `AccuracyShootout` | high - 5 seasons |
| defence projections predict worse: slope 1.26 +- 0.53, spearman 0.22 vs skill 0.39-0.55 | `PositionTrust` | medium - 5 seasons, wide bar |
| held men indexed the wrong pool; Chase priced 29 under his own number | `HeldManCurveIndexTest` | fixed, pinned |

**What is wrong:** nothing decision-relevant. **What cannot be fixed:** the
0.56-0.59 is the fog. No downstream model can be more accurate than its
inputs, and every "improvement" that claims to be is measuring something else.
Whether a blended feed beats Sleeper is unanswerable - the repo holds one
historical alternate file.

### 2. Historical scatter - uncertainty around each rank

Sixteen nflverse seasons of what men at each rank actually returned, as
ratios. `BoardValue.pools`, `LiveBoard.defenceScatter`.

| measured | tool | confidence |
|---|---|---|
| draftable-vs-full-board rank offset is worth 0.01 SD | `RankIndexCheck` | high |
| receivers scatter 30% less than backs (0.33 vs 0.43) | `RankIndexCheck` | high |
| the scorer is honest: hindsight fill worth +114 vs a 35-point bar | `ScorerHonestyAudit` | high |

**What is wrong:** nothing found. This is the most solid piece.

### 3. The room model - who takes whom, when

`BoostedSelectionModel`, 300 trees depth 2, fitted on this league's 2021-2025
drafts - 857 selections at the live sixteen-round schedule (`TrainingRows`).
**This is where the day went.**

| measured | tool | confidence |
|---|---|---|
| fidelity on held-out seasons, worst band gap: RB 8.0, WR 5.1, TE 10.7, QB 12.4 | `RoomFidelity -PfullRounds=true` | medium - TWO seasons |
| defences on the 2026 board: 0% before round 10 (real 0%), but 57% in 10-13 vs real 29% | `DefenceReality` | high on the floor, medium on the shape |
| held-out calibration at Justin's own slots: 0.45% vs linear 1.56% | `BoostLab -PscheduleRounds=16` | high |
| the league waits on TE (1.15), QB (1.22), DEF (1.16) and follows the market on RB/WR | `MarketDrift` (keeper-corrected, relative) | medium |

**What was tried today, and what each did to defences-before-round-10:**

    DEF intercept (f23)                 19% -> 17%
    draft depth + interactions (f24-28) 17% -> 10%
    floor: never earlier than observed  10% ->  0%   <- the only thing that closed it
    per-position run terms              nothing; removed
    ADP offset by league drift          WORSE twice (0.45% -> 0.84% then 0.60% at slots); removed
    opponent temperature                nothing (0.02 men over 80 cells)
    scarcity over replacement (f29)     mixed; helps RoomFidelity, hurts at-slots; OFF

**What is actually wrong, stated plainly:**

- **It is data-limited.** 857 training selections in the shipped fit
  (`TrainingRows`); the nine-round gate that judges features fits on 423. Every
  feature added is fitted to that, and the gate degraded from 0.60% to 1.00% the
  moment five extra columns were left switched on there. (I quoted "435" for
  most of the day - the gate's size, not the shipped fit's. Wrong population,
  right conclusion; TRAPS #59.) The 5-12 point fidelity
  errors are probably close to the floor for this much data. **Adding features
  is not the lever.** I spent most of the day pulling it anyway.
- **The defence timing is a hard boundary, not a soft shift.** 0 of 58 before
  round 10 is a support constraint. Soft features cannot learn a hard edge from
  58 observations - the intercept and depth got it to 10% and stopped. The
  floor is the correct instrument for a boundary, and it is *learned from the
  data* - it is round 1 for every skill position and binds nowhere else. Justin
  objected that a floor is "not a model"; the honest reply is that the
  empirical support of a distribution *is* part of its model, and the two
  attempts to replace it with continuous features both failed on held-out data.
- **What the floor does not fix** - 57% of defences in rounds 10-13 against a
  real 29% - is a soft shape on 58 observations. It is not going to be fixed
  by anything fitted to those 58 observations.
- **TE is consistently off (~11 points both seasons), and the reason is now
  known: it is a limit, not a bug.** `TightEndHabit` shows the model cannot
  tell WHICH manager reaches for a tight end (real-vs-sim correlation -0.09 and
  0.19), and the real data shows why nothing could: a manager's own first-TE
  round in 2024 predicts his 2025 round at spearman 0.01. The habit does not
  persist year to year. The aggregate gap is a bimodal reality averaged into a
  unimodal simulation, and it stays with this much data. Seven TEs are kept this
  year, so the 2026 comparison is confounded and only the held-out one counts.

### 4. The survival table - who is still there at each of his seats

Built at warm from 200 simulated drafts. `LiveBoard.Survival`.

| measured | tool | confidence |
|---|---|---|
| vs the retired ADP cutoff, against the league's REAL 2024 and 2025 drafts: 1.19 men error vs 3.35 | `RealDraftSurvival` | high - real drafts, held out |
| the unconditional approximation costs 0.01 men vs the exact conditional | `MidDraftRank` | high |

**What is wrong:** it inherits every room-model error above, and cannot do
otherwise. It is a good table built on an imperfect room.

### 5. The objective - what a finished roster is worth

`BoardValue.oneSeason`: QB 1, RB 2, WR 3, TE 1, DEF 1, FLEX 2 - ten slots,
the league's real lineup. Lineup set on what was KNOWN, scored on what
HAPPENED. Bench earns its place through the availability channel.

| measured | tool | confidence |
|---|---|---|
| honest (see #2) | `ScorerHonestyAudit` | high |
| `lostBelow` cannot be identified; the model reproduces real bench returns with zero fitted parameters | `BenchCalibration` | high |
| `MOST` appetite cap no longer binds | `AppetiteCapTripwireTest` | high |

**What is wrong:** nothing found. **Note:** Model A scores a *different*
objective - nine skill slots, no defence - which is why it is silent after
round 7 and why its 16-round tail is six backs and no defence. Documented,
pinned by `TwoObjectivesTest`. Not a bug.

### 6. The rollout - completing a roster from a candidate pick

`LiveBoard.rolloutRoster`. Greedy tail, now with legality reserved.

| measured | tool | confidence |
|---|---|---|
| 0 of 5 tails finish without a defence (was: often) | `TailLegality` | high |
| defence timing across the whole axis spans 107 points vs a 125 bar - the round is not measurable | `DefenceTiming` | high |

**What is wrong:** nothing remaining. The round-7/8 defence was a tail that
could imagine an illegal roster; fixed.

### 7. Roster rules - legality

`RosterRules`. Ceilings derived from the lineup, never typed.

**What is wrong:** nothing. TRAPS A1's amnesia (a declined pick leaving no
trace) closed today via `holdAnyway`. Every route to an illegal roster is
tested.

### 8. The screen

`LiveBoard.answer`, and `Draft2026` around it.

| fixed today | consequence if not |
|---|---|
| drift detector fired 137/168 on a clean board | would have told him to abandon the tool from round 3 |
| KN arbiter dead since defences joined the board | the tiebreaker never ran |
| legend named the wrong ranking column | reader takes a different player than the tool |
| one bad Sleeper read ended the session | re-pay the whole warm against the clock |
| legend reprinted every pick, 23 of 45 lines | verdict pushed off screen |
| schedule built once at warm, owner of each pick never re-read | a mid-draft pick trade would misattribute his roster silently; now SEAT OWNER MISMATCH names the picks and says restart |

Added: paired margin between top two; the whole tied set when not separated;
the men actually available at a coin-flip seat; who else is likely there when
the named man is under 60%.

**What is wrong:** nothing found. Cycle is 25s of 60.

## Where the circles came from

Reading the day back, the model changed little and my measurements of it
changed constantly. Specifically:

1. **No noise floor was ever established before comparing variants.**
   `RoomFidelity` differences of 0.5-1.0 points were read as signal. They
   were compared across single runs with different tree configurations
   (BoostLab's "best cell" changes between runs). Any comparison smaller than
   the seed-to-seed spread is noise, and I never measured that spread.
2. **Measurements kept being contaminated, and each contamination looked like
   a model finding.** Keepers counted as draft decisions (changed RB from 5.0
   to 8.0 and QB from 15.6 to 12.4). Historical schedules capped at nine
   rounds (every late band read 0%). Scarcity's replacement level taken from
   the choice set (~TE2), then from a pool with the keepers still in it, then
   recomputed every pick. A harness that never warmed the survival table.
   Three tools certifying configurations nobody runs.
3. **Symptoms were fixed one feature at a time on a data-limited model.** Each
   feature was fitted to 857 selections (423 in the gate), moved a held-out number by less than its
   noise, and was kept or dropped on that basis.
4. **A retracted justification was left in shipped code.** The defence curve
   stayed flattened for hours after I withdrew the 0.019 spearman that
   justified it.

## What would actually solve it

In order. The first two are the ones that break the circle.

1. **Establish the noise floor and refuse to read below it.** DONE, same
   afternoon, and it is the number that validates this whole document.
   `RoomFidelity -PfullRounds=true -Pseeds=5` - the identical held-out
   measurement on five seed sets, nothing changing but the dice:

       POS    mean    min    max   spread
       RB      7.8    7.2    8.3     1.1
       WR      5.6    5.1    6.0     0.8
       TE     10.8   10.2   11.4     1.3
       QB     13.4   12.4   14.2     1.8

   Every room-model feature change made today moved these numbers by 0.1 to
   1.0 points. **Not one of them was distinguishable from a different roll of
   the dice.** The scarcity feature, the run terms, the ADP offset, the
   temperature sweep, the relative scaling - all of it was read inside this
   band. The two things that DID exceed it were the floor (defences 10% to 0%
   before round 10, a different metric entirely) and the population fixes to
   the measurement itself.

2. **Freeze the room model's feature set.** f0-f28, scarcity off. Stop adding
   features to an 857-selection fit. Accept that 5-13 point positional timing error
   is what five seasons of one league can support. Every gate is green at this
   configuration and the recommendation has not moved.

3. **Keep the floor, and say what it is.** A learned support constraint from
   58 observations with none before round 10. It is the right instrument for a
   hard boundary and the wrong one for a soft shape, and it should not be
   asked to do the second job.

4. **Measure decision impact, not fidelity.** The question that matters is
   whether any room-model variant changes what the board recommends at his
   seats. `OpinionCount` already answers it: 3 of 14 seats separated in every
   simulated room (7, 31, 66), 2 coin flips in every room (90, 127), 9
   room-dependent. That is the honest state and no feature today moved it.

5. **Fix the measurement harness once, structurally.** DONE: `forkEvery = 1`
   in `build.gradle`, one JVM per test class, nothing left to leak into. The
   in-suite `RoomTimingTest` failure was shared static state between classes -
   the same fault class as everything above. Two hypotheses about *which*
   state were wrong (one of them, `ModelAScheduleTest` restoring "9" into the
   JVM, was a real leak and is fixed regardless); the third, structural fix
   makes the question moot. `RoomTimingTest` now also prints the ambient state
   it sees in its failure message, so if it ever fails again the message says
   why. Full check under the new setting: **green, 15m 3s** (was 9m with
   shared JVMs; the six minutes buy a suite whose classes cannot poison each
   other).

6. **For TE, the one real unknown: measure, do not guess.** It is off by ~11
   on both held-out seasons and the mechanism is not known. The tool to build
   is a per-manager TE-timing check on real drafts - which managers take a TE
   early, and does the simulation give *those* managers the same habit. That
   is a population question, not a feature question. **Written as
   `TightEndHabit` the same evening; not yet run** - it reports, per held-out
   season, each manager's real first-TE round against his simulated one and the
   rank correlation across managers. 1.0 means the model knows WHO reaches; 0
   means it is guessing which manager.

   **RUN, same evening, and it closes the question.** Real-vs-simulated
   correlation across managers: **-0.09 in 2024, 0.19 in 2025.** The model
   hands almost every manager the same 6-8 round habit. But the real column
   explains why no feature can fix it: KevinDA took his tight end in round 3 in
   2024 and round 16 in 2025; itsabust round 2 then round 5; BHier 13 then 10.
   **A manager's own 2024 round predicts his 2025 round at spearman 0.01 over
   the ten managers in both seasons.** (I first wrote "-0.32 over nine" here
   before the measurement had run - a number typed ahead of its evidence, the
   exact fault this document is about. Corrected in the same hour.) The habit
   does not persist, so there
   is nothing in the history for a per-manager feature to learn. The ~11-point
   aggregate TE gap is the model averaging a bimodal reality - some rooms
   reach in rounds 2-4, some wait to 13-16 - into a unimodal 6-8, and it will
   stay that way with this much data. **Not a bug. A limit.** Stop here.

7. **Then stop.** The draft is tonight. What is on screen is verified,
   robust across feeds, and honest about its own uncertainty. The remaining
   errors are inside the fog of the projections themselves.

## HEAD against the restore point: run HEAD

Justin: "can we fix things to be better than the restore point". They already
are, on every axis that clears the noise floor, and the restore point exists as
insurance only. Head to head, same tools, same real drafts:

| axis | `draft-ready-2026` (tag) | HEAD | tool |
|---|---|---|---|
| who is gone by a later pick, vs the league's REAL 2024/2025 drafts | 3.35 men error (ADP cutoff) | **1.19 men** (survival table) | `RealDraftSurvival` |
| simulated defences before round 10 (real: 0 of 58) | 19% | **0%** | `DefenceReality` |
| rollout tails that imagine a roster with no defence | often - produced a DEF in round 7-8 in 5 of 6 drafts | **0 of 5** | `TailLegality` |
| a held man priced at his own projection | Chase 29 points under | **exact** | `HeldManCurveIndexTest` |
| schedule-drift warning on a clean board | fires 137 of 168 refreshes | **0** | `DriftAlarmCheck` |
| Kim-Nelson tiebreaker in the live tool | dead - threw on the first defence | **runs, PROVEN RB in 126 rollouts** | `Draft2026` |
| legend names the column the code ranks on | no (VS WAIT) | **yes (END TEAM)** | `TableLegendTest` |
| one failed Sleeper read | ends the session, re-pay the warm | **survives, engines stay warm** | `CycleSurvivesAFailureTest` |
| a pick the rules refuse still occupies its seat | no (TRAPS A1 open) | **yes** | `DeclinedManStillCountsTest` |
| margin between top two positions | printed as a verdict, no error bar | **paired 2 s.e.; tied set named; men listed at a coin flip** | `MarginTest`, `OpinionCount` |
| pick-7 verdict | RB | RB, separated, under four feeds | `BoardSourceCheck` |
| tests | 389 | ~510, one JVM per class | `./gradlew check` |

Nothing in the right-hand column is a tune inside the noise; each is a
correctness fix or a change validated on real drafts. The two things that are
the same - the pick-7 verdict and the underlying projections - are the two
things that were already right.

**Tonight: run HEAD.** Use the tag only if HEAD throws at the table, which
`LivePathStress` says it does not across 42 priced picks.

## What tonight rests on

    PreFlight       ALL CLEAR - right draft, right settings, 14 seats, 24 keepers
    BoardSanity     161 men rank-checked, nothing violently inconsistent
    smokeTest       green against the live APIs
    pick 7          RB, separated by 2 s.e., under every feed
    defence         never before round 10 in simulation; taken at rounds 15-16
    cycle           25s of 60; press enter early

Restore point if anything misbehaves: `git checkout draft-ready-2026`.

## After the draft (2026-09-01, 22:15)

The draft ran to 192 picks with the tool live at every seat; no alarm fired.
`TeamRankings` (new, report-only) scores every roster's best legal lineup under
league scoring: Justin 9th of 12 by projected starters (1856), 1st by bench (976).
Two facts for the next model pass, both from the real 2026 board:

- **Defences went in rounds 6 and 9** (slots 1 and 2). "0 of 58 before round 10
  across five drafts" is now 2 of 70, and the learned floor
  (`DraftSimulator.floors()`, never earlier than the league has ever taken it)
  moves to round 6 the moment 2026 joins the training set. The floor was a
  description of five seasons, not a law of this room.
- **The two engines split at pick 18** (board model Hall +10.2, Model A Nabers
  +5.3) and nothing on screen said so. See the open task to name cross-engine
  splits explicitly.
- **The post-draft check went red on one test, `ModelAScheduleTest`**, which
  pinned an exact seven-round shape across the two schedules. The evening
  projections flipped round 2 between RB and WR in one schedule only; the plan's
  own gap there was 0.8 points against a 4.2 tie. Rewritten to the repo's tie
  convention (TRAPS #62). Everything else - 523 tests, including the three new
  tools - passed on the final code.

`DraftExpectation` (2026-09-02, report-only) simulates the draft 200 times from the
pre-draft league with the room model at every seat and sets each seat's expected
best-lineup starters against the roster actually drafted. First run: league mean
gain +17.8, which a faithful room cannot produce (both worlds draft the same pool).
Two room-model faults, both fixed the same day (fix-list item 10): the room left a
defence slot empty in 12% of simulated rosters and a quarterback in 2% (an empty
slot scores zero; slot 1, which owns the last pick of every even round, was left
short in 28% of drafts), and it took Jacobs at pick 40 in every draft on an ADP the
news had passed. With `DraftSimulator.mustFill` and `RecentCollapse` the mean gain
is -0.8, no simulated roster ends short, and held-out fidelity is unchanged within
0.2 of baseline at every position. Result: BHier +52 and tommyrads +47 out-drafted
their seats most, Justin +35 (third) from the ninth-best seat-and-keepers, JFMarino
-53 and Renteez -50 least; jerem9604 had the second-best seat and drafted 33 below it.

`OwnerLadder` (2026-09-02, report-only) breaks every owner's projected starters into
four rungs on one yardstick: the seat alone (his keepers phantomed - off the board, no credit, no slot burned - others as declared),
with his keepers, with the 10k ledger's best pair, and the roster he drafted. Keepers
were worth +15 (BHier) to +127 (jerem9604: Taylor + Bowers); five owners left value on
the table against the best legal pair on this yardstick, searched over every pair of their
top ledger candidates and kept men, each priced as a pair (TRAPS #74, #75): BHier 36 with
Watson r10 + Pitts r13 (the pair Justin named), tommyrads 21 with Rice + Warren, JFMarino 20
with Olave + Odunze, Justin 7 with Tuten + Flowers over Tuten + Purdy - inside two standard
errors, and the 10k ledger's optimizer had Purdy ahead - JakeSK 2; drafting against seat-and-keepers ranged from +51 (BHier) to -52 (JFMarino), Justin
+36. The first run priced five owners' keepers below zero because the planner
defaulted to the nine-round schedule and dropped every keeper kept at round 10 or later;
the second read "seat alone" as a league where nobody keeps (the kept men return to the
board); the third let the owner keep nobody with his own two men back on the board, so an
owner who kept stars could redraft them and the column peaked at jerem9604's slot 9 and
fell 24 points at tommyrads' 11 - bumps Justin spotted and which were the counterfactual,
not noise (TRAPS #73). The seat is now measured with the owner's keepers phantomed, the
planner's own primitive for that question.

`Keepers16` (2026-09-04, report-only; `data/keepers16-<date>.txt` + `.html`) is the keeper
question asked on the SIXTEEN-round game instead of the nine-round one: the old keeper
finder (`KeeperChooser`, the 10k ledger) priced a round-12 or -13 keeper as if he sat at
round 8 or 9 and knew nothing of defences, injuries, or a bench. Same simulation as
OwnerLadder (`OwnerLadder.rungTrials`, the fitted room at every seat, trial t seeded the
same in every world), scored by one shared `WeeklyStarterValue`: seventeen weeks of the
best legal ten - defence included - chosen on preseason expectation and scored on a drawn
outcome, where an outcome is a whole observed player-season from the man's position:tier
cell (its games and its scoring together, applied as a ratio to his projection), so a
keeper's injury risk and boom-or-bust both come from the seasons men of his tier have
actually had. Worlds per owner: SEAT (his declared keepers phantomed), ALONE for every man
the rules let him keep at his real round (the other declared men phantomed), BEST PAIR by
search over the top men by ALONE value plus the declared men, each pair priced as a pair
with the same-round bump. Every +/- is the standard error of the trial-by-trial
difference. What the first two runs got wrong, and the fixes (TRAPS #76-#80): the alone
world phantomed the man being valued (a free pick inside every number); the declared
copy of a keeper overrode his priced copy, so a searched pair could share a round and
score seventeen men; the errors were unpaired; the pair pool could omit the declared
pair; a phantomed quarterback still fed the room's stacking feature; and the yardstick
itself carried a per-man sampling bias frozen across trials that moved one man's value by
fifty points between seeds - the weekly gaussian noise did nothing to the mean (the
lineup is chosen on expectation, so the score is linear in points given who is up) and
everything to that bias, so it is gone, the season and availability draws are stratified,
and `ObjectiveStability` measures what is left (worst seed-to-seed spread of a marginal
6.8 points at 480 scenarios, the default, `data/objective-stability-2026-09-04.txt`; the
week-level noise had put that at fifty). `BustBoomValue` follows the same draw so it is
still the objective at zero rates, and the hindsight test's two-quarterback fixture now
makes the backup's winning weeks through the season draw. The defence
wire is the streamed level (WireRateStress: 7.73 a week over 6.98 held), so a kept defence
is worth its edge over a manager who works the wire, not over one who never touches it.
What it says (`data/keepers16-2026-09-04.txt` + `.html`, 200 drafts a world, 480 outcome
scenarios): keepers are worth +11 (BHier) to +144 (JakeSK) against the seat, Justin's
Tuten + Purdy +67, and those two ARE the best pair his roster offered - the nine-round
ledger had preferred Flowers. Eight of the twelve kept the best pair available to them.
The four who did not: BHier +51 by swapping Daniels for Watson beside Pitts - the pair
Justin named himself from the season-total ladder - JakeSK +14 (Stevenson for Kraft),
tommyrads +13 (Rice for Caleb Williams) and JFMarino +12 (Odunze for McBride). The paired
errors are 3-4 points and the yardstick's own seed floor is 6.8, so a gap under about 8 is
not a finding. Known limits:
Sleeper's defence projections are a stub (sacks, interceptions, recoveries,
blocks - no points-allowed bands), so defence-versus-defence margins sit about a quarter
low; the same-round bump moves the lower-ADP man a round dearer in `KeeperPricing` and the
league's own direction is unconfirmed - the ruleset says the lower-ADP man, the one case
on record went the other way (open question for Justin); a pooled tier is twelve wide
over five seasons, sixty seasons a cell, and it is keyed by PROJECTION rank, which
`RankKeyChoice` measured as the better key: leave-one-season-out, a projection-keyed band
predicts a man's realised season 6.2 +/- 0.8 points better than an ADP-keyed one, in every
season separately (TRAPS #82, `data/rank-key-choice-2026-09-04.txt`), and the policy that
drafts off the valuation gains 84 points a season on real outcomes, ahead in all five
(both arms in `data/policy-backtest-poolkey-2026-09-04.txt`; that policy still trails the
committed RUNBOOK plan by 147 a season either way, which is the same file's own bottom
line). The historical boards it drafts from still tier men by ADP rank while the pool is
keyed by projection, so the 84 is measured with the pool and the board disagreeing - the
consistent configuration is not yet run (TRAPS #83). That also removes the name join, since the
projection feed and the weekly actuals are both by player id - so TRAPS #80's rank
compression, fixed the same day for the ADP path and every board that reads it, does not
arise on the pool's own path at all. The same switch widens the pool from 1466 seasons to
2896 and moves every replacement level; `-PpoolKey=adp` restores the old one.

## Fix list after the 2026 draft (ranked by value for the effort)

1. **DONE 2026-09-02 - One verdict line at the top of every pick** (`VerdictLine`, printed by `Draft2026` under each table; `VerdictLineTest` pins the pick-18 SPLIT wording). Original: SEPARATED (position, margin),
   TIE (the men available), or SPLIT (board model vs Model A, both margins). Four
   of Justin's questions on draft night were about reading the screen, not the
   model. Chip filed.
2. **DONE 2026-09-02, with a finding - Soften the learned floor and add 2026 to the training set.**
   *2026 in the training set:* the draft-night Sleeper feed is frozen as
   `sleeperProjectionsFinal2026.txt` (also under `data/fixtures/2026-pre-draft/`), the
   current season counts as history once that file exists, and `loadObservations`
   reads the league's own completed draft: `TrainingRows -PtrainTo=2026` reports
   1020 selections against 857. *2026 as a held-out target* (`RoomFidelity
   -Ptargets=2026`, shipped fit on 2021-2025, no fixture): worst-band gaps RB 3,
   WR 9, TE 11, **QB 31** - the 2026 room took 57% of its quarterbacks in rounds
   14-16 (history: 13-30%) while the model spread them over rounds 8-13. That is the
   season's real lesson for the room model, and the training set now carries it.
   *The floor as a prior:* implemented (`-PfloorWeight`, `softenFloor`) and first
   measured as "identical to the decimal at every weight" - an artefact: the
   historical fidelity board carried no defences (skill positions only, the
   nine-round rule), so on past seasons the simulated room could not draft one and
   the floor had nothing to move (TRAPS #71). With defences on the sixteen-round
   historical board, `RoomFidelity` gains a DEF row and the three arms differ: mean
   DEF worst-band gap over 2024-2026 is 23.8 with the hard floor, 22.4 at weight
   0.5, 21.2 with no floor - the gain is all 2026, where the real room took
   defences in rounds 6 and 9 and only a loosened floor can produce any - while QB
   worsens 17.4 -> 17.8 -> 18.4 and TE 10.8 -> 12.4 -> 11.1. Within one to two
   points of the noise floor either way, so the hard floor stays the default and
   the principled fix is 2026 in the training set, which moves the defence floor
   to round 6 on its own. The DEF row also shows a limit no floor touches: the
   room takes defences in rounds 10-13 far more than the league does (2025: 28%
   of simulated defences against 0% real; 2026: 53% against 25%). Knob kept,
   default 0. Original: The room took
   defences in rounds 6 and 9 after 0 of 58 before round 10; a hard "never
   earlier than ever" floor should be a prior with weight, not a wall. Rerun
   `TrainingRows` and `RoomFidelity -Pseeds=5` with 2026 in.
3. **DONE 2026-09-02 - Make the suite hermetic and fast.** The feed is pinned too: the full draft-night Sleeper response sits in the fixture directory as `sleeperProjections2026.txt` and is served to every unit test (a first attempt pinned the AdpSnapshot CSV archive, which holds the drafted pool only - six fixtures needing a defence or an undrafted man broke; `snapshot:<date>` and `-PadpSnapshot` remain as opt-in tools); the check runs in 13 minutes after the per-candidate lookup was removed (TRAPS #69). Earlier state: League STATE is now pinned: `data/fixtures/2026-pre-draft` is served to unit tests in place of the day's cache (TRAPS #65). Feeds still float and the two slowest classes are untouched. `ModelAScheduleTest` read the day's live
   feed and flipped when the feed moved; pin tests to a committed snapshot
   (`data/projection-snapshots.csv` already exists - add a `snapshot:<date>`
   source to `ProjectionSources`). Then shorten the two slowest classes (chip
   filed). Target: a full check under 15 minutes on a quiet machine.
4. **DONE 2026-09-02 - Model A within the clock.** Engines run cheap-first inside a 25s budget (`-PcommitteeSeconds`), lookahead-2 takes the rollouts that fit (79 of 150 on the real pick-7 board), and the KN arbiter runs only on a split vote. `CycleTiming` now freezes the first six picks of the real 2026 draft: worst cycle 27.4s, from 42s on draft night (TRAPS #64). Measured 44s at pick 7 against 25s documented;
   lookahead-2 took 23s and the KN arbiter 10s. Either budget it to land inside
   30s or run it concurrently with the board model so the screen never waits.
5. **DONE 2026-09-02 - Injury tags on the live tables.** Sleeper's injury_status is in the same
   response the model reads (`MarketMovers` already parses it); print IR / PUP /
   NA / Sus next to a man's name so a full projection on a hurt man is visible.
6. **DONE 2026-09-02 - Skip keeper picks in the owner-mismatch check.** Keepers cannot be traded
   mid-draft, and a mock built from the league copies them without a league
   user, which is the only reason the rehearsal screamed. Zero loss of detection.
7. **DONE 2026-09-02, with a caveat - Run `AdpSnapshot` daily for real.** A launchd agent cannot read `~/Documents` (TRAPS #63), so in draft season it runs with the `/today` brief; the launchd version is in `tools/launchd/` for when access is granted. It ran on five of the last eight days;
   the movers report is only as good as the history. A scheduled task, not a
   memory.
8. **Set up the season-outcome check now.** Bench is worth zero in the objective
   and Justin finished 1st by bench, 9th by starters. Only actual results can say
   whether that trade was right; `LeagueActuals` and `TeamRankings` against
   December standings is the measurement. Start collecting from week 1.
9. **PR `keeper-rules` -> `master`** (due 2026-09-03).
10. **DONE 2026-09-02 - Two room-model limits `DraftExpectation` exposed.** Fixed the same day: `mustFill` (a roster ends legal; TRAPS #66) and `RecentCollapse` (a man whose projection dropped 30%+ in the last two weeks is re-slotted to the price of his projection; the first, disagreement-based version hurt QB fidelity by 2.5 and was replaced, TRAPS #67). League mean gain +17.8 -> -0.8; RoomFidelity within 0.2 of baseline. Original finding: Across 200
    simulated drafts the fitted room leaves a DEF slot empty in 12% of rosters
    (real managers: never) and strands 67 more projected points per roster on
    the bench; and it takes collapsed men at their national ADP - Jacobs at pick
    40 in every simulation with an 80-point projection, where the real room let
    him fall to 105. Together they put every seat's expectation ~18 points below
    what the league actually drafts. Fixes: a roster-need term for DEF (the
    learned floor says when a defence CAN go, nothing says one MUST), and let a
    projection far below a man's ADP rank lower his choice probability.

