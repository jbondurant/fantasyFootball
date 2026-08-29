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
| 8 | 90 | RB if reasonable, else WR — **never** TE, **not** a backup QB |
| 9 | 103 | RB if reasonable, else WR |

## Rounds 8–16 — one model, both halves of the value

These used to be two rules — "bench insurance" for 8–9 and "stash rule" for
10–16. That split was on the wrong axis. Both values belong to every pick in
the range: a round-8 breakout is keepable at round 8 next year, and a round-14
stash can start for you this season. The measured decay is smooth (44.0 / 32.8
/ 31.2 points over the wire across 8-9, 10-12, 13-16) with no cliff at 10.

`StashValue` prices every pick from 8 to 16 as one sum, both terms floored
because both are options — you drop a bust in week 4 and stream, and you
decline a keeper who is not worth his round:

```
POS  ROUNDS       n this season  keeper next       TOTAL   +/-2se
QB   8-9          7        98.5          3.4       101.9     43.5
QB   10-12       14        49.9         44.7        94.6     40.6
QB   13-16       25        38.2         29.5        67.7     30.6
RB   8-9         27        47.2         20.2        67.4     30.0
RB   10-12       40        42.6         21.1        63.7     21.2
RB   13-16       37        40.4         25.6        66.0     23.8
WR   8-9         48        40.9         11.2        52.1     18.2
WR   10-12       54        30.4          7.8        38.2     13.0
WR   13-16       53        27.5         13.0        40.5     15.4
TE   8-9         11        27.3          0.5        27.8     21.1
TE   10-12       11        15.8          1.4        17.2      9.7
TE   13-16       21        13.3          1.9        15.2     11.9
```

**The rule, in order of how much the data actually supports it:**

1. **Never take a tight end from round 10 on.** The most robust result in the
   table — TE reads 17.2 and 15.2 against RB's 63.7 and 66.0, and the error
   bars do not come close. (At rounds 8–9 the TE cell is n=11 and only
   marginally separated, so this is a 10+ rule, not an 8+ rule.)
2. **Prefer RB.** It holds up across all three bands. Against WR the margin is
   real but only marginally significant — RB 63.7 ±21.2 versus WR 38.2 ±13.0 —
   so take the better player when it is close rather than forcing position.
3. **Take a young QB when the keeper case is the point, not the lineup.** The
   QB keeper column (3.4 / 44.7 / 29.5) dwarfs every other position's, and
   `LateRoundValue` puts late QBs startable the following year 41% of the time
   against 15–19% elsewhere, with young (≤2 years) beating veterans 24% to 15%.
   But you cannot start a second QB behind Purdy, and the QB *totals* rest on
   n=7 and n=14 with ±43.5 and ±40.6 — they do not separate from RB. Rounds
   10–12 is the band where the QB keeper term peaks.

2026 QB stash targets, in order: **Bo Nix, Jaxson Dart, Tyler Shough, Cam
Ward**. If Mahomes, Stafford or Goff somehow last, they are startable quality
at a stash price.

**How much of this is next year:** 30%. This season is worth 36.3 on average
and the keeper option 15.6, after scaling the keeper term by 0.88 — the share
of this league's keepers whose drafter is the one keeping him. The keeper term
is real but secondary, and it matters more to this seat than to others only
because this keeper pair is the league's weakest (+27 against JakeSK's +118),
which is most of why the seat projects 10th.

**Correction (2026-08-29):** this section used to say "this is how Tuten
happened." It is not. `KeeperOrigin` shows Tuten was drafted by Hamrliks and
acquired by trade — he is one of only three keepers in twenty-four that the
keeping manager did not draft. Trades are a real but minor supply line here,
which is why the discount is 0.88 and not something larger.

**Why round 8 is no longer "QB — backup insurance":** `StarterRisk` puts this
nine at 0.90x the injury exposure of an average nine — Henry and Nabers are
unusually durable — and Purdy missing his projected 2.7 games costs only his
weekly edge over QB21, who is startable off the wire for free. A bust costs
only the roster spot; the right to drop him is worth 9.4 of the 44 points a
rounds 8-9 pick averages.

## Things to watch

- **JFMarino (slot 8) autodrafting.** He picks at 17, immediately before your
  18. If he is on autopilot he takes the ADP-best player, which is Josh Allen
  45% of the time — Allen's survival to your pick 18 drops from **90% to 49%**.
  His pick 8 lands right after yours: an instant, ADP-perfect pick is the tell.
- **The QB shelf.** Prescott (366 proj), Lawrence (355), Herbert (347) and
  Mahomes (345) essentially never go inside nine rounds. There is no urgency
  at quarterback beyond the round-8 insurance pick.
- Defense and kicker: last two rounds, as the league always does.
