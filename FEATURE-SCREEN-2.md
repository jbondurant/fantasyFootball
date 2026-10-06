# Feature screen 2: angles a big projection model structurally misses

Screen 1 (`FeatureScreen`, TRAPS #149) tested 46 conventional factors against Sleeper's weekly
projection on 2018-2022 and none survived: best one-sided p 0.004 against a first multiple-testing
threshold of 0.0006, with effects near the top of 0.1-0.25% of RMSE. Justin's reading: an asymmetric
fight is not won with more of the same data. This file is the candidate list for a second screen,
from five brainstorm lenses (model artifacts, sharp bettors, human incentives, private and crowd
information, physical geometry) plus a sixth on money (contracts, cap structure, draft capital),
ranked, then checked against the files on disk by separate agents (2026-09-26).

Nothing here has been tested. The seasons held back for confirmation, 2023-2025, have been read by
no test.

## Data facts found on the way (each checked on the files)

- **Sleeper's DEF rows store the betting line Rotowire used.** `pts_allow` on each defence's weekly
  projection row tracks the closing implied team total of the offence it faces (r 0.94-0.998) in
  quarter-point steps. That makes "the line moved after Sleeper built the week" measurable.
- **The weekly projections are Rotowire's** (`company: rotowire` on the /projections array rows),
  and the array carries each projected man's team, which the /v1 map does not.
- **Sleeper's red-zone stats change scale in 2019** (rush_rz_att per carry 0.045-0.062 before,
  0.15-0.17 after): normalise per season, never mix raw counts across the break.
- **Sleeper's air yards count completions only** (Amari Cooper, 2023 week 4: 1 catch, 16 Sleeper air
  yards, 127 nflverse intended).
- **Snap counts before 2020 include special teams** (`snp`, not `off_snp`).
- **The player database's `age` field is stale**; compute age from `birth_date`.
- **nflverse's contract file stops at 2022** (no deal with year_signed >= 2023; `is_active` is a
  2022 snapshot; `inflated_*` values are look-ahead). Contract features are discovery-only until it
  is refreshed.
- **draft_picks.csv covers every season**, but its career columns (games, AV, Pro Bowls, totals)
  are look-ahead and must never be read. Sleeper ids reach a draft row through the database's
  gsis_id for ~63% of men; the rest need a name + team + week match through nflverse weekly stats.
- **ESPN's weekly reader drops carries and pass attempts** (stat ids 23 and 0); an ESPN-volume
  feature needs them read from the raw cache.

## Candidates, ranked (all computable before kickoff; data status in brackets)

Coherence: Sleeper's model against itself. Rotowire types each man's volume separately, and
nothing makes a team's lines add up.

1. **ESPN against Sleeper on volume** [2018-2025]. ln((ESPN projected carries + catches + 1) /
   (Sleeper's + 1)); two shops read roles from different depth charts. Expected: outcomes move
   toward ESPN's volume, most at RB.
2. **Touchdown budget** [2018-2025]. The team's projected player TDs against Sleeper's own implied
   points (the opponent DEF row's `pts_allow`), net of the team's TD share of scoring. A team
   handed too few TDs for its own line is under-projected.
3. **Passer's ledger against the receivers'** [2018-2025]. Receivers' projected catches, yards and
   TDs against what the QB is projected to complete (ratios run 0.79-1.30). Over-allocated receivers
   are over-projected.
4. **Crowd preseason premium** [2018-2025]. ADP (the FFC board, closed before kickoff) against
   Sleeper's own week-1 number, fading by week 6. The crowd absorbed camp reports that the
   projection shop leaves out on purpose.
5. **Stale line** [2018-2025; power higher in 2020-2022]. Closing implied team total against the
   `pts_allow` Rotowire built the week on. Movement after the snapshot never reached the player lines.
6. **Red-zone role against Sleeper's TD rate** [2018-2025, season-normalised]. Stable red-zone
   opportunity against the TD line that chases realised touchdowns.
7. **Carry budget overdraft** [2018-2025]. The team's projected carries against what it runs, with
   the spread accounted for. Overdrawn backfields are over-projected.
8. **Game script by role** [2018-2025]. Spread times a back's receiving share of his projection:
   catch-up targets for pass-catching backs on underdogs.
9. **How Sleeper split the vacated volume** [2018-2025]. A departed teammate's opportunities
   against how Sleeper re-allocated them, where a fast rule concentrates volume on the heir.
10. **Snaps ahead of touches** [clean from 2020]. Snap share rising before touch share does.
11. **Two desks on the pass rush** [2018-2025]. The opposing DEF row's projected sacks and
    interceptions against the QB row's.

Behaviour and incentives:

12. **Ball-security error last game** [2018-2025]. A lost fumble (RB) or drops (WR/TE) last week and
    the coach's doghouse.
13. **Script-inflated last game** [2018-2025]. Last week's usage surprise produced by a blowout,
    chased by the projection.
14. **Untouched line after a surprise** [2018-2025]. Volume inputs rolled forward unchanged after a
    usage surprise.
15. **Yards the runner owns** [2018-2025]. Yards after contact (RB) and YAC over depth (WR/TE), which
    stick where yards per carry is treated as noise.
16. **Milestone in reach** [2018-2025, rare]. 1,000 yards, 100 catches or 4,000 passing yards within
    reach late in the season, where yardage escalators and round-number chasing live.
17. **Lost-season audition** [2018-2025]. On a team out of contention, young men up and veterans down.

Money (Justin, 2026-09-26: "financial contracts, structures, incentives all play a role"):

18. **Capital edge in the position room** [2018-2025]. His draft capital against the best in his
    room: the staff breaks ties toward the pick the front office has to see work.
19. **Fresh capital behind him** [2018-2025]. A top-100 rookie at a veteran's position, taking the
    role over the season.
20. **Young high pick** [2018-2025]. Own draft capital in seasons 1-3: rope through slumps, or
    Sleeper over-pricing pedigree (either sign is informative).
21. **Contract year** [2018-2022 only]. The last season of his deal, fifth-year options included.
22. **First season on new money** [2018-2022 only]. Post-payday, strongest predicted for backs.
23. **Cheap one-year veteran** [2018-2022 only]. No sunk cost protecting his snaps.
24. **The paid man's share of the room** [2018-2022 only]. Cap dollars as usage protection, with
    unspent guarantees as a slump variant.
25. **No future here, season lost** [2018-2022 only]. An expiring, cheap or cuttable man on a dead team.

Private to this league [2021-2025 only; its own sub-screen, since it cannot use the 2018-2022
split]:

26. **Crowd pass**: a startable projection that no one in the 12-team league rosters.
27. **Manager override**: a man benched by his own manager against the number on the screen.

## Forward-only: worth archiving now, since no history exists

- Player-prop lines (yards and receptions medians, anytime-TD prices) against Sleeper.
- Line movement Sleeper did not follow: Thursday and Sunday snapshots of totals and spreads, plus the
  DEF rows' `pts_allow` at both times.
- Sleeper's own within-week revision: a Tuesday and a Saturday-night read of the weekly projection
  (the cache keeps one read a day).
- FantasyPros weekly consensus rank and expert spread against Sleeper's rank.
- ESPN start and roster percentages, snapshotted before each kickoff window.
- Sleeper trending adds and drops (`/players/nfl/trending/add|drop`).
- Justin's own read before kickoff: +1 / 0 / -1 against Sleeper for the men that matter, committed
  before the first game so the commit time proves it.

## How it would be run

Same protocol as screen 1, as its own family: each feature pre-registered with its definition and
expected direction, discovery on 2018-2022 leave-one-season-out, one-sided Clark-West against Sleeper
recalibrated with a curve, net of the betting line, standardised by placebo banks,
Benjamini-Hochberg at 0.10; the shortlist committed; then 2023-2025 scored once, Holm at 0.05. The
contract features cannot reach confirmation until the contract file is refreshed past 2022 or 2026
results accumulate, so they would run as a discovery-only family and be labelled so.

One caution recorded before anything runs: the ranking agent read screen 1's results and used them
to drop some ideas (weather for backs, for example). That touches only which candidates were chosen,
and the held-back seasons remain the honest test of anything chosen.
