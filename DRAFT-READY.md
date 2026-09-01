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
2. `lostBelow=0.55` and the 15% fragility bar are numbers I CHOSE. Nothing
   fits them to an outcome.
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
5. MOST is hand-typed where RosterRules derives ceilings properly.

## Running experiments while agents work

Do not benchmark the main tree while an agent is editing it. The same
configuration returned 1954 and then 1928 twenty minutes apart, because
`BoardValue.java` was rewritten between the two runs. Use an isolated snapshot:

    git worktree add -f /tmp/ff-stable draft-ready-2026
    ln -sfn <repo>/data/nflverse /tmp/ff-stable/data/nflverse

It reproduces the tagged 1935 / 1792 exactly. `data/nflverse` is gitignored -
131MB, fetched not authored - so the symlink is required.
