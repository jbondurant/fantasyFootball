# Draft night — Tuesday 2026-09-01, 20:45

Slot 7. Keepers Tuten (RB, r12) and Purdy (QB, r13), both free inside the
nine-round game. Nine live picks: **7, 18, 31, 42, 55, 66, 79, 90, 103**.

## Before the draft (Monday evening, ~30 min)

    ./gradlew run -Pmain=AdpSnapshot          # fresh ADP + all four feeds
    ./gradlew smokeTest                       # feeds still shaped as expected
    ./gradlew run -Pmain=KeeperAudit          # board still 24/24 rules-clean
    ./gradlew run -Pmain=DraftNight           # warms the engine, then holds it open

If `KeeperAudit` reports anything other than 24 matching the rules, STOP and
read it — a commissioner edit invalidates the keeper-dependent numbers.

## During the draft

Start this ONCE, before pick 7, and leave it running all night:

    ./gradlew run -Pmain=DraftNight

Then press **enter** at each of your picks. It re-reads the live board
(uncached) and prints the committee vote plus the wait-or-take table from the
current state.

Warm-up is **~55s and is paid once**; each pick after that costs **4-7s**,
measured against the paused mock on 2026-08-29. Running the tools separately
costs 25-45s per pick, because each one pays the warm-up again — that was the
single biggest draft-night risk before this existed.

If a cycle throws, it says so and returns you to the prompt rather than
dying: press enter and go again.

Reading the output:
- **All four engines agree + KN says PROVEN** → take it, no thought required.
- **Engines split, or KN cannot prove separation** → genuinely contested;
  the alternatives listed are all defensible. Take the scarcer position, or
  the player you prefer. Expect roughly two such picks per draft.
- KN never overrides the engines. A KN "tie" means unproven, not equal.

## Fallback ladder (if the tool fails)

1. `./gradlew run -Pmain=DraftPlanner` — slower, single engine, still adaptive.
2. The committed plan below — no compute needed.
3. Best available at a position you still need.

## The committed plan (laptop-dies fallback)

Rounds 1–7 fill the starting nine (Purdy and Tuten already hold QB and one RB).
RB-heavy beat WR-heavy by +1.8 on 3,000 paired trials, so round 2 is an RB.
This sequence is worth about **9 points less** than letting the live tool
decide each pick — use it only if the tool is unavailable.

| Round | Pick | Position |
|-------|------|----------|
| 1 | 7 | RB |
| 2 | 18 | RB |
| 3 | 31 | RB |
| 4 | 42 | WR |
| 5 | 55 | WR |
| 6 | 66 | WR |
| 7 | 79 | TE |
| 8 | 90 | highest upside available — **not** a backup QB |
| 9 | 103 | highest upside available |

### Why round 8 is no longer "QB — backup insurance" (2026-08-29)

Three measurements, all pointing the same way. `BenchValue` joined 111 of this
league's own rounds 8-9 picks to what they actually scored that season: the
position means (RB 46.1, WR 39.6, TE 24.8 points over the wire) overlap inside
two standard errors, so **position does not decide this pick**. `StarterRisk`
put this nine at 0.90x the injury exposure of an average nine — Henry and
Nabers are unusually durable — which shrinks bench cover further. And Purdy
missing his projected 2.7 games costs only his weekly edge over QB21, who is
startable off the wire for free.

Take the highest-upside player instead. A bust costs only the roster spot,
because you drop him in week 4 and stream — that right is worth 9.4 of the
44 points a rounds 8-9 pick averages.

## Rounds 10–16 — the stash rule

**Take young quarterbacks who fell.** Measured over five seasons of this
league: late QBs become startable the following year **41%** of the time
versus 15–19% for every other position, and nine of the ten best late stashes
in league history were QBs (Hurts r14, Burrow r13, Stafford r13, Lawrence
r12…). Young (≤2 years) beats veteran 24% to 15%. Round band does not matter.

2026 targets, in order: **Bo Nix, Jaxson Dart, Tyler Shough, Cam Ward**. If
any of Mahomes, Stafford or Goff are somehow still there, they are startable
quality at a stash price.

This is how Tuten happened. A round-12 pick is a round-12 keeper next year.

## Things to watch

- **JFMarino (slot 8) autodrafting.** He picks at 17, immediately before your
  18. If he is on autopilot he takes the ADP-best player, which is Josh Allen
  45% of the time — Allen's survival to your pick 18 drops from **90% to 49%**.
  His pick 8 lands right after yours: an instant, ADP-perfect pick is the tell.
- **The QB shelf.** Prescott (366 proj), Lawrence (355), Herbert (347) and
  Mahomes (345) essentially never go inside nine rounds. There is no urgency
  at quarterback beyond the round-8 insurance pick.
- Defense and kicker: last two rounds, as the league always does.
