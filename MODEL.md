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
  Conclusion: the LOCATION layer (blended ADP + fitted bias + value rank) is
  what wins; the noise shape adds nothing measurable on five seasons of data.
  A learned model's remaining path to the gate is a better location signal -
  which is exactly step B's order-aware board with manager timing. All four
  models print head-to-head on every DraftBacktest run.
