# In season — the weekly loop

The draft is done. This is what the rest of 2026 looks like. Two moments matter
each week and they are not the same moment: **Tuesday morning** decides claims
and trades, **Sunday morning** decides the lineup.

## Regenerate BOTH, or the numbers are from two different worlds

    ./gradlew run -Pmain=TuesdaySwap
    ./gradlew run -Pmain=LeagueConsole

In that order, back to back. The projection feed is refetched daily and the
player metadata expires weekly, so two artifacts written hours apart can
disagree completely and both be right. Each one now prints the snapshot it was
built from:

    data: projections 2026-09-07, player metadata 2026-09-07

If those lines differ, the reports are not comparable and `check` will say so by
skipping the ladder comparison rather than pretending. This drift cost three
false alarms in one day; running them together is the whole fix.

Then open `data/console-<season>-w<week>.html`. Everything below is on that page.

## Sunday, ninety minutes before kickoff — the lineup

The **This week** tab. It says ten-for-ten optimal ON THE PROJECTIONS, which is
not the same as optimal, and the Status column is where the difference lives.

Every doubtful starter carries a **break-even**: he is worth starting only while
his chance of playing times his projection beats the healthy man behind him, so
the bar is one over the other. It is HIGHEST when your bench is close - a
healthy 9.5 behind a questionable 11.2 needs 85% before the questionable man is
the right start.

The page cannot know the actual probability. That arrives in the inactive report
about ninety minutes before kickoff, which is when this decision is made and not
before. Read the bar, read the report, decide.

Replacements are **exclusive** - one bench man cannot cover four starters - so a
starter may read "nobody healthy left". That is information, not an omission: if
enough of them sit you are starting somebody hurt whatever the arithmetic says,
and it is better to know at ten than at one.

## Tuesday — the wire

The **The wire** tab. Your FAAB is read off the rosters feed, not typed, and
each free agent's worth is computed: the roster with him and without the man he
displaces, valued and subtracted.

Two things to respect:

- a row tagged **inside the noise** is under the objective's own seed-to-seed
  spread. It is the yardstick moving, not the roster improving. DO NOTHING is
  the answer far more often than it feels like it should be.
- a drop that **empties a slot** is not a drop. The roster is full, so cutting
  your only defence means buying one back, and those rows are priced as the
  whole plan - both adds and both drops - or not shown.

## Tuesday — the trades

The **Trades** tab, which defaults to the offers a rival would thank you for and
should usually stay there. Read the columns right to left:

1. **He trades** - deals per season from the league's own log. A manager at
   0.20/yr will not answer your best offer; KevinDA (3.60) and BHier (3.25) are
   the market. This outranks everything else on the page.
2. **vs elsewhere** - his gain minus what the partner he would actually pair
   with gives him. Negative means he has something better waiting.
3. **Your full**, with its own error bar. A trade tagged **noise** has a gain
   that goes negative on another seed; the model cannot tell it from zero.
4. **He gains, simple** - the number he can check himself in ten seconds, and
   the one to put in the message.

Then say the quiet part in the message. If a man you are sending is Questionable,
lead with it - he will see it anyway, and volunteering costs nothing. This is a
keeper league with the same eleven managers every year, so being somebody people
want to deal with compounds into next season in a way one extra point does not.

## What never to trade

The **What you can sell** tab names your two 2027 keepers and what their surplus
is worth. Every trade on the page already prices them - giving one away shows up
in your own number - but the board will not tell you which men they are unless
you look. As of week 1: **Tuten (+58.0)** and **Skattebo (+51.4)**, twenty
points clear of third.

## After the games

    ./gradlew run -Pmain=SeasonLedger

Appends the finished week and judges the season against a bar frozen before week
one. It refuses to call anything FINAL before the regular season ends, which is
the point of freezing it.

## From week 7 — am I still in it

    ./gradlew run -Pmain=SeasonOutlook

Six of twelve make the playoffs. This plays out the real remaining schedule with
each team's weekly score drawn around its best legal ten, at a spread of 24.9 -
MEASURED over 840 team-weeks in five completed seasons, not assumed.

It is the number your own rule fires on: *win 2026, but if not after like
halfway, sell a bit for keepers, not in a drastic way.* Before halfway it will
refuse to say anything decisive, on purpose - a pivot called in week 3 off a 20%
sample is not that rule. Past halfway it commits: above 55% buy, below 20% sell
a bit **with the round 1-3 men staying**, and in between it says undecided
rather than picking.

What it leaves out - byes, injuries arriving, waivers, trades - all make the
season MORE uncertain and push every number toward 50%. So a team it calls dead
is dead by a margin that survives the omissions; a team it calls alive may only
be alive.

## Once a week, or when something looks wrong

    ./gradlew run -Pmain=ProjectionDrift   # do the season projections still move?
    ./gradlew run -Pmain=TradePartners     # who actually trades, from the log
    ./gradlew run -Pmain=TradeStability    # are the board's numbers bigger than its noise
    ./gradlew check                        # 654 tests; read the LOG, not the exit code

**Run `ProjectionDrift` once real games are played.** Keeper surplus, every trade
valuation and every free agent's worth come from the SEASON projection feed. That
feed demonstrably moves - thirty of 585 players changed between 24 August and 7
September, Josh Jacobs by 105.9 - but it went silent for the five days before
week 1. If it is still silent AFTER games have been played, it is frozen at
preseason values and every in-season number here is answering a question about
August. That is the moment to blend actuals in, and the tool exists so the moment
gets noticed instead of assumed away.

`check` takes about half an hour. Read `check-wire.log` for `BUILD SUCCESSFUL`
and the test count - a shell wrapper's exit status can be the echo's rather than
gradle's, which has hidden a red build here more than once. And **0 skipped**
matters as much as 0 failed: a test that never runs is not evidence of anything.

---

# Draft night — Tuesday 2026-09-01, 20:45  *(done; kept for the reasoning)*

Slot 7, 16 rounds. Keepers Tuten (RB, r12) and Purdy (QB, r13) occupy rounds 12
and 13, so you make **fourteen picks**:

    7  18  31  42  55  66  79  90  103  114  127  |  162  175  186
    r1  r2  r3  r4  r5  r6  r7  r8   r9  r10  r11 |  r14  r15  r16

The bar is the keeper gap: **thirty-five picks pass between 127 and 162**, the
largest dead zone in your draft. Anything you want after 127 must be taken AT
127.

## Before the draft (Monday evening, ~30 min)

    ./gradlew run -Pmain=AdpSnapshot          # fresh ADP + all four feeds
    ./gradlew smokeTest -PdraftId=<mock id>   # feeds AND the live-draft path
    ./gradlew run -Pmain=KeeperAudit          # board still 24/24 rules-clean
    ./gradlew run -Pmain=DraftNight           # warms the engine, then holds it open

If `KeeperAudit` reports anything other than 24 matching the rules, STOP and
read it — a commissioner edit invalidates the keeper-dependent numbers.

**Always pass a draft id to `smokeTest`.** Without one, seven `MockDraftSmokeTest`
checks silently SKIP — the entire live-draft path, which is where every
draft-night defect this project has found actually lived. With the id it runs
53 checks. Any paused mock will do.

## During the draft

Start this ONCE, before pick 7, and leave it running all night:

    ./gradlew run -Pmain=DraftNight

Then press **enter** at each of your picks. It re-reads the live board
(uncached) and prints the committee vote plus the wait-or-take table from the
current state.

Warm-up is **~40s and is paid once**; each pick after that costs **about 27s**
(the board model under a second, Model A the rest), measured by `CycleTiming` on
the first six picks of the real 2026 draft on 2026-09-02. The older "4-11s" was
measured on a pick-1 board with the arbiter dead; draft night itself cost 42s
before Model A was given a budget and the arbiter was limited to split votes. Running the tools separately
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

The live tool is `./gradlew run -Pmain=Draft2026 -Pkeepers=Tuten,Purdy -q`
(board model first, Model A second; 25s of the 60). If it fails:

1. `./gradlew run -Pmain=DraftNight -Pkeepers=Tuten,Purdy -q` for rounds 1–7,
   `./gradlew run -Pmain=LiveLateRounds -Pkeepers=Tuten,Purdy -q` for rounds 8+.
   (`DraftPlanner` alone is Model A's nine-round planner, not a live tool — it
   scores nine skill slots with no defence, and it does not read the board.)
2. The committed plan below — no compute needed.
3. Best available at a position you still need. Never a defence before round 10
   — this league has taken 0 of 58 there — and Brenton Strange (TE, ADP 167) is
   98% likely to be there at pick 175.

Reading the BOARD MODEL's screen (the top half of `Draft2026`): rank on the
**END TEAM** column. Below the table it says whether the leader is SEPARATED
from the rest (paired, 2 s.e.) or INSIDE THE NOISE; if inside, it names the men
actually available at each tied position — take the one you believe in, the
model has no preference to overrule. "WHO ELSE MIGHT BE THERE" lists the next
likeliest men where the named one is under 60%.

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
| 7 | 79 | RB or WR — **not** TE |
| 8 | 90 | **TE** |
| 9 | 103 | RB or WR, highest upside — **not** a backup QB |
| 10 | 114 | young QB stash (Nix/Dart) if there — else RB/WR |
| 11 | 127 | **last call** before the 35-pick gap: anything you want, take it here |
| 14 | 162 | RB/WR, or Shough/Ward if stashing a QB |
| 15 | 175 | RB/WR |
| 16 | 186 | **defence** |

### Round 7 is no longer the tight end (2026-08-29)

Model A calls TE at 79. It maximises expected best-nine points from
projections, so it cannot see the three things that decide this pick. Two
models built from five seasons of dated ADP joined to actual outcomes both say
move it back a round.

Scoring a lineup **week by week** off real games played — the only way to see
injuries to the rounds 1–6 starters, since a season total has already absorbed
them:

```
                        points        vs A      +/-2se  seasons won
   A  TE at 79         1355.4
   B  TE at 90         1451.9       +96.5        71.9          4/5
   C  stream TE        1402.8       +47.5        35.2          4/5
```

**B > C > A: draft one, just later.** Both alternatives beat taking him at 79;
waiting beats streaming.

Do NOT skip the tight end entirely. The TE10 you draft beat what streaming
actually supplies by **+53.2 ± 30.6** points a season — that clears its bar.
(An earlier version of this said the wire was *better*. It was scoring the
wire as the single best undrafted tight end chosen with hindsight, which
nobody can pick in advance.)

**Why the flex makes waiting right.** Valuing a pick by the starter-slots it
fills, and sweeping injuries and busts from zero to triple, the tight end
loses in all 30 worlds — and at the frictionless corner, nobody hurt and
nobody busting, it is still −36.3. The reason is that two flex slots mean the
extra receiver is not a bench player at all; he starts every week. The gap
does close as the pick moves back (−36.3 at 79 → −16.9 at 127), exactly the
plateau effect, but it never reaches zero.

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

**At each pick from round 8 on, run the live late-rounds tool:**

    ./gradlew run -Pmain=LiveLateRounds -PdraftId=<id>

It reads the live board, takes the roster you ACTUALLY have, and prices the best
available man at every position — including defence — by what he adds to the
points your starters will score across the season, then multiplies by how often
he survives to your next pick. One number for the tight end, the backup
quarterback and the defence, so they compare directly:

```
POS  BEST AVAILABLE                  ADDS   SURVIVES  LOSS IF WAIT   verdict
TE   Travis Kelce                    61.2       48%           7.9   lean take (8)
RB   Kenny Gainwell                  60.0       16%           3.8   lean take (4)
WR   Jordan Addison                  58.4        0%           0.0   wait - he keeps
DEF  Houston Texans                  27.3        3%           4.2   lean take (4)
QB   Bo Nix                          13.1       96%           0.0   wait - he keeps
```

Take the top row unless LOSS IF WAIT is near zero for everything, in which case
take the highest ADDS. It says whose pick it is — if it reads "someone else",
you are looking at a preview, not your decision. Defence survival IS simulated
now (defences joined the board 2026-08-29).

**Read it as a prompt, not an oracle.** Every model built on this objective has
lost a five-season backtest to the committed plan below — best configuration
1921 against 1998. Its objective was corrected late on 2026-08-29 to centre on
each player's own projection rather than his tier's average, after which it
independently reproduced this plan's RB RB RB WR WR WR opening; but that fix
cannot be scored on history, because per-player projections do not survive at
the right vintage. So it has still never been SHOWN to beat the rules. What it does well is price a SPECIFIC board state you are actually
looking at, which a written plan cannot. Where it disagrees with the plan,
prefer the plan unless it shows a large LOSS IF WAIT.

**Superseded:** `LateWaitOrTake` — same idea, weaker value model.

**The rule, in order of how much the data actually supports it:**

1. **Never take a SECOND tight end from round 10 on.** The most robust result
   in the table — TE reads 17.2 and 15.2 against RB's 63.7 and 66.0, and the
   error bars do not come close. This is about a bench tight end once the
   starting slot is filled; your starter comes at 90, above.
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

2026 QB stash targets — but **the order is a deadline, not a preference**.
`LateSurvival` runs the board to round 16 (the old tool stopped at round 9 and
so called everything safe):

```
PICK  ROUND      Bo Nix   Jaxson Dart   Tyler Shough   Cam Ward
114   10            74%           73%           100%       100%
127   11            42%           50%            99%       100%
162   14             6%            8%            95%       100%   <- 35-pick gap
175   15             0%            2%            78%       100%
186   16             0%            1%            63%        98%
```

**Take Nix or Dart at 114, or at 127 at the latest.** There is no round 14 for
either of them — 6% and 8%. Shough and Ward survive the gap (95%, 100%), so
they are the ones to wait on, and taking either early wastes a pick you could
have spent on someone who would not have lasted.

The 127 → 162 gap is the structural fact of your draft: keepers at r12 and r13
mean thirty-five picks pass with no selection of yours. Anything you want
after 127 must be taken **at** 127.

If Mahomes, Stafford or Goff somehow last, they are startable quality at a
stash price.

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
- **Defense: you MUST draft one.** You need a defence in week 1, and by then
  every other manager has taken the good ones. Stream by SWAPPING it during the
  season, never by skipping the pick. Where to take it, measured directly — one plan, thirteen picks
  held fixed, the defense slid through every slot and scored on real outcomes:

  ```
  round    1     3     6     8    10    11    14    15    16
  mean  1769  1842  1871  1892  1942  1961  1981  1984  1984
  ```

  Monotone: later is better, and it flattens after round 14 — rounds 14, 15 and
  16 are worth the same, so take it whenever it is convenient in there. The
  spread from round 1 to round 16 is **215 points a season**, so this is not a
  free choice; but among sensible placements (round 11 onward) it is worth only
  23 points, so do not agonise.
- **And do not reach.** Measured 2026-08-29 over five
  seasons. Preseason top-four defenses finished top four **3 times in 20 (15%)**
  — chance alone gives 13%, so a preseason defense ranking is close to
  uninformative about which ones will be good. They beat a free defense by only
  **6.9 points a season**, while the skill player at that same pick (rounds
  9–12) beat *his* replacement by **59.5**. Net **−52.7 ± 44.4** a season, with
  the defense ahead in 1 season of 5. Take one at the very end; a defense there
  is still worth ~32 points over the wire, which beats a deep tight end.
- Kicker: this league starts none. Do not draft one.
