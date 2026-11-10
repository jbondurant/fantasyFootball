# Designed to win

Justin, 2026-11-09: "I don't care necessarily about individual weeks, nor individual
trades for now, I want this to be designed optimally to help me win."

This is the plan of record for that. It says what the repo is for, where it falls short
of that today, and the order things get rebuilt in. RUNBOOK.md stays the weekly
how-to; this file is why the weekly loop is changing. Update it as steps land.

## The goal, as one number

    W = P(title 2026) + b x P(title 2027)

Every recommendation is ranked and gated by what it does to W, and by nothing else.
`b` is Justin's to set (open question 1). The constraint is his other stated goal:
to be seen as a good trading partner.

## What an audit found (2026-11-09 and 10)

Five readers went through every lever against that goal. The reports are summarised
here; every number is from a repo output named beside it.

**Sound, and kept.** Title odds as the currency: the rest of the season played out on
paired draws, the bracket by formula (TitleOdds, SeasonTable). The whole-roster trade
search with its exact duplicate skip. A claim priced against the wire without the man.
The league's own FAAB price ladder. NewsCheck. The measured inputs (source accuracy,
trade precedents, play rates).

**Where it does not steer by its own currency.**

1. *Lists are ranked and gated in points, not title odds.* On the week-9 console page
   6 of the 12 SEND trades lower his title odds, and the biggest riser is 76th by
   points with 40 rows shown (data/console-2026-w9.html). The wire names a claim only
   at 11.3 points or more, a noise floor measured for an objective retired on
   2026-10-24: 12 of 13 saved reports say DO NOTHING (data/tuesday-swap-*.txt). Three
   trade lists disagree, and the only one ranked by title odds (the explorer) is
   outside weekly.sh.
2. *Nothing that expires is priced as expiring.* $92 of $100 FAAB is unspent, the most
   in the league, and the bid rule values a dollar at 1.5 points whatever the week. On
   2026-11-09 he had room for two adds with no drop (15 of 16 active, an IR slot open,
   two backs Out) and no tool raised it. No trade code knows the week-12 deadline.
3. *Only certainty is priced.* With a free wire and no injuries to come, a man who
   starts in no week on today's mean is worth exactly 0.0, so depth, handcuffs and
   stashes never appear. Ollie Gordon II was free on 28 September and went for $51
   while Justin held $100; he is RB17. Corey Kiner, behind Justin's Stevenson, was
   free on 7 November and on another roster by the 9th, the day after Stevenson's
   knee.
4. *Trades are searched on one pricing for both sides,* the same Sleeper numbers the
   other manager sees. A trade that gains Justin points nearly always costs the other
   man points on his own screen: 126 of 2,540 listed trades (5%) show him at zero or
   better, and 48 raise his title odds (data/trade-explorer-2026-11-09.json). Nothing
   estimates acceptance. One offer is on record this season; no trade has been made.
5. *2027 has no exchange rate.* One page adds keeper surplus to season points one for
   one, the other shows two columns. The surplus itself is an August number (Tuten
   +58.0 on every page since 14 September). 446 of the 2,540 listed trades send Tuten,
   his best keeper.
6. *The largest trade lever is shut by a rule that has no price on it.* Teams out of
   the race hold trades worth +5 to +8 of title odds at almost no cost to their own.
   The tanking line counts the season points they give up, which they can no longer
   win anything with. The best listed trade under the line is +3.7; the best with a
   team out of the race is +8.1 (same file). The rule is Justin's (open question 2).
7. *One source sits under everything.* Title odds are simulated on Sleeper's weekly
   numbers, which are stalest in weeks 15-17, where the title is decided (TRAPS #152).
   A search over 7 million trades favours whatever that one source overrates.
8. *Model details.* The bracket was fixed where the league re-seeds (corrected
   2026-11-10, worth about 0.1 of title odds). The team-error table drops from 8.5 to
   4.2 points at week 11 with no football reason. Title and playoff odds have never
   been checked against past seasons. Rivals never trade or claim.

## The redesign

1. **One currency.** W, for every list and every gate. A move is named when its
   change in W is beyond its own simulation error, and not otherwise.
2. **Price what expires.** A FAAB dollar, a roster spot, an IR slot and the trade
   window are each worth what they can still buy, and that falls to zero.
3. **Price what might happen.** Injuries to come and roles that change, so a handcuff
   or a stash has a value.
4. **Model the other manager by what he can still win** (this year's title, or next
   year's keepers if he is out of it) and by what his screen shows him. Send only
   offers that help him on both. Log every offer and its answer: refusals are data
   nothing else can supply.
5. **One best estimate.** A tested combination of sources under the simulation, with
   their disagreement shown as risk. The Check-a-trade tab's ten rows become the
   drill-down, not the decision.
6. **One page, one ranked list.** Claims, IR moves, offers and lineup changes together,
   in W. Everything else is reference.

## Build order

Ordered by what expires first and by title odds per unit of work.

| # | Step | Why now | Status |
|---|---|---|---|
| 0 | The league's real bracket (re-seeding) | every number rests on it | done 2026-11-10 |
| 1 | W: the 2027 rate (title odds per keeper point, and how much of a November surplus survives a year), keeper surplus from current numbers | every trade ranking needs it | next |
| 2 | Trades: one title-ranked list in the weekly loop; the default view is offers that help the other manager too (his screen, and what he can still win); FAAB as part of a package; an offer log | deadline is week 12 | |
| 3 | Wire, FAAB and IR in W: rank and gate claims by title odds, raise free spots and IR moves, a bid rule with a clock | $92 expires; weekly | |
| 4 | Consensus projection under TitleOdds (the plug-in is `TitleOdds.projections`), validated by ForwardAccuracy and RosModel | fixes finding 7 | |
| 5 | Injuries to come: availability draws or their closed form, so depth and handcuffs are priced; denial value of a claim | finding 3 | |
| 6 | Calibration against 2021-25, the team-error cliff | trust in the currency | |
| 7 | Park the research tools in COMPONENTS.md (9 of 218 are in the weekly path) | upkeep | |

## Open questions for Justin

1. **How much does 2027 count against 2026?** Default until he says: equally.
2. **The fairness line for offers.** Today: the other manager may not lose more than 20
   season points. Proposed: he may not lose in what he can still win, which is this
   year's title odds for a team in the race and next year's keeper value for a team
   out of it. That opens trades with teams out of the race when they get real 2027
   value, and closes ones where they get nothing they can use. On 2026-10-26 he said
   he wants "to avoid trading with teams out of contention", to be judged by him.

## Log

- 2026-11-10. Audit done. Bracket corrected to the league's re-seeding (Sleeper's own
  brackets for 2021 and 2024 show the 6 seed playing the 1 seed). Plan written.
