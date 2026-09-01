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

1. The backtest models TWO keepers; the league has TWENTY-FOUR. Every score
   here was measured on a board ~22 men deeper than tomorrow's.
2. `lostBelow=0.55` and the 15% fragility bar are numbers I CHOSE. Nothing
   fits them to an outcome.
3. Scatter is indexed by draftable rank but learned from full-board rank.
4. Nobody has audited whether PlanBacktest.seasonPoints - the scorer every
   comparison rests on - fills its own lineup by expectation or by hindsight.
   If it cheats, the honest model is being marked down for being honest.
5. MOST is hand-typed where RosterRules derives ceilings properly.
