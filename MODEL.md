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
