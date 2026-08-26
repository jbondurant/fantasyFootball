# The draft model

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
