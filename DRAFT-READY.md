# The verified state, 2026-08-31, before the overnight work

Tagged `draft-ready-2026`. Justin draws his draft 2026-09-01 20:45. Everything
after this tag is experimental; if any of it misbehaves, come back here and
every number below is what you get.

    git checkout draft-ready-2026

## What to run at the table

    ./gradlew run -Pmain=Draft2026 -Pkeepers=Tuten,Purdy -q

Warms once, ~25s. Then press enter at each of your picks:
board model answers in ~1s, Model A follows for rounds 1-7 (~16s total).
Rounds 8-16 it says why Model A is silent rather than printing noise.

Fallbacks, unchanged and independently verified all week:

    ./gradlew run -Pmain=DraftNight -Pkeepers=Tuten,Purdy -q       # rounds 1-7
    ./gradlew run -Pmain=LiveLateRounds -Pkeepers=Tuten,Purdy -q   # rounds 8+
    ./gradlew run -Pmain=PairwiseOdds -Pfrom=79 -Pto=90 -q         # wait odds

## Verified at the tag

    full suite                green, 389 tests
    Model A shape             RB WR RB WR WR WR TE QB QB
    BoardValue backtest       mean 1935, worst 1792, hindsight-free
    RUNBOOK / DraftNight / LiveLateRounds   zero diff since the freeze

## What is TRUE about the model here

- 24 league keepers are off the board. It will not name a man somebody owns.
- Roster arithmetic is unconstructible-wrong: no third quarterback, no pick in
  a keeper round, no seventeenth man, no unfieldable lineup.
- The lineup is set on what was KNOWN and scored on what HAPPENED. A bench man
  earns his place through availability - the starter being lost - not through
  hindsight.
- Tiers are Boris-Chen-shaped: groups you cannot tell apart, from 2026
  projections and sixteen years of measured scatter.
- 0.5s per pick warm.

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

   Still chosen, still unfitted: the 15% fragility bar.
3. Scatter is indexed by draftable rank but learned from full-board rank.
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

## Running experiments while agents work

Do not benchmark the main tree while an agent is editing it. The same
configuration returned 1954 and then 1928 twenty minutes apart, because
`BoardValue.java` was rewritten between the two runs. Use an isolated snapshot:

    git worktree add -f /tmp/ff-stable draft-ready-2026
    ln -sfn <repo>/data/nflverse /tmp/ff-stable/data/nflverse

It reproduces the tagged 1935 / 1792 exactly. `data/nflverse` is gitignored -
131MB, fetched not authored - so the symlink is required.
