# Projecting a FAAB bid — the plan

Written 2026-09-05, after measuring 1,448 settled contests from this league's own
five seasons. Every number below comes from `FaabBid -Pfit` or a probe over the
same harvest; nothing here is assumed.

## The finding that shapes the plan

**Nothing observable about a player predicts what he costs.** Correlation of the
clearing price with:

| feature | correlation | observable before bidding? |
|---|---|---|
| his projection for the claimed week | −0.04 | yes |
| what he scored the week before | −0.04 | yes |
| his projection JUMP week over week | **+0.08** | yes, but see below |
| total FAAB left in the league | +0.02 | yes |
| the richest rival's FAAB left | +0.00 | yes |
| week of the season | −0.02 | yes |
| what he went on to score that week | +0.14 | no — hindsight |
| **how many managers bid** | **+0.50** | **no — that IS the thing** |

Adonai Mitchell cost $53 on an 8.2 projection. Isaac Guerendo cost $75 with a
projection of 1.0 the week before and 1.0 the week of.

**Justin's mechanism explains it**: what moves a man is an EVENT — the starter in
front of him injured or traded, the man himself traded, a piece of news. Not his
level. The projection jump is that event's observable trace, and it is the only
feature that flips the sign. It is weak for a data reason rather than a
theoretical one (§3).

## 1. The decomposition: do not project the price, project the demand

Price given demand is already strong, monotonic, and measurable **today** from
the existing harvest:

| managers bidding | contests | median | 75th | 90th | max |
|---|---|---|---|---|---|
| 1 | 950 | $0 | $2 | $5 | $71 |
| 2 | 290 | $2 | $5 | $9 | $31 |
| 3 | 116 | $3 | $7 | $12 | $35 |
| 4 | 47 | $5 | $7 | $16 | $37 |
| 5 or more | 45 | **$15** | $33 | $48 | $76 |

So the problem splits cleanly, and only one half is open:

- **price | demand** — done, 1,448 contests, no new data needed
- **demand | the week's news** — unbuilt, and the whole difficulty

A projected bid is then `P(win | bid, predicted demand)`, and the bid follows by
maximising `P(win) × (worth to the roster − bid)` as `FaabBid` already does.

## 2. Why the demand half cannot be built from history

Three hard limits, each verified rather than supposed:

1. **Sleeper serves one settled projection per past week, with no vintage.** For
   a finished season it is not what the market saw on waiver Tuesday, so a jump
   measured against it is not the jump the bidders reacted to.
2. **The weekly projections feed carries stats only** — no team, no injury
   status, no position. Who was hurt and who had just been traded is not
   reconstructible at all.
3. **Rosters are only available as they are now.** Positional thinness — how many
   of the twelve teams needed a back that week — cannot be recovered. (Remaining
   FAAB *is* reconstructible by summing winning bids, and it predicts nothing.)

The mechanism is right and the record cannot show it. The answer is to
instrument, not to fit the weak proxy and call it a model.

## 3. Phase 1 — instrument, starting now (no modelling)

Every day of the season, capture what a Tuesday bidder could have seen:

- **projections per player** — `AdpSnapshot` into `data/projection-snapshots.csv`.
  Already running; extended from "to the draft" to the whole season on
  2026-09-05. A day missed is a day of news that cannot be recovered.
- **each roster's men and remaining FAAB** — new; the rosters endpoint carries
  `settings.waiver_budget_used` per roster.
- **the free-agent pool** — derivable from the rosters snapshot.

Then each waiver clearing joins to the state 24 hours before it, giving the row
that does not exist today:

```
season, week, player, clearing_price, bidders,
proj_3d_before, proj_at_clear, proj_jump,
teams_thin_at_his_position, budgets_remaining, days_since_he_became_free
```

Cost: one command a day, already in the `/today` brief. Yield: ~250 rows a
season, each with the features history cannot supply.

## 4. Phase 2 — the demand model (after roughly one season)

Predict the **number of bidders**, not the price:

- projection jump over the three days before the clearing (the news)
- how many of the twelve rosters are thin at his position
- remaining budgets (weak alone; may matter conditional on demand)
- whether he only just became free

Then compose with the table in §1.

**Held-out validation, and the falsification stated in advance:** fit on all
seasons but one, predict the held-out season's bidder counts. If predicted and
actual bidder counts correlate below **0.30**, the demand model has failed and
the unconditional distribution stands — which is what `FaabBid` already ships.
That number is written here, before the data exists, so it cannot be moved later.

## 5. What Justin does in the meantime

`FaabBid -Pvalue=<points>` already gives the right answer under the honest model:
53% of claims clear at nothing, a dollar wins 53% of the time, three wins 69%,
eight wins 89%. For a man genuinely worth 25 points, bid $6 if a dollar is free,
$3 if a dollar now is worth two later. Against a contested field, $7.

The one judgement the tool cannot make for him: whether THIS man is the one
five managers want. When the answer is obviously yes — a lead back's handcuff the
Sunday his starter goes down — §1 says the median jumps to $15 and the 90th to
$48, and that is the moment to spend.
