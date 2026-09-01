# Draft night, 2026-09-01 20:45

## Run this. Start it before the draft does.

    ./gradlew run -Pmain=Draft2026 -Pkeepers=Tuten,Purdy -q

Warms once, about **30s** (26s of engines plus 3s for the survival table).
Then press enter at each of your picks.

**The board model answers in under a second. Model A follows, and the whole
cycle is 25s of your 60.** So press enter EARLY - the moment the pick before
yours lands, not at :40. If the clock is short, the board model's line is the
one to read: it is the model that knows the 24 keepers, and it is already on
screen while Model A is still thinking.

Rounds 8-16 Model A goes quiet and says why. That is deliberate, not a failure.

**Rank on the END TEAM column.** ADDS NOW and VS WAIT explain the pick; END
TEAM decides it. Where they disagree, END TEAM wins.

If something looks wrong mid-draft, the restore point is one command:

    git checkout draft-ready-2026

Everything after that tag was added on 2026-09-01. It is better tested than
the tag - 491 tests against 389 - but the tag is what a full week of
independent verification stands behind.

Fallbacks if the main tool misbehaves:

    ./gradlew run -Pmain=DraftNight -Pkeepers=Tuten,Purdy -q       # rounds 1-7
    ./gradlew run -Pmain=LiveLateRounds -Pkeepers=Tuten,Purdy -q   # rounds 8+
    ./gradlew run -Pmain=PairwiseOdds -Pfrom=79 -Pto=90 -q         # wait odds

## The truthful ranking, measured 2026-09-01

Every headline number in this repo was measured on a board twenty-two deep men
richer than Justin's. On the real one - `-PholdKeepers=true -PleagueKeepers=true`
- the field collapses:

    starter-sum (1-16)        1848
    RB-heavy folk rule        1842
    RUNBOOK as written        1840
    RUNBOOK front + SS back   1837
    RUNBOOK committed         1833
    board value               1812
    ------------------------------
    best available by ADP     1524

Six strategies inside THIRTY-SIX POINTS against a 125-point bar. That is a dead
heat, and it is the honest end of a week's work: on this league's real board the
choice of plan is not a measurable quantity. The only result that survives - the
only one that ever did - is that every plausible plan beats drafting off ADP by
about three hundred points a season.

Which is worth saying plainly: the value was never in picking the right plan. It
was in having one at all, and in the model refusing the picks that are actually
wrong - a third quarterback, a man somebody else keeps.

**Corrected 2026-09-01, overnight.** That sentence used to end "...and a defence
before round ten." Two things were wrong with it. The model did not do it - an
adversarial pass found a defence in round 7 or 8 in five of six drafts - and
the preference was never measurable in the first place. `DefenceTiming` holds
the roster shape fixed and moves only the round the defence goes in:

    pick   7  1824.6      pick  79  1926.3      pick 127  1925.2
    pick  18  1868.4      pick  90  1929.7      pick 162  1922.0
    pick  31  1879.6      pick 103  1927.9      pick 175  1917.9
    pick  42  1903.4      pick 114  1930.8 <-   pick 186  1917.7
    pick  55  1914.5
    pick  66  1924.1      whole spread 106.2, against a 125-point bar

The peak IS round 10, which is where the heuristic pointed; round 8 is 1.1
points off it; and the entire axis from round 1 to round 16 fits inside one
bar. So the round is not a measurable quantity and it was never a refusal the
objective could enforce. The ordering is smooth and sensible in both directions
- too early wastes a premium pick, too late gets a worse defence - which is
more than noise would give, but no single comparison clears the bar.

## Verified at the tag

    full suite                green, 389 tests
    Model A shape             RB WR RB WR WR WR TE QB QB
    BoardValue backtest       mean 1935, worst 1792, hindsight-free
    RUNBOOK / DraftNight / LiveLateRounds   zero diff since the freeze

Re-verified after the overnight fixes, 2026-09-01:

    full suite                green, 491 tests
    Model A shape             RB WR RB WR WR WR TE QB RB
                              rounds 1-7 unchanged; round 9 was QB, and the
                              cap now counts Purdy - Purdy r8, Tuten r9, which
                              is how you described Model A in the first place
    BoardValue backtest       mean 1931, worst 1748
    board at pick 7           RB Cook 1917.5 > WR Lamb 1892.5 >
                              TE Fannin 1829.1 > DEF Rams 1829.2;
                              a second QB refused by the rules
    live path, 3 full drafts  42 picks priced, 0 throws, every roster legal
    defence taken at          round 9 in all three

**Corrected 2026-09-01.** The line above used to read "5 full drafts, 70 picks,
defence at round 10 in all five". Those numbers were real but they measured the
WRONG CONFIGURATION: `LivePathStress` never called `warmSurvival`, so
`LiveBoard.SURVIVAL` was null and every rollout inside the `answer()` it
exercises fell back to the retired ADP cutoff. It was certifying last night's
estimator, not tonight's. It warms the table now and prints which rule it
measured. With the shipped configuration the defence goes at round 9 rather
than round 10 - `DefenceTiming` prices those at 1927.9 and 1930.8, so 2.9
points against a 125-point bar, immaterial either way.

The backtest moved 4 points on the mean and 44 on the worst season. Both sit
well inside the 125-point bar, and everything that moved them was a bug fix -
held men priced at their own projection, and a rollout that can no longer
imagine a roster with no defence.

## What is TRUE about the model here

- 24 league keepers are off the board. It will not name a man somebody owns.
- Roster arithmetic is unconstructible-wrong: no third quarterback, no pick in
  a keeper round, no seventeenth man, no unfieldable lineup.
- The lineup is set on what was KNOWN and scored on what HAPPENED. A bench man
  earns his place through availability - the starter being lost - not through
  hindsight.
- Tiers are Boris-Chen-shaped: groups you cannot tell apart, from 2026
  projections and sixteen years of measured scatter.
- 0.5s per pick warm for the board model; see the measured cycle below.

## What is KNOWN TO BE WRONG, and is the overnight list

1. ANSWERED 2026-09-01, and it was MATERIALLY MISLEADING, not merely imprecise.
   `EraSlate` transplants all twenty-four keepers by positional ADP rank and
   skips the twenty-four picks they spend; `-PleagueKeepers=true` turns it on
   and it is OFF by default, so every figure above still reproduces.
   `KeeperSlateImpact` scores both boards in one process:

       every strategy loses 55 to 196 points  -  a 141-point spread, over the bar
       the serious field narrows from 95 points wide to 65, both under the bar
       the plan falls from 2nd to 7th and starter-sum rises from 6th to 1st
       plan vs BoardValue: +72 becomes +57, a tie either way, and the paired
       standard error nearly doubles (51 -> 87)

   So no, it does not change which model wins - it removes the grounds for
   thinking one did. On the board Justin actually faces, seven strategies sit
   inside fifteen points of each other and the ORDER of positions in rounds
   1-9 stops being measurable, because the men who made an early back or an
   early receiver worth something are on somebody else's roster.
   Data: `data/keeper-slate-impact-2026-09-01.txt`, `data/keeper-slate-2026-09-01.txt`.
2. ANSWERED 2026-09-01 for `lostBelow`, and the answer is that IT CANNOT BE
   FITTED, which is a better answer than a number. `BenchCalibration` prices
   what the model says a bench pick adds to a realistic roster - keepers plus
   Model A's seven, every starting slot covered - and asks it to reproduce what
   this league's 434 real bench picks returned over the wire: 44.0 in rounds
   8-9, 32.8 in 10-12, 31.2 in 13-16.

       threshold, all lost -> wire   best chi-square  9.7 at lostBelow 1.10
       threshold, all lost -> best   best chi-square 16.1 at lostBelow 1.05
       blend, expected + L(drawn-expected)   33.8 at lambda 1.00

   None clears 5.99, so no setting of either form reproduces the measurement.
   The reason is a CEILING, not a mis-set knob: rounds 13-16 tops out at 22.5
   / 20.3 / 14.0 against a target of 31.2 +/- 6.6, so that band is out of reach
   at every setting swept. And the least-bad threshold, 1.10, is past the
   collapse - the backtest reads 1577 / 1472 there against 1970 at 0.85.

   THE TWO QUANTITIES ARE NOT THE SAME QUANTITY, and that is the finding. The
   model prices a LINEUP MARGINAL; `BenchValue` measured a man's OWN season
   over the wire whether or not he ever started. Priced on the target's own
   estimand, from the same pools, with nothing fitted at all, the model gives
   53.4 / 33.5 / 25.7 against 44.0 / 32.8 / 31.2 - chi-square 7.0 against a
   7.81 bar at three degrees of freedom, NOT REJECTED. So the outcome
   distribution is already right and the gap is entirely the question.
   Unchanged under `-PleagueScoredActuals=true` (target 45.4 / 33.8 / 32.2,
   chi-square 6.3), so it is not a units artefact.

   0.55 therefore STAYS, on the grounds that nothing identifies it: the
   backtest is flat from 0.40 to 0.85 (68 points against a 125-point bar) and
   the over-wire target has resolution (5.2 points per 0.1) but rejects the
   model rather than choosing a value. Blend lambda 0.5-0.75 backtests better
   in-sample (2051-2077 against 1935) and is chosen by every leave-one-season-
   out fold, but held out it is worth +108 - a tie. `-Plambda=0.6` reaches it.

   Still chosen, still unfitted: the 15% fragility bar. **Corrected
   2026-09-01:** it does not refuse anything on this board. `FragilityBinding`
   walks his fourteen seats and asks the shipped predicate - 66 position-picks
   priced, **0 refused**, widest swing 15.0% against the 15% bar, and 59 of the
   66 within two points of it. It is not dead code that could never fire; it is
   a threshold sitting ON the edge of the distribution rather than clear of it.
   Which means "the model refuses fragile picks" is not something to rely on
   tonight - the roster rules do the refusing, and they are tested.

   The swing statistic itself spans only **2.4 points** across a whole draft
   (12.6% narrowest, 13.6% median, 15.0% widest). A lower bar WOULD fire, so
   this is not a quantity with no variation - but it would be sorting picks on
   a 2.4-point spread when board-to-board noise here is measured in tens of
   points. The bar is both above the data and short of signal to place better.
3. ANSWERED 2026-09-01, and it CLOSES. The concrete worry was that Justin's
   draftable RB4 is James Cook, who is really RB6 once Taylor and Achane are
   kept - so the model attaches RB4's historical volatility to an RB6-quality
   man. `RankIndexCheck` measures how fast scatter actually moves with rank:

       RB   rank 2  SD 0.43     rank 12  SD 0.42
            rank 4  SD 0.42     rank 20  SD 0.45
            rank 6  SD 0.43     rank 30  SD 0.51

   Rank 4 against rank 6 - the size of the error this fault causes - is **0.01
   of a standard deviation**. Scatter is flat from rank 2 to rank 20 and only
   widens past 30, so a two-or-three rank offset is noise. Carried as a known
   fault since it was found; it can be dropped.

   Worth keeping from the same table: receivers scatter at SD 0.33 against
   backs at 0.43, about **30% less volatile**, which is a real cross-position
   fact and not an artefact of indexing.
4. ANSWERED 2026-09-01, and the answer is EXPECTATION. The scorer is honest.
   `ScorerHonestyAudit` scores each roster twice - shipped fill against a fill
   sorted on the week's realised points - and the hindsight fill is worth +114
   points a season, on all ten strategies and all five seasons, against a
   clustered 95% bar of 35. A scorer already cheating would show a premium of
   zero. So the honest model is NOT being marked down for being honest; the
   88 points it lost are a model-side difference, and 88 is inside the bar.
   Still not cleared, both catalogued: the availability channel, and the
   streamed-defence rate (C13), which bites only a roster holding no defence.
5. ANSWERED 2026-09-01, and better than the proposed fix. `MOST` no longer
   binds at all: raising every cap to fourteen changes neither the drafted
   roster nor the backtest, on either path. Two changes removed the need for
   it - the greedy tail now asks only whether a pick is LEGAL, and the bench
   earns its place through availability rather than hindsight, so the
   valuation self-limits.

   Kept as a backstop and pinned by `AppetiteCapTripwireTest`. The day that
   test fails, the valuation has stopped discriminating and wants
   investigating - NOT a tighter cap. Capping it would be hiding the fault,
   which is what was happening when the model wanted three tight ends.

## What the overnight adversarial passes found, 2026-09-01

Two independent agents audited the live path and Model A. Both found the same
first fault, which is the one that mattered.

**1. The drift warning cried wolf and would have made him abandon the tool.**
The detector added the previous night compared a SLOT NUMBER against a PICK
COUNT. A keeper slot is a pick number that consumes no pick, this league has
twenty-four of them, and `LiveDraft.livePicks` drops keeper picks - so on a
perfectly clean board the two quantities part company from round 3 and never
rejoin. Measured on a clean 168-pick replay: it fires at **137 of 168
refreshes**, printing "trust DraftNight and the RUNBOOK until it clears". It
never clears. `DraftNight.scheduleDrift` now counts LIVE SLOTS, which is the
quantity the pick count actually measures; `DriftAlarmCheck` replays a clean
draft and throws if it ever fires. It fires 0 times.

**2. The legend named the wrong column.** The footer said VS WAIT "is what to
rank on". The code has always ranked on END TEAM. They disagree in practice, so
a reader following the printed instruction took a different player than the
tool recommended. END also printed to the whole point, showing two rows as
tied when the verdict had a preference; it now prints one decimal.

**3. The rollout could imagine a roster with no defence.** The greedy tail was
pure marginal with no legality constraint, so it often finished with none, and
those rosters were charged the streaming penalty - which made taking a defence
NOW look like the only way to ever have one. `TailLegality` measures 0 of 5
tails finishing without a defence, where they finished `{QB=2, RB=5, WR=8,
TE=1}` before. This is TRAPS A7 living inside the quantity the verdict ranks on.

**4. Every man on his roster was priced below his own projection.** Held men
were ranked against the whole pool while the curve is built from the draftable
pool, so each indexed a list he was being counted against by twenty-four
players who are not in it. Ja'Marr Chase priced 29.1 points under his own
projection: the moment Justin drafted the best receiver on the board, the model
priced him as WR2. Every draftable man now indexes his own projection exactly.

**5. TRAPS A1's amnesia was still open, with a comment claiming otherwise.**
A pick the rules declined went into a print list and never onto the roster, so
the quarterback ceiling of two was counted against one and `full()` read fifteen
on a roster of sixteen. `BoardValue.MOST` was the only thing standing between
that and a third quarterback - far more load-bearing than "appetite". Closed by
`Roster.holdAnyway`, which records a man the rules refuse without weakening
`draft()`, so no model can still PLAN an illegal roster.

**6. Two halves of the screen could describe different boards.** `Draft2026`
read the picks endpoint three times per cycle, uncached, across sixteen seconds
of Model A. One snapshot per cycle now; the footer says how many picks it held.

**7. The screen sent him to a tool that stops at pick 108.** `LateRoundTargets`
never sets `scheduleRounds=16`; his last three picks are 162, 175 and 186. Both
on-screen instructions now name `LiveLateRounds`, which does set it.

Also fixed: `branchWith` did not mean the same slot as `slotOf` and could spend
a pick into a keeper slot; Model A had no drift detector at all; and a tied
committee vote was printed as a verdict decided by enum declaration order, which
happened on the real board at round 3.

**8. A man past ADP 250 broke both the roster and the board.** Listed as still
open when this section was first written; fixed since. The simulator's board
stops at ADP 250 and both faults asked it about a man outside it. Justin's
roster was built by asking where each id landed, so a deep sleeper HE drafted
came back null and vanished from his own roster - one short for the rest of the
night. Picks are now attributed by SEAT, which is right whether or not the board
carries the man. And the simulated arm judged availability the same way, so once
anybody drafted such a player the tool kept offering him: caught at pick 175,
RB Malik Davis, ADP 686, named, drafted, then named twice more.

**Still open, not fixed:** `-Pkeepers` is ignored by `DraftNight` and the other
fallbacks - harmless tonight because both keepers are declared on Sleeper, so
the tools read them from the live draft anyway.

## The one real modelling change of the night

Everything else above is a fault fixed. This is a different rule.

`expectedRank` decides who is gone by a later pick, and it feeds every rollout,
so it sets every END TEAM number on the table. It used a **hard ADP cutoff** -
gone if his ADP beats the seat, there otherwise. That is the rule this repo
already rejects for the wait table, in a comment twenty lines away: *"a hard
cutoff and false in both directions: a man at ADP 6.9 is not certainly gone and
one at 7.1 is not certainly there."* The wait table simulates survival; the
rollout never got the same treatment.

`RankPrediction` scores both against the fitted opponent model, on simulations
the survival rule was not fitted to:

    mean absolute error per position-seat
      hard ADP cutoff     2.69 men
      survival weighted   0.08 men

The errors were systematic rather than noisy. At pick 42 the cutoff said two
tight ends were gone when 0.1 really are - so the model priced the third-best
tight end while the best was still on the board. At pick 186 it said 56 backs
were gone against a true 50.9.

**The backtest cannot see this change.** It replays historical boards where who
went is known rather than predicted, and scores 1931 either way. That is the
wrong instrument, not evidence of no effect - which is why the case rests on the
bounded per-decision measurement, the same reason this repo trusts those over
season totals everywhere else.

Costs 3s at warm, paid once. `-PsurvivalDraws=0` restores the tagged rule.

Tested again where it actually runs. The first measurement used an empty board,
which is the one regime where the rule's approximation is exact and therefore
invisible. `MidDraftRank` scores every (seat now, seat later, position) cell of
a real draft, 21,840 of them: cutoff **4.32**, shipped **0.85**, exact
conditional **0.86**. So the approximation costs nothing, and the cutoff is
worse mid-draft than on an empty board - the case is stronger where the tool
runs than where it was first measured.

**And scored against real drafts, which is the test that counts.** Every
measurement above uses simulations from the very model the table is built from,
so none of them can tell a right table from one reproducing its own generator.
`RealDraftSurvival` scores it against the league's own 2024 and 2025 drafts,
with the choice model fitted only on prior seasons: **ADP cutoff 3.35 men,
survival 1.19**. The change holds up on real football. The gap between 0.85
(simulated) and 1.19 (real) is the model misspecification the simulated tests
structurally cannot see.

`drain` - which sets the rank behind **VS WAIT** - carried the last hard-ADP
estimator, counting men whose ADP fell in the window. Its prior is fitted now,
and as of later the same day it uses that prior **alone**.

I kept the room-observed term at first, and was wrong. The simulated comparison
already preferred the prior alone; I refused it because those draws come from
the very model the prior is fitted on, so they cannot exercise misspecification
- and the room term is what catches a room the model did not expect. Real
drafts can exercise it. `RealMidDraft`, on the league's own 2024 and 2025
drafts, 360 cells:

    retired: room blended with ADP counts   1.89 men
    room blended with the survival prior    1.72
    the survival prior alone                1.56

Both kinds of test agree, so the argument for keeping it is spent. The room term
was there to rescue a *poor* prior; against a fitted one it adds noise. It
survives on the fallback path, where the prior is the ADP count again. Two
seasons is a small sample and the cells are correlated - a consistent sign, not
a proven effect.

The related asymmetry is fine as it stands: `expectedRank` never watched the
room, and blending it in makes that **worse** on the same real drafts (1.63
against 1.57), because it already counts every man really taken at certainty.

END TEAM and the ordering do not move; VS WAIT does - the receiver's cost of
waiting at pick 7 goes 18.4 to 31.0.

## Does tonight's pick depend on whose numbers you use? No.

The board rests on one projection feed, Rotowire via Sleeper, and everything on
the table is downstream of it. The three automatic shops disagree by 40-65
points on elite players, so this is not hypothetical.

`BoardSourceCheck` runs the board model at pick 7 under each feed:

    sleeper                  RB   (best likely there: James Cook)
    espn                     RB   (Derrick Henry)
    cbs                      RB   (Derrick Henry)
    blend:sleeper,espn,cbs   RB   (Derrick Henry)

One distinct verdict. `SourceSensitivity` says the same of Model A's committed
sequence - every source's best sequence starts with **R**: sleeper RRRWWWT,
espn RRRWWTW, cbs RWRWWTT, blend RWRWRWT. They diverge after pick 7, but under
any single source the four plans span 11-42 points, all inside the 125-point
bar.

So the first pick is source-proof, which is worth more than it looks: the
verdict is driven by the SHAPE of the positional curves, which the shops agree
about, rather than by anyone's point estimate of a particular man.

## Is there a better pairwise model? No.

You asked whether a boosted model might beat the odds surface. Ten families
were fitted and scored leave-one-season-out over 65,855 pairs and 16 seasons
(`./gradlew run -Pmain=OddsSurfaces -q`):

    incumbent, isotonic + log-smooth h=0.25    0.59991   baseline
    Bradley-Terry MLE + same smooth            0.59990   tie
    logit d + d*m + d^3                        0.59805   best challenger
    kernel surface                             0.60160   worse
    boosted on latent strength                 0.60372   worse, significantly
    boosted on features, no structure          0.61153   worse, significantly

The best challenger beats the incumbent by 2.29 fold-SE, which clears a
single-comparison 95% bar - but it is one of ten, and an exact sign-flip test
over all 65,536 assignments puts it at **p = 0.23 family-wise**. Nothing is
distinguishable from what ships. Every family that IS significant is
significantly **worse**, boosted trees worst of all.

Two things worth keeping from the exercise. The apparent "winner" is not a
richer model - `d*m` telescopes into a latent strength that is quadratic in
log-rank, so the only direction even pointing at an improvement is a *smoother*
strength curve, not a second dimension. And an untested claim in the shipped
code checked out: `PairwiseOdds.strength` says a full Bradley-Terry iteration
"lands in the same place", and it does (0.59990 vs 0.59991, p = 0.94). No ninth
prose fault.

## The clock, measured

`CycleTiming` times the real `Draft2026` cycle with output swallowed:

    board model   0.3 - 0.6s
    Model A      24.5 - 25.0s
    worst cycle        25.3s   against a sixty second clock

Model A got SLOWER overnight, on purpose. The Kim-Nelson arbiter - the
statistical tiebreaker - had been throwing on the first defence it met and
being caught as "no contenders", so it never ran in `Draft2026` at all. It
runs now (PROVEN RB after 126 rollouts at pick 1) and costs 8.4 seconds. The
comment claiming "1.2s worst case" was measured while it was dead.

The "4-11s" quoted elsewhere was the bare nine-round `DraftNight`, not this.
Press enter EARLY. The board model answers in under a second and it is the one
that knows the twenty-four keepers - read it and let Model A arrive while you
decide.

## Running experiments while agents work

Do not benchmark the main tree while an agent is editing it. The same
configuration returned 1954 and then 1928 twenty minutes apart, because
`BoardValue.java` was rewritten between the two runs. Use an isolated snapshot:

    git worktree add -f /tmp/ff-stable draft-ready-2026
    ln -sfn <repo>/data/nflverse /tmp/ff-stable/data/nflverse

It reproduces the tagged 1935 / 1792 exactly. `data/nflverse` is gitignored -
131MB, fetched not authored - so the symlink is required.
