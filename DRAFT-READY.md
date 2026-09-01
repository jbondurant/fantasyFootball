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

## The clock, measured

`CycleTiming` times the real `Draft2026` cycle with output swallowed:

    board model   0.3 - 0.6s
    Model A      14.7 - 16.3s
    worst cycle        16.6s   against a sixty second clock

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
