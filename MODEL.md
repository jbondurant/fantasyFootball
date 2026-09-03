# The draft model

> **Read `DIAGNOSTIC.md` first (2026-09-01).** This file describes **Model A**
> — the nine-skill-slot objective (`DraftPlanner`/`DraftNight`), no defence,
> rounds 1–7 proven — and its component plan. It is accurate for Model A and
> historical for everything else: the shipped live tool is `Draft2026`, whose
> board model (`LiveBoard`/`BoardValue.oneSeason`) scores the league's real
> ten-slot lineup, defence included, over sixteen rounds, with a survival table
> replacing the ADP cutoff. The two objectives coexist on purpose
> (`TwoObjectivesTest`). "Defense excluded from the optimization" below is a
> statement about Model A only.

One model, one number per decision. This replaces the collection of partial
valuations (pick surplus, VORP variants, forced-script simulation) whose
answers moved with their hidden assumptions.

## The definition that ends the assumption fights

```
V(K) = expected points of my best 9 skill starters,
       drafting OPTIMALLY given keeper set K
value of keeping k = V({k}) - V(none)      (pairs: V({a,b}) - V(none))
```

Both branches are optimized, so a player is always measured against my *best*
alternative, not an assumed one. Brock Purdy scored -26, -7 and +19 under three
earlier models because each fixed a different alternative (forced early QB /
QB at my round-9 pick / Josh Allen at pick 18). Under V(K) that choice is made
by the optimizer, so the number cannot flip with the assumption.

Scope decisions (Justin's, deliberate):
- Objective = the 9 skill starters: QB, RB, RB, WR, WR, WR, TE, FLEX, FLEX.
- Bench is worth exactly 0. It gets boom-or-bust fliers.
- Defense excluded from the optimization; drafted late like the league does.
- All points under league scoring (6-pt passing TDs), via SleeperProjections.

## Components

| # | Component | Status | Replaces |
|---|-----------|--------|----------|
| A | `ManagerProfiles` — per-manager positional timing fitted from 2021-25 with shrinkage to the league mean; flex-share fitted, not assumed | DONE | the bias constants (20.4/-0.1/-11.7/16.3) hand-pasted in three files; the 50/42/8 flex guess |
| B | `BoardSimulator` — true serpentine order from the real draft_order; keeper-occupied slots skip their round; each opponent picks a position from their profile *conditioned on their roster so far and their declared keepers*, then the best player at it by blended ADP/league-value with noise | new | AvailabilityModel's order-blind Gaussian draw; the fixed-script StrategyBot sim |
| C | `MyPickOptimizer` — enumerate my position-sequences over simulated boards; outputs the recommended position per pick, per-player survival at each of my picks, and V(K). Objective is tunable: expected points (default) or risk-adjusted over the BOARD distribution — report mean and a low quantile (p10), rank by mean − λ·(mean − p10), knobs -Prisk / -Pquantile. Risk here is availability risk only: projections stay deterministic, no boom/bust or injury modeling yet. Acceptance test: the round-3-good vs round-8-amazing-but-10%-reached decision comes out as two strategy distributions whose comparison flips as λ rises | new | KeeperChooser.draftPlan's fixed script; WaitOrTake (subsumed) |
| D | `KeeperValuation` v2 = V(K) − V(none) | rewrite | all previous keeper metrics — one number ships |
| E | `Backtest2025` — fit on ≤2024, replay 2025; report position-timing calibration per manager and availability calibration (of players scored 70-80% available, how many were?). PICK_SD and VALUE_WEIGHT get tuned here, never hand-set | DONE (`DraftBacktest`) | scratch Python backtest |
| F | Draft-day mode — C conditioned on live picks | later | SleeperLiveDraft's current advice path |

Order: A → E baseline → B (gate: must beat the order-blind model on E's
availability calibration) → C → D → F. Each step is a commit with tests.

Honesty gate on B: an earlier player-level backtest showed per-manager features
did NOT improve top-1 pick prediction. The claim here is different — order-aware
positional availability — and E measures exactly that. If B doesn't beat the
anonymous model on 2025, its per-manager layer gets cut, not tuned.

## Why order-awareness matters (the motivating case)

I pick 7th. The ten picks between my 1.07 and 2.06 belong to slots 8-12 twice:
JFMarino, jerem9604, Hamrliks, tommyrads, itsabust — the five latest QB
drafters in the league (historical first QB: rounds 7.7-11.4), two of whom have
now locked a keeper QB besides. The order-blind model prices Josh Allen at 74%
to survive pick 7 → 18; conditioned on who actually picks in between, the real
number should be far higher — and B/E will compute it. The QB threats (Renteez
slot 3, patekxwater slot 6, BHier slot 2) all pick BEFORE my 1.07, so the
danger is Allen never reaching me at all, not being sniped between my picks.

## The nine-round game (assumptions set 2026-08-25)

Justin's clarified rules for the model:

  1. League-scored Sleeper projections (6-pt passing TDs) are treated as exact
     season outcomes. No boom/bust modeling.
  2. No injury differential between players.
  3. No in-season trades.
  4. Nothing after round 9 matters: the game is 12 teams x 9 rounds = 108
     selections, minus keeper-occupied slots.
  5. Nobody drafts a defense - which matches history inside rounds 1-9, where
     the league has never taken one.

Two structural consequences:

  - Assumptions 4+5 dissolve the deep-board censoring problem and the DEF
    pollution that sank the first learned models. Rounds 1-9 are ~500
    historical selections, essentially all with solid ADP.
  - A keeper costing round 10+ consumes NONE of the nine picks: the player is
    off the board for everyone else and the slot he occupies is worthless.
    Under these rules a r12/r13 keeper is close to pure free VORP, and keeper
    valuation flips accordingly. CONFIRM before building on it.

### B - the selection model (replaces the landing-draw architecture)

The lesson from the challenger ladder: fitting marginal displacement learns
true facts about the wrong quantity. The fix is structural - model the thing
that actually happens, one SELECTION at a time:

  P(manager m takes player p | board state) = conditional logit over the
  remaining board, with features per (m, p, state):
    - market score: ADP (+ per-player market spread where available)
    - league-value score (points over positional replacement)
    - position x depth terms
    - manager timing covariates (their fitted positional tendencies)
    - roster need: what m already holds, keepers included

  Simulating from this fitted process IS the generative model - players are
  coupled by construction (every selection removes one), so there is no
  independent-draw-then-sort artifact and no marginal-vs-simulator mismatch.
  Availability = Monte Carlo over the process, restricted to the 9-round game
  in true serpentine order with keeper-occupied slots skipped.

"Player-by-player models", honestly: with one draft per season, a free-standing
model per player has n<=5 observations and is not identifiable. Player-by-player
means player-specific COVARIATES through a shared model - each player still gets
his own distinct survival curve (a Purdy with value-rank 6 / ADP-rank 15 gets
mixture-like behavior through the value feature), which is the deliverable that
matters. Candidate per-player spread input: FantasyFootballCalculator publishes
per-player ADP stdev, per season, already reachable via FFCalculatorSD - a
gated feature, not an assumption.

Fitting: maximum likelihood (concave for conditional logit), plain-Java
gradient ascent, on rounds 1-9 of 2021-2024. Seconds, not hours - the
compute-heavy part is the Monte Carlo, which Justin has approved.

### B gates (all on 2025, rounds 1-9 only, fit through 2024) - ALL PASSED

  1. Selection likelihood / top-k vs the incumbent gaussian board ordering.
  2. Per-player availability calibration at my historical pick slots.
  3. Per-manager position-timing calibration (does simulated tommyrads wait on
     QB like real tommyrads).
  Ship only what beats the incumbent; the incumbent stays printed beside it.

Results (SelectionModel + DraftSimulator, 2026-08-25):

  - The first feature set (ADP rank, points rank, need, saturated,
    QB x earliness) passed gate 1 narrowly but FAILED gate 2: simulated
    survival 2.95% weighted error vs the gaussian's 2.29%. Diagnosis on 2024:
    not sharpness (a temperature grid chose tau=1.0 - MLE flatness falsified)
    but LOCATION - the logit had no way to express the league's positional
    bias, the exact layer that had won the location contest for the gaussian.
  - Fix: positional intercepts f5-f7 (QB/RB/TE vs WR baseline), chosen on
    2024 (calib error 2.55% -> 1.37%), never on 2025. Fitted 2021-2024:
    QB -3.7, TE -1.9, RB -0.6 - the QB-late league, now as preferences.
  - Gate 1: log-loss 2.548 vs market-only 3.042; top-5 73.3% vs 55.6%.
  - Gate 2: weighted calib error 1.89% vs incumbent 2.29% (mid-buckets still
    noisier, 11.1% vs 7.6% on ~25-player buckets; weighted is the gate).
  - Gate 2b at my 8 actual 2025 in-draft slots (a keeper occupied the 9th):
    1.15% vs 1.80%.
  - Gate 3: per-manager first-QB-round MAE 1.98 rounds vs 3.19 for a
    league-mean constant. The gaussian has no managers - this is the new
    information, and it is exactly the order-aware signal the Josh Allen
    case needs.
  - Kept green by DraftSimulatorSmokeTest; mechanics unit-tested offline in
    DraftSimulatorTest.

  C therefore rolls out DraftSimulator (the fitted selection process), not
  the gaussian. The gaussian stays in the repo as the printed incumbent.

### C - the optimizer on top

  This restores the decision structure the repo already had - expectimax over
  position choices with Monte Carlo rollouts (max over [take position X now +
  average of simulated completions]) - which was the right architecture all
  along; the 2023-era bugs were in the plumbing around it, not the idea.
  Now taken to full depth and put on the fitted selection model:

  - Scoring rule (Justin's formalization): roster = keepers UNION my picks;
    score = best 9 of it. An out-of-game keeper (cost r10+) consumes no pick
    and "costs a round 9" only in the emergent sense that he benches my
    weakest pick. In-game keepers also consume their round's pick.
  - Search: backward induction over (my pick index, positional counts held),
    values estimated by rollouts under the selection model. Full depth to
    round 9 - the state space is tiny; the compute goes into rollouts.
  - Objective: mean of best-9 total, or mean - lambda*(mean - p10), knobs
    -Prisk/-Pquantile. Availability risk only, per instruction.
  - Per-round output: for each position choice - mean, p10, and the snipe
    decomposition: P(the player I would wait for is gone by my next pick) and
    the conditional drop when he is. That is the wait-vs-take information.
  - Keeper value: V(K) - V(none), both branches optimized.
  - Acceptance test: the round-3-good vs round-8-amazing-but-sometimes-sniped
    case produces two distributions whose ranking flips as lambda rises.

### C results (built 2026-08-25: DraftPlanner + KeeperPlan)

  DraftPlanner is the staged expectimax: at each of my live picks it branches
  over the four positions, values each branch by full rollouts to round 9
  (opponents = the gated selection model, my later picks greedy by marginal
  best-nine), fixes the winner, moves on. Risk knob mean - lambda*(mean-p_q)
  via -Prisk/-Pquantile; the lambda-flip acceptance case is a unit test in
  DraftPlannerTest. KeeperPlan runs V(K) - V(none) for every eligible keeper.

  On the real 2026 board (no keeper declared, 500 rollouts, lambda 0):

  - Plan: RB, QB, RB, WR, WR, WR, WR, TE, WR - expected best-nine ~1794.
    The QB waits for round 2 because Josh Allen survives pick 7 -> 18 in
    ~96% of rollouts (the original motivating case, now answered by code).
  - Keeper decision: Tuten r12 +15.8 and Purdy r13 +14.8 are a statistical
    tie at the top (Monte Carlo +/- ~1.0 each); Flowers r4 +6.0; the r10
    keepers ~0 (they equal a free 9th-rounder, which the best-nine mostly
    benches); Godwin/Andrews/Sutton/Kelce negative; Shaheed r9 -23.
    The old assumption-dependent Purdy spread (-26/-7/+19) collapses to one
    number under the unified model.
  - The snipe table prints per pick per position: usual target, P(gone by my
    next pick), drop when gone - e.g. Breece Hall 90% gone between picks 18
    and 31 (drop 16), Josh Allen only 4% gone between 7 and 18.

  Keep in mind: valuations move as ADP drifts and as leaguemates declare
  keepers - rerun KeeperPlan close to the declaration deadline.

### Mock drafts - investigated 2026-08-25, not recoverable

The hypothesis (players a manager reaches for in late mocks get reached for
again in the real draft) is good but untestable: Sleeper's per-user drafts
endpoint returns LEAGUE drafts only - verified across all twelve managers and
seasons 2021-2026, exactly one real draft each per season and nothing else -
and historical mocks 404 even by a known draft id, so they are ephemeral.
Settled by live experiment 2026-08-25: Justin created a fresh mock while
logged in and it did NOT appear on his own per-user draft list, immediately or
after propagation delay - mocks never attach to the public list, even for
their creator, even mid-draft. They DO serve fully at /v1/draft/{id} while
live, and the draft object carries a `creators` field naming whose mock it is
- so attribution exists, only enumeration does not. Consequence: a mock whose
LINK is shared is readable and attributable until Sleeper prunes it.
MockDraftReader archives any pasted link under data/mocks/ permanently and
prints its reaches vs current ADP; Justin's own 2026 mock is the first entry.
Anything archived this way is report-only unless enough accumulates to gate.

Two substitutes:
  - FFC per-player ADP stdev (per season, backtestable) - the gated feature
    already planned for the selection model.
  - Late-market ADP drift: AdpSnapshot appends today's Sleeper ADP to
    data/adp-snapshots.csv; run every day or two before the draft and the
    risers are the market's reaches. REPORT-ONLY: there is no historical
    drift series to backtest, so it cannot pass a gate and never feeds the
    fitted model - it informs the human.

## Standing rule

Every numerical answer comes from code committed to this repo — a runnable
main or a test — never from scratch scripts. Scratch analyses worth keeping
get ported here (A and E port the last of them).

## Open questions (defaults in place until answered)

1. RESOLVED 2026-08-25: JFMarino declared (Trey McBride r3, Chris Olave r5).
   All eleven opponents are now declared; only my own keepers are open.
2. Objective: pure expected points (default) or risk-adjusted (penalize thin
   floors)? Matters most for boom/bust candidates.
3. Injury/news overrides: an overrides file (player → exclude or points
   multiplier), since projections lag news. Default: build the hook, empty.
4. Keeper-cost convention: Jayden Daniels sits at r7 on the board; the ruleset
   reads r6. Commissioner's call feeds B's board. Default: use the board.
5. Pick trading is enabled in this league; the model assumes my pick schedule
   is fixed. Out of scope unless told otherwise.

## Definition of done

E reports availability calibration on 2025 within agreed error (proposal:
bucketed predicted-vs-actual survival within ±10 points), and the Allen
7→18 survival is produced order-aware. Then: second projection source.

## Results so far (2026-08-25)

- A fitted: league bias QB +20.0, RB +1.2, WR -10.4, TE +17.5 (season-centered,
  pinned by smoke test). Per-manager ADP-residual offsets are small after
  shrinkage (-3..+6); the timing hazard for B is a separate fit.
- E on 2025 (fit 2021-24): pick prediction ADP 14.7/42.9 (top-1/top-5) ->
  +league 16.0/50.6 -> +manager 16.0/51.3. Availability calibration weighted
  error 0.4-0.7%, worst bucket 9.3 points - inside the +/-10 gate. Tuning grid
  is a flat plateau (SD 16-24, VW 0-0.25 all within noise; the 2024-chosen
  point transfers worse to 2025 than the defaults), so SD=20 / VW=0.25 stand
  and `DraftBacktest` prints the surface every run.
- Production now removes the 22 declared keepers from the draftable pool and
  marks their rounds as consuming nobody. That thinned board moved the keeper
  numbers: Flowers +30, Tuten +12, Purdy -3.
- The learned-availability challenger ladder, all tuned on 2024 and scored
  once on 2025 (weighted error / mid-bucket gap):
      gaussian (shipped)     0.45% / 2.6%
      empirical bootstrap    1.38% / 9.2%   (censoring flaw)
      censored MLE           1.20% / 8.3%   (censoring fixed - helped)
      + dispersion rescale   falsified: scale 1.0 optimal, wider strictly worse
      hybrid (gaussian location + learned asymmetric shape)
                             0.64% / 6.0%
      superset (shape tuned end-to-end, family contains the gaussian)
                             0.51% / 4.3%  - the fit chose scale 20 (the
                             gaussian's own sigma) and asymmetry 1.2; the 2024
                             surface is a plateau that cannot tell 1.0 from
                             1.6, and the freedom cost 0.06 points on 2025
  Conclusion: the LOCATION layer (blended ADP + fitted bias + value rank) is
  what wins; the noise shape adds nothing measurable on five seasons of data.
  A learned model's remaining path to the gate is a better location signal -
  which is exactly step B's order-aware board with manager timing. All four
  models print head-to-head on every DraftBacktest run.

### KeeperWhy - keeper values, explained by slot (2026-08-25)

  `./gradlew run -Pmain=KeeperWhy -Pwhy=<last name>` decomposes V(K)-V(none)
  by lineup slot group and prints who mans the QB slot in each branch. For
  Purdy r13 at 500 rollouts: QB slot -50.3 (Allen 95% of no-keeper rollouts,
  413.6 mean, versus Purdy 363.3), RB +14.5, FLEX +51.5, net +14.8. The
  keeper's value is not Purdy-over-Allen - the model drafts Allen either way
  it can - it is the freed round-2 pick cascading the skill roster up one
  notch. The QB-late league bias already CUTS this value: Allen costing pick
  18 rather than pick 7 is exactly why the net is only ~15.

### The QB-run question (Justin's herding hypothesis, 2026-08-25)

  Hypothesis: "people draft QBs in trendy chunks - if I stop taking QBs
  early, the league would take them even later." Tested three ways:

  - Raw co-occurrence (QBMarket): QB repeats within 3 picks 27.5% after a QB
    versus 22.2% otherwise (40 events) - but TE goes the other way (17.1% vs
    24.1%), so raw counts are tier/ADP confounds, not evidence.
  - Pooled run feature (same-position count in the last 6 selections): fit
    +0.01 - the opposing positional effects cancel. Dropped.
  - QB-only run feature: fit -0.57, and it WON the 2024 chooser (1.19% vs
    1.40% calibration, 400 trials/cell) with all 2025 gates still passing.
    Controlling for ADP, need, saturation and per-manager timing, a recent
    QB run SUPPRESSES the next QB pick. The trendy-chunks story is the
    confound; demand depletion is the signal. Shipped in shippedFeatures().

  Consequences: (1) my early QBs did not detectably push the league toward
  QBs - the fitted reaction runs the other way; my restraint would not slow
  the QB market further. (2) Kept QBs already flow through the simulator as
  roster state (3 declared for 2026 so far; rerun as declarations land).
  Keeper answers under the shipped model: Tuten +15.9, Purdy +14.2 (gap
  ~1.7 vs +/-1.0 noise - still effectively a tie), Flowers +5.6.
  Observational caveat: one league, ~500 selections - "no detectable
  herding" is the claim, not "impossible".

### The tier-cliff question (Justin's TE observation, 2026-08-25)

  Observation: recent TE boards had substantial drops between few players.
  TierCliffs confirms it in the projections the managers saw: TE 2022 fell
  37 then 50 points across the top three; 2023 36/22; 2025 24/26. The 2026
  TE board is gradual (16/14/14) - and under league scoring the big 2026
  cliff is at QB: Allen 415 -> 380, which is why the planner fights for him.

  As an OPPONENT-behavior feature (f9: the drop below the best remaining
  player at his position), the cliff fit to -0.12 and the chooser rejected
  it (1.25% vs 1.19%): national ADP already prices tiers, so the local gap
  magnitude predicts nothing extra about who gets taken. It also dissolves
  the TE anti-herding puzzle - post-cliff TEs are just low-ADP, low-value
  players; no chase mechanism needed. The feature stays in the code with
  the chooser variant for re-verdicts, but out of shippedFeatures().

  My OWN decisions feel cliffs fully regardless: best-nine scoring uses
  point magnitudes, and the snipe table's drop-if-gone IS the local cliff.

### FeatureLab - the full candidate battery (2026-08-25)

  Ten candidate features, each judged one at a time against the shipped set
  on 2024 (fit 2021-2023, 400 trials, ship margin 0.10 calibration points
  because 2024 has now judged many variants). NONE shipped:

    value fall      +0.17%  coef -1.75 (fallers keep falling - falling is
                            informative, not a discount rack)
    turn-pair swap  +0.03%  coefs +0.01/-0.16 - Justin's keeper-cost swap
                            (worse target first at the turn so the better
                            one keeps cheaper) is NOT visible in league
                            history; ~40 pair events may be underpowered.
                            The strategy remains individually rational.
    wait x adp      +0.00%  coef -0.09, nothing
    flex need       -0.02%  coef +0.94 - real behavior, best candidate,
                            below the ship margin
    QB depletion    +0.16%  coef -0.59, redundant with the shipped QB run
    TE timing       +0.22%  coef +0.40, calibration worse
    RB timing       +0.11%  coef +0.14
    QB stack        +0.04%  coef +0.79 - stacking exists, does not help
    rookie          +0.18%  coef -0.17
    ADP spread      +0.23%  coef -1.18 - the long-queued FFC feature FAILED
                            its gate (159 players matched for 2024)

  The shipped set f0-f8 survives the whole battery - the model is not
  detectably missing any of these signals at this sample size (304 training
  selections). Infrastructure kept: Context-based features, positional
  earliness, team/rookie/spread archives, so any candidate can be re-judged
  as seasons accumulate. The honest next test is the 2026 draft itself.

### Domain-driven candidates (2026-08-25): loyalty, keeper stash, and the
### information-set test

  Three mechanisms specific to THIS league's construction, same lab, same
  margin. None ship; each leaves a finding:

  - loyalty (f21, "my guy": manager rostered him in a prior season): the
    behavior is REAL - coefficient +0.37, managers do go back to their
    former players - but availability calibration worsens (+0.32). Known
    tendency, no shippable signal.
  - keeper stash (f22, lateness x first-two-seasons player): +0.08,
    nothing. Rounds 1-9 are not where this league stashes; benches
    (rounds 10+, outside the game) presumably are.
  - league-scored value input swap (not a feature): retrain and simulate
    with the 6-pt league-scored projections the draft room displays,
    instead of the raw 4-pt national feed. Calibration DEGRADES 1.19% ->
    1.59%. The league demonstrably drafts off NATIONAL consensus value,
    not its own scoring - which is why QBs go ~20 picks late in a 6-pt
    league year after year. This validates the model's deliberate
    asymmetry: opponent behavior is fitted on consensus inputs, while MY
    planner values everything at league scoring. The gap between those
    two value systems (the unpriced 6-pt QB premium) is exactly the edge
    the expectimax is harvesting when it takes Allen at pick 18.

### Rookies vs ADP (2026-08-25): yes, a keeper-league premium - on benches

  RookieMarket, pick minus national ADP with veterans as the baseline:
  rookies go 3.0 picks earlier than veterans inside rounds 1-9, and 8.1
  picks earlier in rounds 10+ - every season, direction stable. The stash
  economics explain it: a rookie DRAFTED in round 12 keeps at round 12,
  cheaper than the round-10 cost of an undrafted pickup, so benches bid
  rookies up (Nacua went pick 177 vs ADP 248 in 2023). Inside the
  nine-round game the effect is too small to move the selection model
  (the lab's rookie/stash features already said so); for MY bench rounds
  it is a live draft-day fact: expect late rookies ~8 picks before ADP.

  Follow-up (same day): "can we use the rookie premium in the model anyway?"
  RookieMarket now ends with the deciding diagnostic - signed subgroup bias
  of the SHIPPED model's rookie survival on 2024: rookies +2.3% (observed
  minus predicted, N 351, inside noise), veterans -0.6%. The sign means the
  model already sends rookies slightly TOO early inside rounds 1-9 if
  anything - hand-encoding the raw -3-pick premium would push the wrong
  way. The -8.1 bench premium lives outside the game, where there is no
  prediction to improve. Verdict: using the raw finding to override the
  failed gate would be double-dipping the same 495 selections; the honest
  uses are bench-round draft intuition and re-running FeatureLab when the
  2026 draft adds data.

  Second follow-up: "what if ADP is already shifted by the keepers?" Correct
  suspicion, both mechanically checkable sides now in RookieMarket: pick
  numbers reduced by keeper slots passed, ADP reduced by kept players
  (veterans by construction) ranked ahead. Verdict: the in-game rookie
  premium was two-thirds artifact - rounds 1-9 DIFF -3.0 collapses to -0.9
  adjusted, because rookies sit deeper where more kept veterans have
  vanished ahead of them. The bench premium is genuine - rounds 10+ DIFF
  -8.1 barely moves (-7.8 adjusted; deep in the draft the two corrections
  cancel). National ADP still contains other keeper leagues' rookie demand,
  so the surviving -7.8 is a floor. Everything now agrees: no in-game
  rookie effect (raw stat, lab features, and model subgroup bias all say
  so), one real bench-stash premium outside the game.

### "A better model?" (2026-08-25) - the answer was more data, not more model

  Asked whether a better model CLASS exists. Assessment: at ~500 selections,
  trees/nets have nothing to learn from that the logit has not; the honest
  upgrades are (1) more training data, (2) a simulator mixture with the
  gaussian, (3) hierarchical per-manager structure, (4) an external mock
  corpus as a base model. Tested (1) immediately since it was free:

  TRAIN_ROUNDS = 13 shipped. Training on rounds 1-13 (game still ends at 9)
  improved survival calibration on BOTH judged seasons - 2024: 1.19% ->
  1.04%, held-out 2025: 1.86% -> 1.52% - at a QB-timing cost of 0.15 rounds
  (2.08 vs 1.93, far inside gate 3's beats-the-constant bar of 3.19).
  Honesty note: an ad-hoc confirm guard written minutes earlier (timing
  within +0.15) failed by ~0.002 rounds; shipping follows the project's
  pre-registered gate definitions, not the stricter spur-of-the-moment one,
  and the smoke tests pin both properties. Round 11 cutoff was WORSE than 9
  - the window is empirical, not "more is always better". Production fits
  now all flow through SelectionModel.fitShipped(), the single definition
  of the shipped model.

  Still open if ever needed: the simulator mixture (logit + gaussian rollout
  blend, weight tuned on 2024), per-manager sharpness (hierarchical scale on
  utility), isotonic display-layer recalibration of reported survival
  probabilities, and the external-corpus transfer (fit a base logit on
  public mock drafts, league correction on top) - highest ceiling, blocked
  on finding bulk draft data.

### The model-class question (2026-08-25): the boosted challenger WINS

  Justin asked why not pytorch / LightGBM / CatBoost / sklearn / HF. Answer
  by construction: nets and pretrained models have nothing to offer at ~500
  selections, but gradient-boosted trees - interaction discovery over the
  full feature set - were the one class with a real argument. Implemented in
  plain Java (BoostedSelectionModel: listwise-softmax boosting, XGBoost-style
  Newton leaves, quantile splits, deterministic, dependency-free), given ALL
  23 features including every one the linear lab rejected, and judged by
  BoostLab exactly like every feature: hyperparameters on 2024, one look at
  2025.

  Verdict - the boosted model swept every gate on held-out 2025:
    calibration 0.57% vs linear 1.52%; my slots 0.50% vs 1.17%;
    QB-timing MAE 1.91 vs 2.08 (constant 3.19).
  The unit test pins the capacity claim (trees learn an XOR interaction the
  linear utility cannot represent). SHIPPED: BoostedSelectionModel.fitShipped
  (300 trees, depth 2, lr 0.1) is now the simulator's brain everywhere -
  planner, keeper tools, smoke gates - with currentSeasonExtras() feeding
  production the same feature columns training saw. Rollouts run parallel
  across cores to pay for the 300-tree scoring. The linear SelectionModel
  stays in the repo as the interpretable companion (its coefficients still
  name the league's behaviors; the trees only out-predict it).

  Lesson recorded: twelve features failed to improve the LINEAR model, yet
  the trees extracted real signal from the same columns - the information
  was in the interactions, not the margins. And deeper training (rounds
  1-13) was shipped the same day; both "more data" and "more model" won,
  in that order.

### Other projection sources (2026-08-25): the bridge, validated

  Facts first: "Sleeper's projections" are Rotowire's (the feed rows say
  company=rotowire) - a single shop, so a second opinion has real value.
  Justin's proposed trick - adapt any site's points with Sleeper's projected
  event counts (a 4-pt site is off by exactly 2 x passing TDs) - is now
  ProjectionBridge, and its validation on the one site publishing both
  points and stat lines: QB mean abs error 2.5 points (worst 6.6), non-QB
  0.16. The trick works; TD-count disagreement between sources is the whole
  residual.

  Usage: data/external-projections/<name>.csv, two formats - a points sheet
  (# passTD=4 rec=0.5 header, bridged) or sportsbook season props (Sleeper
  stat keys as columns, scored directly, no bridge needed). Either becomes
  the planner's value feed via -Pprojections=<name>; uncovered players keep
  Sleeper's numbers. FantasyPros is parsed only as the validation
  instrument (top-10 per position server-rendered), per Justin's distrust.

  On sportsbook props as the source (Justin's efficient-market argument):
  largely right, with the caveats he guessed at - season-long props are
  low-limit, high-vig markets that books shade toward public star bias, so
  the arbitrage bound is loose; lines are medians not means (matters for
  skewed TD counts); and de-vigging asymmetric juice moves the implied
  number. Still the best public signal for the handful of players a
  decision hangs on - hand-key those into a props CSV rather than trusting
  any one site's table wholesale. Cross-source level differences run +20
  to +40 points on elites (FP vs Sleeper), but plans depend on RANKS, so
  the decision test is running KeeperPlan under both feeds.

### Projection source slots (2026-08-25, built while Justin was out)

  All fifteen requested sources are registered, selectable slots
  (ProjectionSources; -Pprojections=<name> on the planner and keeper tools,
  blend:<a>,<b> averages feeds). Two are automatic: sleeper (default) and
  borischen - his half-PPR tiers fetch free from S3 and transplant onto
  Sleeper's points curve with tier-mates sharing their rank-range mean, so
  tier structure survives. The paywalled thirteen (ETR, Fantasy Points,
  PFF, Draft Sharks, 4for4, Footballguys, FTN, RotoViz, Unexpected Points,
  FantasyOmatic, Action Network, NumberFire, RotoGrinders) go live the
  moment a subscriber CSV export lands in data/external-projections/ -
  points sheets bridge, stat/props sheets score directly; the directory is
  gitignored because subscriber exports are personal-use data. No paywalls
  were scraped; NumberFire/RotoGrinders/Fantasy Points render their tables
  in-app, so even their free views have no clean fetch.

  First sensitivity finding: on Boris Chen's values the no-keeper plan
  flips to RB,RB,RB,WR,WR,QB,... - the QB waits for round 6, because his
  QB tier 1 holds three names sharing one value, which erases the Allen
  cliff that makes the Sleeper plan take QB in round 2. The QB decision is
  source-sensitive; the keeper table's sensitivity is one
  -Pprojections=borischen KeeperPlan run away.

  Accuracy ranking between sources is deliberately deferred (Justin's
  call): sources plug in now, get archived by daily runs going forward,
  and the shootout happens when actuals exist to score them against.

  Keeper sensitivity across feeds (200 rollouts, noise ~+/-1.8):

                     sleeper   borischen   blend
    Tuten r12         +15.8      +20.2     +20.4   <- first under EVERY feed
    Purdy r13         +14.0       +0.1     +12.0   <- source-fragile: his value
    Flowers r4         +5.4       +8.7      +9.8      rides on the Allen cliff,
                                                      which Chen's flat QB
                                                      tier 1 erases
  The robust conclusion survives the projection-source question: keep
  Tuten. Purdy's case depends on believing Sleeper/Rotowire's QB spread
  over Chen's tiers - exactly the kind of dependence the slots exist to
  expose.

  More automatic feeds (same day, on request): probed ESPN, CBS, NFL.com,
  FantasySharks, FFToday, NumberFire for free fetchability. Two landed:
  espn (their fantasy API, 480 players, full stat lines via the
  X-Fantasy-Filter endpoint, stat ids verified against a known QB) and cbs
  (server-rendered stat tables, 373 players, fixed column layouts with a
  loud parse sanity-check). Both score under league settings through the
  one shared scorer. NFL.com's old API is dead; FantasySharks ignores its
  JSON flag; NumberFire renders in-app. Four automatic feeds now archive
  daily (940 rows today). Notable pattern: ESPN and CBS both project
  elites 40-65 points HIGHER than Sleeper/Rotowire - Rotowire is the
  conservative outlier on stars, which matters for cliff-driven decisions
  like the Allen round-2 call; CBS has Purdy at 384 vs Sleeper's 363.

  Keeper sensitivity, all five feeds (200 rollouts each):

                sleeper  borischen   espn    cbs   blend(all 4)
    Tuten r12    +15.8     +20.2    +20.6   +8.9      +26.2    <- FIRST under
    Purdy r13    +14.0      +0.1     -1.2   -0.6      +19.4       every feed
    Flowers r4    +5.4      +8.7     +8.6    n/a      +16.8

  Tuten ranks first on every projection source that exists in the repo.
  Purdy spans -1.2 to +19.4 - he is a bet on Rotowire's specific QB spread.
  The keeper question is settled as robustly as it can be before the
  declaration deadline: keep Tuten (pair decision pending Justin's second
  slot, all keeper values move as leaguemates declare).

  Correction (Justin's catch, same day): borischen is clustered FantasyPros
  ECR - consensus expert RANKS, whose panel includes ESPN and CBS analysts.
  The repo therefore has THREE genuinely distinct projection shops
  (Rotowire via sleeper, ESPN, CBS) plus one consensus-of-ranks feed, not
  five independent opinions. Evidence properly weighted: Tuten still ranks
  first under each of the three primary shops SEPARATELY (+15.8 / +20.6 /
  +8.9), which is real triangulation; Chen and the blend agreeing adds
  little new. Purdy's fragility also survives - it is primary-shop
  disagreement (Rotowire +14 vs ESPN -1 vs CBS -1), not a consensus
  artifact. For blending, blend:sleeper,espn,cbs is the independent-shops
  average; adding borischen double-counts the consensus.

## DECISION LOCKED (2026-08-25): keepers = Tuten r12 + Purdy r13

  Justin declared Tuten (first under every feed) and Purdy (best under the
  default feed and the blend; ESPN/CBS rated him ~0; Flowers was the
  alternative not fully analyzed for lack of time). Both cost r10+, so all
  nine picks stay live. Sleeper does not show the declaration yet - the
  commissioner hand-enters keepers - so the planner grew a pair-aware
  -Pkeepers=Tuten,Purdy knob that dedupes automatically once the
  declaration lands in the API.

  The locked plan (500 rollouts, sleeper feed):
    [RB, WR, RB, WR, WR, WR, TE, QB, RB] - best-nine 1807.6 (+/- 1.1),
    p10 1779.8.
  Structure: no early QB (Purdy holds the slot); round 2 is effectively a
  coin flip between WR (1816.0) and taking Allen anyway (1810.9) when he
  survives - Allen is 72% gone by pick 18 under this plan, so the pivot
  only exists at pick 7; round 8's QB is a cheap Purdy-upgrade lottery
  (Dak-tier, ~1 point of expectation); round 9 is flat across all
  positions - a free bench/stash pick, where the rookie bench premium
  says to expect late rookies a round early.

  Remaining pre-draft work: rerun DraftPlanner as leaguemates declare and
  ADP drifts (AdpSnapshot daily); component F (live draft mode) is the
  last unbuilt piece of the roadmap.

### LeagueOutlook (2026-08-25, late): every seat optimized - and everyone
### has declared

  All 12 managers' keepers are now in Sleeper. LeagueOutlook runs the
  expectimax from every seat (their slot, their keepers, everyone else on
  the fitted model) and decomposes: keeperless seat value (the slot alone)
  and per-keeper marginal deltas (drop one, re-optimize, diff).

  Findings at 150 rollouts:
  - Justin's seat ranks 10th of 12 (1809.6). His keeper haul (+24.4) is
    third-worst; the league's class is led by JakeSK's Nacua at r13
    (+112 alone), jerem9604's Taylor r4 + Bowers r7 (+128 total),
    itsabust's JSN r5 (+75), patekxwater's Collins r8 (+74).
  - Slot value (keeperless column): slot 1 1828 down to slot 12 1763 -
    serpentine position is worth ~65 points this year; slot 7 sits at 1785.
  - Purdy's marginal value GIVEN Tuten is +6 (vs +14 standalone): the two
    keepers' freed-pick cascades overlap - marginal keeper value
    diminishes, which the pair decomposition now shows directly.
  - The strategic read: the keeper gap to the leaders (~80-100 points) is
    sunk; the catch-up levers are draft execution (the model's edge - the
    unpriced 6-pt QB premium among them) and the two flat rounds (8-9)
    the plan leaves for upside.

### Slot value, settled (2026-08-25, late): three constructions and a fix

  Justin's question - why the keeperless column had bumps at slots 3/10 -
  exposed that per-seat counterfactuals live in different worlds. Clean
  curve (no keepers anywhere): monotone 1888 -> 1838, no U shape, no
  bumps; the anomalies were board-return and pass-through artifacts. His
  final construction (SlotValue): the MOCK-ROOM world - every keeper
  pinned to a slot, out-of-game keepers onto round 9 (second onto 8), one
  shared world, keeper projections subtracted post-hoc. Raw subtraction
  (his spec) makes the four QB-keeper seats the bottom four - the
  cross-positional baseline trap - so the res-vorp column subtracts value
  over replacement at each seat's round-9 pick instead. Result: JakeSK
  1805 and BHier 1799 lead (early slots, cheap burned rounds); JFMarino
  1692 and jerem9604 1697 trail (late slots plus r3-r7 keeper rounds
  burned); Justin 1740, mid-low. res-vorp prices in the real cost of
  WHERE each team's keeper rounds sit, which the clean curve cannot see.

### The 10,000-rollout ledger (overnight 2026-08-26, +/-0.6)

  Search/evaluate split, top-8 per team, results committed in
  data/keeper-ledger-10k-2026-08-26.txt. Precision resolved the open
  puzzles: Herbert collapses to +0.4 (the 60-rollout +8.4 was noise - a QB
  below the round-6 shelf is pure unclaimed insurance) while Purdy holds
  +10.4, a ten-point gap matching the waterline math exactly. Justin's
  pair is decisively optimal: Tuten +16.3, Purdy +10.4, Flowers +5.6
  (Purdy over Flowers by 4.8 at +/-0.85). Daniels flips NEGATIVE (-5.2),
  making BHier's kept pair worth ~-3 combined against +37 unkept - the
  league's worst keeper decision; McBride confirmed -2.6. Nine of twelve
  teams kept their exact top two.

### The policy tournament (2026-08-26): every architecture, one game

  The conversation that produced it: Justin re-derived, from first
  principles, why max-over-committed-heads is legitimate (the max ranges
  over strategies you control, priced by averages that already contain
  availability odds) while max-inside-rollouts is clairvoyant, and asked
  what his old shuffled-tail design at depths 1-3 was actually worth. So:
  PolicyTournament races 11 policies for MY seat in one shared game -
  7 live picks, Tuten+Purdy pinned on my rounds 8-9 (withKeeperSlots),
  composition exactly 1QB/2RB/3WR/1TE/2FLEX enforced by a Needs ledger,
  opponents by the shipped boosted model. With QB removed by Purdy the
  committed space is only 742 sequences (the multiset sum
  140+105+210+42+105+140, pinned by test), so the exhaustive-committed
  entry IS the converged architecture's committed half: every full
  commitment priced on CRN, winner re-priced fresh. The adaptive family
  (oldschool-1/2/3 with shuffled tails = his 2022/2023 design as actually
  used live; adaptive-greedy = same depth-1 lookahead, greedy tails)
  runs on the new resumable-simulation primitive (SimState/simulateFrom/
  branchWith) - the same primitive component F needs. Paired eval on
  shared fresh seeds; vs-exhaustive column is a paired difference.

  RESULTS (declared game, 800 trials, paired vs exhaustive RRRWWWT 1810.1):
  adaptivity is worth +6 to +8 - every adaptive/reactive policy beat every
  committed plan. greedy-raw and adaptive-greedy tie on top at 1817.7
  (+7.6/+8.1); the oldschool ladder reads +6.0/+7.0/+7.8 at depths 1/2/3,
  each level buying ~1 point - Justin's 13-second depth-2 tool was already
  near-optimal, and depth 3 would have added ~0.8 within +/-1.2 noise. The
  staged one-round frontier found the exhaustive optimum exactly (no
  valley: with Purdy killing the QB dimension the landscape is smooth,
  which is also why plain greed captures the whole adaptive premium).
  Shipped RWRWWWT scores -3.4 in THIS game - not an indictment: it was
  optimized for the real 9-pick free-keeper game, a different ruleset.
  adp-follower -9.6 prices the market-mimicry penalty; random floor -96.
  Inner=16 argmax noise mildly understates the adaptive rows, so +8 is
  closer to a floor. Flowers counterfactual (-Pkeepers=Tuten,Flowers,
  r4 burned + Tuten pinned r9, 2520 sequences) queued as the test of
  whether depth starts mattering once QB timing re-enters.

  FLOWERS COUNTERFACTUAL (Tuten r9-pinned + Flowers r4 burned, QB open,
  2520 sequences, 800 trials): the QB dimension detonates exactly where
  the theory said. greedy-raw COLLAPSES to -38.7 (the raw-points trap:
  it grabs ~340-pt QBs early; in the Purdy game it was +7.6 and tied for
  best) while greedy-vorp holds +1.8 - the waiting table is worth ~40
  points here. The staged one-round frontier SPRINGS THE VALLEY TRAP
  Justin predicted: it commits QB at round 2 (RQBRWWTW, -10.3) because
  its greedy-raw tails take QBs early and undervalue every wait branch,
  while exhaustive-committed finds QB at the LAST live pick (RRRWWTQB,
  1817.6) - his 1500-now/1400-valley/1800-late scenario, live in real
  numbers. Adaptive family leads again (+5.9 to +9.8), and oldschool's
  RANDOM tails BEAT adaptive-greedy's raw-greedy tails (+9.8 vs +5.9):
  a uniformly-random stand-in is unbiased about QB timing where greedy-
  raw is confidently wrong - being wrong at random beats being wrong
  systematically. Depth no longer ladders (9.8/9.5/8.8 at d1/2/3,
  +/-1.1): at inner=16, more heads spread the same rollouts thinner.
  CAUTION on cross-game reads: Flowers-world means (~1827) vs Purdy-world
  (~1818) do NOT reopen the keeper decision - the two games charge
  different pick prices (r4+r9 burned vs r8+r9) and hold different boards
  (Purdy returns to the pool); the decision-relevant comparison was the
  10k ledger's real-rules game (Purdy +10.4 > Flowers +5.6), and keepers
  are locked besides. ACTION ITEM the trap exposes: DraftPlanner's staged
  search with raw-greedy tails is exactly this vulnerable for QB-open
  seats (LeagueOutlook keeperless seats, V(none) branches) - fix is
  VORP-aware tails and/or explicit QB-round enumeration, then diff all
  twelve seats' plans.

### The baseline audit: alarm, quantification, acquittal (2026-08-26)

  Justin's question - "why doesn't this affect keeper logic?" - exposed
  that every ledger delta's V(base) branch is a QB-open seat planned by
  the same staged search the tournament just caught mistiming QB.
  BaselinePlanAudit: ALL TWELVE keeperless baselines commit QB in rounds
  1-3 (mine RQRWWWWTW, QB r2; itsabust QB r1). Alarm justified. But
  BaselineQbTimingCheck (my seat, QB slid through all nine rounds, 10k
  fresh-seed evaluations) ACQUITS the search: QB r2 = 1785.0 is the true
  global peak - late QB loses (r9 -0.4, r8 -1.4), and the REAL valley is
  the middle (r3-5, about -16). The keeperless QB landscape is BIMODAL:
  elite QB at pick 18 or shelf QB at r8-9, never between - and the two
  peaks are within ~1 point, so V(base) is priced right, delta ~= 0, and
  Purdy +10.4 > Flowers +5.6 STANDS. Cross-validation: 1785.0 here
  matches the 10k ledger's keeperless-seat value exactly. Why the
  tournament's trap didn't transfer: the Flowers game burns r4 and locks
  a WR, thinning the early skill allocation until QB-late wins by 10;
  the real 9-pick baseline game keeps QB-early and QB-late in a near-tie
  with QB-early on top. Lesson recorded: the tournament diagnosed a real
  MECHANISM (raw-greedy tails cannot see across valleys), but whether a
  given game HAS a valley is an empirical question - audit before fixing.
  Residual caveats: variants held the skill order fixed (a jointly
  re-optimized QB-late plan might close its ~1-point gap, never by ~10);
  other seats unverified at 10k, itsabust's QB-r1 the one outlier worth
  a spot-check if their numbers ever matter.

### Tournament v2 (2026-08-26): vorp tails, timing heads, five ML entries

  Declared game: oldschool-2-vorp WINS (+8.7) - Justin's combo, ahead of
  both parents. Flowers game: the oldschool family clusters on top
  (+8.8..+9.8, depth and tail variants within noise; RAW tails remain
  the only handicap - adaptive-greedy +5.9). The three v2 verdicts:

  1. timing-committed (Justin's structured head: commit QB round + TE
     round only, RB/WR live by VORP): finds QB@r8 TE@r7 over just 42
     heads in the Flowers game - the valley problem solved at 1/60th of
     exhaustive's search cost - and BEATS exhaustive-committed +5.2,
     because its smooth dimension stays adaptive. Best value-per-compute
     on the board; the natural draft-night engine.
  2. ml-imitation (distilled oldschool-2-vorp): +7.8 and +8.7 in the two
     games, within noise of its teacher, at O(trees) per pick with the
     tightest SEs in the adaptive tier. Distillation works: judgment of
     the slow lookahead, none of its clock.
  3. The rest of the ML family: ml-cem found the exhaustive optimum
     exactly in the Flowers game but mode-collapsed onto the shipped
     plan in the declared game (evolution mirrored: optimum there, -4.2
     here) - learned searches are cheap but need restarts; ml-fittedq
     +5.7/+5.0 (escapes its VORP guide only in the QB-open game);
     ml-reinforce +7.3 declared but 0.0 Flowers - the QB-open surface
     is where linearity dies, which is QB timing in one sentence.

  Draft-night architecture that falls out: precompute timing-committed
  search + imitation distillation; live picks by oldschool-2-vorp with
  clock-scaled inner rollouts; greedy-vorp as the never-time-out floor;
  never raw-greedy anything.

## The improvement plan (2026-08-26, planned before building - Justin's call)

Every item carries its test; nothing ships without one. Priorities assume
the draft is ~1-2 weeks out: P0 = before draft night, P1 = if time allows,
P2 = post-season / 2027 infrastructure.

### A. Projection models - the input everything multiplies through

  A1 (P1) Historical accuracy shootout NOW, not post-season: the repo has
      HistoricalProjections and 2025 actuals - score whatever feeds can be
      reconstructed for 2025 (MAE + rank correlation, per position).
      TEST: per-feed error table on 2025; decides whether the blend or a
      single feed drives draft night.
  A2 (P1, gated on A1 showing spread) Learned blend: regress actuals on
      multi-feed projections, position-aware weights - the FIRST genuinely
      open ML problem in the repo (real data, unknown process). LightGBM/
      PyTorch belong here, not in the policy game.
      TEST: held-out season MAE must beat the best single feed.
  A3 (P2) Projection uncertainty: per-player variance from feed
      disagreement + FFC spread; makes the risk knobs mean something
      beyond availability. TEST: interval calibration on actuals.
  A4 (P2) Boom/bust + injury: currently assumed away by the nine-round
      spec. Before modeling anything, run the DECISION-SENSITIVITY test:
      perturb projections within realistic variance, count how often any
      actual decision (keeper ranks, plan positions) flips. If decisions
      are stable, the assumption stays; model accuracy without decision
      impact is decoration.

### B. Opponent model / simulation fidelity

  B1 (P0) Robustness sweep, cheap and decisive: re-run the planner and
      tournament winners across an ensemble of plausible opponent brains
      (temperature x TRAIN_ROUNDS x bootstrap refits). TEST: do MY
      decisions change across ensemble members? Stable = draft night is
      safe; unstable rows name exactly which decisions are fragile.
  B2 (P0) Stress suite: opponents deviating on purpose (a manager goes
      QB-early, a WR run starts round 3, one seat autodrafts pure ADP).
      TEST: value of my plan under each stress vs the model world -
      quantifies brittleness the ensemble cannot see.
  B3 (P1) Autodraft detection for draft night: an absent manager's picks
      follow ADP nearly deterministically - detectable live after 2-3
      picks, and exploitable (their future picks become near-certain).
      TEST: rehearse against a Sleeper mock with autodrafters.
  B4 (P2) Boundary checks: CHOICE_SET and ADP_LIMIT truncations - widen
      both, TEST plan/value invariance.

### C. Policy layer - mostly solved, transfer what won

  C1 (in flight) Flowers-game ceiling: decides if ANY policy headroom
      remains where valleys live. Declared game already measured flat.
  C2 (P0, the big one) REAL-GAME TRANSFER: the tournament's lessons live
      in the mock-room abstraction; DraftPlanner's real game still
      searches with raw-greedy tails and a one-round frontier. Port the
      winning stack - VORP tails + timing-committed (QB round x TE round)
      heads - into the real-game search. TEST: paired 10k evaluate of
      old-search vs new-search plans for all 12 seats; ship if any seat
      improves, keep if none regress.
  C3 (P1) Risk columns for free: the tournament already stores per-trial
      scores - report p10/p25 alongside means, decide whether draft-night
      policy maximizes mean or mean minus lambda x downside. TEST: none
      needed beyond the report; it is a decision, informed by A3 later.
  C4 (P2) ml-general: one policy trained across randomized worlds
      (keeper configs, projection noise, seats), frozen, entered in BOTH
      tournament games. TEST: matches per-game specialists in both =
      learned the mechanism; its value is robustness + speed, not points.

### D. Component F - draft-day mode (P0, the build that remains)

  The stack is tournament-decided: precompute (round-1 opening book,
  timing-committed search, imitation model) + live loop (oldschool-2-vorp,
  clock-scaled inner) + floor (greedy-vorp, never raw). Pieces: wire
  SleeperLiveDraft's board into the resumable-sim primitives; snipe-
  conditional pre-plans between picks; per-pick latency budget.
  TEST: full end-to-end rehearsal against a Sleeper mock via -PdraftId -
  recommendations sane vs offline plan, every pick under the 60s clock.

### E. Infrastructure and process

  E1 (P1) Simulator speed: profile simulateOnce (boosted inference and
      board mutation are the suspects); a 2-5x speedup multiplies every
      experiment above. TEST: benchmark harness before/after, identical
      outputs on fixed seeds.
  E2 (P1) Persist per-trial score arrays from tournament runs (enables
      C3 and post-hoc stats without reruns).
  E3 (P0) Open the keeper-rules -> master PR (~50 commits) once Justin
      wants review; keep AdpSnapshot daily.

### F. Facts needed from Justin (elicitation, P0)

  - Exact draft date and time (schedules the precompute window and the
    final night-before run).
  - Are in-draft pick trades allowed/likely in this league?
  - Who might be absent or autodrafting? Any known strategy chatter?
  - Is the keeper board locked, or can managers still switch (Kevin
    switched once already)?

## Overnight run plan (2026-08-26 night, Justin asleep; objective = MY draft
## score under exact Sleeper projections, draft in ~5 days)

  Priorities re-ranked for that objective: projection work parked
  (projections declared exact), lab games parked (lessons extracted).
  1. TimingPlanner: (QB round x TE round) heads + roster-aware VORP fill,
     in the FULL-RULES game; 10k paired vs the shipped plan.
  2. Real-game adaptive premium: receding-horizon vs committed, 10k-ish.
  3. Opponent-model ensemble + stress suite -> stability verdict or an
     if-then deviation sheet for draft night.
  4. Precompute artifacts: pick-7 opening book, pick-18 snipe branches,
     real-game imitation model, latency benchmark.
  5. Morning report; commits between phases.

### Overnight phases 2-3 (2026-08-27): the premium is real, the plan is robust

  ADAPTIVE PREMIUM, full-rules game, paired at 10k/300: adaptive depth-2
  lookahead with VORP tails scores +12.4 (+/-1.4) over the shipped
  committed plan - BIGGER than the lab's +6..+10. Hierarchy: adaptive
  +12.4 >> timing QB@none/TE@r7 +1.8 > shipped 0 > reactive vorp -3.5
  (reactive alone loses in the real game; the bench changes its
  calculus). Committed-vs-committed stays a dead heat; the value lives
  entirely in re-deciding from the live board. Component F is worth a
  dozen points. That is the draft-night mandate, priced.

  WORLDS RACE, six worlds x four candidates at 2000 rollouts: the best
  timing head is QB@none TE@r6-or-r7 in EVERY world - the structure of
  the answer is world-invariant. The shipped plan sits within ~1.5 of
  each world's own optimum in five of six worlds, including both QB
  appetite shifts (draft-proof against being wrong about QB hunger).
  The one exception: a SHARPER league (linear t0.7) - there the shipped
  plan leaves ~16 points and reactive VORP leads (1883.4): the more
  predictable the room, the more adaptation pays. Consistent with the
  premium: if draft night looks scripted, trust the live tool more, not
  less.

### v3 championships (overnight 2026-08-27): every family, both games

  DECLARED GAME - total consensus: seven independent committed searches
  (exhaustive, staged, DP-with-proof, SA, NRPA, CEM, evolution) all
  found RRRWWWT. bnb screen: 44 of 742 evaluated, true optimum, regret
  0.0. Adaptive tier +5..+7.5 with ml-imitation +7.5 (+/-0.5) and
  oldschool-2-vorp +7.2 on top; MCTS +5.9 does NOT beat flat lookahead
  at equal budget in a game this small (and costs 4x the clock).

  FLOWERS GAME - the valley grades everyone: the EXACT DP fell in
  (-12.9): its proof passed (CE = enumerated max) but certainty-
  equivalence is the wrong objective in a correlated-availability
  valley - it commits QB r2. Lesson of the night: exactness is only as
  good as its objective. bnb (CE bounds + rollout evaluation) is the
  sound hybrid: 106 of 2520, regret 0.0 again. All four metaheuristics
  found the same near-optimum RRWWWTQB (-4.1): they jump valleys but
  land beside the peak. MCTS 1831.2 (+6.7) TIES the top flat lookaheads
  in the bigger game (oldschool-3 +7.1, oldschool-2-vorp +6.8) - tree
  allocation starts paying exactly where the space grows. Raw-greedy
  tails remain the one poison (adaptive-greedy -0.6). ml-reinforce
  jumped to +5.2 (the depth-table features fixed its QB blindness).

  Combined with the real-game premium (+12.4): the lab adaptive ceiling
  is ~+7, the real game rewards adaptation nearly double - bench slack
  amplifies option value. Draft-night engine choice unchanged:
  oldschool-2-vorp (or MCTS at scale) live, imitation as instant
  answer, B&B screening for any committed pre-search.

### Morning report (2026-08-27): the night's verdict

  ADDENDUM: the last three challengers all reached the top tier.
  exit-agent (lookahead on twice-distilled tails) nominally tops BOTH
  games (1821.4 / 1831.5); exit-policy is the best instant policy in
  both (1820.5 / 1827.5), beating single-cycle imitation - the second
  ExIt cycle paid. hop and saa-replan TIE the top flat lookaheads at
  ~1/30th their compute (50s per 150 trials vs oldschool-3's 1900s):
  solving sampled futures exactly beats rolling out per head. And
  saa-committed found the TRUE valley-game optimum RRRWWTQB in 2
  SECONDS (exhaustive: 31 minutes) - the scenario-DP keeps
  distributions and does not fall into the valley CE-DP fell into.
  Committed search is a solved, instant problem now.

  ARENA (the 4-5 scoreboard): honest negatives. Base, Bayes and robust
  committed plans are IDENTICAL (RRRWWWT maximizes mean, min and base
  alike) - the plan is too world-invariant for hedging to buy anything.
  pace-vorp v1 never beats plain vorp - its depletion-pace signal
  barely moves across these worlds and its correction is crude. The
  base-model lookahead wins the MEAN column and every non-drone world:
  ADAPTIVITY TO THE BOARD SUBSUMES MODEL HUMILITY - the board reflects
  whatever world is true, so a board-adaptive policy is implicitly
  world-adaptive, and explicit inference on top added nothing this
  game can measure.

  THE COMPUTATION, chosen: live engine = hindsight/saa-replan (S=100+
  per pick, sub-second) cross-checked by oldschool-2-vorp when the
  clock allows; exit-policy as the zero-latency answer; saa-committed
  (seconds) for any committed search; bnb screen when rollout-priced
  search is wanted. Committed plan for the real game: shipped RWRWWWT
  +QB8+RB9 stands (dead heat with all challengers, +12.4 below live
  play). Tonight: precompute on this stack + wire component F.

## Tonight's docket (2026-08-27, final - the reaches/certificates/engine night)

  DIAGNOSIS (cheap, first):
  D1 Gap certificate: clairvoyant bound (mean of per-scenario exact
     optima, machinery exists in hop) minus best policy value, real
     game. THE number that says how much algorithm work remains.
  D2 ReachAudit: real vs simulated reach-size distributions, league and
     per manager - the tail-calibration defect quantified, and each
     manager's reach fingerprint (epsilon) measured.
  D3 AppetiteAudit - DONE, run today: league first-QB round 6.1 -> 4.1
     -> 3.7 -> 5.4 -> 5.6. NOT monotone drift and NOT always-low: a
     2022-23 early-QB ERA (mean ~4) that cooled back to ~5.5. Matches
     Justin's own lore - his first-QB rounds were 5,2,1,2,2: he helped
     cause the era he remembered. Renteez is the one permanent early
     drafter (4,3,2,3,2); patekxwater cooled (3,3,4 -> 8); itsabust and
     JFMarino always late. Kept-QB composition co-moves (5 kept in the
     hottest year). Verdict: recency/regime weighting IS justified -
     pooled earliness overstates current QB appetite; 2026 (4 kept QBs)
     most resembles 2024.

  MODELING (gated on D2, judged on 2025 TAIL calibration, fit <=2024):
  M1 Sniper mixture: pick = chalk with prob 1-eps, reach-mode with prob
     eps; league eps + per-manager eps shrunk. The heavy-tail fix.
  M2 Graveyard resurrection under the tail metric: loyalty, FFC spread,
     QB-stack, rookie - all "real but unhelpful" for AVERAGE metrics,
     never judged on tails. (Clarified: the boosted brain already
     consumes all rejected features - rejection was the linear model's
     verdict; what is new here is per-manager submodel structure and
     the tail-metric judgment, plus multiple-testing debt honestly
     carried.)
  M3 Regime weighting of earliness per D3.
  M4 Ensemble + sniper scenario sampling -> survival BANDS; a sniper
     world in the robustness suite; decision-impact check (expect
     wait-or-take rows to shift toward take; plan structure to hold).

  ENGINES (the 15-30s budget, preferably 15):
  E1 Real-game scale study: premium vs compute (inner 16/64/256, hop
     S 24/500/5000) - does +12.4 grow with the clock?
  E2 Committee engine: hop + saa-replan + oldschool-2-vorp + mcts +
     exit-agent vote; agreement = confidence, disagreement = surfaced
     with numbers for the human.
  E3 Online filter: per-observed-pick posterior updates of eps and
     earliness, scenario refresh; tested in mismatch + sniper worlds.
  E4 Pondering harness + generalized opening book (precompute answers
     for plausible boards between picks; live search as fallback).
  E5 Component F wiring + full mock rehearsal, latency logged.
  E6 AlphaZero wires (priors + leaf values) ONLY if E1 shows rollout
     starvation. C1: re-run the certificate after upgrades.

  Still owed by Justin: draft date/time, in-draft pick trades allowed?,
  likely autodrafters, keeper-board lock status.

### Data integrity (2026-08-27, Justin's call): dated ADP + draft dates

  DRAFT DATE, answered from the API: the 2026 draft object carries
  start_time = Tuesday 2026-09-01, 20:45 local. Five days out.

  ADP PROVENANCE, audited: historical ADP = Sleeper's per-season
  adp_half_ppr from the season projections record - ONE frozen snapshot
  per season, not dated to the league's draft day. Justin's concern is
  legitimate: every model trained on past seasons inherits whatever
  timing that snapshot has. Docket item D4: walk the previous-league
  chain's /drafts collections, print every season's draft start_time,
  and bound the snapshot-vs-draft-date mismatch; cross-check FFC
  per-season ADP where it disagrees. For the draft that matters, the
  problem is already solved forward: our own AdpSnapshot archive
  (daily since 2026-08-25) gives exact-dated ADP through draft eve,
  and the final night-before run uses the freshest snapshot.

  PURDY, on the record: the keeper pair is conditioned on as FACT
  (locked, entered), never as optimal. Justin declared under time
  pressure; no model assumes the call was right, the counterfactual
  machinery (-Pkeepers) treats it as a variable, and 2027 keeps get
  decided from scratch by the full machinery with dated ADP.

### ADP provenance verdict (2026-08-27): partially contaminated - adjudicate by gates

  Sleeper-vs-FFC per season: spearman .94-.98, mean rank gap 6-10 - not
  a season-end table, but the disagreement TAILS lean the contamination
  way: Josh Jacobs 2022 at sleeper 44 vs ffc 111 (preseason afterthought,
  led the league in rushing - rank 44 "preseason" is a smoking gun),
  Addison/Thomas promoted in 2024, the 2025 QB demotions. 2021 reads as
  pure noise (rho .981). Mild, season-dependent pollution - and the
  tails are exactly where reach modeling lives, and the negative board-
  wide surplus in QbMarketGap now has a candidate explanation.

  THE DECISIVE TEST (tonight, D4b): make the historical market feature
  switchable (sleeper stored vs FFC preseason), refit the brain both
  ways, judge on held-out 2025 calibration. The league drafted off what
  their draft room showed - if FFC-preseason explains their August picks
  BETTER than the stored snapshot, the snapshot was polluted and FFC
  ships as the historical market input. The model's own gates adjudicate
  provenance. 2026 is unaffected either way: live preseason ADP + our
  dated AdpSnapshot archive through draft eve. Also rerun QbMarketGap
  under FFC ADP as a cross-check of the coolness magnitudes.

### Historical Sleeper ADP, hunted online (2026-08-27)

  Wayback CDX: exactly ONE archived Sleeper projections API capture
  exists - 2021-08-09, full 1978-player response with adp_half_ppr,
  harvested and committed (data/sleeper-projections-dated-2021-08-09
  .json.gz; it also carries DATED projections - shootout fuel). The
  dated-vs-stored diff DATES the stored 2021 snapshot: Dobbins (rank
  26->335) and Etienne (48->342) crashed on their late-August season-
  ending injuries, Edwards SPIKED 116->73 (post-Dobbins, pre-his-own
  Sep 9 injury), Robinson spiked 70->33 (post-Etienne). Stored 2021 =
  Aug 28-Sep 9 window: DRAFT-EVE, well-timed, clean for our purposes.
  Contamination is therefore season-dependent: 2021 clean; 2022 stands
  accused by Jacobs (44 vs FFC 111 - inexplicable preseason); 2023-25
  tails mixed. Remaining route for 2022-25: FantasyPros' archived ADP
  pages (near-draft captures exist for every season: Aug 6+Sep 13 '22,
  Aug 30+Sep 1 '23, Aug 3 '24, Aug 1+Sep 3 '25) IF their table carries
  a Sleeper column - being verified. Fallback unchanged: FFC preseason
  ADP with the gates adjudicating. Either way D4b proceeds tonight
  with three candidate market feeds where the archive delivers.

### League intel (Justin, 2026-08-27): the artifact on their screens

  "Many people in the league may be using FantasyPros half-PPR
  rankings." This names the probable TRUE market feed: FP half-PPR ECR
  - a national consensus at standard 4-pt passing TDs, which is exactly
  the information-set the model already inferred behaviorally (league-
  scored values DEGRADED calibration; national consensus explained
  picks). The 6-pt QB edge survives unchanged; the feed gets named.
  D4b grows a third candidate: dated FP half-PPR ECR from near-draft
  Wayback captures per season, adjudicated by the same held-out gates -
  whichever feed best explains their actual August picks is what they
  were looking at. If FP wins, the LIVE tool's opponent model switches
  to current FP ECR on draft night (repo already has FantasyPros
  plumbing; smokeTest covers it).

### Provenance verdict REVERSED (2026-08-27, same day - the audit worked)

  The dated harvest acquits the stored snapshots. Jacobs 2022: dated
  Aug-6 Sleeper ADP rank 47, dated Sep-13 rank 45, stored 44 - the
  stored value matches dated preseason Sleeper almost exactly. The
  "smoking gun" was a CROSS-PLATFORM split (Sleeper's crowd loved
  Jacobs at ~46 while FFC's mock population had him 111), not
  contamination - and Sleeper's crowd was the one vindicated by the
  season. Combined with 2021's injury-forensics dating (draft-eve),
  the working conclusion flips: stored per-season Sleeper ADP is
  draft-eve clean until proven otherwise. Tonight's D4 still diffs
  stored-vs-dated for every season systematically (8 dated CSVs now
  committed, 2021-2025), and the negative board-wide surplus in
  QbMarketGap needs a new explanation (platform scale, not pollution).
  The D4b bake-off is UNCHANGED and now cleaner: sleeper-stored vs FFC
  vs FP-ECR is a question about which artifact the LEAGUE follows, not
  about data quality - Justin's testimony says FP rankings, the gates
  will say.

### The stray taxonomy (Justin, 2026-08-27) - M2 finalized

  Justin's behavioral spec: the league follows various rankings feeds
  but strays from them systematically. Two mechanisms, cleanly split:
  systematic strays are FEATURES (shift the mean of the choice model);
  idiosyncratic strays are the sniper EPSILON (widen the tails). The
  named hypotheses, mapped:

  - QB-cool: TESTED and SHIPPED (QB intercept + QB x earliness; the
    drone gap prices it at 8-20 picks vs market, every season).
  - Rookies: tested for AVERAGE calibration (rejected); retest under
    tails + interact with keeper-eligibility (rookie reaches in a
    keeper league are stash bets - the keeper-stash feature also
    retests here). Data: rookiesForSeason exists.
  - Older players: NEW - never tested. Age feature from Sleeper player
    records; hypothesis is age-fade beyond what rankings already price.
  - Big names that dropped: partially tested as value-fall (-1.75,
    "fallers keep falling" - the league LETS fallers fall, i.e. fades
    faded names rather than buying them); retest as a distinct
    last-season-ADP-vs-this-season-ADP drop feature, both metrics.
  - Team biases (homers): NEW - infer each manager's favorite teams
    from five seasons of draft history vs baseline (shrunk hard; 45ish
    picks per manager), then a player-team x manager-affinity feature.
    teamOf data exists; the stacks machinery already walks it.

  Feed heterogeneity ("various adp rankings"): D4b upgrades from
  pick-one-feed to FITTED BLEND - market feature = learned convex
  combination of sleeper-dated/FFC/FP-ECR (and per-manager feed
  assignment if the likelihood supports it; ~45 obs per manager,
  shrunk). Whichever mixture best explains held-out 2025 picks is the
  league's information diet, measured rather than assumed.

### Feed taxonomy and the consensus+deltas reparameterization (Justin, 2026-08-27)

  Justin's recursion catch, generalized: the feed universe is mostly one
  consensus factor wearing different logos. Taxonomy:
  - PRIMARY BEHAVIORAL: platform ADPs - real drafts by distinct user
    populations (Sleeper, Yahoo, ESPN, CBS, NFL, Fantrax, FFC mocks).
  - PRIMARY EDITORIAL: in-house ranking sets (ESPN's rankers etc.) that
    shape their platform's defaults.
  - DERIVED/RECURSIVE: FP ECR (average of whichever analysts are
    checkmarked that day), FP AVG (average of platforms), Boris Chen
    (clusters of FP ECR) - consensus of consensus, near-zero new
    information, and COLLINEAR in any naive blend fit.

  The fix: reparameterize D4b as CONSENSUS + DELTAS. Market feature =
  consensus factor (any aggregate; they are the same object) + weighted
  deltas of primary feeds from consensus. Recursive feeds have ~zero
  delta and drop out automatically; the blend weights live on the
  deltas, which are the identifying variation and well-conditioned.
  The behavioral question becomes "whose DEVIATIONS from consensus do
  the league's picks track?" - sharper than raw feed weights.

  Honest identification limit: ~45 in-game picks per manager can
  identify Sleeper-vs-ESPN-scale deltas, not FP-vs-BorisChen-scale
  ones; near-duplicates get collapsed into equivalence groups rather
  than pretending the fit can tell them apart.

  ESPN, per Justin's gut: likely the most independent delta in the
  universe (idiosyncratic in-house ranks + captive drafter population).
  Kept in the fit even though its league weight probably lands near
  zero - the fit decides, not the gut, in either direction.

## The reality program (2026-08-27, Justin's two nights): what is projection
## accuracy actually, and who audits the auditors

  External auditors that exist and are partially meaningful:
  FantasyPros Accuracy Awards (scores ~140 experts' preseason draft
  rankings vs outcomes annually - real, public, but their own metric and
  champions do not repeat year over year), FantasyFootballAnalytics'
  projection studies (R-squared by source; the robust finding: composite
  beats every individual source), and the wisdom-of-crowds ADP
  literature (ADP is roughly as predictive as expert consensus).
  Consensus of that whole literature: preseason projections explain
  maybe R2 0.2-0.4 of season outcomes (QB most predictable, RB least -
  injuries dominate), no source reliably beats the aggregate, and
  year-to-year accuracy crowns are mostly luck. "Sleeper projections
  100% accurate" is therefore a fiction of convenience - useful for
  ranking POLICIES (everyone optimizes the same fiction), dangerous if
  it ever decides between near-tied PLAYERS.

  We audit it ourselves - dated data, our methodology, in-repo:

  NIGHT 1 (with the feed harvest + D4b bake-off):
  - Ingest ACTUAL season points 2021-2025 from Sleeper stats.
  - Score every source we hold against actuals, per season and
    position: Sleeper projections (stored + the dated 2021 capture),
    the league-scored bridge, and every dated feed as a predictor
    (Sleeper/Yahoo/FFC/ESPN/CBS ADP, FP ECR, Boris Chen) - our own
    accuracy audit of the ranking sites, with capture dates known.
  - Deliverable: the reality table - R2 / rank correlation / MAE by
    source, position, season; consensus-vs-individuals verdict.

  NIGHT 2 (the decision consequences):
  - Fit the ERROR DISTRIBUTION per position (variance + injury/bust
    tail mixture) from five seasons of projection residuals.
  - Decision-sensitivity: rerun keeper ledger + plan search under
    projections perturbed by the measured errors - which decisions
    flip, which are noise-proof. The exactness fiction gets replaced
    by measured error bars ONLY where decisions actually move.
  - Risk knobs get real inputs at last (A3 delivered by data).

  Timeline holds: nights of 27th-28th = reality program; 29th-30th =
  component F + precompute on whatever the reality program certifies;
  31st = final dated-ADP run; draft Sep 1 20:45.

### Feed resemblance verdict (2026-08-27): the room reads Sleeper's board

  FeedResemblance walks every real draft in pick order and scores each
  dated feed by the chosen player's rank among that feed's available
  players (mean log2; data/feed-resemblance-2026-08-27.txt). Verdicts:

  1. SLEEPER'S OWN BOARD WINS every season - sleeper-dated/stored and
     the FP-page Sleeper columns own the top cluster (2025: 3.94 vs
     fp-ecr 4.22; 2023: 3.92-3.97 vs 4.05; 2022: 3.28 vs 3.60). The
     room drafts in the Sleeper app and follows the Sleeper defaults.
     Justin's FP-rankings testimony is NOT supported at the aggregate
     level (per-manager blend fit tonight may still find individual FP
     readers). The model's incumbent market feature is thereby
     VALIDATED as the right base; consensus+deltas proceeds with
     Sleeper as the base and the rest as deltas.
  2. ffc-api tops tables at 62-84% coverage - partly a coverage
     artifact (only chalkier picks scored); treat with suspicion.
  3. Dated-vs-stored: near-tie in 2023/2025, dated clearly better in
     2022 (3.28 vs 3.44) - stored snapshots fine, dated preferred
     where held.
  4. Format-vs-date tradeoff is real but format usually wins: halfppr
     at 9d beats std at 1d in 2022; std at 4d beats ppr at 0d in 2024.
     Use nearest-in-format, fall back across formats beyond ~2 weeks.
  5. THE ROOM IS GETTING INDEPENDENT: league-wide mean log2 rose 2.5
     (2021) -> 3.2-3.4 (2022) -> ~3.9-4.2 (2023-25). Partly keeper
     mechanics (keepers remove chalk), partly real strategy drift -
     separate the two in the reach model tonight. NFL.com and Yahoo
     rank last everywhere; nobody reads them.

### Abusing Draft Rankings: real, current, and the causal artifact (2026-08-27)

  Justin's screenshot overruled my dead-lead verdict: the reddit series
  EXISTS (firstseedsports.com, weekly Google Sheet, 2026 edition posted
  six days ago), and it captures the thing our resemblance fit could
  only proxy - the platforms' DEFAULT DRAFT-ROOM RANKINGS, the literal
  on-screen order. Sleeper ADP won the resemblance race because the
  room follows the defaults; this sheet IS the defaults. Corrections on
  the record: Gemini's item 3 was real (my searches whiffed; Google
  found it instantly), and item 4 too - BeatADP is real and is the
  sheet's upstream source for Sleeper rankings. Harvested and
  committed: data/sleeper-defaults-2026-20260820.csv - 200 players,
  consensus ADP vs FP ECR vs Sleeper default rank vs the sheet's
  "Landmine Score" (a per-player early-in-YOUR-room risk rating = an
  independent snipe-risk signal to compare against our snipes()).
  Sheet updates Fridays: grab the Aug-28 refresh, 4 days before the
  draft, and check BeatADP directly for fresher dailies. Historical
  editions being recovered from archived pages (background); if the old
  sheets are live, "sleeper-defaults" joins FeedResemblance to test
  defaults-vs-ADP as the true causal feed.

### The causal test: defaults vs ADP (2026-08-27, hunt closed)

  Historical ADR sheets recovered live at their original Google ids
  (2020-2024; 2025's id was recycled into 2026 and its history is
  lost). Authenticity verified by top-3s per year; 2024's stamp is
  2024-09-02, one day after that draft. Sleeper DEFAULT rankings for
  2021-2024 extracted and entered in FeedResemblance:

  DEFAULTS == ADP, within noise, every season (2022: 3.32 vs 3.28;
  2023: 3.93 vs 3.97 - defaults nominally the best dated feed; 2024:
  4.11 vs 4.11 dead tie at 1-3 days). The recursion is now MEASURED:
  Sleeper ADP is generated by rooms following Sleeper defaults, so the
  two are interchangeable proxies of the room's sheet. The model's
  market feature needs no change; use whichever is fresher.

  What the ADR find still buys for 2026: (a) the defaults-vs-consensus
  deviation columns and the Landmine score - an independent, room-
  specific snipe-risk signal to cross-check snipes() and feed the
  sniper model; (b) freshness: Friday sheet refresh Aug 28 + BeatADP
  dailies, alongside our own dated archive, through draft eve.

### The fog, measured (2026-08-27): the accuracy shootout

  Every source scored as a predictor of actual half-PPR outcomes,
  top-150, five seasons (data/accuracy-shootout-2026-08-27.txt):

  1. SLEEPER'S PROJECTIONS WIN EVERY SEASON: spearman .60-.70 vs the
     market feeds' .34-.50. Against the wisdom-of-crowds literature,
     the projection feed this project optimizes on beats every ADP and
     ranking sheet as an outcome predictor, all five years. The input
     was the right one.
  2. THE EDGE, QUANTIFIED AT THE INFORMATION LAYER: the room drafts on
     its sheet (a ~.40-.48 predictor); Justin drafts on projections
     (~.60). The project's advantage is that correlation gap, now a
     measured number instead of a thesis.
  3. THE FOG IS LARGE: even the best source hits only 14-16 of its
     top-24; a third of everyone's "first two rounds" busts annually.
     Projections-exact is a strong fiction - Night 2 fits the residual
     distribution and finds which decisions survive it.
  4. POSITION FOG IS NOT STABLE: QB predictability collapsed in 2025
     (.45 proj, ~.1 market, vs .74 in 2021); TE 2025 went NEGATIVE for
     everyone; RB 2025 was unusually clean. Fixed position-risk
     constants are wrong; year-varying humility is right.
  5. Market feeds cluster tightly (.34-.50) - one consensus, mediocre
     at outcomes, excellent at behavior (the resemblance race). Two
     jobs, two winners; the architecture's split (points feed vs
     market feature) was correct all along.

### Justin's display-discrepancy hypothesis (2026-08-27, mid-shootout)

  The Sleeper room shows two things side by side: the default rank
  order (4-pt national consensus) and each player's PROJECTED POINTS
  UNDER LEAGUE SCORING - 6-pt passing TDs, so QBs' displayed points
  look rich relative to their rank. Justin's hypothesis: part of the
  league's QB deviation from ADP is a response to that visible
  discrepancy. The old information-set test rejected league-scored
  values as the SOLE market input (calibration degraded 1.19->1.59) -
  the room mostly follows ranks, which IS the exploited edge - but a
  MIXTURE was never tested: rank-following plus a small, possibly
  per-manager response to the displayed-points-vs-rank gap,
  concentrated at QB. Tonight's stray taxonomy gains feature #6:
  (league-scored points rank minus market rank) x position, per
  manager. If some managers read the points column, the model finds
  them; if the coefficient is ~0, the edge is even safer than assumed.

### Docket audit (2026-08-27): what was missing

  A. THE RE-DERIVATION CASCADE (the big one): after D4b + sniper + strays
     produce an upgraded brain, it must be RE-GATED (held-out 2025, both
     average and tail metrics) and every downstream number re-derived
     under it - plan, adaptive premium, snipe odds, worlds stability.
     Implied everywhere, scheduled nowhere. Now: Night 2, after the fits.
  B. ROUNDS 10-16 (the forgotten half of draft night): the game ends at
     r9 but the draft does not. Bench picks in a keeper league carry
     real option value - Tuten IS a former late-round stash. Build the
     late-round recommender: 2027 keeper-cost-aware stash values (cheap
     rounds = cheap 2027 keeps), upside/spread/age fliers, DEF+K timing
     per league norms. Night 2/3.
  C. E1 scale study (does +12.4 grow at the 15-30s budget) - was "first
     thing," got displaced by the data campaign. Explicitly Night 2; it
     decides the engine config the precompute bakes in.
  D. D1 gap certificate - approved, still unbuilt; 20 lines on the hop
     machinery. Tonight.
  E. DRAFT-NIGHT RUNBOOK + PAPER FALLBACK: RUNBOOK.md (launch commands,
     fallback ladder, what to do on an API hiccup, timezone check) plus
     a printable one-pager (plan, per-round targets, snipe list,
     Landmine flags) in case the laptop dies. Precompute night.
  F. THE 31ST SANITY SUITE: smokeTest (feed rot), KeeperAudit (board
     changes), draft-order confirmation, fresh projections morning-of
     if news breaks. Runbook items.
  G. STILL OWED BY JUSTIN: in-draft pick trades allowed? likely
     autodrafters? keeper board locked or can managers still switch?

### Afternoon results (2026-08-27): reaches fingerprinted, the gap certified

  REACH AUDIT (vs the defaults sheet the room sees): league median
  reach 10 (2022) -> 16 -> 18 -> 18 (2025); share>=10 rose 51% -> ~80%.
  The room-gets-looser trend is confirmed on the causal sheet. CAVEAT
  for tonight's sniper fit: this metric conflates positional need with
  true deviation (taking the best RB past ten higher-ranked WRs counts
  as reach 10) - fit epsilon on WITHIN-POSITION reach, and let the
  choice model keep owning position selection. Fingerprints are
  surprisingly uniform (medians 13-18); BHier loosest (>=25: 30%),
  and justinb314's 35% share of 25+ reaches is the league's largest -
  the model's own past recommendations register as reaches against the
  room's sheet, as they should.

  GAP CERTIFICATES (unpenalized information-relaxation): declared game
  22.1 +/- 1.7; Flowers 19.5 +/- 1.8. Read correctly: this bounds
  clairvoyance, not collectible skill - the bound pays for KNOWING the
  future, and three prior measurements say most of it is uncollectible
  by any causal policy (flat compute-scaling curve; HOP scoring level
  with lookahead, not 10 above; adaptive tier clustered within ~2).
  Actionable headroom is a small slice of 22; tightening the bound
  needs the penalized version (learned-value penalty), worth building
  only if a decision ever hinges on it. Note: 400 futures solved
  exactly in 1-3 seconds - the scenario walker is draft-night fast.

### Sniper mixture: REJECTED by its own gates (2026-08-27 evening)

  The scale grid chose 0.0 on the train side - every nonzero mixture
  moved the simulated reach shape AWAY from reality (0.31 -> 0.43 ->
  0.49 -> 0.56), and the 2025 confirmation agreed. The boosted brain's
  fitted softmax already reproduces the room's within-position reach
  distribution; the calibration gates had implicitly priced the chaos.
  Justin's reach phenomenon is real (40% of picks take the 5th+ best
  at position) and ALREADY MODELED. No re-derivation cascade needed.
  Fingerprints kept for draft-night color: BHier .47 / KevinDA .46
  loosest, Renteez .33 / jerem .32 most scripted. A finer reach-mode
  (within-position, need-preserving) could be tried post-season; the
  discipline says the brain stands.

### Stray diagnostics: five nulls and a homer list (2026-08-27 evening)

  Within-position reach as response, need controlled: rookies +0.2
  (+/-0.4) nothing; young +0.6 (+/-0.3) borderline-tiny (and the brain
  already ingests the young column); faded names +0.5 (+/-0.4) noise -
  mild brand-loyalty direction, opposite of old value-fall, unproven;
  6-PT DISPLAY (#6): -0.5 (+/-0.5) at n=65 - NO detectable response to
  the richer QB points column. Justin's edge is certified safer: the
  room ignores the display, exactly as the info-set test implied. The
  QB-cool remains the league's single big systematic stray, already
  shipped. M2 closes with the model UNCHANGED - consistent with the
  sniper rejection: the brain was already adequate. Homer pairs kept
  as runbook color, not features (416 pairs tested, expect false
  positives): BHier x MIN 7 picks vs 1.8 expected is the standout;
  tommyrads GB/HOU/JAX, Hamrliks SF/SEA, itsabust DET. M3 (regime-
  weighted earliness) deferred into Night 2's re-gate cascade where a
  brain change belongs.

### Per-manager feed table (2026-08-27): ties, as honesty predicted

  At ~45 picks per manager, the consensus feeds are indistinguishable
  for most rows (differences under 0.1 = ties). No manager demonstrably
  reads FP-ECR over Sleeper - Justin's testimony finds no per-manager
  support either. Mild ESPN leans for Renteez (3.54 vs 3.70) and
  jerem (borderline); JFMarino and patekxwater are clearly Sleeper-
  sheet drafters. The one big separation is justinb314 (espn 3.21 vs
  sleeper 3.82) and it is an artifact worth smiling at: my model-driven
  picks happen to align with ESPN's idiosyncratic ordering - the table
  correctly detects that I am the one manager not reading the room's
  sheet. Market feature stays global Sleeper. NIGHT 1 CLOSES: the
  brain survived every challenge (sniper rejected, strays null, feed
  validated twice, fog measured, gap certified); tomorrow is
  consequences - error distribution, decision-sensitivity, regime
  gate + re-derivation, scale study, committee, late rounds, runbook.

### Draft Sharks injury data, harvested (2026-08-27 night)

  Justin remembered an injury-risk column; it is Draft Sharks' Injury
  Predictor, and the per-player data ships in the clear inside their
  page JSON: injury_prob, proj_games_missed, durability for 329
  QB/RB/WR/TE (update stamp 2026-07-07 - July vintage; re-harvest
  closer to the draft). Committed: data/draftsharks-injury-2026-0707
  .csv. Sanity: McCaffrey 78%, Goedert 83%, Bowers 81%, Mariota 88%.

  Integration, respecting the game spec: the spec says "no injury
  differential," so this does NOT enter the main projections without
  an explicit spec change. Two uses that respect the boundary:
  (1) DecisionSensitivity v2 - player-specific fog (injury_prob and
  proj games missed replacing tier-average bust rates where available)
  so the stress test uses the best per-player reality model we hold;
  (2) the draft-night one-pager - an injury flag as the tiebreak
  between near-tied players, surfaced to the human, who decides.

### Draft Sharks historical: dead, with a consolation prize (2026-08-27)

  Historical DS ADP and historical injury PREDICTIONS are unavailable:
  Wayback excludes draftsharks.com entirely (zero snapshots by CDX
  wildcard, www variant and availability API - a subscription-site
  exclusion), their own ?year= params are ignored (all 415 profiles
  identical to today's), and archive.today rate-limited. Consequence
  for honesty: DS injury_prob enters the stack as UNVALIDATED external
  opinion - we cannot audit their model the way we audited Sleeper's
  projections, so it informs the one-pager and the sensitivity draws
  but never silently reweights a projection.

  The consolation is better than the loss: their pages embed real
  injury EVENT history - 1,422 named NFL events across the 329
  profiled players, 2019-2026, with dates, games missed, body part and
  condition (data/injury-history-2019-2026.csv). That is ground truth,
  joinable to the actuals already ingested, so the fog can be
  decomposed: how much of the measured bust mass (RB tier-1 5%, deep
  TE 54%) is injury versus role loss versus projection error - and
  whether prior-injury history predicts next-season busts at all. That
  is a post-season model upgrade with a real gate, not a draft-week
  scramble.

### The scale study answers the clock question (2026-08-27)

  Real-game adaptive premium vs inner rollouts: inner 16 = +12.4,
  inner 64 = +11.4 (paired, +/-2). FLAT - four times the compute buys
  nothing. Justin's 15-30s budget is therefore luxurious: the engine
  needs well under a second per pick to collect the full premium, and
  the surplus clock goes to the committee (several engines voting, so
  disagreement flags contested picks) and to pondering between picks,
  not to deeper single-engine search. Draft-night config settled.

### Clairvoyance, caught twice in one night (2026-08-27)

  DecisionSensitivity v1 reported 186 points of regret; v2 kept it
  after removing the argmax. The cause was not noise - it was the same
  clairvoyance this project spent two days eliminating from the
  planner, reintroduced in a new place: the fog study let the POLICY
  pick players under the sampled truth, so it dodged exactly the busts
  it could not have known about. "QB@none loses 90% of truths" really
  meant "if you knew Purdy would bust, you would draft a QB" -
  unactionable. v3 splits the two roles: decisions see PROJECTIONS,
  scoring sees TRUTH. The lesson generalizes - any study that samples
  an outcome and then optimizes must keep the optimizer blind to the
  sample. Worth remembering for the 2027 keeper analysis.

### Fog robustness (v4, 2026-08-27): QB insurance is real, and the shipped
### plan already had it

  Six pre-registered timings, decisions on projections only, scored
  under 40 sampled truths (paired), mean value:

     QB@r8 TE@r9   1553.3   +45.3      <- best under fog
     QB@r8 TE@r7   1547.1   +39.1      <- THE SHIPPED PLAN
     QB@r6 TE@r7   1536.8   +28.8
     QB@none TE@r8 1520.2   +12.1
     QB@r2 TE@r7   1511.5    +3.5
     QB@none TE@r7 1508.1     0.0      <- the fog-free optimizer's pick

  THE FINDING: with projections treated as exact, a second QB adds ~0
  and the timing search prefers QB@none. Under MEASURED fog it is worth
  +39, because Purdy is not a certain 363 - he carries the measured
  12% tier-1 QB bust rate, and a late QB is a cheap option on that
  collapse (best-nine takes the max, so the backup only ever helps).
  The exactness fiction was hiding an insurance premium, and the
  SHIPPED PLAN ALREADY BUYS IT: shipped is RB,WR,RB,WR,WR,WR,TE,QB,RB
  - QB at round 8, exactly the winning family. The plan Justin locked
  is fog-robust; the fog-free optimizer's QB@none was the artifact.
  TE r7 -> r9 is the only suggested tweak (+6, needs SEs before it
  means anything).

  HONEST CAVEAT: the nine-round game has no waivers. In a real season
  a busted QB gets streamed, so drafted insurance is worth somewhat
  less than +39 - the direction is right, the magnitude is an upper
  bound. It does not change the ranking: keep the round-8 QB.

  CLARIFICATION (Justin, same evening): the two games differ in what
  rounds 8-9 mean, and it matters for reading the fog table. In the
  TOURNAMENT (lab) game the keepers are PINNED onto rounds 8-9 by
  construction, so "QB at 8" there is Purdy's slot and only 7 picks
  are live. In the FULL-RULES game - which is what the fog study ran -
  Tuten (r12) and Purdy (r13) cost rounds outside the nine-round game
  and consume NO slot: nine live picks plus two free keepers, eleven
  players, best nine. Verified by printing a rollout roster: round 8
  drafts a real second QB (Trevor Lawrence in that draw), not Purdy.
  So the +39 insurance premium is the value of a genuine backup
  against Purdy's measured 12% bust rate, and the shipped plan's
  round-8 QB is a live pick that already buys it.

### The insurance test (2026-08-27): Justin's WR hypothesis, measured

  Justin's challenge: rounds 8-9 are pure insurance, and a WR should
  be promoted more often than a backup QB (three WR slots plus flexes
  = many more chances someone busts). Frequency versus severity, so:
  hold rounds 1-7 at the starting-nine fill, vary only the two
  insurance rounds, 40 fog draws paired.

     QB+RB  1543.6   +0.0   <- shipped tail, best under fog
     QB+WR  1529.2  -14.4
     TE+WR  1528.3  -15.4
     RB+RB  1527.0  -16.6
     WR+RB  1514.6  -29.0
     QB+QB  1512.1  -31.5
     WR+WR  1500.4  -43.2   <- worst

  Frequency loses to structure. After 7 picks + 2 keepers the roster
  is 1 QB / 3 RB / 4 WR / 1 TE and ALL NINE START (the two flexes
  absorb the spare RB and WR). A WR bust is indeed likelier, but the
  flex structure already covers it - the 4th WR slides up or an RB
  takes the flex. QB is the ONLY slot with no redundancy: nobody else
  on the roster can fill it, so Purdy's collapse is a ~250-point hole
  a backup fills completely. QB+QB (-31.5) confirms the shape: one
  backup covers the 12%, a second needs BOTH to bust. No-fog column
  spans one point across all seven tails - the exactness fiction could
  never have decided this.

  CAVEAT, sharpest here: no waiver wire in the model, and QB is the
  most streamable position in reality. The ranking is sound; the
  magnitude of QB insurance specifically is the most overstated.

## THE TWO-MODEL SPLIT (Justin, 2026-08-27) - architecture decision

  Mixing risk into the optimizer muddied three answers tonight. From
  here they are separate models with separate jobs:

  MODEL A - THE OPTIMIZER (primary; decides rounds 1-7)
    Objective: maximize the STARTING NINE, no defense.
    Roster: 9 spots. Keepers fill 2 of them, pinned at rounds <=8 and
    9 (mock-room convention); 7 live picks fill the rest.
    Assumptions: projections EXACT. No injuries. No replacement level.
    No waivers. This is the original nine-round spec, unchanged.
    Implementation: PolicyTournament (already built and validated -
    742 sequences, 8 search families, ~28 policies, both keeper
    worlds). Its answer IS the pick plan for rounds 1-7.

  MODEL B - THE RISK MODEL (secondary; advises rounds 8+)
    Keepers in their TRUE spots (Tuten r12, Purdy r13), so rounds 1-9
    are all live picks and the roster is 11 deep.
    Adds: measured fog (FogFit), per-player injury risk (Draft Sharks),
    waiver replacement level. Answers what the optimizer cannot see -
    what the picks BEYOND the starting nine are worth as insurance.
    Implementation: FogFit + DecisionSensitivity + InsuranceTest.

  DIVISION OF LABOUR, and it matches how the draft actually goes:
  picks 1-7 build the starting nine (Model A); picks 8-9 (and 10-16)
  buy depth and insurance against the season (Model B). Neither model
  overrides the other because they answer different questions.

  CONSEQUENCE for tonight's findings: the QB-insurance result (+39 no
  wire, smaller with one) is a MODEL B statement about round 8 - it
  never contradicted Model A's round 1-7 plan. And the replacement
  level in Model B must come from the FULL 16-round draft, not the
  nine-round window; the nine-round version put the wire at QB8, which
  is why its insurance numbers looked too generous. Fix pending.

### Where the two models stand (2026-08-27, end of night 2)

  MODEL A (rounds 1-7, exact projections, keepers pinned 8-9):
    committed plan  RB, RB, RB, WR, WR, WR, TE  - agreed by SEVEN
      independent searches (exhaustive/742, staged, DP, SA, NRPA,
      CEM, evolution)
    live policy     oldschool-2-vorp, +7.2 +/- 1.1 over committing
    instant fallback ml-imitation, +7.5 +/- 0.5 (tightest error bars)
    NOTE: differs from the older "shipped" plan RWRWWWT, which came
    from the nine-live-pick framing. Model A goes RB-heavy (4 RB
    feeding the flexes) rather than WR-heavy. Needs one confirmation
    run under the settled spec before Tuesday - it is the plan.

  MODEL B (rounds 8+, fog/injury/replacement, keepers at r12/r13):
    round 8 insurance ranking, no wire: QB+RB best, WR+WR worst
      (-43) - QB is the only slot with no flex redundancy
    with a wire the spread narrows; the wire level must be recomputed
      from the FULL 16-round draft (pending - the nine-round version
      put replacement at QB8, far too rich)
    per-player injury risk (Draft Sharks, 329 players) plugs in here

  REMAINING BEFORE TUESDAY:
    1. Model A confirmation run at high rollouts -> the final plan
    2. Model B wire fix -> the round 8-9 recommendation
    3. Component F wiring + mock rehearsal (-PdraftId)
    4. Precompute (opening book for pick 7, snipe branches)
    5. RUNBOOK.md + printable one-pager
    6. Monday: final dated-ADP run; smokeTest; KeeperAudit

### Insurance, settled with a real wire (2026-08-27) + COMPONENT F BUILT

  Replacement measured from five FULL historical drafts: QB21, RB61,
  WR81, TE19 (the nine-round version had said QB8 - absurdly rich).
  With that wire the round 8-9 choice is nearly a free one:

     QB+RB   1571.3   +0.0     TE+WR  1570.4   -0.9
     RB+RB   1568.6   -2.7     QB+WR  1557.6  -13.7
     WR+RB   1557.0  -14.3     WR+WR  1542.2  -29.1
     QB+QB   1540.1  -31.1

  Justin was right to push back: the +43 QB premium was an artifact of
  a model with no streaming. Top three within 3 points = take whoever
  is best available. The only surviving advice is negative: do NOT
  double WR (already four on the roster) and do NOT take two QBs.

  COMPONENT F (LiveDraft) is built and verified against the live board.
  Reads Sleeper, replays picks into a SimState (stateAfter/slotOf),
  recommends by depth-2 lookahead with VORP tails - the engine that
  won both lab worlds - and prints every alternative with margins so
  near-ties reach the human. Timings on this machine: 36s engine warm,
  13.4s per decision at 150 rollouts (inside the 15s preference; 300
  rollouts takes 21s, inside the 30s cap). Bug found and fixed on the
  first live read: keeper entries were consuming live schedule slots.

  NEXT (F polish): a persistent loop that warms ONCE and then polls,
  so the 36s fit is paid before the draft rather than at every pick.

### The certificate, decomposed (2026-08-27): ScenarioTree

  Class 1 and Class 2 turned out to be one build: a conditional-
  expectation penalty needs branching scenarios, and branching
  scenarios ARE the tree. Solved by backward induction with
  non-anticipativity (scenarios sharing an observation share an
  action), 120 futures, 7 picks:

    buckets  distinct obs/pick   committed  ADAPTIVE  clairvoyant
      2      1,1,1,1,2,2,3         1827.6    1827.6      1841.1
      6      5,7,3,3,4,3,3         1827.6    1834.6      1841.1
     12      10,10,4,7,11,10,4     1827.6    1836.5      1841.1

  The adaptive row is the exact optimum of the tree - the ceiling for
  ANY non-anticipative policy on that information structure. It rises
  with observation granularity, as it must: coarse buckets hide board
  detail a real drafter sees. HONEST LIMIT: at fine granularity with
  only 120 scenarios each group holds ~12 futures, so part of the rise
  is in-sample overfitting rather than real information. The true
  adaptive optimum is bracketed at roughly +7 to +9 over committed,
  with clairvoyance taking the remaining ~5-7 of the 22.1 gap.

  MEASURED AGAINST OUR ENGINES: oldschool-2-vorp scores +7.2 over
  committed in the tournament. That sits AT the tree's adaptive
  estimate. So the honest verdict on five days of "can anything beat
  this?": the live engine is at or very near the achievable ceiling,
  and the residual is dominated by knowing-the-future value that no
  algorithm can collect. Remaining headroom is 1-2 points at most.
  A knob bug found here: `buckets` was never forwarded in build.gradle,
  so the first sweep silently ran three identical configurations.

### CORRECTION (2026-08-28): the tree is a floor, not a ceiling

  The 400-scenario run exposes a labelling error in the entry above.
  ScenarioTree restricts the policy to a four-number bucketed
  observation; the live engines see the whole board. A better-informed
  policy can beat a worse-informed one, so the tree's "ADAPTIVE" row is
  a LOWER bound on what is achievable - not the ceiling it was called.

  The tell was in the numbers: tree adaptive = +5.6 over committed at
  400 scenarios, while oldschool-2-vorp measures +7.2. A policy cannot
  exceed a genuine ceiling. What it exceeded was a handicapped
  observer's optimum.

  HONEST STATE OF THE BOUND:
    valid upper   clairvoyant, ~+12.5 over committed (loose - contains
                  future knowledge nobody can collect)
    valid lower   our measured engine, +7.2
    true optimum  unestablished, somewhere between
  Tightening the tree toward full-board observation does not fix this:
  it trades the information handicap for in-sample overfitting (the
  120-scenario run read +6.9, the 400-scenario run +5.6 - the earlier
  number was partly fitting noise).

  CONSEQUENCE: the algorithm hunt is NOT formally closed, and the
  earlier "1-2 points left at most" was overconfident. Up to ~5 points
  separate our engine from the only valid upper bound.

  Justin's question - "how do we know the ceiling?" - was the right
  one, and the answer is that we do not. What we have is a floor we
  are standing on and a loose roof. Also worth recording: every
  scenario-based bound here samples futures INDEPENDENTLY of my
  actions, so none of them price the blocking channel (my pick changes
  what opponents take, changing what returns to me). That channel is
  outside every bound computed this week.

## THE FIVE NIGHTS (2026-08-27 23:54 -> draft Tue 2026-09-01 20:45)

  Ordered by expected points on Tuesday, not by interest.

  N1 tonight   four new engines (depth3, two-stage recourse, regret-
               match, mean-minus-downside) + tree at 400 + THE BLOCKING
               MEASUREMENT: does my pick shift next-round availability?
               That test decides whether the un-ceilinged strategic
               class is real or an illusion.
  N2 Aug 28    ROUNDS 10-16 - the largest unbuilt thing and not
               algorithmic. Seven more picks with zero coverage today;
               Tuten was a round-12 stash and became the best keeper in
               the league. Needs 2027 keeper-cost-aware stash values,
               upside ranking (spread/age/rookie), DEF+K timing.
  N3 Aug 29    branch on N1: blocking real -> build the strategic
               engine (models opponent RESPONSE; no bound constrains
               it). Blocking null -> penalized certificate done
               properly, closing the bound question.
  N4 Aug 30    the re-derivation cascade promised and skipped: M3
               recency-weighted earliness fitted and gated, and if it
               ships, re-derive plan/premium/snipes/robustness under
               the new brain. Plus WorldsRace under Model A (the
               existing robustness check used the nine-pick framing).
  N5 Aug 31    LOCKDOWN. Fresh ADP, smokeTest, KeeperAudit, final plan
               run, full rehearsal, printable one-pager. Nothing
               experimental.

  Daytime, minutes not hours: persistent warm loop for F (pay the 17s
  fit once, not per pick); fall-through to insurance ranking when
  Model A's margins go flat in the late rounds.

  HONEST EV NOTE: nights 1 and 3 chase 1-2 points out of ~1830.
  Rounds 10-16 and execution reliability are worth more in
  expectation, which is why they hold the protected slots.

### The blocking channel is negligible (2026-08-28) - N1 result

  Took Cook vs Barkley at pick 7 (same position, roster effect held),
  same random stream, measured best-available at pick 18 over 600
  trials. Largest positional shift: 0.35 points. QB +0.35, RB +0.14,
  WR +0.06, TE 0.00.

  WHY: eleven picks separate my turns, made by twelve managers reading
  a shared sheet, so one player's removal is absorbed by board depth
  long before it returns to me. Denial does not propagate in a league
  this size.

  TWO CONSEQUENCES:
  1. Every bound computed this week is VALID. The action-independence
     assumption I flagged as "false in reality" holds empirically here
     to within a third of a point - the certificate, the tree,
     hindsight and two-stage are all uncompromised.
  2. The strategic/blocking class - the one family no bound
     constrained - has nothing in it to win. N3 switches from building
     a strategic engine to the penalized certificate.

  A cheap test that saved a night of building the wrong thing.

### N1 results (2026-08-28): the algorithm hunt closes empirically

  Four new engines against the incumbent, 400 trials, paired:

                            standard   rich(100 scen, inner 32)
    two-stage-recourse         +2.7      +2.9   <- nominally best
    blend mean-0.35xdownside   +2.6      +2.7
    oldschool-2-vorp           +2.3      +2.7   <- incumbent
    depth3-vorp                +2.3      +2.6
    regret-match               +0.6      -0.2
    greedy-vorp (floor)         0.0       0.0     +/-1.2 SE

  DEPTH IS EXHAUSTED: depth-3 exactly matches depth-2, so more plies
  buy nothing - now measured, not argued. Two-stage recourse leads by
  0.2-0.3 in both runs (inside noise, but consistently on top, and it
  is the local form of what computes the ceiling - if anything ships
  it is this). REGRET-MATCH IS A REAL LOSER: minimising worst-case
  regret costs mean points, so the downside protection is not free.
  Seven independent families now converge within ~0.5 points. The
  hunt is closed empirically, if not provably.

### N2 first result: the late-round rule is QB, and it is not close

  255 late picks (rounds 10-16) over four seasons, joined to the
  FOLLOWING season's actuals. "Hit" = scored at or above a starter's
  line the next year (QB12/RB24/WR36/TE12):

    QB   n=39   41% hit   mean 219.9   best 383.0
    RB   n=77   16% hit   mean  78.1
    WR   n=107  15% hit   mean  69.8
    TE   n=32   19% hit   mean  70.7
    young(<=2yr) 24% vs veterans 15%; round band irrelevant (19/20%)

  NINE OF THE TEN BEST LATE STASHES THIS LEAGUE EVER MADE ARE QBs:
  Hurts r14 2021 -> 383, Burrow r13 -> 366, Stafford r13 -> 358,
  Prescott r10 -> 352, Lawrence r12 -> 350, Goff r15 -> 336, Caleb
  Williams r11 -> 324, Murray r15 -> 308, Goff again r12 -> 305.

  This is QB-coolness surfacing in a THIRD place (after the drone gap
  and the survival table): because the room will not draft quarter-
  backs, startable ones fall to rounds 10-15 every single year, and
  stashing one is the highest-hit-rate late move available.

  CAVEAT: the hit rate measures "became startable", not "was worth
  keeping to me". Justin already has Purdy, so a stashed QB competes
  with him - the value is the 2027 KEEPER option (kept at his draft
  round), which is exactly the Tuten trade. That option is worth most
  for a young QB taken late, which is precisely the Hurts/Burrow/
  Williams pattern.

### N2 complete (2026-08-28)

  TREE CONVERGED: adaptive floor +5.6 (400 scenarios) -> +6.0 (1000),
  clairvoyance +5.5. Stable, so the floor is real; our engine's +7.2
  sits above it because the engine sees the whole board while the tree
  observes four bucketed numbers.

  COMMITTEE SPLITS DO NOT REPRODUCE: seed 7 split at rounds 3 and 7,
  seed 11 at 2 and 4, seed 23 at 2 and 7. So Tuesday should bring
  roughly TWO genuinely contested picks, but which rounds they fall in
  depends on the board - they are real uncertainty, not a fixed weak
  spot that could be pre-solved.

  N3 REPRIORITISED: the penalised certificate would tighten a bound
  that no longer changes a decision (the engine hunt closed
  empirically at N1). N3 becomes the actionable half of the late-round
  work - ranking THIS year's rounds 10-16 candidates by the measured
  base rates plus 2027 keeper option value - and F's polish (warm
  loop, insurance fall-through).

### N3 (early): the 2026 late-round target list

  data/late-round-targets-2026-08-28.txt. The top SEVENTEEN are all
  quarterbacks; the first skill player is 18th with a score six times
  lower. Nix (348 proj, 83% survives), Dart (341, 65%), Shough (315,
  100%) lead among young QBs; Mahomes (345, 69%), Stafford (344, 65%)
  and Goff (343, 98%) are startable-quality at a stash price.

  FORMULA IS CRUDE, honestly: projection x measured position hit rate
  x youth bonus proxies "expected startable-ness next year", not true
  keeper surplus. It ignores that only TWO players can be kept (so a
  stash competes with Tuten), uses no 2027 projections, and QB's raw
  numbers are inflated by the 6-pt passing TDs relative to a QB12 bar.
  Ordering WITHIN quarterbacks is soft. The direction is not: three
  independent measurements now agree (drone gap, survival table, and
  nine of the ten best stashes in league history).

  TUESDAY RULE, rounds 10-16: take young quarterbacks who fell.

### Justin's league intel, priced (2026-08-28)

  1. IN-DRAFT TRADES: never happened; ignored. No modelling change.
  2. KEEPER BOARD: re-audited fresh (cache cleared) - 24 matching the
     rules, 0 disagreeing. Unchanged. Re-verify Monday.
  3. AUTODRAFT: JFMarino is SLOT 8, adjacent to Justin's slot 7, and
     Justin puts him at ~50/50 to autodraft the first five rounds.
     Serpentine order makes that decisive: at slot 8 he picks 17th -
     the last pick before Justin's 18th.

     Human vs drone JFMarino, survival to MY pick 18, 600 trials:
       Josh Allen    415 proj   90% -> 50%   <- the headline
       Ashton Jeanty 211        20% ->  1%
       Pickens       206        27% -> 39%   (MORE available)
       London        205        30% -> 38%
       Rice          185        71% -> 80%

     WHY: a human JFMarino is one of the league's latest QB drafters
     and walks past Allen; a drone takes best-ADP, and Allen sits near
     the top of NATIONAL adp because the rest of the world does not
     share this league's QB aversion. The drone eats him. It also eats
     Jeanty, while spending nothing on receivers - so the WR board
     gets BETTER at pick 18 in the drone world.

     DRAFT-NIGHT RULE: watch JFMarino's first two or three picks. If
     they match ADP exactly he is autodrafting -> Allen will not reach
     18, take him at 7 if wanted, and expect a richer WR board at 18.
     If his picks deviate from ADP he is live and the normal survival
     table applies. This is a live-detectable, decision-changing
     signal - the first one the project has found.

### Justin's three answers, resolved (2026-08-28)

  IN-DRAFT TRADES: technically possible, never happened - ignored, and
  correctly so; modelling them would add a branch nobody uses.

  KEEPER BOARD: VERIFIED unchanged. 24 keepers, all matching the
  rules, Tuten r12 / Purdy r13 intact, BHier still Daniels r7 + Pitts
  r13. No repeat of last week's Kevin switch. Re-verify Monday.

  JFMARINO AUTODRAFT (~50/50 for rounds 1-5) - the big one, because of
  WHERE he sits. Justin slot 7, JFMarino slot 8, and the snake puts
  him at pick 17: immediately before Justin's pick 18.

    human world      his pick 17 scattered (Pickens 11%, London 9%,
                     Nabers 9%); JOSH ALLEN 90% ALIVE at my 18
    autodraft world  his pick 17 is ALLEN 45% of the time; Allen
                     drops to 49% alive at my 18

  The mechanism is the league's own edge running backwards: an
  autodrafter takes the ADP-best player and does NOT share the room's
  QB-coolness. Allen sits there precisely because humans here will not
  take him; a robot has no such hangup. So the one manager sitting
  immediately before Justin's second pick is the most likely person to
  break the QB shelf.

  Damage is contained: shipped plan 1812.4 human vs 1810.4 autodraft,
  about two points - the plan does not depend on Allen.

  DRAFT NIGHT: check whether JFMarino is present before pick 7 (his
  pick 8 lands right after Justin's - an instant ADP-perfect pick is
  the tell). If he is autodrafting, discount Allen-at-18 from ~90% to
  ~50%. The QBs behind Allen are unaffected (Jackson 99%, Burrow 100%,
  Prescott 100%) - an ADP robot does not reach that deep in two rounds.
  The live committee handles this automatically once his pick 8 is on
  the board; this is the WHY behind the shift.

### KN wired into the live committee (2026-08-28), with two corrections

  Kim-Nelson ranking and selection now arbitrates every live pick.
  Per-decision cost measured across a full draft: 0.00-1.2s, spending
  45-64 rollouts on the contested early picks and 0-8 on settled late
  ones - the adaptive budget allocation equal-allocation cannot do.

  CORRECTION 1 - delta matters enormously. At delta=1 KN reported
  "tie" at pick 1 while all four engines had RB ahead of WR by 24
  points. That is not a tie: draft outcomes are high-variance, so
  proving separation inside a 1-point indifference zone needs more
  evidence than 64 rollouts can supply. At delta=3 it PROVES RB in 50
  rollouts. Shipped setting: delta=3, alpha=.05.

  CORRECTION 2 - KN never overrides the engines. A KN "tie" means it
  could not PROVE separation within budget, NOT that the candidates
  are equal; the first draft of the wiring printed "this pick does not
  matter much - take the scarcer position", which would have given
  actively bad advice on a 24-point edge. Absence of proof is not
  proof of absence. KN is now advisory: it reports its verdict beside
  the vote and never replaces it.

  JUSTIN'S QUESTION - why does KN say PROVEN when committed models
  underperform? Because KN is NOT committed. It runs fresh at every
  pick from the live board (compute-at-every-round family); "PROVEN"
  is a local claim about THIS board, not a sequence. The real caveat
  is different: KN proves the ranking of ITS OWN depth-1 VORP-tail
  estimates, so a biased tail would make it confidently wrong - which
  is exactly why it advises alongside four independent engines rather
  than replacing them.

  Also settled tonight: dFBA-as-draft-policy scores 1820.1 against
  greedy-vorp's 1820.3 - indistinguishable. The LP relaxation of a
  draft IS marginal-value greedy; stoichiometry is a costume. A joke
  with a real structural punchline.

### KN with a real budget (2026-08-28): 1-point resolution, under 3s

  Justin asked what 10 seconds of rollouts would buy. Measured across
  a full draft, budget 600:

    delta    pick 7      pick 18       pick 31      worst pick
    0.5    73 PROVEN   518 PROVEN    431 PROVEN    8.0s, one TIE
    1.0    45 PROVEN   179 PROVEN    145 PROVEN    2.8s, all proven
    2.0    25 PROVEN    76 PROVEN     72 PROVEN    1.2s, all proven

  At delta=1 EVERY pick resolves, including 18 and 31 - the two the
  rehearsal independently flagged as contested and which budget 64
  could not settle. They need ~180 and ~145 rollouts, 2.8s and 1.7s.
  SHIPPED SETTING CHANGED: delta=1, budget=600 (was delta=3, budget
  64).

  delta=0.5 is where it breaks: pick 18 needs 518 rollouts and 8s, and
  pick 55 exhausts 600 without resolving. That is the procedure being
  honest - at half-point resolution the candidates are inside the
  noise, and no affordable sampling separates them. So the ceiling of
  this machinery is ~1 point of resolution per pick in under 3s, and
  below that the differences stop being meaningful rather than merely
  unproven.

### N4: M3 recency weighting REJECTED (2026-08-28)

  Half-life chosen on 2024 (brain through 2023): pooled 0.75%, every
  weighted variant 0.94%. Confirmation on held-out 2025: pooled 0.57%,
  recency-weighted 0.57% - a tie. Pooled earliness stands, so NO
  re-derivation cascade is triggered.

  Third consecutive "the brain is already adequate" verdict, after the
  sniper mixture (scale 0 chosen) and the five null strays. The
  appetite audit's era finding is real, but the boosted brain's fitted
  coefficient on earliness already absorbs it - reweighting the input
  does not improve survival calibration. Worth knowing that the model
  is harder to improve than the diagnostics suggested.

### N4 complete: the plan showdown settles round 2 (2026-08-28)

    RB-heavy RRRWWWT (Model A)   3000 trials  1813.1  +1.8 vs shipped
    WR-heavy RWRWWWT (shipped)   3000 trials  1811.3   0.0
    live engine (lookahead-2)     600 trials  1821.4  +9.4

  RB-heavy wins by +1.8, small but outside the noise on paired trials,
  confirming what seven independent searches said at 150 rollouts. The
  fallback sequence now opens RB at pick 18. The live engine beats both
  committed plans by ~9 - the adaptive premium, one more time - so the
  sequence is genuinely a fallback and the runbook says so.

  PROCESS NOTE: I twice reported a background job as running when it had
  finished, because `pgrep -f <ClassName>` matches the grep command's own
  line. The same bug deadlocked the N1->N2 pipeline this morning. Match
  on the JVM process, not the command string.

  NIGHT 4 IS COMPLETE: M3 gated and rejected, plan showdown decided,
  RUNBOOK.md written. Remaining: N5 lockdown only.

### The RB/WR crossover (2026-08-28) - Justin's challenge, and he was right

  Mean best-available projection at my picks:

    pick     RB               WR
      7    249/236/228     222/210/206    RB +27
     18    203/192/190     200/192/189    even
     31    190/187/178     187/185/181    even
     42    169/158/153     184/177/176    WR +15
     55    156/150/146     170/162/155    WR +14
     66    147/143/140     161/149/143    WR +14
     79    141/133/127     153/142/139    WR +12

  Justin's claim holds: WRs out-project RBs at equal draft position
  FROM PICK 42 ON. The crossover is between picks 31 and 42.

  "Four RBs filling the flex" was my sloppy description and it misled
  him. The two plans differ at exactly ONE pick:
    RB-heavy  RB(7) RB(18) RB(31) WR(42) WR(55) WR(66) TE(79)
    WR-heavy  RB(7) WR(18) RB(31) WR(42) WR(55) WR(66) TE(79)
  Both take receivers at 42/55/66 - correctly harvesting the side of
  the crossover Justin identified. The only question is pick 18, where
  the board offers RB 203 vs WR 200. A three-point edge, which is why
  the measured plan gap was +1.8 and not something dramatic.

  PRACTICAL: the fallback says RB at 18 because it is worth ~2 points
  on the AVERAGE board. If the real board at 18 has a receiver clearly
  ahead of the best RB, take the receiver - and the live tool will say
  so, because it reads the actual board rather than the average one.

### Source sensitivity of the PLAN (2026-08-28)

  The keeper decision was stress-tested across three shops in August;
  the plan never was. Best sequence under each feed, then every plan
  scored under every feed (within-column only - CBS projects ~200
  higher in absolute terms):

    plan        sleeper    espn      cbs     blend
    RRRWWWT      1814.6   1942.3   1996.9   1897.7   <- best: sleeper, espn
    RWRWWTT      1794.2   1898.6   2038.1   1889.1   <- best: CBS (+41)
    RWRWRWT      1802.5   1922.6   2030.2   1902.1   <- best: blend (+4.4)

  THREE OF FOUR AGREE. Sleeper and ESPN both choose RRRWWWT, and under
  the blend it loses by 4.4 - inside noise. CBS is the real dissenter:
  under its numbers a TWO-TIGHT-END plan beats ours by 41, because CBS
  is structurally bullish on TEs rather than merely higher overall.

  NOT CHANGING THE PLAN. The accuracy shootout established Sleeper's
  projections as the best outcome predictor we hold (spearman .60-.70
  vs actuals, five seasons); CBS's projections have NO such validation
  because we only began scraping them this year and cannot score them
  against past outcomes. Switching to satisfy an unvalidated source
  trades a measured edge for an unmeasured one. Scope is limited
  anyway: this affects only the FALLBACK sequence - the live engine
  reads the real board and never follows a fixed order.

### Committee robustness across wrong-model worlds (2026-08-28)

    TRUE WORLD      committee  greedy-vorp  committed   engine-greedy
    base               1823.1     1821.0     1816.1        +2.1
    linear             1834.7     1832.3     1822.9        +2.3
    drones             1785.7     1784.2     1783.7        +1.4
    chaos              1896.9     1885.7     1888.9       +11.2
    all-autodraft      1807.5     1791.4     1805.6       +16.1
    qb-hungry          1824.5     1822.5     1817.6        +2.0

  THE ENGINE NEVER LOSES TO GREEDY. That was the failure mode worth
  hunting - a tool that wins in the world it was fitted to and loses
  when the model is wrong would be unsafe to trust on draft night. It
  does not happen in any of six worlds.

  And the edge GROWS as the world gets strange: +11.2 in a chaotic
  room, +16.1 when everyone autodrafts, versus ~+2 in familiar worlds.
  Adaptation is worth most exactly when the board misbehaves, because
  a fixed plan cannot notice. The all-autodraft column also answers
  Justin's JFMarino worry from the other side: an autodrafting room
  makes the live tool MORE valuable, not less.

### The Landmine score is not a new signal (2026-08-28, correction)

  MODEL.md recorded the ADR Landmine column on 2026-08-27 as "an
  independent, room-specific snipe-risk signal to cross-check
  snipes()". That was written without reading the author's own
  definition, which sits on the sheet's Main tab: a 1-10 rating of
  "how much earlier they're ranked on your platform versus expert
  consensus (ADP + FantasyPros)".

  Both of those are columns in the same CSV, so the claim is testable.
  LandmineCheck regresses the score on the gaps we already hold
  (200 players, the 2026-08-27 sheet):

    platform gap (consensus ADP - Sleeper rank)     R2 = 0.734
    + FantasyPros gap (ECR - Sleeper rank)          R2 = 0.975
    + where on the board he sits (log rank)         R2 = 0.975

  NOT INDEPENDENT. 97.5% of the column is a restatement of two feeds
  already in the model. It must not be fed in as a third opinion, and
  it cannot validate snipes() - agreement would only prove that both
  read the same two feeds.

  The residual is real but tiny (max 0.8 on a 1-10 scale) and is
  almost entirely QBs: Love +0.8, Goff +0.6, Jones +0.4, Mahomes +0.4,
  Ward -0.4, Maye +0.3, Lawrence +0.3, Purdy -0.3, Mayfield +0.3. That
  is the author's stated positional damping ("adjusted so it doesn't
  overreact when the gap is just position-based"), not knowledge about
  any room. Nothing here to harvest.

  What the ADR find still buys is therefore only freshness: dated
  default boards, which AdrProvenance now scores against real draft
  dates.

### Model B goes live, and says nothing (2026-08-28)

  Justin, mid-rehearsal: "didn't we have another model for after we
  have 9 players?" Yes - and it had no live implementation. Model A
  had LiveCommittee; Model B had InsuranceTest, a study over FIXED
  position sequences on an AVERAGE board, unreachable from a draft.
  So at the mock's pick 89 the honest state was: Model A correctly
  silent (flat 1808.4), and a day-old offline table being hand-
  translated by the assistant. LiveInsurance closes that gap.

  Three measured ingredients, all now actually wired: FogFit's
  per-tier ratios (bust rate included), the waiver wire measured from
  FULL 16-round histories (shared with InsuranceTest via
  replacementRanks so they cannot diverge), and Draft Sharks' games-
  missed applied RELATIVE to the pool average - the absolute injury
  level is already inside the fog constants, which were fitted on
  outcomes that contained injuries. MODEL.md had claimed the injury
  file "plugs in here"; nothing had ever read it until now.

  THE RESULT IS A NULL, and it took error bars to see it:

    draws    1st  2nd  3rd  4th   spread
      40      RB   TE   WR   QB     11.6
     120      QB   TE   RB   WR      5.5
     600      TE   WR   RB   QB      2.1  (+/- 3.8)

  Three runs, three different winners, gaps shrinking with the noise.
  At 600 draws every position is inside every other one's interval.
  Model B has NO preference at a bench pick - which independently
  reproduces the offline finding ("top three within 3 points") on a
  live board. The 40-draw run was nearly reported as "RB, not QB";
  the paired standard error is what stopped it.

  Why it is flat: the wire sits at QB21/RB61/WR81/TE19. A 12-team
  league with one QB slot makes QB21 a startable free quarterback, so
  a backup QB adds little; RB61 is junk, so a bench RB adds more. The
  two effects cancel.

  CONSEQUENCE for the handover. LiveCommittee's bench message now
  routes by round rather than sending everything to LateRoundTargets:
  rounds 8-9 -> LiveInsurance, rounds 10+ -> LateRoundTargets, whose
  keeper-option logic is calibrated for the cheap late rounds. With
  Model B indifferent, the keeper option is the only signal at pick
  89 with real separation (Nix 228 vs the best non-QB at 35) - though
  a round-8 keeper price is dear, so it is worth less than a score
  tuned for rounds 10-16 suggests.

## The rounds 1-16 model: plan (2026-08-29, supersedes the plan above)

Justin's ask: keep Model A for rounds 1-7 untouched, and build a second model
spanning rounds 1-16 with the keepers pinned at r12 and r13, which decides
when to take a tight end or a defence from historical data, injury and bust
risk, and the points a player is likely to contribute to STARTING lineups.
Trained on past seasons, with projections dated near the draft - his league
drafts 1 September and the season starts 9 September, so a projection from a
month out carries preseason-injury risk that a week-out projection does not.

The earlier plan in the section above is folded into this one; it was scoped to
the tight end question alone.

### What the data actually supports (checked, 2026-08-29)

**Sleeper's historical projections endpoint is UNUSABLE for training.** It
serves something for past seasons, which is the trap. Compared against the
real dated snapshot in `data/sleeper-projections-dated-2021-08-09.json.gz`,
only 27% of 453 overlapping players match, and player 333 reads 323.66 in the
August snapshot against -5.28 today. Those are rest-of-season values frozen at
season end - a "projection" that already knows who got hurt. Training on them
would have leaked the outcome into the feature and looked excellent doing it.

**Right-vintage inputs that DO exist**, roughly a week before each season:

| source | seasons at right vintage | carries |
|---|---|---|
| `fp-ecr-dated-*` | 2022-09-06, 2023-09-04, 2024-09-03, 2025-09-01 | `rank_ecr`, `pos_rank`, **`rank_std`**, bye |
| `fp-adp-halfppr-*` | 2021, 2022, 2023, 2025 (2024 is 3 August) | consensus ADP |
| `sleeper-defaults-*` | 2021-09-01 … 2024-09-02 | positional rank |

**Newly verified as available:** weekly half-PPR at
`/v1/stats/nfl/regular/<season>/<week>` (463 skill players a week), and
defence via `position[]=DEF` in both stats and projections.

**The gap:** dated projected POINTS for past seasons barely exist - one 2021
snapshot at the wrong vintage, a WR-only CBS file, and an empty
`external-projections/`. So the model's "projection" input has to be RANK at
the right vintage, mapped to expected points by a curve fitted from history.
That is how the repo already works, so it is not a compromise, but it does
mean 2021 is only half-usable and the effective sample is four seasons.

**`rank_std` is the find.** Expert disagreement per player, available at draft
time, is exactly the per-player uncertainty the scalar dials were faking. It
has to earn its place out of sample, but it is the right shape.

### Phases

**0 - data foundation (75 min). DONE 2026-08-29.** `WeeklyActuals` pulls all
five seasons x eighteen weeks, cached forever, and keeps scoring separate from
availability - a man who played and scored two is available-and-bad and can be
benched, a man who did not play cannot be started at all, and collapsing them
would lose the distinction the objective turns on.

The gate passed cleanly:

    SEASON     matched  mean |diff|   worst diff    over 1 pt   verdict
    2021           603        0.000          0.0            0   RECONCILES
    2022           578        0.001          0.3            0   RECONCILES
    2023           544        0.000          0.0            0   RECONCILES
    2024           555        0.000          0.0            0   RECONCILES
    2025           574        0.000          0.0            0   RECONCILES

Not one player in five seasons differs by more than a third of a point between
the weekly sum and the season total. The two feeds are the same data at two
resolutions, so the weekly one can carry the starter-sum objective.

Still outstanding in this phase: defence actuals and ADP, and the vintage audit
table - though the provenance fix below did most of the latter.

**1 - per-player draft-time distribution (60 min).** Mean from the rank-to-
points curve; spread calibrated from `rank_std`; availability from the
games-played distribution by position and tier, with its correlation to
scoring MEASURED rather than assumed independent. *Gate:* does `rank_std` beat
a flat per-tier spread out of sample? If not, drop it and say so.

**2 - the curve layer (45 min).** Peaks, valleys and plateaux per position per
season, and positional scarcity as the slope between your pick and
replacement. Tight end and defence timing should FALL OUT of these curves
rather than be hand-set - defence is folk-wisdom flat, and this is where that
gets tested instead of repeated.

**3 - the sequential 1-16 model (90 min).** Roll all sixteen rounds with
keepers pinned at r12 and r13, choosing positions to maximise expected
weekly-starter contribution. Several candidate policies compared, the way the
rounds 1-7 committee does, rather than one engine trusted alone.

**4 - train and validate (60 min).** Fit on 2022-2023, tune on 2024, touch
2025 once. Baselines: Model A's plan plus folk rules for 8-16, best-available
by ADP, and the committed RUNBOOK plan. Metric is realized weekly-starter
points on held-out seasons. *If it does not beat the baselines, keep the
simpler rules and record that it lost.*

**5 - integration (30 min).** Model A is untouched. The new model runs beside
it, and disagreement in rounds 1-7 is information, exactly as a split
committee is. RUNBOOK gets only what survived Phase 4.

Six hours, not the four or five hoped for. If it has to fit in five, cut
Phase 2 to a printed curve with no scarcity term, and keep 0, 1, 3 and 4.
Phase 4 is not cuttable - nothing built on 2026-08-29 was validated out of
sample, and four reported results were corrected that day.

### Honest expectations

Four usable seasons, and three once 2025 is held out. Several questions here
are not answerable at that n, and the deliverable of Phase 4 may well be "the
folk rules were fine". That is a real result and cheaper to accept than to
discover in week 6.

### Historical Sleeper projections: what is actually reachable (2026-08-29)

Three suggested routes, tested rather than taken on trust.

**Full-season endpoint `/v1/projections/nfl/regular/<year>` - UNUSABLE.**
Against the real dated 2021-08-09 snapshot only 27% of 453 overlapping players
match; one reads 323.66 in August and -5.28 today. Rest-of-season values frozen
at season end.

**Week-1 endpoint `/v1/projections/nfl/regular/<year>/1` - USABLE, and it is
the find.** A week-1 projection can only have been made before week 1. Tested
on 2021:

    week-1 projection vs AUG-9 preseason snapshot : r = 0.914  (n=302)
    week-1 projection vs ACTUAL week-1 outcome    : r = 0.703  (n=425)

It tracks the preseason snapshot far more closely than the outcome, which is
what a real projection does and what the contaminated season feed does not.
Coverage is 755-970 players in every season 2021-2025.

**League matchup archives - FALSE.** `/v1/league/<id>/matchups/1` returns
`custom_points, matchup_id, players, players_points, points, roster_id,
starters, starters_points`. Actual points only; no projections anywhere.

Untested and deliberately not pursued: ffverse/ffscrapr (`dp_sleeper_players`
is a player-id map, not projections), RotoWire's subscriber archive, and
community GitHub/Kaggle dumps - third-party provenance is exactly the thing
this section exists to guard against.

**How the week-1 projection must be used.** NOT scaled to a season. It is a
single game against a specific defence, so multiplying by 17 propagates one
matchup into a season estimate. Use it as a preseason-vintage RANKING signal,
and map rank to expected season points with the historical curve the repo
already fits - the same treatment ADP gets. Matchup effects are then noise
across a position group rather than bias in a total.

Only week 1 is safe. Week 2's projection is made after week 1 is played, so
for a 1 September draft it leaks results.

**The leak that remains, stated.** Only one player projected above 10 points
scored zero in week 1 of 2021, which suggests the feed reflects final inactives
- so it may carry up to 8 days of news the drafter on 1 September did not have.
That bias runs one way: it makes projections look more reliable than they were,
and therefore understates bust risk, which is the very thing being modelled.
Bound it in Phase 0 by comparing how well the 2021 week-1 projection and the
2021-08-09 snapshot each predict the season, and carry the gap as a correction
rather than ignoring it.

**Two experiments this makes possible**, both falsifiable:

1. Does week-1-projection rank beat consensus ADP rank at predicting end-of-
   season points? If not, the simpler input wins and this whole route is moot.
2. Does a later vintage bust less? ADR's July ranks and FantasyPros' early-
   September ranks cover the same seasons, so Justin's premise - that a
   month-out projection carries more preseason-injury risk - becomes a number
   instead of a reasonable belief.

### Experiment 1 result: week-1 projection vs market rank (2026-08-29)

    SEASON      week-1 proj  best ADP rank      delta   which ADP
    2021              0.588          0.415     +0.173   sleeper-dated-20210907
    2022              0.570          0.504     +0.066   fp-consensus-adp
    2023              0.579          0.479     +0.100   ffc-adp
    2024              0.558          0.405     +0.153   sleeper-DEFAULTS
    2025              0.559          0.452     +0.107   sleeper-stored-adp

    mean delta +0.120 over 5 seasons - week-1 projection WINS (5 of 5)

    by position (week-1 minus best ADP):
       QB    -0.000   no difference
       RB    -0.036   no difference
       WR    -0.027   no difference
       TE    -0.210   MARKET RANK IS BETTER

**It wins across positions and loses within them.** That is not a
contradiction, it is the whole finding. The overall spearman rewards ordering
players ACROSS positions - is this back worth more than that receiver - and a
projection in points does that natively while a market rank encodes draft
convention and positional scarcity instead. Within a position, where the
question is which of these four tight ends, the projection adds nothing at
QB/RB/WR and is much worse at TE.

**Design consequence, and it is concrete:**

- cross-position comparisons (when to take a tight end or a defence, the whole
  point of the 1-16 model) -> week-1 projection
- within-position ordering (WHICH tight end) -> market rank
- never rank tight ends by week-1 projection: 0.20, 0.23, 0.22, 0.19, 0.02
  spearman across the five seasons, against 0.35-0.53 for ADP

The market appears to know something about tight ends that the projection does
not, which is plausible: tight end production concentrates in a few roles, and
human consensus reads role better than a stat line does.

### A pre-existing leak this exposed

`AccuracyShootout` has been scoring `sleeper-projections` - the season endpoint
- as a predictor since it was written, and it topped the table every year:
0.603, 0.699, 0.602, 0.613, 0.597, against 0.34-0.50 for every honest feed,
and 14-16 of 24 top-24 hits against 6-12. It was not forecasting. It is
renamed `sleeper-season-LEAKED` and excluded from the experiment rather than
deleted, so the leak stays visible.

Anything previously concluded from "Sleeper's projections beat ADP by a mile"
rested on that source and should be re-derived. The honest version of that
claim is the +0.120 above - real, consistent, and a third the size.

### Experiment 2 result: vintage barely matters (2026-08-29)

Same feed against itself at two ages - the only comparison that isolates
vintage from a source's own quality:

    SEASON  FEED                early  spearman     late  spearman   delta  days
    2022    sleeper-dated    20220806     0.491 20220913     0.489  -0.003    38
    2023    sleeper-dated    20230830     0.436 20230901     0.430  -0.006     2
    2024    sleeper-dated    20240803     0.398 20240829     0.405  +0.007    26
    2025    sleeper-dated    20250801     0.388 20250903     0.450  +0.062    33

    mean delta +0.015 over 4 pairs; the later capture won 2 of them.

**A month of preseason news is worth about +0.015 rank-correlation, and it is
not consistent** - two of four pairs got WORSE with age. The single real
movement is 2025, +0.062 across 33 days, one season out of four.

So Justin's premise - that a month-out projection carries more preseason-injury
risk - is not supported at this sample size. The practical consequences are
good news for the 1-16 model:

- the 2024 ADP hole (dated 3 August rather than September) stops mattering
- ADR's July captures are usable as rank inputs, not just as bye-week sources
- vintage discipline is still right for AVOIDING LEAKS, which is a different
  thing entirely and is where it earned its keep today

Four pairs is thin, and 2025 shows the effect can be real in a given year.
This says the average cost is small, not that vintage never matters.

### The provenance bug this exposed

The `sleeper-defaults-<season>-<date>.csv` files carry **fabricated dates**.
Commit 2bd97be extracted them all from the same mid-July ADR workbooks and
named them after each season's draft, so `sleeper-defaults-2022-20220901.csv`
is July content wearing a September name. Re-extracting the 2022 workbook at
its true manifest date produced a file identical in all 208 ranks.

Consequences:

- `AdrProvenance` reports those dates as capture dates and passes them as
  admissible. Its 2021-2024 rows are wrong: it is reading filenames, not
  provenance. The 2026 rows, harvested live this month, are fine.
- Experiment 2 now detects two "captures" that score identically to six
  decimals and prints SAME DATA rather than averaging them into an answer.

**Fixed 2026-08-29.** True dates established empirically - re-running the
extractor on each ADR workbook and checking which reproduced the file
rank-for-rank - then the three provably wrong files renamed and provenance
moved out of filenames into `data/adr/provenance.csv`, which `AdrProvenance`
now reads. A name is a label anyone can type; the manifest records what
actually produced each file.

What the tool said before, and what is true:

    BEFORE (filename dates)          AFTER (verified provenance)
    2021  2021-09-01   4d   yes      2021  2021-07-17   50d  stale
    2022  2022-09-01   3d   yes      2022  2022-07-16   50d  stale
    2023  2023-09-01   4d   yes      2023  2023-08-19   17d  yes
    2024  2024-09-02  -1d   NO       2024  UNKNOWN       -   NO, unverified

2021 and 2022 were being certified as three and four days pre-draft while
holding a board seven weeks old. 2024 was rejected for the wrong reason - the
fabricated date happened to fall after the draft.

Two files were checked and left alone: `sleeper-defaults-2024-20240902.csv`
keeps its name because renaming it to another guess repeats the bug, and the
manifest marks it UNKNOWN so the tool refuses it; and
`sleeper-defaults-2026-20260827.csv` turned out to be correctly dated - the
26 and 27 August workbooks are identical, so the first-match test that flagged
it was wrong, not the file.

## Design: the 1-16 model as Model A with a different objective (2026-08-29)

Justin's specification: keep the rounds 1-7 model, add one that spans 1-16 with
the keepers in their real spots, prices bust whether it comes from injury or
from a healthy player underperforming, and maximises the points his STARTERS
score over the season - counting the bench man who overtakes a starter, and
checking whether a tight end is really better than the wire at a given round.

The good news is that almost none of this is new machinery. It is the existing
search with a different scoring function and a longer horizon.

### The objective

For a roster R, the thing being maximised is

    V(R) = SUM over weeks of  bestNine(who is available that week, that week's points)

Not a season total. A season total fixes who starts all year, which is exactly
why it cannot see a bench player - and why LiveInsurance returned STARTS = 0%
for every candidate. The weekly max is what makes a bench man worth anything:
he scores only in the weeks he beats the people ahead of him, which is an
option payoff, convex in dispersion. That is also why bust and injury belong in
the same term. Both are ways a starter vacates a slot; one does it by being
absent and the other by being present and bad.

**Byes are out of scope by choice, and that buys a 17x saving.** With no byes
the weeks are exchangeable, so

    V(R) = 17 x E[ bestNine(available, weekly points) ]

One week evaluated over sampled scenarios, multiplied by seventeen. This is
exact for the EXPECTATION - week-to-week injury correlation changes the
variance of a season, not its mean - and it turns a 1700-call evaluation into a
200-call one.

### What gets reused

`StartingLineup.bestNine(roster, points)` is already the single-week lineup
optimiser; it has only ever been fed season totals. Feed it weekly points and
an availability filter and it is the inner loop of the new objective, unchanged.

The seam is one interface:

    interface RosterValue { double of(Collection<String> roster); }

with `SeasonTotalValue` wrapping today's `bestNine` and `WeeklyStarterValue`
implementing the above. `DraftPlanner` takes a `RosterValue`. Model A keeps the
old one and is untouched - Justin's requirement - and the new model passes the
new one. The fourteen existing call sites move behind the interface in one
mechanical change.

`DraftPlanner.scheduleRounds()` already extends the board to sixteen rounds
with keepers occupying r12 and r13 (added 2026-08-29), so the horizon is done.

### What is new

**Player outcome samples.** Each player needs a joint draw of availability and
scoring, per scenario. From the vintage work:

- cross-position mean: the week-1 projection, which beat market rank by +0.120
  spearman across five seasons
- within-position ordering: market rank, which beat the projection at every
  position and by 0.210 at tight end - so tight ends are never ranked by
  projection
- dispersion: the per-tier empirical distribution of outcome-over-expectation,
  with `rank_std` (expert disagreement, available at draft time) tested as a
  per-player modifier and dropped if it does not earn its place out of sample
- availability: games played by position and tier, with its correlation to
  scoring measured rather than assumed away

**Sample Average Approximation.** Draw the scenario set ONCE, hold it fixed,
and evaluate every candidate roster against the same draws. The search then
optimises a deterministic function, comparisons are paired by construction, and
the noise that wrecked several of today's readings cannot reappear between two
options. The repo already ran `saa-replan` in the rounds 1-7 committee, so the
pattern is familiar here.

### The tight end question answers itself

No rule is needed, and none should be written. `bestNine` fills any slot it
cannot fill from the roster with the waiver wire, so a tight end who beats the
wire by little adds little to V(R), and the search declines him without being
told to. Same for a defence. The 2026-08-29 finding that drafting a TE10 beats
streaming by +53.2 +/- 30.6 points becomes a prediction this model should
reproduce, not a constant to hard-code - and if it does not reproduce it, one
of the two is wrong and that is worth knowing.

### Cost

Per roster evaluation: S scenarios x one `bestNine`. At S=200 that is the same
order as a single rollout today. The search multiplies it by the rollout count,
so expect the 1-16 model to cost roughly what Model A costs times S/17. Offline
training and validation can afford that; draft-night use will want a smaller S
with the scenario set cached, and `DraftNight` already holds a warm engine to
hang it on.

### Order of work

Phases 0, 1 and 4 of the plan above stand: weekly actuals, the per-player
distribution with its gates, and an out-of-sample test against Model A's plan,
best-available-ADP and the committed plan. This design replaces phase 3. Phase
2 folds in - peaks, valleys and scarcity stop being a separate layer, because a
curve's shape is already inside V(R) through what the wire offers at each
position.

### Phase 1, first result: availability and scoring are NOT independent (2026-08-29)

    POS           n  correlation   verdict
    RB          422        0.347   NOT INDEPENDENT
    WR          519        0.210   NOT INDEPENDENT
    TE          202        0.102   NOT INDEPENDENT
    QB          185        0.669   NOT INDEPENDENT

Every position positive, quarterback overwhelmingly so. `StarterContribution`
draws games played and scoring independently; that is wrong, and wrong in the
direction that matters. Independent draws understate how often a roster is
short AND weak in the same week, which is exactly the week a bench man is worth
something.

**Why it is positive is the more useful finding.** This is not mostly injury.
A player who loses his ROLE - benched quarterback, back who falls out of a
committee - records both fewer games and fewer points in the games he does
play. So availability-loss and bust are not two risks to be modelled
separately. They are one event, job loss, observed through two measurements.
QB at 0.669 is the clearest case, because a benched quarterback stops playing
almost entirely.

**The fix is simpler than modelling the correlation.** Bootstrap whole observed
player-seasons - games, mean when playing, spread when playing - as a single
unit from the position-and-tier pool, instead of drawing the parts separately.
The correlation is then preserved by construction and nothing has to be
estimated.

Separately, the distributions themselves:

    POS  TIER          n     games  sd games    pts/game sd pts/game
    RB   1-12         60      14.8       3.4        15.0         7.8
    RB   13-24        60      13.1       4.4        11.1         6.3
    RB   25-36        60      13.9       3.0         9.2         5.9
    TE   1-12         60      14.0       3.0         9.2         6.0
    TE   13-24        60      14.3       3.4         6.0         4.8
    TE   25-36        55      14.6       2.7         4.7         4.1
    QB   1-12         60      14.3       3.7        19.0         7.6

Two things stand out. Availability barely varies by draft rank - every tier
plays 13 to 15 games - so what a high pick buys is scoring rate, not health.
And week-to-week spread is enormous relative to the mean: a tier-one back
averages 15.0 a game with a standard deviation of 7.8. That spread is the
whole reason a bench player ever starts, and a model working in season totals
cannot see any of it.

The TE tiers are worth noting against the tight end work: TE1-12 average 9.2 a
game against 6.0 for TE13-24, about 45 points across a season, which is the
same order as the +53.2 +/- 30.6 streaming margin measured independently.

### Phase 1 gate: rank_std does NOT earn its place (2026-08-29)

FantasyPros' `rank_std` - how much its contributors disagree about a player -
was the one per-player uncertainty signal available at draft time, and the
obvious candidate to replace the scalar dials. It fails.

    PREDICTOR OF THE MISS          mean error
    tier average (baseline)              29.4
    rank_std alone                       31.1
    tier PLUS rank_std                   29.4

Fitted on 2022-2023, judged once on 2024-2025, 480 held-out players. Added on
top of the tier it improves prediction by 0.01 points, and the fitted slope on
the residuals is -0.02 - indistinguishable from nothing.

The first version of this test was a straw man: it compared rank_std ALONE
against the tier alone, which it lost 31.1 to 29.4. That is not the question -
it would lose on rank information it never carried. Regressing the training
residuals against how far a player's disagreement sits from his own tier's is
the honest test, and the verdict came back the same, which is the only reason
the first answer survives.

The raw diagnostic looked promising in the wrong direction - correlations of
-0.142, -0.072, -0.168, -0.083 between rank_std and the miss - but that is a
rank artifact. Disagreement grows with depth, and deep players have smaller
absolute misses because their expectations are smaller.

**So the per-player distribution has no per-player modifier, and Phase 1 ends
simpler than it started:**

    draw a whole observed player-season - games, mean when playing, spread when
    playing - as ONE unit from the pool for that position and tier

Nothing estimated, nothing fitted, and the availability-scoring correlation
(RB 0.347, WR 0.210, TE 0.102, QB 0.669) is preserved by construction rather
than modelled. That is the whole of the layer the 1-16 design calls for.

### The objective is built, and it sees what the old one could not (2026-08-29)

`RosterValue` is the seam: `SeasonTotalValue` wraps Model A's rule untouched,
`WeeklyStarterValue` implements V(R) = 17 x E[best legal lineup in one week].
The decisive test is what a TENTH man is worth on a full nine:

    TENTH MAN                  POS    tier    season totals      starter sum
    De'Von Achane              RB        0             +2.4           +155.8
    Jeremiyah Love             RB        1             +0.0            +92.3
    A.J. Brown                 WR        0             +0.0           +134.2
    Rashee Rice                WR        1             +0.0            +98.9
    Tucker Kraft               TE        0             +0.0            +74.0
    Oronde Gadsden             TE        1             +0.0            +40.1
    Trevor Lawrence            QB        0             +0.0            +66.2
    Michael Penix              QB        2             +0.0            +22.5

Eleven of twelve bench candidates are worth exactly nothing under season
totals - only Achane registers, and only because he outscores a starter
outright. That is the blindness that produced STARTS = 0%. Under the starter
sum every one is worth something, graded by tier and by position, and the
ordering at the top tier is RB 155.8 > WR 134.2 > TE 74.0 > QB 66.2, which is
the flex advantage arriving from a third independent direction.

**A wire bug caught in the same run.** The first version took the deepest tier
the pool happened to hold as the waiver wire, which put the WR wire at rank
~133 and 0.4 points a week - a man who barely plays, not a wire. Every marginal
was inflated by it. The wire is now the replacement level `InsuranceTest`
measures from full sixteen-round histories - QB21, RB61, WR81, TE19, worth
12.2, 3.4, 4.2 and 5.0 points a week - and the marginals fell by 10-20%. A
sanity check: Achane's +155.8 is 9.2 points a week over the wire, against a
tier-zero back averaging 15.0 a game at 82% availability, which is 8.9. The
objective is arithmetically consistent with its own inputs.

### What remains

The objective exists; the SEARCH does not yet use it. Next is passing a
`RosterValue` into `DraftPlanner` so the sixteen-round board is optimised
against the starter sum rather than best-nine, at which point the tight end and
defence timing questions are answered by the objective instead of by rules.
Then Phase 4, which is not optional: fit on early seasons, judge once on held
out ones, against Model A's plan, best-available-ADP and the committed plan.

### The 1-16 model runs (2026-08-29)

`RosterValue` is wired into `DraftPlanner`: one field, set in the constructor
rather than lazily because `valueOf` is called from inside a `parallel()`
stream and lazy initialisation would have threads racing to create it. Three
call sites moved. Model A's plan is byte-identical afterwards - `[RB, WR, RB,
WR, WR, WR, TE, QB, QB]`, 1812.1, p10 1783.6 - so Tuesday's tool is untouched.

First run, 40 rollouts and 100 scenarios, sixteen rounds, keepers at r12/r13:

    objective: best-nine season totals
       plan  RB WR RB WR WR WR TE QB QB QB QB QB QB QB

    objective: weekly starter sum
       plan  RB WR WR RB WR WR RB WR QB TE TE WR TE TE

**The old objective degenerates and the new one does not.** Six consecutive
quarterbacks after round 8 is not a plan; it is the flat-table artifact, the
same one that made the committee print "4 of 4 engines say QB" at pick 89 when
every column read 1808.4. Season totals cannot rank a bench pick, so map order
decides. The starter sum keeps making distinguishable choices through round 16,
which is the entire reason for the redesign.

**What it says, with a warning attached.** No tight end until pick 114 - round
10 - which agrees in direction with the independent finding that waiting beats
taking one at 79, and goes further. But it then takes FOUR of them, and that
deserves suspicion rather than adoption. A weekly max over four cheap tight ends
does add value in the model, because some week one of them is up and good; in a
real season you would drop three of them by October. The objective has no
roster-churn term and no bench-size limit, so it is free to hoard redundancy at
whatever position is cheapest. That is a modelling gap, not a discovery.

**Nothing here is validated.** This is one run at low settings against no
baseline. Phase 4 is what decides whether any of it beats Model A's plan,
best-available-ADP, or the committed plan, and until then the RUNBOOK should
not move a single pick on the strength of it.

### The bench-churn constraint was a mis-set wire (2026-08-29)

The first 1-16 run took four tight ends, which nobody would hold past October,
and the obvious diagnosis was a missing roster-churn term. It was not. Two
things were already right and one number was wrong.

**Churn was already modelled.** The weekly fill starts a rostered player only
if he beats the wire, and otherwise takes the wire - which is exactly what
dropping him and streaming achieves. A bench player who busts already
contributes nothing beyond the wire; his downside was floored all along.

**A roster cap would not have bound.** Fourteen picks into a sixteen-man roster
leaves no slack, and the search already trades picks against each other.

**The wire was too weak, and that made the floor too generous.** It averaged the
whole replacement tier, which is what the wire offers a manager who never
touches it. A manager who streams takes the BEST option available, so the wire
is the top of that band, not its middle. Chosen on expected rate rather than on
what the player went on to score - picking the best realised outcome is the
hindsight that wrecked `wireLevel` in TightEndTiming, and this is deliberately
the honest version.

Selected by RANK rather than by tier, too: tiers are twelve wide, so QB21 fell
in the 13-24 band and its best quarter came back as QB13-15 at 18.3 points a
week - a startable quarterback somebody owns, not a wire option.

    wire QB  15.8   RB 6.8   WR 7.5   TE 7.2   points per week

The hoarding largely goes with it:

    before   RB WR WR RB WR WR RB WR QB TE TE WR TE TE     (four tight ends)
    after    RB WR WR WR WR WR RB WR QB TE TE WR RB TE     (three, the last at 186)

Taking a first tight end at 114 with none on the roster is right, and a second
as cover is defensible. The third, at the final pick of the draft, costs
nothing and proves little. Seven receivers is the part still worth arguing
with, though it is less obviously wrong - with three WR slots and two flexes,
receivers are the most startable thing on the board.

Still unvalidated. Phase 4 decides.

## Phase 4: the new model does NOT beat the folk rules (2026-08-29)

Every strategy drafted from each season's real ADP board and was scored on what
those players actually did, week by week, in the league's real lineup - QB,
RB2, WR3, TE, FLEX2, DEF. No distributions, no scenario draws, nothing marking
its own homework.

    STRATEGY                     2021   2022   2023   2024   2025    mean  vs ADP  wins
    RUNBOOK committed            2082   1705   2342   2031   2241    2080    +466   5/5
    RB-heavy folk rule           1975   1789   2300   1908   1946    1983    +369   5/5
    starter-sum (1-16)           1849   1961   1804   2180   2148    1989    +374   5/5
    best-nine (Model A)          1506   1671   1976   1783   1701    1727    +113   3/5
    best available by ADP        1672   1519   1388   1662   1831    1614      +0   0/5

**The committed plan already in the RUNBOOK wins.** It beats the starter-sum
sequence by 91 points a season on the mean and 3 seasons to 2 head to head. The
starter-sum row is also FLATTERED - the model that produced it drew its
distributions from these very seasons - so the true gap is wider than 91.

The plan said: if it does not beat the baselines, keep the simpler rule and
write down that it lost. It lost. **The RUNBOOK does not change.**

Three things worth keeping from the run:

**Position thinking is worth a lot.** Every deliberate strategy beat
best-available-by-ADP, by 113 to 466 points a season, 5 of 5 seasons for the
top three. The null hypothesis loses badly, which is a real result even though
it flatters nobody in particular.

**Model A must never be extended past round 9.** Its sixteen-round plan is the
worst of the thinking strategies at 1727, barely clear of drafting blind,
because it degenerates into six consecutive quarterbacks once the starting nine
is full. It is excellent inside the game it was built for and useless outside
it, which is exactly why it was left untouched.

**What was tested is a fixed sequence, not the model.** The starter-sum row is
one position order derived from the 2026 board and replayed on five other
seasons. A per-season policy - rerunning the search against each season's own
board - would likely do better, and that is the honest next experiment rather
than a defence of this result. But it has to be run before anything is claimed,
and this result stands until it is.

**And none of these models reasons about defence at all.** Every strategy was
handed one at its last pick because the league starts one and no model has an
opinion. That the objective omits a starting slot entirely is a gap in the
design, not in the backtest.

## Defence: the folk claim is true, and now has a number (2026-08-29)

Justin's challenge - defences are notoriously unpredictable, but nobody had
calculated it. Measured the same way every feed in this repo is measured, the
rank correlation between a preseason board and what actually happened:

    SEASON         QB       RB       WR       TE      DEF
    2021        0.773    0.728    0.701    0.610    0.527
    2022        0.463    0.598    0.616    0.382    0.119
    2023        0.378    0.533    0.664    0.468    0.461
    2024        0.571    0.600    0.643    0.517    0.047
    2025        0.466    0.701    0.573    0.568    0.232

    mean        0.530    0.632    0.639    0.509    0.277

**The claim holds.** A preseason defence ranking carries less than half the
information of any skill position - 0.277 against 0.578 - and in two of five
seasons it carried essentially none. The folk rule of taking a defence last is
correct for a measurable reason, not out of habit.

### The design gap, closed

`V(R)` omitted the DEF slot entirely, which meant every roster was scored a
starter short. The slot is now filled, from the wire when the roster has none.
Two bugs surfaced doing it, both from gating the board on
`StartingLineup.isSkillPosition`: rostered defences were given no sampled
outcomes at all and were silently treated as never available, and the pool held
no defence seasons. Both fixed; the pool grew from 1328 player-seasons to 1466.

What a drafted defence is worth over the wire, which is the question that
decides the timing:

    Detroit Lions        DEF  tier 0   +32.5
    San Francisco 49ers  DEF  tier 1   +26.9
    New York Jets        DEF  tier 2   +16.2

    for comparison       RB   tier 0  +124.8   tier 2  +51.2
                         WR   tier 0   +95.6   tier 2  +55.1
                         TE   tier 2   +15.3
                         QB   tier 2   +15.7

**The curve is flat, which is the 0.277 showing up as money.** The best defence
on the board beats a wire defence by 32 points; the twenty-fifth beats it by
16. You cannot tell them apart in advance, so the good one is not much better
than the ordinary one. A defence is worth less than a tier-two back or receiver
and more than a tier-two tight end or quarterback - which places it exactly
where the RUNBOOK already puts it, at the end of the draft.

### What is still open, and why it is not worth closing

The SEARCH cannot pick a defence: `DraftPlanner`'s positions are QB/RB/WR/TE
and its board is gated on skill positions. At the last pick or two a defence is
genuinely the best available choice - +32.5 against +15.3 for a deep tight end
- so the search would take one if it could, and it currently takes a low-value
skill player instead.

That is worth roughly thirty points a season, once, at the final pick. Opening
the board to defences touches the construction Model A depends on, three days
before a draft, to reproduce a rule the RUNBOOK already states. Not worth it.
Recorded here so the next person knows it is a choice rather than an oversight.

### Should a top-four defence be chased? No, and it is not close (2026-08-29)

Justin's own tendency, priced against what the pick costs.

    DID THE PRESEASON TOP FOUR ACTUALLY FINISH THERE?
    2021     11, 6, 16, 26      0/4
    2022     5, 15, 3, 19       1/4
    2023     8, 23, 1, 3        2/4
    2024     23, 19, 11, 13     0/4
    2025     5, 7, 9, 16        0/4

    3 of 20 finished top four (15%). Chance alone gives 13%.

That is the whole story in one line: a preseason defence ranking is very nearly
uninformative about which defences will actually be good. It is the 0.277 rank
correlation seen from the sharp end.

    SEASON    DEF1-4      adp    vs free    round   skill at that adp      vs free
    2021       122.8      108      -12.5      r10   Kenyan Drake (RB)         41.6
    2022       135.3       99        3.1       r9   Melvin Gordon III (RB)   -14.8
    2023       152.3      108       26.1      r10   Courtland Sutton (WR)     77.3
    2024       112.3      144       -6.5      r12   Jakobi Meyers (WR)       116.1
    2025       134.3      141       24.1      r12   Dallas Goedert (TE)       77.4

A top-four defence beat a free one by 6.9 points a season - and in two of five
seasons it was NEGATIVE. The skill player passed over at that pick beat his own
replacement by 59.5. The pick is worth **-52.7 +/- 44.4** points a season, and
the defence won 1 season in 5.

It clears its error bar, but only just, and five seasons with one named player
a side is thin. The count is the sturdier read and it says the same thing: 1 of
5, and 15% against a 13% coin.

**Not the same as "never draft a defence."** At the FINAL pick one is still
worth about +32 over the wire, ahead of a deep tight end at +15. The finding is
about the round, not the position: take one, take it last, and do not reach.

## The per-season policy is worse still (2026-08-29)

Phase 4 tested a fixed sequence and lost. The fair version - the model as a
POLICY, drafting on each season's own board, with a LEAVE-ONE-OUT pool so it
never sees the season it is judged on - was supposed to be the model's best
case. It is its worst.

    STRATEGY                     2021   2022   2023   2024   2025    mean  vs ADP  wins
    RUNBOOK committed            2082   1705   2342   2031   2241    2080    +466   5/5
    RB-heavy folk rule           1975   1789   2300   1908   1946    1983    +369   5/5
    starter-sum (fixed seq)      1849   1961   1804   2180   2148    1989    +374   5/5
    starter-sum POLICY           1660   1838   1962   1886   1801    1829    +215   3/5
    best-nine (Model A)          1506   1671   1976   1783   1701    1727    +113   3/5
    best available by ADP        1672   1519   1388   1662   1831    1614      +0   0/5

**Against the committed plan: -251 points a season, ahead in 1 season of 5.**

Removing the leakage made it worse, which is the clearest possible confirmation
that the fixed sequence's better showing was the leakage flattering it. That
caveat was written before the number was known; it was right.

### The choices are visibly wrong, which matters more than the score

    2021: RB WR WR WR RB QB WR WR WR WR DEF RB TE RB
    2022: RB RB WR WR QB WR RB WR TE RB RB DEF DEF DEF
    2023: RB RB RB QB RB WR WR QB WR TE RB DEF WR TE
    2024: RB RB QB WR QB RB RB RB RB WR DEF DEF TE DEF
    2025: RB WR WR WR QB WR WR RB RB TE RB DEF DEF WR

Two pathologies stand out and neither is a matter of taste. It takes a
quarterback in round 3 in 2024 and two in 2023, when the QB wire supplies 15.8
points a week and the marginal there cannot justify a third-round pick. And it
takes THREE defences in 2022 and 2024, when only one can ever start - a second
defence should be worth approximately nothing, so something is scoring it above
zero.

Those are bugs, not preferences, and they are the leads for anyone continuing.
But finding them would not rescue the conclusion; it deepens it. A model whose
choices are visibly wrong and whose score is 251 points behind a folk rule is
not close to usable.

### The verdict, plainly

Three independent tests now say the same thing. The fixed sequence lost. The
policy lost by more. Model A extended past round 9 lost worst of all. The only
robust finding in any of them is that thinking about position at all beats
drafting blind by 113 to 466 points a season - which every strategy here
already does, including the RUNBOOK's.

**The RUNBOOK does not change.** The 1-16 model is not draft-ready and should
not be used on Tuesday. What was worth building is the measurement apparatus
around it: the weekly actuals, the outcome distributions, the availability-
scoring correlation, the defence predictability numbers, and this backtest -
all of which stand on their own and are what caught the model.

## Both pathologies were one bug: hindsight start/sit (2026-08-29)

The policy took a third-round quarterback and three defences. Those looked like
two separate faults; they were one, and it was mine.

**The lineup fill sorted candidates by the week's REALISED points.** That is
perfect start/sit - it hands a manager a lineup nobody can set, because you
choose before kickoff. And it rewards redundancy in proportion to weekly
spread: two quarterbacks are worth the max of two draws only if you know in
advance which will hit. Quarterback has the widest weekly spread of any
position (sd 7.6 on a mean of 19.0), so stacking quarterbacks looked
outstanding, and a second defence looked free money for the same reason.

The bug was in BOTH the model and the evaluator, so the backtest was rewarding
the very behaviour the model was inventing.

Fixed on both sides. `WeeklyStarterValue` now carries the expected rate and the
realised points separately: lineups are set on what you EXPECTED, and scored on
what happened. `PlanBacktest` sets its lineups by PRESEASON RANK - which
understates a real manager who learns during the season, but never uses
information from the future and is applied identically to every strategy.

The pathologies went with it:

    before   2022: ... RB RB DEF DEF DEF      2024: RB RB QB WR QB ...
    after    2022: ... RB RB QB TE DEF        2024: RB RB QB WR RB ...

No defence stacking anywhere, and no season takes two quarterbacks.

**The verdict does not move.**

    STRATEGY                     mean  vs ADP  wins
    RUNBOOK committed            1984    +542   5/5
    starter-sum (fixed seq)      1886    +444   5/5
    RB-heavy folk rule           1870    +428   5/5
    starter-sum POLICY           1751    +309   5/5
    best-nine (Model A)          1613    +171   3/5
    best available by ADP        1442      +0   0/5

Still -233 points a season against the committed plan, still ahead in 1 season
of 5. Every score fell, because hindsight had been inflating all of them - the
ADP baseline most of all, from 1614 to 1442, which is what you would expect
since it stacks nothing.

That the pathologies were not the reason it was losing is worth knowing. A
better-behaved model that loses by the same margin is evidence the problem is
the objective or its inputs, not the search.

## What the models do after round 7, and where the model actually loses (2026-08-29)

    STRATEGY                 rounds 1-7                rounds 8-16
    starter-sum (1-16)       RB WR WR WR WR WR RB      WR QB TE TE WR RB DEF
    best-nine (Model A)      RB WR RB WR WR WR TE      QB QB QB QB QB QB DEF
    RUNBOOK committed        RB RB RB WR WR WR WR      TE WR QB TE QB RB DEF
    RB-heavy folk rule       RB RB RB WR WR WR TE      QB WR RB WR TE QB DEF

    STRATEGY                     season   from r8-16   share   starts r8-16
    RUNBOOK committed              1984          684     34%             66
    RUNBOOK front + SS back        1963          664     34%             66
    starter-sum (1-16)             1886          677     36%             69
    RB-heavy folk rule             1870          650     35%             65
    ModelA front + SS back         1848          664     36%             67
    best-nine (Model A)            1613          429     27%             34
    best available by ADP          1442          278     19%             34

**The back half is a third of the season.** 34 to 36% of points for every decent
strategy, from nine of fourteen picks. The common claim that late rounds barely
matter is wrong here, and by a wide margin.

**What separates a good back half from a bad one is STARTS, not points.** The
strategies that score well get 65 to 69 starts out of their late picks; Model A
and best-available-ADP get 34 each. Model A stacks quarterbacks and you can
start one; ADP ignores positional fit and ends up with men who cannot enter the
lineup. A late pick is worth what it STARTS, and the rule that follows is
simply: take players who can get into your lineup, which in this league means
flex-eligible ones.

**And the starter-sum model's deficit is almost entirely in rounds 1-7.**

    RUNBOOK minus starter-sum:   front half 91    back half 7    total 98

Its back half is within seven points of the committed plan - noise. It loses in
the early rounds, taking five receivers in the first six picks where the
committed plan takes three backs. That is precise and it is good news: the model
is competitive exactly where it was built to add something, and loses where
Model A already works and where Justin said not to touch anything.

### But the halves do not compose

The obvious move - the committed plan's front with the model's back - does NOT
beat the committed plan. 1963 against 1984, ahead in 2 seasons of 5. Twenty-one
points on two thousand is inside the noise, so the honest reading is that they
are tied, not that the hybrid is worse.

The reason is worth keeping: **back-half value is not separable from the front
half.** The committed plan's late picks fill tight end and quarterback because
its early rounds spent nothing there. The starter-sum's late picks were chosen
against a receiver-heavy front that needed different things. Bolting one onto
the other breaks the complementarity that made each work.

That kills the tidy conclusion. There is no "use Model A early and the new model
late" - the halves were fitted to each other, and a draft plan is one object.

### Tight end slot, or another back/receiver? (2026-08-29)

The question sounds like starter-versus-bench and is not. This league starts
QB/RB2/WR3/TE/FLEX2/DEF, so with two flex slots a "backup" receiver walks into
the lineup. Both candidates fill a STARTING slot; the only question is which
slot is worth more, and that changes as the roster fills.

    PICK   ROUND  ROSTER SO FAR      best TE   best RB   best WR   take TE?
    7      1      1QB 1RB               33.3     114.3      94.1   no, by 81
    18     2      1QB 2RB               33.3     118.1      82.1   no, by 85
    31     3      1QB 3RB               32.9      54.5      93.7   no, by 61
    42     4      1QB 3RB 1WR           32.9      49.5      53.7   no, by 21
    55     5      1QB 3RB 2WR           33.8      52.9      45.9   no, by 19
    66     6      1QB 4RB 2WR           36.0      35.0      42.3   no, by 6
    79     7      1QB 4RB 3WR           33.4      35.2      28.9   no, by 2
    90     8      1QB 5RB 3WR           29.4      25.0      23.7   YES, by 4

**The tight end is worth a flat ~33 points at every pick, and depth decays past
it.** That is the whole shape. A tight end is not getting better as the draft
goes on - the plateau means the tight end at pick 90 is about as good as the one
at pick 7 - while the back or receiver you could take instead falls from 114 to
25. The crossover is where decay meets the flat line, and it lands at **round 8**.

So: no, do not take a starting tight end before adding depth. Not in round 6
(depth wins by 6), and not in round 7 (by 2, effectively a tie). From round 8
the empty slot is the most valuable thing left to fill.

**This is the third independent route to the same answer.** `TightEndTiming`'s
swap said waiting to 90 beat taking one at 79. The RUNBOOK already places the
tight end at round 8. And this marginal analysis, built on completely different
machinery, puts the crossover in the same place.

**Do not read the rows past round 8.** They run 8.8, then 35.6, then 12.2, which
the board cannot produce honestly - it only loses players, so the best available
tight end cannot improve. That is sampling noise and which specific tight end
happens to be on the board, not signal. The trustworthy part is rounds 1 through
8, where the trend is monotone and large.

## Defences in the simulation, without touching Model A (2026-08-29)

Three gates, all on the same condition - `scheduleRounds() > GAME_ROUNDS` - so
the nine-round game never sees a defence and the sixteen-round one always does:

- the BOARD: defences are added to the draftable pool
- the BALLOT: `positions()` returns five positions instead of four, so the
  search can branch on one
- `SelectionModel.features` gained a DEF starter slot, without which it threw
  the moment a defence reached it

**Model A is unchanged and no slower.** Plan `[RB, WR, RB, WR, WR, WR, TE, QB,
QB]`, 1812.8, p10 1784.4, byte-identical with the change stashed and unstashed.
43 seconds either way; the sixteen-round model costs 44. The branching factor
Model A was tuned with is untouched because it never reaches the wider ballot.

**A false alarm worth recording.** Model A read 1812.8 against the 1812.1
verified earlier today, which looked like the change leaking. It was not - the
same number appears with the change stashed. The projections themselves moved:
the Boris Chen feed failed during the lockdown, fell back to a stale cache, and
later succeeded and cached fresh. Worth knowing that the plan drifts slightly
as feeds refresh; 0.7 points on 1812 is immaterial.

**And the model still declines to draft one.** With defences on the board, on
the ballot, and in the objective - 32 in the projections, 17 on the board after
the ADP limit - the sixteen-round plan takes none. That is not a bug, it is the
0.277 predictability arriving as a decision: a drafted defence is worth about 9
points over a streamed one, so the model would rather have another skill player
and take whatever the wire offers.

Which is a stronger version of the RUNBOOK's rule. The RUNBOOK says take one
last; the model says the pick is barely worth spending at all. Both agree it is
never worth spending early, which is the only part that has been tested against
outcomes.

### Where the defence pick actually belongs (2026-08-29)

Every model gave an opinion; none had been tested. So force it: one plan,
thirteen picks held fixed, the defence slid through all fourteen slots, each
variant scored on real outcomes across five seasons. Only one pick's position
differs between rows, so the difference between them IS the cost of taking a
defence that early.

    AT   ROUND    mean          AT   ROUND    mean
    1    1        1769          8    8        1892
    2    2        1802          9    9        1911
    3    3        1842          10   10       1942
    4    4        1866          11   11       1961
    5    5        1844          12   14       1981
    6    6        1871          13   15       1984
    7    7        1831          14   16       1984

**Monotone, and it flattens at the end.** Later is better at essentially every
step, and rounds 14, 15 and 16 are indistinguishable at 1981/1984/1984. The
spread from first pick to last is 215 points a season, so this is not a free
choice - but among sensible placements, round 11 onward, it is worth 23 points.
Take it in the last two rounds and stop thinking about it.

**It converges with the top-four finding by a different route.** `DefenceTiming`
priced chasing a top-four defence - whose ADP puts it in rounds 9 to 12 - at
-52.7 points a season against the skill player passed over. This curve says
taking the defence at round 10 instead of round 16 costs 42. Two independent
methods, same order of magnitude, same direction.

The dips at rounds 5 and 7 (1844, 1831) are which specific players the plan
ends up with in those variants, not structure. The trend is what to read.

### Every model streams the defence - and it turns out they are right (2026-08-29)

With defences on the board, on the ballot and in the objective, five variants
drafted all sixteen rounds:

    MODEL                            defence at
    best-nine season totals          NEVER - streams one
    starter sum, 100 scenarios       NEVER - streams one
    starter sum, 400 scenarios       NEVER - streams one
    starter sum, 1200 scenarios      NEVER - streams one
    starter sum, 400 (other seed)    NEVER - streams one

Unanimous across objectives, scenario counts and seeds. None takes one early,
which is what would have been alarming; none takes one at all.

**That claim was untestable until now, and the reason is a fault in the
backtest.** `seasonPoints` scored an unfilled defence slot as ZERO, which
quietly assumed you cannot pick a defence off waivers. That is false - defences
are always available - and it made "never draft one" look catastrophic while
every model that could choose freely was doing exactly that.

Fixed: an unfillable defence slot is now STREAMED, at the wire rate
`WeeklyStarterValue` measures. And the models win:

    RUNBOOK committed         1998   (defence drafted at pick 14)
    committed, DEF streamed   2011   (that pick spent on a back instead)

**+13 points a season, and it is now the best strategy on the board.** But 13
points on two thousand is 0.6%, and streaming wins 3 seasons of 5, so the
honest reading is that streaming is AT LEAST AS GOOD as drafting one - not that
it is meaningfully better. What it does kill is any argument for spending a
pick on a defence, which now has no support from any direction: not the 0.277
predictability, not the placement curve, not the top-four test, and not this.

The streamed rate is computed from `WeeklyStarterValue.wireRates`, not typed in.
The first version hardcoded 8.7 read off another tool's output, which is the
same prose-drift fault this repo spent the day fixing - the moment the wire
calculation moves, a copied constant becomes a lie.

### Correction: streaming costs a roster spot (2026-08-29)

Justin's catch, and it was a real fault. The roster is sixteen - ten starters
and six bench - and fourteen picks plus two keepers fills it exactly. So taking
a defence off waivers means DROPPING somebody. The backtest was crediting a
streamed defence on top of a full roster, handing that strategy a player nobody
has.

Fixed: a roster with no defence now loses its last drafted man before scoring,
so both strategies field the same sixteen bodies.

    RUNBOOK committed         1998   1998     (defence drafted at pick 14)
    committed, DEF streamed   2011   2007     (before / after the roster cost)

The correction takes 4 of streaming's 13 points. It is small because the man
dropped is a round-16 pick worth almost nothing - which is itself the reason
streaming survives at all: the roster spot it consumes is the cheapest one you
own.

Streaming still leads, +9 a season, ahead in 3 seasons of 5. That is inside the
noise, so the conclusion is unchanged in substance and weaker in strength:
streaming is AT LEAST AS GOOD as drafting a defence, and nothing supports
spending a real pick on one. Take a defence last, or stream one and spend the
pick elsewhere - the evidence does not separate them.

### Correction: you must draft a defence (2026-08-29)

Justin again, and this one voids a row rather than adjusting it. **You need a
defence in week 1.** Fielding none is not a legal roster, so "never draft one"
was never a strategy - and the models' unanimous refusal to take one was
answering a question nobody can act on.

Worse, the rate that row was credited with is the TOP QUARTILE of undrafted
defences. That assumes you reliably land a good one after eleven other managers
have taken theirs, which is exactly backwards: the defences left on waivers
after a sixteen-round draft are the ones nobody wanted.

The row is kept, relabelled `[not legal] no DEF drafted`, because it still
measures something real - what a DRAFTED defence is worth over a wire-level one,
which is about 9 points a season. It is not an alternative to drafting.

**So the question was never draft-or-stream. It is only WHERE, and the
placement sweep already answered it:**

    round    1     3     6     8    10    11    14    15    16
    mean  1769  1842  1871  1892  1942  1961  1981  1984  1984

Draft one in the last two rounds, then stream by SWAPPING defences during the
season - which is an in-season activity and costs no pick at all. That is what
"streaming" actually means here, and nothing measured today argues against it.

### What the models got wrong, and why it is instructive

Five model variants unanimously declined to draft a defence. They were not
wrong about the VALUE - a drafted defence really is worth only ~9 points over a
wire one - they were wrong because nothing in the objective knows a lineup slot
must be FILLED. `V(R)` quietly fills an empty defence slot from the wire every
week, for free and forever, which is a rule of the model rather than a rule of
the league.

That is the same class of fault as the roster-spot omission: the objective
models points well and constraints badly. Any future work on it should start
there.

## There is no free wire (2026-08-29)

Justin's correction, and it is the deepest of the day. It is not about defences.

`fill()` credited every unfilled slot with wire points, at no cost, every week.
That is an unlimited supply of players nobody has. **Any player who fills a
lineup slot occupies one of sixteen roster spots** - the wire lets you UPGRADE a
spot, never ADD one. There is no stream that does not take up space.

The consequences ran through everything:

**It is why no model would draft a defence.** With the free wire removed, every
starter-sum variant drafts one:

    MODEL                          with free wire        without
    starter sum, 100 scen          NEVER                 pick 10 (round 10)
    starter sum, 400 scen          NEVER                 pick 11 (round 11)
    starter sum, 1200 scen         NEVER                 pick 9  (round 9)
    starter sum, 400 (other seed)  NEVER                 pick 13 (round 15)

**It is why "never draft a defence" scored so well.** That row falls from 2007
to 1850 once its free defence is taken away - 157 points, all of it phantom.

**And the model and the evaluator disagreed about it.** `PlanBacktest` scored an
unfilled slot as zero all along; the objective credited it to the wire. The
model was optimising for a world it was never scored in, which is a fault
serious enough to have invalidated every comparison between them.

Both now agree: no free wire anywhere.

### It did not rescue the model

    starter-sum POLICY    1731   (was 1751 with the free wire)
    RUNBOOK committed     1984

Aligning them made the policy slightly WORSE, and it remains 253 points a season
behind the committed plan. The correction was necessary and it was not the
missing piece.

There is a visible over-correction: three of four variants now take a defence in
rounds 9 to 11, which the placement sweep prices at about 40 points worse than
rounds 14-16. Zero for an unfilled slot is too harsh in the other direction - a
manager really can add a body off waivers, he just has to drop one to do it. The
truthful model is a swap, not a free add and not an impossibility, and neither
extreme is right.

That is the next thing to build, and it is the same lesson as the roster-spot
and must-draft corrections: **this objective models points well and constraints
badly.** Three faults in a row, all of them constraints, all of them found by
Justin rather than by the tests.

## The constraint layer, and one fix that failed (2026-08-29)

Three corrections in a row said the same thing - the objective prices points
well and constraints badly - so the floor under all of them is now in the
search: **you start one defence and one quarterback, so a second defence can
never play and a third quarterback is a wasted spot on a sixteen-man roster.**
Skill positions stay uncapped, because the flexes make a fourth receiver
genuinely startable.

Model A is untouched, byte-identical at 1812.8 / p10 1784.4 - its ballot has no
defence and nine rounds never reach three quarterbacks.

**It fixed something bigger than defences.** Model A extended to sixteen rounds
used to degenerate into six consecutive quarterbacks. It now reads

    RB WR RB WR WR WR TE QB QB RB RB RB RB RB

which is a plan rather than an artifact. The double-defence and double-
quarterback pathologies are gone from every variant.

### But removing the free wire over-corrects a GREEDY policy

With no free wire an unfilled quarterback slot looks like losing 320 points for
the season, so the greedy policy rushed to fill slots and opened every single
season with a quarterback at pick 7. That is not a constraint problem; it is a
lookahead problem. Greedy values the roster AS IT IS, and a half-built roster
with no free wire looks catastrophic rather than unfinished.

**A completion heuristic was tried and failed, badly.** Filling each trial
roster's empty mandatory slots with the best men still on the board was meant to
remove the panic. It made every candidate's roster nearly identical, masking the
marginal it was supposed to measure: the policy collapsed to two quarterbacks
then twelve running backs and scored **1055**, below drafting at random. Removed,
with the reason recorded in the file so nobody tries it again.

The policy stands at 1731 against the committed plan's 1984.

### Where this actually leaves the objective

The truth about the wire is a SWAP - not a free add, not an impossibility. A
manager can put any player in an empty slot, but only by dropping someone, so
each roster spot is worth at least wire level and there are exactly sixteen of
them. Neither extreme models that, and the greedy evaluator cannot represent it
at all without lookahead.

That is the honest state: the constraint layer is right and worth keeping, the
free-wire removal is right in principle and mis-serves a greedy policy, and the
model remains well behind the folk rules. **Nothing here changes the RUNBOOK.**

## The wire is a SWAP, and the bench is fungible (2026-08-29)

Justin's formulation, and it is the right one. It reconciles everything and my
removal of the free wire was an over-correction.

**The bench is fungible.** If a tight end goes down and no tight end is
rostered, you drop your least useful backup and add one off waivers the same
day. You never need positional COVER - you need spots you can convert. So a
rostered man is worth exactly what he EXCEEDS the wire by, which is what
`max(player, wire)` computes, and which is the whole value of a bench pick:

    bench value = P(he beats the wire) x the margin when he does

That is the formula, and the free-wire fill already implemented it. Turning it
off made an unfilled slot a permanent zero, which a greedy policy read as
catastrophe and answered by opening every season with a quarterback at pick 7.

**The real constraint is the BUDGET, not the week.** Sixteen spots, eight of
which must cover the starting positions, leaving eight for upside. Streaming a
position consumes one of those spots - which is why fielding a defence costs a
spot whether you draft it or swap for it. That is charged in the roster
accounting, where it belongs, rather than by pretending the wire does not exist.

Both halves of the point are now modelled: **the wire is reachable, and reaching
it takes up space.**

### It is the best the policy has ever scored

    starter-sum POLICY, no free wire (my over-correction)     1731
    starter-sum POLICY, original                              1751
    starter-sum POLICY, wire as swap + roster constraints     1859
    RUNBOOK committed                                         1998

+128 over the over-correction and +108 over anything before it, and the plans
read like plans again - no quarterback at pick 7, no stacking, a tight end in
the right region. Model A is byte-identical throughout at 1812.8 / p10 1784.4,
and the suite is green.

**Still 139 behind the committed plan**, and that gap is what remains. But the
direction of travel finally reversed: every previous change made the model worse
or left it flat.

### What the day's corrections were really about

Four of them - roster spot, must-draft-a-defence, no free wire, and now this -
and none was a bug in the search or the distributions. Every one was about what
a LEGAL ROSTER is: how many spots, which slots must be filled, and what it costs
to reach the wire. The objective prices points well and always priced
constraints badly, and the constraints turn out to be where the model's deficit
lived.

## Trying to make it draft-ready: how far it got, and where it stopped

Two hypotheses tested and one confirmed.

**Quarterback timing was NOT the error.** The policy takes a quarterback in
rounds 3-6 while the committed plan waits until 10, which looked like an obvious
fault. Forcing it later makes things worse:

    qbFrom=0   1848      qbFrom=7   1758
    qbFrom=4   1850      qbFrom=9   1673

**Forcing a defence at the last pick costs 11 points** (1859 -> 1848),
consistent with everything else measured today: drafting one and streaming one
are within noise of each other.

**The front half was the error, and pinning it helps:**

    free choice throughout          1859
    committed front, model back     1921
    committed front + DEF last      1912
    RUNBOOK committed               1998

Giving the model the committed plan's opening seven picks is worth **+62**, the
largest single improvement of the day. It is the best the model has scored.

### But it is still 77 behind, and the gap moved

Committed front plus model back scores 1921 against the committed plan's 1998,
so with the corrected objective the model's BACK half is now 77 points worse
too. It is behind in both halves - front by 62, back by 77. An earlier
decomposition had the back half within 7 points; that was measured under the
scoring that has since been corrected four times, and it does not survive.

**So: not draft-ready, and not by Tuesday.** The day moved it from 1731 at its
worst to 1921 at its best - real progress, all of it from constraints rather
than from the search or the distributions - and it is still comfortably behind a
plan Justin already has written down.

**Use the RUNBOOK.** `DraftNight` for rounds 1-7, the measured rules for 8-16,
`LiveLateRounds` as the adaptive read where it is competitive. Everything built
today that IS trustworthy is measurement, not optimisation: the weekly actuals,
the outcome distributions, the availability-scoring correlation, defence
predictability, the placement sweep, and this backtest - which is the thing that
kept saying no.

## Trying to beat the rules: the obstacle is n=5, and it is fixable

Three attempts, all failed, and the third explains the other two.

**Scarcity lookahead made it worse.** Greedy ignores that backs deplete faster
than receivers, which is exactly the edge the committed plan's RB-heavy opening
exploits - so scoring positions by what they will have LOST by the next pick
should have helped. Sweeping the weight:

    scarcity   0      1      3      8     20
    score   1859   1831   1803   1764   1772

Monotonically worse. The objective already prices scarcity implicitly, through
the roster the marginal is computed against, so an explicit term double-counts.

**Sequence search overfits, spectacularly.** Hill-climbing whole plans, fitted
on 2021-2023 and judged once on 2024-2025:

                             train       TEST
    RUNBOOK committed         1960       2054
    hill-climbed sequence     2021       1863
    difference                 +61       -191

It gained 61 on training and lost 191 on test, and the plan it found opens with
a TIGHT END at pick 7. That is what memorisation looks like.

### That is the real obstacle, and it is not the model

Fourteen positions fitted on three seasons has more free parameters than data.
The committed plan wins because it encodes priors drawn from far more football
than five seasons - and no amount of tuning on five seasons will beat priors
built on decades.

**So the path to beating the rules is data, not cleverness.** Checked:

    Sleeper season actuals        back to 2009      17 seasons
    Sleeper weekly actuals        back to 2015+     11 seasons
    FFC half-PPR ADP              back to 2018       8 seasons
    FFC standard ADP              back to 2009      17 seasons

Half-PPR ADP starts in 2018, but STANDARD ADP goes back to 2009 with 145-202
players a season. Draft order barely differs between formats above the flex -
the repo already measured Sleeper defaults and Sleeper ADP as interchangeable
proxies - so standard ADP is a usable stand-in for the early years, with the
substitution measured rather than assumed.

That is 8 clean seasons and up to 17 with a documented proxy, against the 5 in
hand. Triple the data is the difference between fitting a sequence and
memorising one.

**The next session's first job is the harvest, not the model.** Everything tried
today failed for the same reason, and more model work on five seasons will fail
the same way.

## Shrinkage: the model cannot improve on the plan at any threshold

The right method for a weak model against a strong prior is not to replace it
but to follow it and deviate only where the evidence pays for the deviation.
The committed plan IS a prior, built on far more football than five seasons.
So: give the plan's pick a head start of DEVIATE points, and let the model
overrule it only by beating that margin.

    deviate   1e9    200     80     30     10      0
    score    1998   1998   1998   1956   1746   1859

**At a large threshold it reproduces the committed plan exactly** - 1998, to the
point - which confirms the harness is right. And then every deviation the model
wants to make loses. There is no threshold at which it adds anything: 30 costs
42 points, 10 costs 252.

That is the most direct test yet of whether the model knows anything the plan
does not, and the answer is no, not one pick's worth.

The dip at deviate=10 scoring below deviate=0 is the composition finding again:
partial adherence breaks the plan's complementarity without replacing it with
the model's own coherence. A draft plan is one object; half of one is worse than
either whole.

### What is still worth having from it

At **deviate=80** the policy made ZERO deviations across all five seasons - it
scored 1998, the committed plan to the point. So that setting is the plan plus a
safety valve: it follows the written order, and would only override it for a
board unlike anything in five seasons. That is shippable in a way the free model
never was, because its downside is bounded by the plan itself.

### What could still beat it, and it is not modelling

Two routes remain untried, and only one is cheap:

**Imitation, not optimisation.** The rounds 1-7 committee already contains an
`ml-imitation` policy that scored near the top of the tournament. Learning to
reproduce plans that WORK, conditioned on board state, is a different problem
from optimising an objective - and it degrades to the plan rather than away from
it. That is the right shape for n=5.

**More seasons.** Sleeper actuals reach 2009 and FFC standard ADP reaches 2009;
half-PPR ADP only starts in 2018. Eight clean seasons, up to seventeen with a
measured format proxy. Everything tried today failed because fourteen positions
cannot be fitted on five seasons, and that is the only fix that changes the
arithmetic.

## Converging on the prior from outcomes alone (2026-08-29)

Justin's objection to the shrinkage experiment was right: starting a model FROM
the committed plan is not convergence, it is a prior with a model attached. So
this one is never shown the plan. It learns a small correction to the objective
from outcomes only, and we see where it lands.

The parameter count is the design. Fourteen free positions on three seasons
memorised them - the sequence search opened with a tight end at pick 7 and lost
191 out of sample. So four numbers instead: one multiplier per position on the
model's marginal, receivers pinned at 1.0. Coordinate ascent on 2021-2023,
judged once on 2024-2025.

    start   RB 1.00  TE 1.00  QB 1.00  DEF 1.00   train 1893
    pass 1  RB 1.30  TE 1.00  QB 1.00  DEF 0.60   train 2009

                                           TEST
    unweighted model                       1813
    fitted weights                         1730
    RUNBOOK committed (the prior)          2054

**It converged on the prior's DIRECTION.** Told nothing about the committed
plan, the fit moved to RB x1.30 and DEF x0.60 - upweight running backs,
downweight defences. That is the folk rule, rediscovered from outcomes by a
model that had never seen it. It is the strongest evidence produced today that
the rule encodes something real rather than habit.

**And the magnitudes still overfit.** +116 on training, -83 on test. Even four
parameters on three seasons is too many, which is the same wall everything else
hit.

### What this settles

The prior is not beating the model because the model is badly built. It is
beating it because five seasons cannot re-derive, to useful precision, what
decades of collective play already encoded. The direction is recoverable from
five seasons; the magnitudes are not.

That makes the data harvest the only route left worth taking, and it is now
well-specified: Sleeper season actuals to 2009, weekly actuals to 2015, FFC
standard ADP to 2009 with half-PPR from 2018. Eight clean seasons, seventeen
with a format proxy that must itself be measured rather than assumed.

At eight to seventeen seasons, four parameters becomes a comfortable fit and
fourteen becomes arguable. Until then, the honest ranking is unchanged:

    RUNBOOK committed        1998        <- use this
    starter-sum, best config 1921
    free-choice policy       1859
    best available by ADP    1442

## Why five seasons was never a problem for Model A

Justin's question, and the answer corrects something I implied. It is not that
Model A had more data. It is that **its unit of observation is a pick, not a
season.**

    LAYER                                            examples
    Model A choice model (who gets taken)                 693
    outcome distributions (games, scoring)               1466
    Model A objective: best nine of projections             0
    1-16 objective: weekly starter sum                      0
    a plan fitted against SEASON outcomes                    5

**Model A never fits anything at the season level.** Its objective has no free
parameters at all - the best legal nine out of projections is a DEFINITION, not
a fit - and the two layers it does learn see 693 picks and 1466 player-seasons.
Its projections come from outside entirely. Five seasons yields hundreds of
examples when the thing being learned is "who gets taken next".

Everything that overfitted today was fitted against whole SEASON outcomes, of
which there are five, because a season's roster score is ONE example. Fourteen
positions against five examples memorised them; four position weights against
five examples still overfitted.

### The correction this forces

I said the model loses because five seasons cannot re-derive decades of
knowledge. That is true of the TUNING and false of the model itself. The
unweighted 1-16 model fits nothing at the season level either - and it still
loses, 1813 to 2054 on the same holdout, and did better than the tuned version.

So its deficit is NOT overfitting. It is approximation error in the objective:
tier bucketing twelve players wide, a greedy search with no lookahead, and the
constraint faults Justin found four times over. Those are fixable by better
modelling, and they do not need a single extra season.

**Which changes the priority.** The data harvest helps only if the plan is to
TUNE something, and today's evidence says tuning is the part that fails. The
better route is Model A's: keep the objective parameter-free, learn only things
with pick-level or player-level examples, and fix the approximations - starting
with the greedy search, since Model A's own advantage over greedy is the
lookahead it has and the 1-16 policy does not.

## Projections as the centre, history as the risk (2026-08-29)

Justin's diagnosis, and it was the real defect. **The model was throwing the
projections away.** `WeeklyStarterValue` bucketed players into twelve-wide tiers
and handed each the tier's historical average, so a back projected 300 and one
projected 200 were the same player to it. Model A uses each player's exact
projection. The 1-16 model was therefore strictly LESS informed than Model A
despite carrying a risk layer - which answers his question: it lost to a fixed
plan because it knew less, not because the plan knew more.

Now the projection is the centre and history supplies only the shape around it.
A drawn historical season becomes a RATIO - how far that man landed from what
his slot promised - applied to THIS player's projection, with his games played
carried along so the measured availability-scoring correlation survives.

**A units bug surfaced immediately.** Players now carried projections while the
wire still came from historical actuals, and projections run lower, so every
defence and every deep tight end scored a marginal of exactly 0.0. The wire is
now the projection of the man at the replacement rank on the same board:

    QB21 18.5   RB61 3.1   WR81 4.5   TE19 6.7   DEF13 5.1  points per week

### It converges on the prior's opening

    starter sum, 400 scen   RB RB RB WR WR WR DEF WR TE WR QB TE TE WR
    starter sum, 1200 scen  RB WR RB WR WR WR DEF RB TE WR TE QB WR TE
    other seed              RB RB RB WR WR WR DEF WR TE TE WR QB WR TE

Three of four variants now open **RB RB RB WR WR WR** - the committed plan's
exact opening, arrived at from projections plus measured risk with no knowledge
of the plan. That is the convergence Justin asked for, and the position weights
experiment had already pointed at it (RB x1.30) before overfitting its
magnitude.

The quarterback also falls to rounds 11-12, because QB21 projects 18.5 a week
and a startable one is nearly free - which is what every other measurement today
said.

### What is still wrong: DEF at round 7

Three variants take a defence in round 7, which the placement sweep prices at
1892 against 1984 for round 16 - about 92 points too early. The cause is not
valuation, it is LOOKAHEAD: greedy sees a defence worth taking and cannot see
that an equally good one will still be there in round 16, because defences
barely decay. Model A has that lookahead; this policy does not.

That is now the single identified defect, and it is the same one behind the
QB-at-pick-7 behaviour earlier. **Not a data problem and not an overfitting
problem** - a search problem, fixable without another season.

### Honest limit on validating any of this

The backtest cannot see the improvement. Historical per-player projections do
not exist at the right vintage - only week-1 projections, which must not be
scaled to a season - so the backtest substitutes a smoothed per-rank curve and
scores 1818. The live model is now better specified than history can measure,
which is an uncomfortable but accurate place to be. **The RUNBOOK still stands
for Tuesday.**

## The Model-A-shaped rebuild: right shape, one unresolved disagreement

Justin's judgement was that the starter-sum work "deviated outside of what makes
sense", and he was right. That objective carried about ten hand-set choices and
was wrong in eight of them inside a day. Model A's carries none, because its
objective is a DEFINITION - the best legal lineup out of projections - which
cannot be wrong, only its inputs can.

`RiskDiscountedValue` keeps the definition and adds three measured numbers, not
ten judgement calls:

    value(player) = [ mean + trust x (projection - mean) ] x (17 - gamesMissed) / 17

- **games missed** per player from Draft Sharks, or his position's measured
  average (RB 3.1, WR 2.9, TE 3.0) where he is not in the file
- **trust** = how far a position's preseason ranking has historically predicted
  the season: RB 0.632, WR 0.639, QB 0.530, TE 0.509, DEF 0.277. A projection
  you cannot believe is regressed toward its positional mean
- **an unfilled slot** scores at the replacement-rank player's own discounted
  projection, never zero. Zero says a slot you have not drafted for stays empty
  all season, and it is what made the first version reach for a defence

No sampling, no tiers replacing projections, no wire subsystem, no scenario
count. Model A's search, ten slots, sixteen rounds.

### It still takes a defence in round 7, and that disagrees with measurement

    RB WR RB WR WR WR DEF TE QB QB TE TE RB RB

The placement sweep, on real outcomes, prices round 7 at 1892 against 1984 for
round 16. The model disagrees with it by about 92 points and I have not resolved
why.

The likely cause is not the objective but the OPPONENT model. `LiveLateRounds`
reports the best available defence surviving to the next pick only 3% of the
time, so the search believes defences are about to be taken and reaches for one.
`SelectionModel` was never trained on defence picks - the board only started
carrying them this afternoon - so its probabilities for them are extrapolation
from coefficients fitted on skill positions. That is a real and specific
suspicion, and it is untested.

**So: right shape, three measured parameters instead of ten guesses, and one
open disagreement with a measurement I trust more than the model.** Not
draft-ready, and the RUNBOOK is unchanged for Tuesday. The next step is to check
whether the choice model's defence probabilities are sane, which is a contained
question with a clear answer.

## The model reads the league's defence habit off its own drafts (2026-08-29)

Justin: "my league picks late for defenses, not something you should note, but
something your models should pick up on." Exactly right, and it was the bug.

`SelectionModel` filtered defences out of its training observations, so the
choice model had never seen a single defence picked and could not know when they
go. Measured from this league's five completed drafts:

    round 10  #                 (1)
    round 11  ####              (4)
    round 12  ####              (4)
    round 13  ########          (8)
    round 14  ############     (12)
    round 15  #############    (13)
    round 16  ################ (16)

    zero before round 10; 16% before round 13; the mass in 14-16

Three blind spots, all now gated on `scheduleRounds() > 9` so Model A's fit is
untouched:

1. defences excluded from the training board
2. defences excluded from the recent-picks feature, which is how the model sees
   positional runs
3. **`TRAIN_ROUNDS = 13`** - the subtle one. 41 of 58 defences go in rounds
   14-16, so a model trained to round 13 saw only the seventeen EARLIEST
   defences ever taken and concluded they go early, which is backwards. The
   training window now runs to 16 when the schedule does.

The third had to be applied to `BoostedSelectionModel`, not `SelectionModel` -
the boosted fit is the one production uses, and patching the other did nothing.

**The result:**

    defence survival to my next pick   3%  ->  51%
    LiveLateRounds verdict             lean take  ->  wait - he keeps
    16-round opening                   RB WR RB WR WR WR  ->  RB RB RB WR WR WR

The opening is now the committed plan's exactly, reached from projections, a
risk discount, and the league's own draft history - with the plan never
consulted. Model A byte-identical at 1812.8 / p10 1784.4. Suite green.

**Still open:** the `Draft16` SEARCH continues to place a defence at pick 7 even
though the live tool now says wait. Those are different code paths - the search
scores a whole plan through rollouts, the live tool scores one pick - so the
remaining fault is in the rollout tail policy rather than in the choice model or
the objective. Contained, and next.

## What predicts, what does not, and what to do about each (2026-08-29)

One reliability number per position was too coarse. Measured by position AND
tier, with the shape of the error alongside:

    POS  TIER         n  predicts     bias   spread     bust     boom
    RB   1-12        60     0.037     0.99     0.35       8%       7%
    RB   13-24       60     0.335     1.00     0.46      17%       7%
    RB   25-36       60    -0.020     1.02     0.49      15%      18%
    RB   61-72       57     0.200     1.01     0.94      37%      28%
    WR   1-12        60     0.357     0.99     0.33       8%       7%
    WR   61-72       60    -0.090     1.03     0.59      17%      23%
    TE   1-12        60     0.319     1.01     0.35       5%      12%
    QB   1-12        60     0.394     0.99     0.32      10%       0%
    DEF  1-12        60     0.126     1.00     0.23       0%       0%
    DEF  13-24       59     0.127     1.01     0.25       2%       3%

**Within a tier, the board is nearly blind everywhere.** 0.04 to 0.39, and
RB1-12 is 0.037 - at pick 7 the board cannot tell the top twelve backs apart at
all. Yet position-level predictability is 0.63, which means the TIERS are
ordered correctly and the ordering INSIDE them is mostly noise. **The tier is
the unit of decision; the player within it is nearly a coin flip.**

**That corrects an earlier diagnosis of mine.** I told Justin the tier bucketing
was throwing away the projections and making the model less informed than Model
A. The principle was right, but the measurement says the discarded information
was largely noise - which is why the per-rank version scored 1818 against the
bucketed 1859, no better. The bucketing was not the deficit.

**Failure looks different at each position, and shrinkage only fits one case:**

- **DEF: spread 0.23-0.25, bust 0-2%, boom 0-3%.** Not merely unpredictable -
  nearly IDENTICAL. Every defence returns close to its billing and its billing
  is close to every other's. That is the real argument for taking one last: not
  that you cannot pick the good one, but that there is barely a good one to
  pick.
- **QB 1-12: 10% bust, 0% boom.** No upside whatever, only downside. An elite
  quarterback cannot pay off, he can only fail to. Never reach.
- **TE 1-12: 5% bust, 12% boom.** The only tier in the table where upside beats
  downside more than two to one. If any early reach is defensible it is this
  one - which sits oddly beside the finding that TE should wait until round 8,
  and is worth resolving rather than papering over.
- **Spread grows sharply with depth** - RB 0.35 at tier 1 against 0.94 at tier
  6. Late picks are lottery tickets by measurement, not by folklore.

**How to account for each, then:** shrink hard WITHIN a tier, since that
ordering is noise; keep tier-level differences, since those are real; and
handle skew separately from spread, because averaging a 12%-boom tight end and a
0%-boom quarterback the same way destroys the only reason to take the first.

## The round-4 bust against the round-10 boom, measured (2026-08-29)

Justin: how can those two be modelled together, when whether the round-10 back
ever STARTS depends on what the round-4 back did?

They need no model. It is a joint question - both outcomes must be realised in
the same season for the comparison to mean anything - and five seasons of real
outcomes contain both. Every early-tier player against every late-tier player at
the same position, within a season:

    POS  EARLY     LATE      pairs  late wins  avg margin   value
    RB   1-12      13-24       720        20%          76    15.6
    RB   1-12      37-48       720        10%          63     6.1
    RB   13-24     25-36       720        42%          74    31.3
    WR   1-12      13-24       720        32%          61    19.2
    WR   13-24     25-36       720        40%          58    23.4
    TE   13-24     25-36       660        37%          44    16.2

**Superseded by the smoothed version below** - twelve-wide buckets produced
exactly the jitter Justin predicted, where a round-10 back could read 10%
between a round-9 at 8% and a round-11 at 13%. That is chunking, not football.

**The finding that matters more:** RB 13-24 against RB 25-36 is **42%** - nearly
a coin flip. Your second back and a round-six back are almost interchangeable.
Receivers are the same, 40%. But tier ONE is genuinely different: only 20% of
tier-two backs beat a tier-one back.

So the shape of the position curve is a cliff and then a plateau, not a slope.
One elite back or receiver is worth reaching for; after that the tier-two
versus tier-three distinction is close to a coin toss and should not drive a
pick. That is the measured version of why RB-heavy openings work and why the
middle rounds feel interchangeable - they nearly are.

It also explains the within-tier blindness from the profile above, arriving from
the other direction: if a tier-two back beats a tier-three back only 58% of the
time, then no board could reliably tell them apart, because they are not
reliably different.

### Smoothed: the crossover as a curve, not chunks (2026-08-29)

Justin's objection to the bucketed table was right - twelve-wide disjoint bins
over five seasons let adjacent depths leapfrog on sampling noise, and the bin
edges are arbitrary. Every query now pools pairs within +/-8 ranks, so the
curve has to fall smoothly or the wobble is real:

    RB   later man    vs RB6 (your RB1)      vs RB18 (your RB2)
                    wins margin value      wins margin value
         RB24         20%     65  13.0       37%     74  27.1
         RB30         19%     58  10.8       36%     71  25.5
         RB36         16%     56   9.1       34%     67  22.6
         RB42         13%     56   7.0       26%     64  16.6
         RB48         11%     58   6.2       25%     59  14.4
         RB54          8%     50   4.3       20%     54  11.0
         RB66          6%     50   3.2       14%     54   7.6
         RB72          4%     50   2.1       11%     44   5.0

It falls monotonically now, and that is the point - a curve that has to be
smooth cannot hide noise as structure.

**What it says, read as a curve.** The margin barely moves with depth - a back
who beats your RB1 does it by 50 to 65 points wherever he was drafted. What
collapses is the PROBABILITY: 20% at RB24 down to 4% at RB72. So a late pick is
not a smaller prize, it is the same prize at longer odds, which is the exact
shape of a lottery ticket and the reason bench value is an option rather than an
asset.

**And your second starter is the one who gets replaced.** Every value against
RB18 is roughly double the same row against RB6 - 27.1 against 13.0 at RB24.
Bench picks do not threaten your best player, they threaten your second and
third, which is where the flex actually turns over.

The rank-to-round labels are approximate (rank / 2.4) and should not be read as
exact; the ranks themselves are the measurement.

## Nightly runs, and a footgun caught (2026-08-30)

**The trap first.** The risk objective's first backtest came back scoring the
committed plan EXACTLY in all five seasons - 2035, 1654, 2191, 1960, 2148 - and
choosing its precise sequence five times running. That was not the objective
agreeing with the plan. `DEVIATE` defaulted to 1e9, meaning "never leave the
committed plan", so the run replayed the prior back at itself. Any experiment
that forgot the flag would silently measure the prior and look like a triumph.

The default is now 0 - prior never consulted - and opting INTO it is the
deliberate act. It was caught only because the result was too perfect, which is
not a reliable detector.

**The risk objective, actually measured:**

    starter-sum POLICY (risk objective)   1885   +443 vs ADP   5/5
    RUNBOOK committed                     1998   +556          5/5

1885 is the best any model has scored - ahead of the starter sum's 1859 and the
weighted variants - and still 113 behind the plan. Its plans now vary by season
rather than repeating, which is what an adaptive policy should do.

**The rest of the nightly:**

- **DEF-at-7 survives 300 trials** in `Draft16`, so it is not sampling noise.
  But the defence SURVEY at 200 trials puts it at pick 8 in three of four
  variants and pick 11 in the fourth - later than before the choice model
  learned about defences, and moving the right way.
- **The placement sweep is unchanged**: best at pick 13 of 14, worst at pick 1,
  spread 215 points. The measurement the model still disagrees with.
- **Lockdown green**: 53 smoke checks passed, 0 failed; KeeperAudit 24/24; Model
  A byte-identical at 1812.8 / p10 1784.4.

**Where that leaves the whole effort.** The Model-A-shaped objective is the best
model built - a definition plus three measured numbers, 1885 - and it is still
113 short of a plan written from folk knowledge. The remaining disagreement is
concentrated in one place, defence timing, where the model says round 7-8 and
five seasons of outcomes say round 14-16.

## Defence at pick 7 is a modelling failure - one cause found, not the cause

Justin is right that this is a bug rather than a disagreement, and it should be
treated as one. Two candidate causes checked so far.

**Ruled out: the rollout tail policy.** `bestByPosition` does not filter, so the
greedy tail can and does take a defence. Rollouts are not leaving the slot empty.

**Found and fixed, but not the culprit: false scarcity.** `ADP_LIMIT = 250` cut
the draftable board to **17 defences of 32**. Twelve teams chasing seventeen
defences is a shortage that does not exist, and reaching for one early is
rational under it. Defences are now exempt from the limit and the board carries
all 32.

That was a real bug and worth fixing. It did not move the pick: the plan is
still `RB RB RB WR WR WR DEF ...`, so scarcity was not what was driving it.

**What is left to check, in order:**

1. Print the actual marginals at that decision - what the objective says a
   defence, a tight end and a receiver each add at pick 79 with that roster.
   Everything so far has been inference about those numbers; nobody has looked
   at them.
2. The `unfilled` replacement values. DEF reads 84.8 and TE 54.4, and if the TE
   figure is too HIGH then filling the tight end slot looks cheap and the
   defence wins by default. Both come from the replacement-rank player's
   discounted projection and neither has been sanity-checked against what a
   round-16 defence or a round-8 tight end actually returns.
3. Whether the shrinkage interacts badly: defences shrink toward a mean that is
   itself computed over only 32 players, while receivers shrink toward a mean
   over 200.

Model A remains byte-identical at 1812.8 / p10 1784.4 and the suite is green
throughout. **Nothing here reaches Tuesday's tooling.**

## The instrument, not the model: what a gap has to be worth (2026-08-30)

Every ranking in this document above rests on five numbers per strategy - five
seasons, drafted from seat 7, against one fixed sequence of opponent picks. The
per-season spread is around 200 points. Nobody had ever asked what that makes
the error bar, and the answer is that most of the day's findings were inside it.

`PowerBacktest` is the answer. It is a measuring instrument and proposes no
strategy at all.

### What it changes

**Twelve seats, not one.** The same season drafted from twelve slots is twelve
largely-distinct rosters. Slot 7 stays reportable on its own because it is
Justin's seat.

**Opponents that vary.** The other eleven no longer take strictly the best
available by ADP forever. They draft from a board perturbed by `PickDisplacement`
- this league's own residuals, fitted on 814 real picks - so a strategy meets
many rooms instead of one. The perturbation reproduces the league's known
habits without being told them: a top-100 player moves 13.4 selections on
average, and the first quarterback goes at 33 against 19 on the flat board, a
14-pick delay against the +16.4 offset the model was fitted with.

**Common random numbers.** The opponent board for a world is drawn ONCE and
handed to every strategy, so all comparisons are paired.

**Errors clustered on season.** This is the part that cuts against the tool's
own headline. Twelve slots times eight worlds is 480 draws but NOT 480
independent observations: every draw inside a season is scored on the same
realised football. The season is the unit of independent randomness and there
are still five. The naive independent-draws error is 4 to 8 points; the honest
clustered one is 10 to 73. Reporting the first would have been a worse lie than
the one being fixed.

### The number

    significance bar (95%, two-sided)     94 points   [range 28 - 204]
    detectable at 80% power              125 points   [range 37 - 273]
    the old instrument's bar             217 points

**A challenger must beat the RUNBOOK by about 125 points before we are entitled
to believe it.** The old design could not see anything under 217. Every gap this
repo has ranked strategies on - 30 to 150 points - sat inside the old bar, and
most sit inside the new one.

### The re-ranking

    STRATEGY                    mean   vs RUN  SE(seas)   95% bar     wins
    starter-sum (1-16)          1914      -93      44.0       122      30%
    RUNBOOK committed           2007       +0       0.0         0        -
    RB-heavy folk rule          2046      +39      26.5        74      60%
    RUNBOOK front + SS back     2008       +1      10.2        28      51%
    ModelA front + SS back      1974      -33      33.7        94      41%
    [not legal] no DEF drafted  2017       +9      10.0        28      60%
    best available by ADP       1426     -581      63.3       176       0%   REAL
    starter-sum POLICY          1910      -97      73.3       204      37%
    best-nine (Model A)         1714     -293      39.4       109       4%   REAL
                                                            [out of domain]

`best-nine (Model A)` is Model A run past round 7, outside its domain: its
objective is the best legal nine, two keepers plus seven picks fill those slots
exactly, and from round 8 it is indifferent - a `DraftPlanner` run shows the
value pinned at 1812.8 for every pick from the sixth onward. It is scored
because its error bar is a real reading on the instrument, not because it is a
fair entrant.

**What survives.** Exactly one ordering among the plausible strategies: they all
beat drafting best-available-by-ADP, by 500-600 points, overwhelmingly. Nothing
else clears its bar. The RUNBOOK, the RB-heavy folk rule, the two spliced plans,
and the starter-sum policy are one statistically indistinguishable tier.

**What was noise.** The starter-sum sequence's 98-point deficit is -93 +/- 44,
inside the bar. The policy's "-179 points a season, ahead in 1 of 5" becomes
-97 +/- 73 across 480 draws - not significant, and its five-season win count was
five coin flips. The 62-point gain from giving the model the committed front,
"the largest single improvement of the day", is smaller than the error on either
row. The RB-heavy folk rule, reported 114 points BEHIND the RUNBOOK, comes out
39 ahead - a 153-point swing from changing nothing but the seat and the
opponents, which is the cleanest demonstration available that the old design was
measuring luck.

### Why more slots will not save us

    STRATEGY                 sd(season)   sd(draw)     SE now   SE floor
    starter-sum (1-16)               97        163       44.0       43.4
    RB-heavy folk rule               58        115       26.5       26.0
    starter-sum POLICY              163        164       73.3       72.9

`SE floor` is what the error would be with infinitely many slots and opponent
worlds on these same five seasons. It is already within a point of `SE now`.
Raising the worlds from 8 to 32 moves the starter-sum error from 44.0 to 43.8.
**The slot and opponent axes are exhausted.** What is left is seasonal, and only
seasons fix it:

    seasons        5      8     10     14     17
    95% bar      104     70     60     48     43

This is the same conclusion the n=5 section reached from the other direction,
now with a price on it: the harvest from five seasons to fourteen roughly halves
the bar. It does not get below ~45 points, so a plan that is 30 points better
than the RUNBOOK is not provable with any amount of football that exists.

### Two things the instrument cannot see

**A bias, by construction.** It measures how big a gap must be to beat noise. A
systematic error moves every row together and is invisible to it. One is
confirmed and live: Sleeper's `pts_half_ppr` pays four points per passing
touchdown while this league pays six, understating every starting quarterback by
55-66 points a season. `LeagueActuals` is the switch and `PlanBacktest.board`
grades through it, so this tool follows it automatically - `PowerBacktest.GRADER`
is deliberately NULL, because a non-null default here would silently overrule
that toggle and re-impose its own scoring, which is the `-Pdeviate` fault in a
new hat. Every number in this section was measured with the switch OFF, and the
whole table has to be rerun when it flips.

**Its own opponent model.** The absolute level moves a lot with `-Pjitter`
(RUNBOOK scores 1934 at 0.5, 2007 at 1.0, 2136 at 2.0) because sloppier rooms
leave better players on the board. The ORDERING is stable across that sweep,
with one exception: at jitter 2 the starter-sum sequence's deficit becomes
significant. Read the level as an artifact and the ordering as the finding.

### Guards

The tool checks itself on every run: at jitter 0 and seat 7 it IS the old
harness, so all nine strategies and the adaptive policy must reproduce their old
single-slot scores exactly, and it throws if they do not. That is deliberate
protection against the `-Pdeviate` class of fault, where a default quietly turned
a backtest into a replay of the thing it was testing. There is no `-Pdeviate`
here and nothing this tool runs is ever shown the committed plan.

Seasons are discovered by globbing the ADP files, never listed, so the harvest
widens every degree of freedom without a code change.

    ./gradlew run -Pmain=PowerBacktest -Pkeepers=Tuten,Purdy -q

Around 8 seconds on 8 idle cores; it was measured at 23-39 s while several other
agents were building concurrently. `-Pseeds` sets the worlds, `-Pjitter` the
opponent chaos, `-PnoPolicy=true` drops the only expensive strategy.

Model A remains byte-identical at 1812.8 / p10 1784.4, plan
[RB, WR, RB, WR, WR, WR, TE, QB, QB]. Nothing here reaches Tuesday's tooling.

## Every scoring rule, checked: two more mismatches, and the fix is off (2026-08-30)

`ScoringAudit` found that the backtest grades quarterbacks at four points a
passing touchdown while the league pays six. `pts_half_ppr` is a single
precomputed number, so that could not be the only place it disagrees.
`ScoringRuleAudit` walks all 34 categories in the league's `scoring_settings`
against what that field measurably applies, and prices each gap for a starter.

### What "standard" means, established rather than assumed

Sleeper's advertised default for a new league and the arithmetic inside
`pts_half_ppr` are two different things. So the audit first REBUILDS the
published field from raw components using `LeagueScoringSettings.halfPprFeed()`
and reports how many real stat lines come back exact:

| season | skill lines exact | defence lines exact |
|--------|-------------------|---------------------|
| 2021   | 594 / 603 (fumbles charged) | 32 / 32 |
| 2022   | 487 / 578 | 30 / 32 |
| 2023   | 534 / 544 | 30 / 32 |
| 2024   | 545 / 555 | 31 / 32 |
| 2025   | 572 / 574 | 30 / 32 |

That reconstruction is the evidence for every "standard" value below, and it
turned up a fact worth knowing on its own: **the feed did not hold still.** In
2021 `pts_half_ppr` charged -1 for every fumble; from 2023 it charges none;
2022 is split between the two. A season total is not a stable unit across the
harvest, which is the strongest argument for scoring outcomes from components.

### The three mismatches

| rule | league | standard | moves a starter, per season |
|------|--------|----------|------------------------------|
| `pass_td` | 6 | 4 | **QB +59.1** |
| `fum` | -1 | 0 | QB -7.5, RB -2.2, WR -0.9, TE -0.7 |
| `pts_allow_14_20` | 1 | 0 | **DEF +4.7** |

Every other category agrees exactly. `pass_yd` and `rush_yd` look different at
machine precision only - Sleeper serves scoring settings as 32-bit floats, so
0.04 arrives as 0.03999999910593033.

Net effect on a starting player-season, published field against league scoring,
top 12 QB / 24 RB / 36 WR / 12 TE / 12 DEF pooled over five seasons:

| pos | as graded | league | gap | of which rules | drift |
|-----|-----------|--------|-----|----------------|-------|
| QB  | 331.3 | 384.7 | **+53.4** | +51.6 | +1.8 |
| RB  | 223.0 | 221.4 | -1.6 | -2.2 | +0.6 |
| WR  | 192.9 | 192.3 | -0.6 | -0.9 | +0.3 |
| TE  | 153.8 | 153.2 | -0.6 | -0.7 | +0.2 |
| DEF | 150.5 | 155.2 | **+4.7** | +4.7 | +0.0 |

"drift" is the part the rules do not explain - the 2021/2022 fumble vintage.

### Yes, DEF is affected, and it is a level shift not a re-ranking

The one defensive mismatch is worth +4.7 a season to every defence, because
holding a team to 14-20 points is a common outcome and the league pays 1 for it.
Per season the gap runs 3.8 to 5.5 on average, and the top defence is unchanged
in all five seasons; between 0 and 4 of the top twelve swap adjacent places. So
**what a defence is worth against a receiver moves; which defence to want does
not.** Every finding about when to draft a defence was made about a DEF pool
priced ~5 points a season light - real, and small next to the gaps that argument
turned on.

Note the projection side is NOT corrected and should not be: `SleeperProjections
.scoreStatLine` falls back to `pts_half_ppr` for a defence because the
projection feed publishes only a stub for one (no `def_td`, no points-allowed
bands), so there is nothing to rescore from.

### The corrected path, and why it is off

`LeagueActuals` is the league-scored grader: `seasonPoints`,
`seasonDefencePoints` and `weeklyPoints` dispatch on `-PleagueScoredActuals`,
which **defaults to the old pts_half_ppr behaviour**. Off, they return
byte-identical numbers to the `HistoricalActuals` / `WeeklyActuals` calls they
replaced - `PlanBacktest` still prints RUNBOOK 1998 and Model A still lands on
1812.8 / p10 1784.4. `PlanBacktest.board` and `OutcomeDistributions.load` both
grade through it, so the roster scoring and the streamed-defence price move
together; the wire rate is cached per flag value because it is denominated in
whatever the pool is scored in (8.7 a week feed-scored, 9.0 league-scored).

Anything that grades outcomes should call the dispatchers rather than the feed.
That includes new seasons: one switch then moves the whole harvest.

### What flipping it changes: nothing about the ranking

`ScoringImpactReport` scores everything both ways in one process, and checks
that both scorings draft the IDENTICAL roster in every season for every strategy
- so a row's change is a pure re-measurement of one fixed roster, not a
different plan.

| strategy | as graded | corrected | change |
|----------|-----------|-----------|--------|
| RUNBOOK committed | 1998 | **2033** | +35 |
| RUNBOOK front + SS back | 1977 | 2019 | +41 |
| starter-sum (1-16) | 1900 | 1942 | +42 |
| RB-heavy folk rule | 1884 | 1918 | +34 |
| ModelA front + SS back | 1862 | 1903 | +41 |
| best available by ADP | 1442 | 1463 | +21 |

**The order is unchanged, top to bottom.** The RUNBOOK keeps first place. But
its "lead" was never one: paired season by season under the corrected scoring it
beats `RUNBOOK front + SS back` by 15 points with a paired standard error of 77,
losing 2 of 5 seasons. That is a tie, and it was a tie before the fix too.

### The bias was real and pointed the way it was said to

Sliding the RUNBOOK's first quarterback from round 10 to each earlier round
(swapping him with whoever held that pick, so the fourteen positions never
change) shows the fix is worth +35 to the committed round-10 shape and +51 to
+57 to shapes that take one in rounds 1-3. Taking a quarterback early gains
~21 points MORE from the fix than taking one late - exactly the direction a
missing two points per passing touchdown predicts, since an early quarterback
throws more of them.

It still does not move the answer. The best round is 3 under both scorings, and
round 3 beats round 10 by 52 points a season with a paired standard error of 54.
The whole timing curve spans 78 points and the noise on any one comparison is
the same size.

    ./gradlew run -Pmain=ScoringRuleAudit -q
    ./gradlew run -Pmain=ScoringImpactReport -q
    ./gradlew run -Pmain=PlanBacktest -PleagueScoredActuals=true -q

### One row in the table that is not evidence

`best-nine (Model A)` runs Model A over all fourteen picks. Model A optimises
the starting NINE, which two keepers plus seven picks already fill, so from
round 8 its objective cannot tell one position from another and its trailing
quarterbacks are an artefact of a question outside its domain. Read `ModelA
front + SS back` instead. The row stays because other work is running against
it.

## The committed plan is a plateau, not a peak (2026-08-30)

`ShapeSensitivity` maps the neighbourhood around the RUNBOOK's fourteen-slot
shape by re-scoring perturbations on **PlanBacktest's own scorer** - the same
code, not a copy - so nothing here can quietly disagree with the 1998 it is
testing. `data/shape-sensitivity-2026-08-30.txt` is the run.

**The tie band is 125, and that choice is the whole result.** Not the naive
95-point standard error of a five-season mean: 94 is bare significance and 125
is what this design detects four times in five. Declaring ties at 95 would call
a real difference a tie one time in five.

**Every single slot is free.** All fourteen, exhaustively, each of the five
positions:

    of 52 legal single-slot changes, 52 tie the committed plan and 0 are
    clearly worse or better

The worst legal single-slot swap in the entire plan costs **115 points** -
inside the band. The only slot with no alternative is round 16, and that is
legality, not value: you must own a defence. All eight real adjacent swaps tie
too, and the plan sits at the **50th percentile of its own adjacent swaps** -
the literal median of shuffling its neighbouring picks.

    FAMILY                                          legal    tie tie rate plan's %ile
    single-slot changes (exhaustive)                   52     52   100.0%      76.9
    adjacent swaps (exhaustive)                         8      8   100.0%      50.0
    two-slot changes (exhaustive)                    1300   1068    82.2%      89.8
    reshuffles of the committed budget (sampled)     9991   2371    23.7%      99.2
    all budgets (sampled)                            5600    103     1.8%      99.9
    plans a human would write (sampled)              9999   1065    10.7%      99.8

The last row is the honest denominator - at least one QB, two RB, three WR, one
TE, exactly one defence, defence no earlier than round 10. **1,065 of 9,999
plans a thinking person might have written are indistinguishable from the
committed one.** The plan is at the 99.8th percentile of that family and
simultaneously tied with a tenth of it, which is the definition of a plateau:
the top tenth of the plan space cannot be ranked by five seasons.

### The three contested decisions, priced

Cost curves, moving one man through every round (section 2 of the run):

- **Tight end.** Only round 2 (-126) escapes the band. Rounds 1 and 3 through
  16 all tie, including the round-7 placement the RUNBOOK argues against
  (-115) and the round-8 one it argues for. The curve is also non-monotonic -
  round 7 costs more than rounds 4, 5 and 6 - which is the signature of noise,
  not of a decision. **Dropping the starting tight end entirely scores +11.**
- **Defence.** The one decision with real content. Rounds 1, 2, 3, 5 and 7 are
  all outside the band (-214, -182, -142, -139, -153); from round 10 on it is
  free (-42, -23, -3, 0, 0). Individual rounds wobble, but the aggregate is
  unambiguous: a defence in the first seven rounds costs on the order of 110 to
  215 points, and after round 10 the round does not matter. This reproduces
  `DefenceRound` **exactly**, which is a free cross-check - two independently
  written tools building the same fourteen sequences.
- **Second quarterback.** Free everywhere. Every one of the fourteen placements
  ties, and **dropping him entirely costs 12 +/- 10** - nothing. Consistent with
  him being a keeper stash whose value is next season's, which this backtest
  cannot see at all.

### The plan's win over the models does not survive a paired error bar

`PlanBacktest` prints the means but not the paired error, and every strategy
drafts the same five boards, so the difference is paired and much sharper:

    STRATEGY                       mean    worst   vs plan  paired se   beats
    starter-sum (1-16)             1900     1733       -98        131     2/5
    best-nine (Model A)            1627     1376      -371        100     0/5
    RB-heavy folk rule             1884     1734      -114         63     1/5
    RUNBOOK front + SS back        1977     1819       -20         67     2/5
    ModelA front + SS back         1862     1771      -136         78     1/5

**Only Model A is actually beaten.** The folk rule's -114 and the starter sum's
-98 are inside their own error bars. "The committed plan beats every model we
have built" is true of the point estimates and false of the evidence. The
metric agent reached the same place from the other side: varying draft seat and
opponent world flipped the folk rule from 114 behind to 39 ahead, a 153-point
swing from changing nothing about the plan.

### What is actually load-bearing

Two things, and neither is a sequence:

1. **Draft a defence, and draft it late.** Rounds 10 to 16, anywhere.
2. **Open with backs and receivers.** 92.5% of the tied plans take RB or WR in
   round 1. But only 40% of them take two backs in rounds 1-3, and the tie set
   averages 5.4 WR against 3.8 RB - so "RB-heavy" specifically is NOT what the
   tie set has in common, and the RUNBOOK's `RB RB RB` opening is one member of
   a set that mostly does something else.

Everything else in the fourteen slots - which of RB or WR in any given round,
the tight end's round, the second quarterback's round and existence - is
decoration this project has been treating as a decision.

**Caveat that cuts against the sharpest finding here.** PlanBacktest scores the
fourteen DRAFTED men only; the keepers are absent. Every shape has to supply its
own starting quarterback, and Justin does not - Purdy is kept at round 13. The
single most load-bearing slot in the grid before the band was widened was the
round-10 QB (115 points), and that is measuring a hole his real roster does not
have.

**Is it worth trying to beat 1998? No** - not on five seasons. Roughly a
thousand plans tie it, no perturbation is distinguishable from it, and the one
shape that scored clearly above (2097, `RB RB QB WR WR QB RB TE WR RB WR DEF WR
TE`) is the maximum of ~27,000 draws and won 4 of 5 seasons. It is a selection
artifact, not a candidate, and it is recorded here so nobody re-finds it and
believes it. **Use the RUNBOOK on Tuesday** - not because it is the best plan,
but because it is inside the plateau and the plateau is what the evidence
supports.

Model A remains byte-identical at 1812.8 / p10 1784.4 with plan
[RB, WR, RB, WR, WR, WR, TE, QB, QB]. `PlanBacktest` was refactored (its drafting
loop split into `draft()` so a caller can ask WHO a shape drafted) and prints
byte-identical numbers. **Nothing here reaches Tuesday's tooling.**

## Auditing the three measured constants (2026-08-30)

`RiskDiscountedValue` is a definition, and a definition cannot be wrong. But it
takes three inputs that are not definitions - a trust coefficient, a
games-missed model, and a replacement rank per position - and each had been
carried on the strength of the sentence that introduced it. `ObjectiveAudit`
runs the model against defensible alternatives to each and reports the two
things that matter: does the sixteen-round plan change, and does the backtest
move.

    ./gradlew run -Pmain=ObjectiveAudit -Pkeepers=Tuten,Purdy -q
    ./gradlew run -Pmain=ObjectiveAudit -Pplans=false -q   # backtest only, ~5 min

The `vs base` column is now printed with a PAIRED standard error - the error of
the season-by-season difference from baseline, not of the variant's own mean.
Every variant drafts the same five seasons off the same five boards, so most of
the enormous season-to-season spread (1666 to 2055 in the baseline row alone)
is common to both sides and cancels. **Nothing in the table clears two of its
own standard errors.** No variant has been shown to be better. What survives is
which constants move the PLAN.

### Ranked by how much they move the answer

**1. The trust coefficient and its window - the only constant that moves the
plan.** Six of six window variants changed the sixteen-round plan; the biggest
backtest move was +60 +- 59. Three separate faults:

*It is the wrong statistic.* `PositionPredictability.reliability()` returns a
SPEARMAN RANK CORRELATION, and the formula needs a regression slope in points.
A rank correlation is scale-free - it says whether the ordering survives, not
how far a points gap shrinks - and is not an estimate of that quantity at any
sample size. `TrustCoefficient` estimates the thing itself: the origin-through
slope of realised gap on projected gap, measured in the same window the
objective shrinks toward.

*It is not measurable at the shipped window.* The raw slopes at w=6 are QB
1.26+-0.72, RB 1.07+-0.49, WR 0.71+-0.47, TE 1.03+-0.28, DEF -0.00+-1.58. Every
skill error bar covers both the shipped value and 1.0 - "shrink by forty
percent" and "do not shrink at all" are indistinguishable on five seasons. The
point estimates sit at or above 1.0, so if anything the projections are too
TIMID inside a neighbourhood and 0.578 shrinks the wrong way.

*It cannot do the job it was added for.* Setting the defence trust to zero
changes nothing - same plan, +3 +- 3. Once the shrinkage target is a LOCAL
neighbourhood, trust=0 no longer means "this ordering is worthless", it only
means "smooth the curve locally", and DEF1's neighbourhood still sits well above
DEF13's. Neither target can express what the defence measurement actually says:
shrinking to the position mean inflates defences (their mean sits near their
top), shrinking to a local mean leaves the local slope intact whatever the
coefficient. The trust term is worth -4 to -5.5 points on the top six men at
each position and +-1 everywhere else. That is its entire effect.

The window is the live tension: it controls defence timing and elite-skill
valuation with the SAME knob, in opposite directions. Only a window wide enough
to re-create the position-mean shrinkage that crushed Kelce pushes the defence
later. There is no setting that fixes both.

**2. The replacement ranks - correct as shipped, and insensitive.** Derived
three ways in `ReplacementRanks`. The shipped ranks answer "who is the best man
nobody rosters"; the defensible question is "who is left at MY last pick", since
that is when an empty slot actually gets filled. Measured over five 192-pick
drafts, that shifts QB 21->20, RB 61->60, WR 81->80, TE 19->18, DEF 13->11.
The all-drafted counts sum to 189 against 192 roster spots, which reconciles -
the count is measuring what it claims. **The correction changes nothing**: same
plan, 1856 -> 1856. The DEF-only 13->11 change is +3 +- 3 with the same plan.
The ranks only matter if you get them grossly wrong - textbook starters-only
VORP (QB12 RB24 WR36 TE12 DEF12) costs -85 +- 65 and does change the plan.
This constant is right and can be left alone.

**3. Availability - the smallest, and it should stay linear.** Deleting the term
entirely is +26 +- 33 and changes only the dead rounds 9-14. Two real defects,
neither worth fixing:

*A seam.* A player in the 329-row DraftSharks export keeps ~92% of his
projection; a player outside it takes the 2021-2025 positional average and keeps
~82%. Those are different scales - a 2026 forecast against five years of
realised absence - so CSV membership is worth 7-16% of a man's value. It runs
one way: the drafted man is in the file and the replacement man is not, so every
skill marginal is inflated against its own replacement.

*Defences keep 100%*, being in neither table. That is literally true - a defence
does not tear an achilles - and is the largest single cross-position asymmetry
in the objective. Correcting it changes nothing: same plan, +3 +- 3.

On the functional form: games played and points per game correlate +0.35 at
running back and +0.67 at quarterback, so a man who misses games also scores
less in the ones he plays and a linear (17-g)/17 understates him. That is NOT a
licence to bend the curve. The correlation is measured on REALISED seasons,
where playing badly causes the benching that cuts games played - the arrow runs
backwards from what a projected absence would need. Leave it linear.

### The defence chase was the wrong chase

Three commits have gone after the early defence. Measured directly on outcomes -
the model's own sixteen-round shape, then the same shape with the defence moved
to the last pick:

    the model's plan (DEF r8)        1904
    ...DEF moved to the last pick    1891      -14 +- 53
    RUNBOOK committed (DEF last)     1998      +94 +- 78

**The round-8 defence costs 14 points, which is nothing.** The model's ~94-point
deficit to the committed plan survives moving the defence to where the plan puts
it, so the deficit is in the skill-position ordering and always was. This agrees
with `PolicyBacktest`'s existing `[not legal] no DEF drafted` row, which put a
drafted defence at about nine points a season over a wire one.

### A units fix, additive only

`HistoricalActuals.leaguePointsBySleeperID` scores actuals under the league's
own settings, because joining a six-point-TD projection to a four-point-TD
outcome puts a false slope on every quarterback. It is a NEW method;
`pointsBySleeperID` is untouched, so nothing moves under the agents running
against the current grader.

Model A remains byte-identical at 1812.8 / p10 1784.4, plan
[RB, WR, RB, WR, WR, WR, TE, QB, QB]. **Nothing here reaches Tuesday's tooling.**

## Thirteen seasons, and whether the old ones count (2026-08-30)

The backtest ran on five seasons because five FantasyPros CSVs happened to be
on disk. Five seasons cannot separate a good draft plan from a lucky one -
`PowerBacktest` prices the 95% bar at ~104 points there - and no amount of slot
or opponent variation helps, because every draw inside a season is scored on the
same realised football. The SEASON is the unit of independent randomness.

**The harvest: 13 usable seasons, 2013-2025.** Fantasy Football Calculator
serves 12-team ADP from real drafts back to 2010; Sleeper serves scored seasons
over the same range. `EraIngest` fetches, caches and joins them, and leads its
report with the match rate because that is where this fails quietly.

  - Join: 94.9% (2013), 99.3-100% every season after. `EraBoards` reports it per
    season with the unmatched names, and refuses a season below 90% overall or
    95% inside the top 100 by ADP.
  - 2010 (63.7%) and 2011 (72.1%) are REJECTED and not recoverable: Sleeper has
    no stat rows at all for men who left the league before it existed - Randy
    Moss, Michael Turner, Rashard Mendenhall. 2012's FFC board is 93 players
    deep, too shallow to draft from.
  - Boards are PPR for every season, deliberately. Half-PPR exists only from
    2018, and using it would put a format change at 2018 - exactly where the
    old-versus-recent question is asked. `FormatProxyAudit` measures the
    substitution on the seven seasons carrying both: plan-value agreement 0.837
    pooled, against 0.28 between two seasons of the same format. The format
    matters far less than the season does.
  - Depth caps the game at ELEVEN rounds (FFC boards join at 145-205 players; a
    16-round replay drafts to pick 186 and runs off the end of them).

**The keeper structure is reproduced, not ignored.** Justin holds Purdy and
Tuten before the draft, so he starts with a quarterback and a back in hand.
Replaying history as a plain draft would score plans against a different
problem. `EraKeepers` matches on positional ADP RANK, not on keeper cost -
Purdy is QB9 and Tuten RB23 on the 2026 board, so each historical season hands
me its QB9 and RB23 free, and rounds 12-13 cost nothing in an eleven-round game.

**Every season is graded the same way**: rebuilt from raw component stats under
the league's settings via `LeagueActuals`, always, never Sleeper's
`pts_half_ppr`. This matters more than it sounds. The feed changed its own
arithmetic mid-history, so a harvest that graded new seasons one way and old
ones the other would manufacture a regime shift out of bookkeeping.

### The regime test: the old seasons ARE the same game - in THIS game

Two tests, both in `RegimeShift`, both on held-out seasons:

  AGREEMENT (rank correlation over all 415,650 legal plans, 78 season pairs):
    within 2013-2018  0.264      within 2019-2025  0.293      across  0.280
  Seasons agree with each other only weakly - 0.28 - but they agree that weakly
  WITHIN an era too. The across-era number sits between the two within-era
  numbers, which is as clean a null as this can produce.

  DRIFT (agreement regressed on the GAP between two seasons - the recency
  question in its strongest form, since it does not need a cutoff to exist):
  **-0.0109 +/- 0.0056 per year**, 78 pairs. Marginal, and that interval is a
  FLOOR: 78 pairs come from 13 seasons, so they are not independent and ordinary
  least squares does not know it. Read as "not significant, pointing gently
  toward mild drift".

  TRANSFER (fit on one era, score on 2021-2025, top 1% of plans rather than one
  noisy winner): fitting on recent seasons instead of old ones is worth
  **+29.7 +/- 31.1 points a season**. Inside the noise, same gentle direction.

**Recommendation, as the tool prints it: BORDERLINE - pool all 13 seasons, or
hedge gently. Do NOT cut.** The strongest era signal is 2.01 standard errors on
an interval that is itself optimistic, and down-weighting costs real resolution
(13.0 effective seasons -> 11.8 at a half-life of 8) to insure against a bias
that cannot be measured. The half-life grid's apparent winner is grid search:
its held-out curve reverses direction twice, which a real recency effect would
not.

The verdict is computed in `RegimeShift.verdict` with a three-way rule, because
the drift estimate read 1.95 sigma on a 20,000-plan subsample and 2.00 sigma on
all 415,650 - a two-sided threshold flipped the RECOMMENDATION between those two
runs. Anything between 2 and 3 sigma now prints BORDERLINE rather than picking a
side.

The one defensible hedge: both marginal estimates lean the same way, so a LONG
half-life - 8 seasons or more - costs about 1.2 effective seasons (95% bar 101 ->
106) and is cheap insurance. A cutoff year is not the same thing and is not
recommended: it is a half-life of zero, it throws away seasons that the drift
test says are still evidence, and there is no year in the data where anything
changes.

**But the answer is structural, not universal.** Run `-PnoKeepers` and all three
tests fire (`data/regime-shift-nokeepers-2026-08-30.txt`): across-era agreement
0.159 against within-era 0.210 and 0.213, drift -0.0143 +/- 0.0051 (2.80 sigma),
transfer +89.5 +/- 31.7 (2.82 sigma) - and +51.1 and +41.5 at 2021 and 2017
cutoffs, so it is not an artifact of where the split falls. The keeper structure
removes the quarterback-timing decision, and quarterback timing is where the
eras differ most. Anyone using this harvest for a keeperless question should
weight by recency; Justin's own game does not need to.

### What the harvest is worth

`EraSample`: two plans from the top 1% differ by +/-186 points season to season,
so the 95% bar falls from 163 points at five seasons to 101 at thirteen. The
honest planning number - the difference a fair test finds eight times in ten -
is 145 points. Thirteen seasons make the bar crossable; they do not make small
differences real.

`RegimeShift` also prices the overfitting directly: the best plan chosen ON the
held-out seasons scores +402, honest fits score +99 to +104. Searching 415,650
plans on a handful of seasons buys ~300 points of pure flattery, and the
single-winner comparison flips sign between plan subsamples. Judge plan families,
not winners.

Model A remains byte-identical: [RB, WR, RB, WR, WR, WR, TE, QB, QB], 1812.8,
p10 1784.4. Every file here is new; the only shared edit is additive knobs in
build.gradle.

## The committed plan's 1998 is a training score, and so is everything near it

`ShapeSearch` was built to answer one question: is the RUNBOOK's 1998 an honest
out-of-sample number, or is it inflated because we chose those fourteen slots by
looking at the same five seasons it is scored on? Fourteen parameters fitted on
five observations and then graded on those five, while every model it beats is
graded leave-one-out.

The honest version:

    for each held-out season S in 2021..2025:
        search shapes using ONLY the other four
        score the winner on S, which it never saw
    report the mean of the five held-out scores

**Seeded from random legal shapes and nothing else.** The committed shape is
never a start, never a hint, never in an objective - it enters as a scored
entrant so its rank sits beside everybody else's. That is deliberate protection
against the `-Pdeviate` class of fault, where a default quietly handed a search
the answer it was meant to rediscover.

    ./gradlew run -Pmain=ShapeSearch -q
    ./gradlew run -Pmain=ShapeSearch -Pshape="RB RB QB DEF WR ..."   # who a shape drafts

### The answer, and it is not the one the question expected

    RUNBOOK committed, five seasons (IN-SAMPLE)        1998
    LOO pick by train MEAN (out-of-sample)             2041   fold sd 90
    LOO pick by train WORST SEASON                     1950
    LOO pick by train MINIMAX REGRET                   1895
    LOO whole-slate average                            1955
    best available by ADP (the null)                   1442

An honest picker that never sees the season it is graded on scores **2041**,
slightly ABOVE the committed plan's in-sample 1998. So the fixed-shape family is
genuinely worth about two thousand points out of sample, the models at 1885 were
not chasing a mirage, and 1998 is not fraudulent.

**But 1998 is still a training score, and the selection tax is measured: +126.**
That is what a shape fitted this hard on four seasons loses when it meets a
fifth. It is the honest discount on any number chosen by looking at these five.

**And the two are not separated.** Paired season by season, which is the sharp
test because both shapes draft the same five boards:

    LOO by train mean - RUNBOOK   +81  +252   -91  +127  -156   mean +43, SE 74

Plus or minus 74 straddles zero. Nothing smaller than about 148 points is
distinguishable here, which agrees with `PowerBacktest`'s clustered 125.

### The RUNBOOK is an ordinary member of a family it does not lead

Its rank on the four training seasons swings from 2,870th to 48,558th of 111,720
shapes depending on which season is dropped; held out, from 1,025th to 103,812nd.
Four of the five folds' tie sets do not contain it. It is not in the consensus
slate - the 1,565 shapes inside the band no matter which season you drop.

Where it does stand out is the wrong direction. Against 20,000 shapes drawn
uniformly from the legal space, **1998 beats 99.46% of them by mean - and its
worst season beats only 60.46%.** Excellent on average, close to a coin flip on
the floor, which for a man who plays one season is the statistic that matters.
Its 1654 in 2022 is 252 below the worst any honest pick ever posted. At 1.5
per-season spreads that is suggestive, not established.

### The argmax is worthless; the tie set is the finding

At the measured 125-point band, **5,096 to 17,825 shapes tie with the leader in
each fold** - up to 16% of everything evaluated. Naming one fourteen-slot
sequence as best is meaningless. What the tied plans agree on is not:

    TRAIT                              tied  chance  lift   RUNBOOK
    two backs in the first three        85%     25%  3.48   yes
    opens with a back                   87%     33%  2.65   yes
    first QB by round 3                 73%     35%  2.11   NO
    no receiver until round 3           80%     41%  1.92   yes
    five or more receivers              95%     60%  1.56   yes
    first QB after round 8               6%     24%  0.24   yes

And per parameter, with the folds' medians beside the random null:

    POS  tie set    null       per fold          verdict
    QB   3 / 3 / 6  1 / 5 / 11  3  3  3  3  3    PINNED
    RB   1 / 1 / 2  1 / 2 / 6   1  1  1  1  1    PINNED
    TE   6 / 8 / 8  1 / 5 / 11  8  8  8  8  8    PINNED
    WR   1 / 4 / 5  1 / 2 / 5   4  4  4  4  4    LEANS (band as wide as chance)
    DEF  4 / 6 / 11 2 / 8 / 13  9 10  8  6  8    FREE (folds disagree)

The committed plan matches the pinned structure exactly on backs and tight end.
**The one place it disagrees with the tied field is the quarterback**: every
fold puts the first QB in round 3, the committed plan waits until round 10, and
only 6% of tied plans wait past round 8.

### Do not act on the quarterback finding yet

It is the conclusion most exposed to the two known faults in this instrument,
and they push opposite ways:

- **Outcomes are graded at 4 points a passing touchdown; the league pays 6.**
  Every starting quarterback is scored 55-66 points a season light, so QB-early
  wins here DESPITE a handicap. Correcting it should strengthen the finding.
- **The eleven opponents take strictly best-available-by-ADP.** The real league
  lets quarterbacks fall about 16 selections. So this backtest makes QBs scarcer
  than they are and rewards reaching, which weakens the finding.

Until `ShapeSearch` is rerun on the corrected grader and against `PowerBacktest`'s
displaced opponents, round 3 is a hypothesis, not a plan.

### A prediction that failed, and why the failure matters

The defence slot looked like a free overfitting detector. No opponent in
`PlanBacktest` ever drafts a defence, so the same defence waits at pick 186 as at
pick 42, while moving it late shifts every skill pick onto a less picked-over
board. The DEF-last rotation is therefore ADP-dominant, and the search preferring
the defence EARLY had to be fitting noise.

It is not. DEF-early beat its own DEF-last twin by **+235 a season on training
and +164 out of sample, in four folds of five.**

`-Pshape=` prints why, and the answer is worth carrying to every other shape
conclusion in this repo: rotating one slot does not merely change WHEN you draft.
The other eleven drain the board between your picks, so the two rosters share
almost no players at all - in 2021, DEF-last got Cooper, Aiyuk, Chark and
Beasley where DEF-early got Higgins, Waddle and Fournette. **A shape is a lottery
ticket over fourteen board positions, and an ADP-dominant ticket is not a
better-scoring one.**

That said, the defence market here is fictional - eleven teams that never draft
one - so the defence slot is the least trustworthy parameter this instrument has
and the one it argues hardest about. The folds cannot agree on it either. Take
the defence last, as the committed plan does.

### What this is worth, honestly

The fixed-shape family survives leave-one-out at about 2000, and every plausible
member of it beats best-available-by-ADP by 500-600. Below that, at five seasons,
the shape family **cannot be resolved**: thousands of plans tie, the honest
picker and the committed plan differ by less than their own error bar, and the
committed plan's higher-variance floor is suggestive rather than proven. Seasons
are the only axis that would sharpen this; more restarts do not (fold winners are
identical at 3, 10 and 30 restarts) and neither do draft slots or opponent worlds
(`PowerBacktest`: SE 44.0 to 43.4 with infinitely many).

The search space was 54,678,624 ordered legal shapes (QB 1-2, TE 1-2, DEF exactly
1, RB >= 2, WR >= 3); the pool evaluated was 111,720 of them, 0.2%, so every tie
count above is a FLOOR on how many shapes tie, never a ceiling.

Full output: `data/shape-search-2026-08-30.txt`. Model A remains byte-identical
at 1812.8 / p10 1784.4, plan [RB, WR, RB, WR, WR, WR, TE, QB, QB]. Nothing here
reaches Tuesday's tooling.

**One correction to an earlier table.** The `best-nine (Model A)` row in
`PlanBacktest.STRATEGIES` (1627) is Model A run past round 7, outside its domain
- two keepers plus seven picks fill its nine starting slots exactly, so its
trailing picks are artifacts. It is not a fair entrant and must not be read as
the floor of the model field. The legitimate mixed entrant is `ModelA front + SS
back` at 1862.

## The two channels the objective cannot see, bounded (2026-08-31)

Justin, the night before the draft: *"a round 11 pick should also likely be a
wr/rb, not because I expect many starters to get injured, but because I expect
some starters to bust, and some bench players to boom."* The mechanism he names
is real and it is in the code: `WeeklyStarterValue.oneWeek()` drops a starter
only when he is drawn `!up()` (injury, line 189) and ranks the survivors by
`Draw::expected` (line 198), the preseason projection, which never updates.

The question that pays before a draft is not whether the channel is missing. It
is whether adding it would change a pick. `BustBoomSweep` bounds that, and the
answer is no.

### The sweep, and why it is a bound rather than a model

`BustBoomValue` is `WeeklyStarterValue` with a bust/boom level attached to each
player-season and the season split in two: weeks 1..LAG set the lineup on the
preseason projection exactly as today, weeks LAG+1..17 set it on the realised
level. Three free parameters - bust rate, boom rate, detection lag - swept over
5 x 5 cells at each of picks 79, 90, 103, 114 and 127.

It is deliberately over-powered in three ways, all pushing the channel's value
UP: detection after the lag is **perfect**, the bust/boom multiplier is applied
**on top of** a drawn historical season that already contains busts and booms,
and promotion is **free** - no roster churn, no waiver cost, no week lost to a
wrong call. A bound that survives all three survives an honest model.

At bust = boom = 0 it reproduces `WeeklyStarterValue` **exactly** - 2452.6
against 2452.6, gap 0.000 - because the bust/boom uniforms are drawn from a
separate generator that leaves the shipped draw sequence untouched. That is
checked at runtime in the tool and again in `BustBoomValueTest`.

### The result, and the decomposition that matters

Of 125 cells, 60 reorder something and **10 change the top position**. All ten
are at pick 103, all RB -> WR, all at rates of 20% or more.

But the top-position changes appear at lag **"never"** - where the lineup is
forbidden to react at all. So they are not the missing channel. They are the
magnitude knob: multiplying realised rates by 0.60 and 1.60 changes what men
SCORE, quite apart from who gets STARTED. Separating the two:

```
the magnitude knob alone (visible at lag 'never')     2 of 25
DETECTION, the channel actually under test            0 of 25
```

**Detection never changes the top position at any of Justin's picks, at any
bust rate to 30% and any lag from one week to never.** The detection lag was
predicted to be the hinge; it is not, because the thing it is a hinge on turns
out not to move. Even the two knob-driven flips are at a pick where RB and WR
start 2.3 points apart (54.7 against 52.4), which is inside anything.

This agrees with two measurements already in the repo, from different
directions. `StarterContribution`'s bust dial (2026-08-29) sorts its lineup by
`Player::perGame` **after** the bust scaling - perfect detection at zero lag,
the most generous corner this sweep contains - and the tight end lost in all
thirty worlds. And the boom channel was measured directly on real outcomes
(2026-08-30): a round-10 back outscores a top-twelve back 10% of the time, worth
6.1 points in expectation, against a 125-point bar.

### Where the value went instead: the quarterback

The one large number in the sweep is the bench QB, which gains +28 to +32 points
in every cell at 20% / lag 3, moving from fifth to second. It is structural -
one quarterback slot means a boom quarterback displaces the starter outright -
and it is also the most over-powered cell in the grid, since perfect detection
of a quarterback's level is worth more than perfect detection of anyone else's.
It never reaches the top of the ranking and so never changes a pick. Worth
remembering as the place to look first if this is ever built properly.

## The streamed-defence rate is chosen after the season (2026-08-31)

This one **does** move a conclusion, and it is the more important of the two.

`PlanBacktest.streamedDefencePerWeek()` returns 8.75 a week from
`WeeklyStarterValue.wireRates()`. `LateRoundValue` turns it into "a drafted
defence is worth -12.9 against streaming one". `wireRates` does two things and
its comment defends only the first:

1. it picks the candidate pool by **preseason ADP rank** - clean, and decided
   before the season;
2. it then sorts those candidates by **realised season rate** and averages the
   best quarter of them - which is a choice made with the season already run.

Step 2 sets the number. The comment - *"Chosen on expected rate, not on what the
player went on to score"* - describes step 1 and is silent about step 2. This is
a softened version of the MAX-over-undrafted-players fault that reversed two
findings when it was fixed.

`WireRateStress` measures what hindsight-free streaming policies actually
return on the same five seasons and the same join:

```
band mean (never touch the wire)          6.41 /wk    no hindsight
hold best undrafted by ADP, all season    6.44 /wk    no hindsight
stream on form, react after week 2        7.69 /wk    no hindsight   <-- honest
stream on form, react after week 4        7.48 /wk    no hindsight
SHIPPED pooled top quartile               8.75 /wk    RANKS BY REALISED
ORACLE best undrafted each week          18.99 /wk    the ceiling
```

The shipped rate is high by about 1.1 a week, roughly 19 points a season. It is
not absurd - the oracle at 19.0 shows a matchup-driven streamer has room above
7.7 - but nothing measurable supports it, and it was not chosen by a policy.

### It flips the sign of both defence measurements

The two existing numbers rest on the same 8.75, so correcting it moves both.
`PlanBacktest` at the shipped rate against the honest one, via the new
`-PwireDef` override:

```
                        8.7/wk    7.69/wk
RUNBOOK committed         1998       1996
[not legal] no DEF        2007       1988
streaming's margin          +9         -8
```

Streaming led by 9 and now trails by 8. Measured against the drafted defence
directly, on one denominator - same seasons, same feed, same 18 weeks, no
borrowed constant - a held DEF1-3 returns 135.8 a season against 138.4 for an
honest stream, a gap of 2.6 with a standard error near 9.

**All of these are the same finding: it is a wash.** The claim that survives is
not "-12.9, so let the defence fall" but "drafting against streaming is inside
the noise in both directions, and a last-round pick is the cheapest spot you
own". `RUNBOOK.md` already says the right thing - *"you MUST draft one... Stream
by SWAPPING it during the season, never by skipping the pick"* - and its
round-by-round table is flat from round 14 to 16. **Nothing in the runbook
changes.** What changes is that streaming is no longer free upside, and the
-12.9 must not be quoted again.

Full output: `data/wire-rate-stress-2026-08-31.txt`,
`data/bust-boom-sweep-2026-08-31.txt`.

## The bust/boom channel, measured three ways (2026-08-31)

**Superseded in one place by the nflverse rerun at the end of this section: the
detection lag is week 11 on sixteen seasons, not week 12 on thirteen. Everything
else below stands.**

The night before the draft, `RESEARCH-PLAN.md` asked for three numbers that a
bust-and-boom promotion channel would need. All three are now measured from data
already on disk, by four new entry points, and the headline is that **the channel
is real, positive, and roughly ten times too small to matter.**

    ./gradlew run -Pmain=DetectionLag        the hinge: how fast is a bust visible
    ./gradlew run -Pmain=BustBoomRates       rates by position and draft tier
    ./gradlew run -Pmain=PromotionBehaviour  what the twelve humans really did
    ./gradlew run -Pmain=LineupPromotion     what the channel is worth, in points

Outputs saved to `data/detection-lag-2026-08-31.txt`,
`data/bust-boom-rates-2026-08-31.txt`, `data/promotion-behaviour-2026-08-31.txt`
and `data/lineup-promotion-2026-08-31.txt`.

### 1. Detection lag: week 11-12, and that is the whole story

For every drafted starter (ADP <= 108) at every cut week k, two predictors of
his rest-of-season points per game compete: his August price, read off the ADP
board through a per-position curve fitted LEAVE-ONE-SEASON-OUT, against his own
points per game over weeks 1..k. Thirteen seasons, about 1,300 graded men a
week. Nothing is dated later than it should be: the to-date rate reads weeks
1..k, the target reads weeks k+1..W, and the two never share a week.

    WEEK k      n   RMSE pre   RMSE std     edge   w* std
    1        1290       3.33       7.51    -4.18     0.08
    3        1318       3.50       5.00    -1.50     0.22
    6        1292       3.72       4.26    -0.54     0.37
    9        1238       3.99       4.21    -0.22     0.43
    12       1148       4.47       4.37    +0.10     0.54
    14        931       4.87       4.81    +0.05     0.52

**The crossover is week 12, bootstrapped over seasons at 95% [10, 15].** The
optimal blend weight on form crosses 0.5 in the same week. By position, and this
is the one genuinely useful split: **RB 7 [6, 13]**, TE 11 [8, 15], WR 12
[9, 15], QB never inside 14 [12, 15]. A back's form is worth reading by week
seven; a quarterback's essentially never is, which is the opposite of how the
league behaves.

Two things this does NOT say. It does not say form is useless before week 12 -
the blend beats the board from week one, and beats it by 0.2 to 0.4 points a
game from week four on. And the leave-one-season-out curve borrows the SHAPE of
the ADP-to-points relation from seasons after the one it grades, which a real
August manager would not have. That help goes to the preseason side, so week 12
is a conservative bound: the true crossover is at or before it.

### 2. Bust and boom rates, thirteen seasons

`FogFit` had this on five seasons against a projection feed. `BustBoomRates`
widens it to thirteen against the ADP board itself - the price twelve managers
actually paid - and divides availability out, because the injury channel is
already in the model and folding it in double-counts it.

    POS  TIER               n    mean     sd    bust    boom startable
    RB   rounds 1-2       162    1.00   0.32      5%     19%      88%
    RB   rounds 7-9       161    0.98   0.40     12%     21%      39%
    RB   rounds 10-13     193    0.87   0.46     21%     16%      18%
    WR   rounds 1-2       127    0.98   0.21      1%      7%      94%
    WR   rounds 7-9       176    0.99   0.33      7%     19%      56%
    WR   rounds 10-13     223    0.95   0.38     11%     20%      39%

The two numbers a promotion rule actually needs, as "did he finish inside the
league's own starting depth", with 95% intervals over seasons:

    POS   BUST: a rounds 1-9 pick   BOOM: a rounds 10-16 pick
          not startable              startable
    QB    36% [30%, 41%]             33% [26%, 40%]
    RB    27% [25%, 30%]             32% [27%, 38%]
    WR    16% [13%, 19%]             47% [39%, 55%]
    TE    31% [24%, 38%]             55% [48%, 63%]

Note the asymmetry the sweep should carry: **a receiver taken in rounds 1-9
fails to be startable only 16% of the time, a back 27%.** That is the same cliff
`1ca1d9f` found from the other direction.

### 3. What a real manager did - and it is reachable

The transaction log IS reachable, one request per league per week, for all five
completed seasons: `/v1/league/{id}/transactions/{week}`. So are the weekly
lineups: `/v1/league/{id}/matchups/{week}` returns, for every roster in every
week, both the men on it and the ten actually started. Nobody had looked at
either. `LeagueTransactions` harvests both, cached forever.

What the log says. About 28 completed adds a team a season, near two a week,
flat from week 1 to week 14. **Of players drafted in rounds 1-4, only 21% are
ever cut by the man who drafted them; rounds 5-9, 40%; rounds 10+, 65% - and 14%
of those late picks are gone before week 2 is over.** For rounds 1-9 the median
cut comes in **week 7**, quartiles 4 and 10 - close to, and slightly ahead of,
the week-12 statistical crossover, which says this league's managers act on
about the evidence the numbers justify, marginally early. Between 60% and 71% of
all adds are men nobody drafted.

A one-for-one swap gains the ROSTER **+7.0 rest-of-season points, 95%
[+4.2, +9.9]**. That is not a lineup gain, and the distinction is the finding.

### 4. What it is worth: +8.5 points against a 125-point bar

`LineupPromotion` replays five seasons of real rosters under four lineup rules,
same roster each week, slots enforced, league-scored:

    PRESEASON   fill the nine by August and never update  <- this IS the model
    FORM        blend August with points per game to date <- the missing channel
    ACTUAL      what the human really started
    PERFECT     best nine by realised points              <- the ceiling

Both rules are granted the availability knowledge `WeeklyStarterValue` already
has through its `!up()` draw, because otherwise "form" gets credit for detecting
injuries the model can already see. Points per team per season, weeks 1-14:

    form over preseason, injury channel ON     +8.5  95% [+0.8, +16.3]  <- the prize
    the injury channel by itself             +208.1
    a real human over preseason+up            +17.0  95% [+9.9, +25.0]
    hindsight over a real human              +177.0  95% [+168.6, +185.2]

Sweeping the blend weight, the best mechanical rule sits near 0.15-0.30 and is
worth **+12.7 to +14.3**; weight 1.0 is worth -7.3, so a rule that forgets the
board is worse than one that ignores form. Weight 0.00 reproduces preseason to
the point, which is the control proving the sweep measures the blend.

**Read the four rows together.** The injury channel the model already has is
worth 208 points. Everything the missing bust/boom channel could add is worth
9-14. And the hard ceiling on ANY promotion rule - matching a real, engaged
human with a waiver wire, beat reports and a Sunday morning - is +17. The
95% bar for a plan-level claim is 125. **The channel cannot change a pick, and
this is now measured rather than argued.** `StarterContribution` reached the
same corner from the other side on 2026-08-29: strip out failure and a bench
player contributes nothing, and it never gets better than a tie.

One constraint from the archive that makes this a behavioural quantity rather
than a modelling convenience: Sleeper cannot chain auto-subs. A sub ties to one
starter and both games must not have begun, so promotion is an active weekly
decision. Any rule assuming the roster self-heals is modelling a platform that
does not exist.

### What is still guesswork, plainly

The lineup numbers rest on FIVE seasons, not thirteen - the league's own
transaction and matchup history starts in 2021 - so `[+0.8, +16.3]` is as tight
as this sample gets. The detection lag has thirteen. The `nflverse` weekly feed
would take the paired ADP-plus-outcome sample to sixteen seasons and remove
Sleeper's coverage holes for men who retired early; it was not downloaded here
because it needs Justin's approval first.

### 5. The same question on sixteen seasons (nflverse), 2026-08-31

Justin approved the nflverse download the same night, so the whole study was
rerun on it. `NflverseWeekly` reads `data/nflverse/stats_player_week_YYYY.csv`
and scores from RAW COMPONENTS under this league's own settings - the files'
`fantasy_points` columns are never read, because they are a 4-point passing
touchdown and this repo has already been burned once by choosing on 6 and
grading on 4. `NflverseBoards` joins them to the same FFC boards.

    ./gradlew run -Pmain=NflverseBoards                     the join, and its match rate
    ./gradlew run -Pmain=DetectionLag  -Psource=nflverse
    ./gradlew run -Pmain=BustBoomRates -Psource=nflverse

**Why it is a better sample, not just a bigger one.** Sleeper has no stat rows
at all for men who left before it existed, and the men who vanish are
disproportionately the ones who BUSTED - so the thirteen-season Sleeper sample
was biased in exactly the direction this study cares about. Randy Moss, Michael
Turner and Rashard Mendenhall are all missing from Sleeper's 2013 and all
present here.

The join holds up, and the match rate is a first-class output rather than a log
line, with a season below the gate refused outright:

    16 of 16 seasons usable, 2010-2025. Match 97.6% to 100.0%, top-100 98.0% to
    100.0%, at most 4 names loosened in any season.

The names that got away are almost all men who played no regular-season game at
all - Michael Thomas 2021, Joe Mixon 2025, Brandon Aiyuk 2025 - who therefore
have no rows to join to. That is a real limitation and it cuts one way: **the
total-loss season is absent from this sample**, so the bust rates below are
bust-given-he-played and understate the manager's true exposure. The injury
channel, which is where those men belong, is measured separately and is worth
208 points.

**The detection lag, sixteen seasons, n ~ 1,500 a week:**

    WEEK k      n   RMSE pre   RMSE std     edge   w* std
    3        1605       3.56       5.07    -1.51     0.23
    6        1570       3.81       4.27    -0.46     0.38
    9        1506       4.05       4.20    -0.15     0.46
    11       1455       4.35       4.28    +0.07     0.52
    13       1318       4.77       4.52    +0.25     0.60

    crossover week 11, bootstrapped over seasons at 95% [9, 14]

    POS   cross   95% interval
    RB       9    [ 6, 11]
    WR      11    [ 9, 15]
    QB      13    [ 8, 15]
    TE      13    [ 8, 15]

Three seasons and the missing busts move the headline from **12 [10, 15]** to
**11 [9, 14]** and tighten running backs from [6, 13] to **[6, 11]**. The
ordering is unchanged and is the part to carry: **a back's form is worth reading
around week 9, a receiver's around 11, and a quarterback's essentially never.**

**Independent confirmation, from a different method.** The `InSeasonLearning`
work in `RESEARCH-PLAN.md` estimates `kappa`, the games of evidence it takes to
move the preseason prior halfway, from player-week scatter rather than from any
crossover: QB 12.14, RB 5.87, WR 10.84, TE 10.09. Those are the same ordering
and nearly the same magnitudes as the crossover weeks above, computed from a
completely different quantity. Two methods agreeing on RB-fast/QB-slow is worth
more than either alone.

**Bust and boom on sixteen seasons** move only slightly, all inside the
thirteen-season intervals - which is itself the reassurance that the smaller
sample was not badly biased on this particular question:

    POS   BUST rounds 1-9 not startable   BOOM rounds 10-16 startable
    QB    34% [28%, 39%]                  32% [26%, 39%]
    RB    25% [22%, 28%]                  32% [26%, 37%]
    WR    15% [12%, 17%]                  48% [41%, 55%]
    TE    25% [19%, 31%]                  50% [42%, 59%]

**What does not change.** The value. `LineupPromotion` is capped at five seasons
whatever the outcome feed does, because it needs THIS league's own transaction
and matchup history and that starts in 2021. The prize stays +8.5 points a team
a season, 95% [+0.8, +16.3], best-case +12.7 to +14.3 at a blend weight near
0.15-0.30, against a 125-point bar and against +208 for the injury channel the
model already has. **A wider outcome sample sharpened the lag and left the
verdict alone.**

---

## The backtest was drafting against the wrong board (2026-09-01)

Every backtest figure in this document was measured stripping **two** keepers
from the historical board — Purdy and Tuten. This is a twelve-team keeper
league and every team keeps two, so the real board is missing **twenty-four**,
most of them from the top: Nacua, Taylor, Bowers, Smith-Njigba, McBride,
Achane, Collins, LaPorta. `LiveBoard` was corrected for this; the backtest was
not.

`EraSlate` transplants the whole slate the way `EraKeepers` transplants
Justin's: by **positional ADP rank**, never by price. Bucky Irving costs a
13th, and a 13th-round back on the 2015 board is a replacement-level man being
called a keeper. It also transplants the **picks** — a keeper spends its
owner's selection, so twenty-four picks choose nobody; removing the men without
removing the picks would have the other eleven eat twenty-four extra players
off the *bottom* of the board, which is a different wrong board rather than a
smaller one. Justin's own two are unchanged, and their ranks still come from
`EraKeepers`.

`-PleagueKeepers=true`, **off by default**. `KeeperSlateImpact` scores both
boards in one process:

    STRATEGY                       2 KEEPERS   24 KEEPERS    shift   paired se
    RUNBOOK committed                   2029         1833     -196          63
    RUNBOOK as written                  2007         1840     -167          78
    RUNBOOK front + SS back             2007         1837     -170          61
    RB-heavy folk rule                  1978         1842     -136          53
    starter-sum (1-16)                  1976         1848     -128          99
    board value (fixed shape)           1968         1812     -156          71
    ModelA front + SS back              1956         1841     -115          45
    board value (adaptive)              1935         1783     -152          82
    best-nine (Model A)                 1752         1621     -131          50
    best available by ADP               1579         1524      -55         126

**The shift is not uniform**, which is the only reason it counts: 141 points
between the strategy hurt most and the one hurt least, over the 125-point bar.

**The ordering changes and the change means nothing.** The committed plan falls
from 2nd to 7th and starter-sum climbs from 6th to 1st — but the serious field
was 95 points wide before and is 65 wide now, both inside the bar, and seven
strategies now sit within fifteen points of each other. What a realistic board
removes is not a winner but the illusion that rounds 1–9 position order was
ever being measured. The men who made an early back worth more than an early
receiver are on somebody else's roster.

**The plan against the board model**, the question that mattered: +72 becomes
+57. A tie both times, and the paired standard error nearly doubles, 51 to 87 —
the realistic board is *noisier* per season as well as lower, because a
shallower pool makes it matter more which men happen to land where.

Separately, `-PkeeperRanks` corrects a real fault: `BoardValue` read a kept
man's rank off a running count of who had left the board, which for the first
man kept is 2 by arithmetic rather than by measurement — it priced Purdy as the
second-best quarterback alive. Worth +17, inside the bar, and not the story.

Data: `data/keeper-slate-impact-2026-09-01.txt`, `data/keeper-slate-2026-09-01.txt`.

---

# The bench constant, fitted to a measurement and refused by it (2026-09-01)

`BoardValue.oneSeason` has exactly one free parameter, and it is the only
reason that model owns a bench at all: a man whose drawn season falls below
`lostBelow` times what his rank normally returns counts as **lost**, is
benched, and the lineup fills by expectation from whoever is left. It works —
without it the marginal of a bench man is exactly zero and the search drafted
sixteen receivers. `0.55` was chosen in one sitting and nothing had ever asked
it to reproduce anything.

`BenchValue` had the target all along and it had never been called: 434 real
bench picks from this league, five seasons, joined to what each man actually
scored, floored at the wire line. **44.0** points in rounds 8-9, **32.8** in
10-12, **31.2** in 13-16. It is now `BenchValue.overWireByBand`, asked for
rather than retyped.

`BenchCalibration` prices the model's own bench picks against a realistic
roster — Purdy and Tuten held, then Model A's seven, `RB WR RB WR WR WR TE`, so
every starting slot is covered before the first bench pick and a bench man can
only pay through availability — and sweeps three one-parameter families.

## No form reproduces it

    threshold, all lost -> wire (shipped)   best chi-square  9.7  at lostBelow 1.10
    threshold, all lost -> his best man     best chi-square 16.1  at lostBelow 1.05
    blend, expected + L(drawn - expected)   best chi-square 33.8  at lambda 1.00

Three bands, one fitted parameter, 95% at 5.99. Nothing clears it.

**It is a ceiling, not a mis-set knob.** The highest rounds 13-16 value
reachable anywhere on the grid is 22.5 (threshold/wire), 20.3
(threshold/best man) and 14.0 (blend), against a target of 31.2 ± 6.6. That
band is *out of reach at every setting*, so no amount of turning gets there.
The model's implied bench value decays far faster with round than the
measurement does: 27.2 → 8.1 → 1.3 at the shipped 0.55, against 44.0 → 32.8 →
31.2.

**And the least-bad setting is past the collapse.** 1.10 backtests 1577 mean /
1472 worst, against 1970 at 0.85. The fit's preferred value is exactly where
the model stops working, which is evidence about the fit and not a discovery.

## Because they are not the same quantity

`BenchValue` measures a man's **own** season over the wire, floored at zero,
whether or not he ever entered a lineup. `BoardValue` computes a **lineup
marginal** — what the roster's best legal lineup gains by owning him. A bench
receiver who scores 150 behind three better receivers is worth 150-over-wire to
the first and nothing at all to the second.

Priced on the target's own estimand, from the same pools, at the same picks,
with **nothing fitted**:

    BAND             MARGINAL   OWN/WIRE   MEASURED   +/-2se
    rounds 8-9           27.2       53.4       44.0      9.2
    rounds 10-12          8.1       33.5       32.8      6.9
    rounds 13-16          1.3       25.7       31.2      6.6

    chi-square:  MARGINAL 148,  OWN/WIRE 7.0  (bar 7.81, three dof, none fitted)

**The outcome distribution is already right.** Sixteen seasons of nflverse
scatter, with no parameter at all, reproduce what this league's real bench
picks returned. The gap between the two columns is the question being asked,
not an error in the model. Under `-PleagueScoredActuals=true` the target moves
to 45.4 / 33.8 / 32.2 and OWN/WIRE improves to 6.3, so this is not a
4-versus-6-point-passing-touchdown artefact.

## So the parameter is not identified, and 0.55 stays

Two independent things could have set it and neither does:

- **the backtest cannot.** 1939 / 1935 / 1954 / 1970 across lostBelow 0.30 to
  0.85 — 35 points against a 125-point bar. Flat.
- **the over-wire target has the resolution but rejects the model.** 5.2 points
  of rounds 8-9 value per 0.1 of lostBelow, against a 9.2-point bar, so it
  *could* pin the parameter to about ±0.09 — if the model could reach the
  target at all. It cannot, so the interval 1.05-1.15 around the minimum is not
  an error bar; it is where the miss is smallest.

TRAPS.md D21 applies exactly: a parameter that cannot be identified should not
be tuned. `lostBelow` stays at 0.55.

## The blend, and why it is still a tie

Justin's continuous form — order the lineup on `expected + lambda * (drawn -
expected)`, lambda 0 a useless bench and lambda 1 best ball — fits the bands
*worse* than the threshold (33.8 against 9.7), because it overshoots rounds 8-9
to 53.4 while still reaching only 14.0 in 13-16. It backtests better:

    lostBelow=0.55 (shipped)   1890 1792 2097 1841 2053   mean 1935  worst 1792
    blend lambda=0.50         2134 1924 2067 2056 2074   mean 2051  worst 1924
    blend lambda=0.75         2134 1880 2238 2031 2102   mean 2077  worst 1880

but that table is scored on the seasons the row was chosen from. Choosing the
rule on four seasons and meeting the fifth, five times, the blend is picked
every fold — lambda 0.75 four times, 0.50 once — and returns **2043 against the
shipped 1935: +108, inside the 125-point bar. A tie.** The direction is
consistent and the size is not measurable, which is the honest summary.

It is also a strong claim about football: lambda 0.75 is a manager who is
three-quarters prescient about season totals. `-Plambda=0.6` reaches it and it
is off by default.

## Two side findings

**The "never empty a position" guard never guarded.** It read
`entry.getValue().size() > 1` from inside that same list's own `removeIf`, and
`ArrayList.removeIf` tests every element before removing any of them — so
`size()` reported the original count however many were already doomed. Two men
at a position who both had bad seasons were *both* dropped and the slot fell to
the wire, which is precisely what the comment above it promised could not
happen. The behaviour is kept, because it is arguably the better model, and it
is now a named choice: `-PwireWhenAllLost=false` fields the best-expected man
instead, worth +39 mean / +7 worst, inside the bar.

**`BenchValue` graded outcomes off the raw feed** rather than through
`LeagueActuals`, which is the one place the repo says everything that grades an
outcome must go. Now dispatched; byte-identical with the flag off.

Run it: `./gradlew run -Pmain=BenchCalibration -PholdKeepers=true`
