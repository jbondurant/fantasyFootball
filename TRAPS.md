# Every way a draft model here has been wrong

Written 2026-08-31. Justin: "find a way of creating a model that doesn't fail
into our basic nonsensical traps." This is the catalogue. Each entry is a real
mistake this repo made, not a hypothetical, and each one should end up as a
failing-first test rather than as a paragraph somebody is supposed to remember.

The rule for the model that comes next: **these must be structurally
impossible, not merely avoided.** A model that could draft three quarterbacks
but happens not to is not fixed.

## A. Roster arithmetic

1. **Three quarterbacks.** The roster starts ONE. Justin keeps Purdy. Any model
   that drafts two has bought a third quarterback for a one-quarterback lineup.
2. **Two quarterbacks inside the first ten rounds.** Even two total is only
   defensible as a next-year keeper stash, late. `RankDraft` did this by pricing
   the wait in raw points; `BoardValue`'s first bench attempt did it by pricing a
   backup at 88 points at every pick.
3. **Keepers cost picks, at named rounds.** Tuten costs round 12, Purdy round 13.
   That is why there are 14 live picks and a 35-pick gap between 127 and 162. A
   model that assumes 16 picks, or that spreads the keeper cost evenly, is
   drafting a different league.
4. **Keepers are ON the roster and OFF the board.** Nobody drafts a man you
   already own, and the lineup slot he fills is filled. Until 2026-08-31 the
   backtest charged the two rounds and never delivered the two men.
5. **The roster is sixteen: ten starters, six bench.** Fourteen picks plus two
   keepers fills it exactly. There is no spare spot.
6. **A streamed player occupies one of those sixteen.** Picking a defence off
   waivers means DROPPING somebody. Crediting a streamed defence on top of a full
   roster hands the strategy a player nobody has - it inflated streaming by four
   points a season until Justin caught it.
7. **A roster with no tight end - or no quarterback - is not legal.**
   `ShapeSensitivity.legal()` tested only for a defence and waved through rosters
   that field nobody at TE, then scored the empty slot at zero.

## B. Scoring the outcome

8. **This league pays 6 for a passing touchdown, not 4.** Projections used the
   league's own settings while outcomes were graded from Sleeper's standard
   `pts_half_ppr`, understating every starting quarterback by 55-66 points a
   season.
9. **Two more rules differ**: fumbles (-1 here, 0 there) and the `pts_allow_14_20`
   defence band (+4.7 a season to every defence).
10. **The feed changed its own rules mid-history.** `pts_half_ppr` charged -1 per
    fumble in 2021, none from 2023, split in 2022. A season total is not a stable
    unit across a harvest. Score from RAW COMPONENTS, always.
11. **A dead default paid 0.4 for a passing touchdown.** Dead code is read as a
    statement of what the defaults are.

## C. Hindsight

12. **Filling a lineup by realised points.** Sort by EXPECTED, score on REALISED.
    Getting this wrong reversed several published findings.
13. **The waiver-wire rate was set by hindsight** - the pool was chosen by
    preseason rank, then sorted by REALISED rate and the best quarter averaged.
    A comment directly above it claimed the opposite. It inflated streaming by
    ~1.1 points a week and reversed the defence conclusion.
14. **An earlier wire took the MAX over undrafted players**, which is the same
    fault louder.

## D. Inference

15. **The season is the unit of independent randomness.** Slot and opponent
    variation are exhausted: infinitely many of both moves the standard error
    from 44.0 to 43.4. 480 draws are not 480 observations.
16. **The bar is real and large.** 125 points at five seasons, ~95 at sixteen.
    Every gap smaller than that is a tie, including gaps we spent days ranking.
17. **Selection optimism is +126** for a 14-slot shape fitted on four seasons and
    met by a fifth.
18. **Argmax of a noisy field.** Nothing in the smoother bake-off cleared its own
    bar; picking the lowest number would have been selection, not measurement.
    Choose from the middle of a flat basin.
19. **Bounded per-decision estimands resolve; unbounded point totals do not.**
    A bench marginal in points: +5.4 ± 3.9, t = 1.4. A win rate on the same
    seasons: t = 7.69. Prefer a question with a bounded answer.
20. **A rank correlation is not a regression slope.** Spearman was shipped for
    weeks as a shrinkage coefficient, which needs points.
21. **A parameter that cannot be identified should not be tuned.** The trust
    coefficient's bar covered both 0.578 and 1.0.

## E. Cross-position comparison

22. **Raw points are not comparable across positions.** A quarterback's rank
    curve is steeper in absolute points because he scores more. Pricing the wait
    in raw points cost `RankDraft` 68 points and over-bought quarterbacks. Use a
    MARGINAL against a filled lineup, which needs no replacement level chosen by
    hand.
23. **Positions do not drain at the same rate.** Assuming a uniform decay
    - `(next - pick) / 5` - threw away the only signal the model had and drafted
    `TE TE QB QB`.
24. **A within-position matrix knows nothing about what a position is worth**,
    nor about what is already on the roster.

## F. Model discipline

25. **A default that means "never deviate" reproduces the committed plan and
    looks perfect.** `-Pdeviate` defaulted to 1e9.
26. **Model A is a rounds 1-7 model.** Two keepers plus seven picks fills the
    starting nine, after which its objective is indifferent and prints
    whatever. Scoring it as a 14-round strategy measures nothing.
27. **Prose drift.** Three times in three days a comment described a mechanism
    the code did not implement - most damagingly a comment that specifically
    denied the hindsight sitting twelve lines below it. Comments on an objective
    are load-bearing: they are what gets quoted when someone asks what the model
    believes.
28. **A mean-based lineup makes every bench man worth exactly zero**, because an
    average cannot beat a better average. Pool the neighbourhood as a
    DISTRIBUTION instead; the spread is the only part that pays a bench pick.
