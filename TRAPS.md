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

## G. The live path

Added 2026-09-01, from the adversarial pass over Model A and `DraftNight` -
the half of `Draft2026` that had never had one. `ModelAAudit` is the tool;
`ScheduleDriftTest` is the pin.

29. **A pick of a man the board does not carry consumes no slot.**
    `DraftSimulator.stateAfter` increments the schedule INSIDE its
    `board.contains` guard. That is right for a keeper - the loop above has
    already eaten his keeper slot - and wrong for a kicker, a man past the ADP
    cut, or an id we do not know. From that pick on, every seat is priced one
    early and every player is attributed to the wrong manager, which is how
    both live tools build Justin's roster. Not changed the night before a
    draft; DETECTED, and both tools print it.
30. **A pick NUMBER is not a pick COUNT.** The detector for 29 first shipped as
    `slot.pickNumber() != taken.size() + 1`. A keeper slot is a pick number
    that consumes no pick, and this league has twenty-four of them with the
    earliest at pick 32, so on a perfectly clean 168-pick replay that test
    fires at 137 of 169 refreshes, starting in round 3, and tells Justin all
    night to distrust a tool that is working. Count LIVE slots.
31. **`slotOf` and `branchWith` must mean the same slot.** `slotOf` scans
    forward past keeper slots and does not write the index back, so a state
    resting on a keeper slot reported one pick and branched into another -
    the man credited to the keeper's owner, costing no live pick, leaving one
    extra real pick before the brancher's next turn. `WaitCheck`, Model A's own
    wait-or-take table, branches straight off a state from `stateAfter`.
32. **A round test standing in for a roster test is exact only on one roster.**
    Model A goes indifferent when the starting NINE IS FULL, not when the round
    turns 8. Measured: playing Model A's own plan the spread is 2.75 at round 7
    and exactly 0.00 from round 8, so the boundary is right to the round - but
    on a roster that still owes a tight end at round 8, which is the shape the
    RUNBOOK itself recommends, the objective still discriminates and
    `Draft2026` has already gone silent.
33. **Four engines are not four opinions.** `LiveCommittee`'s `hindsight` and
    its `lookahead` at depth 1 are the SAME estimator - one-position
    `HeadPolicy`, `simulateFrom`, `bestNine` - differing only in seed offset and
    sample size. They agreed 9 of 9 on the live board. The comment above them
    says hindsight solves each scenario exactly with no tail policy, which is
    prose drift (F27), and the duplicate carries two of the four votes.
34. **A plurality with no tie rule is a tie rule.** `vote` tallies into an
    `EnumMap` and scans with a strict `>`, so a 2-2 split goes to whichever
    position is DECLARED first - QB before RB before WR before TE - and prints
    as "2 of 4 engines say RB" with nothing to say it was a coin flip. It
    happened at round 3 on the real board, in the rounds that matter, and in
    the direction the keeper slate has already gutted.

## H. The night before the draft

Added 2026-09-01, later the same day, from two more adversarial passes and from
following the measurements where they went. Several of these are faults in code
written that morning to fix the ones above - the catalogue is not a record of
old sins.

35. **A silent estimator is worse than a loud one.** `PolicyTournament` buckets
    the board into maps keyed by SKILL positions and indexes them with the
    player's own position, so the first DEFENCE threw. Defences joined the board
    when DEF entered `PairwiseOdds.CAP`, and `Draft2026` forces
    `scheduleRounds=16`, so they are always there: the Kim-Nelson arbiter had
    NEVER RUN in the tool Justin uses, and the catch reported it as an ordinary
    fallback. Its own comment claimed "45-64 rollouts, 1.2s worst case" - a
    number measured while it was dead. The real cost is 8.4s. A `catch` that
    prints only `getMessage()` cannot locate a NullPointerException; name the
    frame.
36. **The legend must name the column the code ranks on.** The footer under the
    table said VS WAIT "is what to rank on". The sort has always ranked on END
    TEAM. They disagree in practice, so a reader following the printed
    instruction takes a different player than the tool recommends. Ten instances
    of prose drift in this project and this was the first in text the user
    reads.
37. **A greedy tail with no legality constraint prices an impossible roster.**
    `rolloutRoster` was pure marginal capped by `MOST`, so it often finished
    with NO DEFENCE and was then charged the streaming penalty - which made
    taking a defence NOW look like the only way to ever have one. A defence in
    round 7 or 8 in five of six drafts. Reserve seats for unfilled named
    starting slots, derived from the lineup, never typed.
38. **A rank must index the list its curve was built from.** `projectionRanks`
    ranked the whole pool while `thisYear` builds the curve from the DRAFTABLE
    pool, so every held man was priced against twenty-four players who are not
    in it - Chase 29 points under his own projection. The fix is not to add the
    keepers back: putting them in the sorted list shifts everyone below them
    instead (Penix, 51 points). Slot them in by counting how many draftable men
    beat them.
39. **A refused pick must still occupy its seat.** TRAPS A1 again, still open
    after being named: a man the rules declined went into a print list and never
    onto the roster, so a ceiling of two was counted against one and `full()`
    read fifteen on a roster of sixteen. `MOST` was the only thing standing
    between that and a third quarterback. Record him - and NOT through `draft()`,
    which must keep refusing, or no model can be stopped from PLANNING an
    illegal roster.
40. **One failed cycle must not end the night.** `Draft2026` had no guard
    between freezing the picks snapshot and thawing it, and the engine that
    could throw - the board model, the one read first and the only one speaking
    after round 7 - was the one NOT wrapped, while the second opinion was. One
    refused read from Sleeper and he re-pays the whole warm against a sixty
    second clock.
41. **A harness that does not warm what the tool warms certifies nothing.**
    `LivePathStress` never called `warmSurvival`, so every rollout it exercised
    fell back to the estimator the survival table had retired. "70 picks priced,
    0 throws, defence at round 10" was true of a configuration nobody runs, and
    it went into DRAFT-READY as verification of one that does. Print which rule
    was measured.
42. **A model validated only against its own simulations is not validated.**
    Every measurement of the survival table - 2.69 to 0.08 - scored it against
    more draws from the model it is built from, which cannot separate a right
    table from one reproducing its own generator. On the league's REAL 2024 and
    2025 drafts the same table scores 1.19 against the cutoff's 3.35: the change
    holds, and the 0.85-to-1.19 gap is the misspecification the simulated tests
    are structurally unable to see. The same test then REVERSED a judgement
    made hours earlier on simulated evidence alone - the room-observed term in
    `drain`, kept on the argument that a simulation cannot exercise
    misspecification, and dropped when a real draft could.

## I. Checks that are not checking

Added 2026-09-01. Four in one day, which makes it a category rather than an
accident. None of these is a bug in the model; each is a piece of verification
that looked green while verifying nothing, which is worse than having none,
because it stops anybody looking.

43. **A suite nobody runs.** `./gradlew smokeTest` - the live-API check the
    build file has always said to run before a draft - was red for hours while
    `./gradlew test` stayed green and I ran only that. Green on the suite you
    happen to run is not green. The fix is not remembering: `check` now runs
    what is cheap, and DRAFT-READY names the one that is not.
44. **A build red long enough to become scenery.** `./gradlew javadoc` had
    failed for months on eighteen cosmetic HTML complaints, and hiding among
    them were two `{@link}`s to classes that no longer exist. Turn off the
    check that costs readability, keep the one that finds stale documentation,
    and hang it off `check` so it cannot rot again.
45. **A test that cannot see its own input.** `DraftReadyCommandsTest` reads
    DRAFT-READY.md. Gradle did not know that, so the task was UP-TO-DATE and
    the test never ran - it passed with a deliberately planted dead command in
    the file. Declare the documents as task inputs. A check that cannot observe
    the thing it checks is decoration.
46. **A plant that does not land looks exactly like a vacuous test.** Testing
    whether `TableLegendTest` bites, I planted a forbidden string, saw the test
    pass, and nearly recorded it as vacuous. The plant had not applied - my
    replacement text did not match the source. It bites. VERIFY THE PLANT
    LANDED before believing what its absence tells you, or the test-the-test
    habit produces false findings of its own.

## J. The wrong population

Added 2026-09-01, evening. Justin named the class - "look for other such
oversights" - after the first one, and every genuine fault found afterwards was
an instance of it: a statistic computed over one set of men and applied to
another. Distinguish it from a SCALE error, which never reaches a tree model at
all (a tree splits on thresholds; monotone rescaling changes nothing). Scale
errors were chased for an hour and found to be nothing. Population errors were
real every time.

47. **Replacement level taken from the choice set.** The scarcity feature's
    baseline was the median of a position WITHIN the top sixty by ADP - three
    or four tight ends, so about TE2. It computed "better than TE2", which the
    cliff feature already says, and did nothing. A null result where theory
    predicts a signal is evidence of a logical failure, not evidence about
    football.
48. **Replacement level with the keepers still in it.** Rebuilt from the full
    points map, which contains the twenty-four kept men - seven tight ends this
    year - so the TE12 it found could not be drafted by anyone. Take it from the
    board that actually exists.
49. **A baseline that moves.** Passing the CURRENT board recomputed replacement
    every pick, so it drifted down as the pool emptied and inflated late
    surplus - worse than no feature (13.6 vs 12.4). Replacement is a property of
    the pool you started with. Fix it at draft start.
50. **Keepers counted as draft decisions.** RoomFidelity built the "real"
    positional timing from every pick, keepers included, while the simulation
    never drafts one. Corrected, RB went from 5.0 to 8.0 and QB from 15.6 to
    12.4 - the position called solid was the worst of the skill three, and the
    one called worst was not. The measurement was contaminated in a way that
    reversed the conclusion.
51. **A keeper's round as evidence of when a room drafts.** floors() counted
    McBride's round-3 keeper slot as a tight end being drafted in round 3. The
    commissioner assigned that round; nobody chose him there.
52. **Bench rates from the wrong round band.** benchGuidance advises round 8+
    from rates measured on rounds 8-9, and fires through round 16 where the
    true rates are 40-95% lower. Same ordering, wrong magnitudes on screen.
53. **A retracted justification left in shipped code.** The defence curve
    stayed flattened for hours after the 0.019 spearman behind it was
    withdrawn - measured on ADP order and applied to a projection curve. The
    claim was retracted in conversation; the code kept running. Retract in a
    commit or it did not happen.
54. **Comparing the best cell against the best cell.** BoostLab reports its
    best tree configuration, which changes between runs, so on/off comparisons
    of a feature were made across different models. Compare the SHIPPED cell.
55. **No noise floor before comparing variants.** Every room-model tune was
    read as signal at 0.1 to 1.0 points. The seed-to-seed spread of the same
    measurement is 0.8 to 1.8. Not one tune was distinguishable from dice. The
    floor should be the first tool built, not the last.
56. **A test that restores the wrong value.** ModelAScheduleTest captured "the
    previous value" on every call; the second call stored "9" and @AfterEach
    restored 9 into the JVM for every class after it, and a nine-round board
    carries no defences. Capture once. Or fork a JVM per class and have nothing
    to leak into - which is what the build does now.
57. **A number written before its measurement ran.** I typed "spearman -0.32
    over nine managers" into DIAGNOSTIC.md and a commit message while the tool
    that would produce it was still in the same shell command, then ran it: 0.01
    over ten. Same conclusion, fabricated figure, in the document whose whole
    subject is numbers being wrong. Write the number AFTER the tool prints it,
    or write "pending" - never a plausible placeholder.
58. **A line-number citation and an edit above it, seconds apart.** RosterRules
    prints "RUNBOOK.md:191" on screen when it refuses a second quarterback. I
    wrote a test to pin that citation and, in the same command, edited RUNBOOK's
    fallback ladder above line 191 - moving the rule to 206 and making the code's
    citation stale before the test could run. Line numbers in code are references
    that nothing updates. Re-check them after ANY edit to the cited file, or
    cite an anchor rather than a number.
59. **The gate's sample size quoted as the model's.** "435 rows" appeared in
    DIAGNOSTIC.md, the agent definition and a memory file as the size of the
    shipped room-model fit. It was BoostLab's 2024 gate set - 2021-2023, rounds
    1-13, actually 423. The shipped fit is 857 (2021-2025, rounds 1-16;
    `TrainingRows`). The conclusion drawn from it was measured on the right fit
    and stands; the number under it was the wrong population's. Even the
    document about population errors had one.
60. **A schedule built once and never re-read.** The seat order comes from
    Sleeper's draft_order at warm and AAAConfiguration caches that JSON for the
    life of the process. A pick trade during the draft changes who owns a seat on
    Sleeper and nothing in the tool; minePicks walks the stale schedule and the
    slot-count drift detector, which only counts, cannot see an owner swap. The
    feed sends picked_by on every pick and fetchPicks parsed it and threw it away.
    Compare it to the schedule every cycle, say how many picks were compared, and
    tell him to restart. Found by asking "what could still bite tonight that no
    measurement covers".
61. **A snapshot with two halves and a second freeze path that fills one.**
    freeze() captured picks and their owners together; freezeWith(), used by
    every offline harness, captured picks only. The owner half fell through to a
    live network fetch inside tools meant to run offline and compared real owners
    against simulated pick numbers. Same fault as the warm-up divergence: one
    thing assembled two ways. Caught reviewing my own commit within the minute;
    a harness run would have shown it as a mismatch warning on a clean board.

62. **A test that pinned a coin flip.** `ModelAScheduleTest` demanded that the
    nine-round and sixteen-round plans agree exactly in rounds 1-7. Under the
    evening projections (re-fetched 20:10 for the draft) the two schedules
    landed on opposite sides of the round-2 RB/WR choice - the nine-round plan's
    own gap between them was 0.8 points against a two-standard-error tie of 4.2.
    That is the same coin flip Justin faced live at pick 18. The suite went red
    after the draft on a fact the model had been saying all along: round 2 is
    a tie. Fixed by asserting agreement only where the nine-round plan has a
    clear preference (gap beyond two standard errors) and printing the coin
    flips. A shape test that ignores the margins is asserting the seed.

63. **A scheduled job that cannot read the files it schedules.** The daily
    `AdpSnapshot` was installed as a launchd agent and smoke-kicked: zsh could not
    open its own script under `~/Documents`. Not a path error - a launchd-spawned
    `ls` lists the file, a launchd-spawned `head` gets "Operation not permitted".
    macOS protects Documents from background agents, and no dialog appears for
    one. Caught only because the job was kicked once by hand after installing;
    a job installed and trusted would have failed silently at 09:30 every day.
    Never install a schedule without running it once from the scheduler itself.

64. **A timing instrument that measured a different question every day.**
    `CycleTiming` froze whatever the live draft held. Before the draft that was
    a pick-1 board (the "25s" in DRAFT-READY); after it, a finished draft with
    Model A silent (11s, measured 2026-09-02 and nearly written up as the
    committee's speed). The real pick-7 board on draft night cost 42s. Now it
    freezes the first six picks of the real 2026 draft - one board, one
    question, reproducible. An instrument whose input drifts is not measuring.

65. **A suite that read the live league, the morning after the league changed.**
    Four tests written against the pre-draft league (two keepers per roster,
    no pick made) failed on 2026-09-02 with no code change behind them: Sleeper
    had emptied every roster's keepers field overnight and the planner derived
    "kept" from it. Three hours of a check went to a league with no keepers.
    The suite now reads the league's state from `data/fixtures/2026-pre-draft`
    (`-DfixtureDir`, set only for unit tests); feeds still float. A test that
    reads the world is a test of the world, and the world does not hold still.

66. **A floor that says when a defence CAN go, and nothing that says one MUST.**
    The learned floor kept simulated defences out of the early rounds; no rule
    made a roster end legal. Across 200 simulated 2026 drafts the room left a
    defence slot empty in 12% of rosters and a quarterback in 2% - the league,
    never - and an empty slot scores zero, so every seat's expectation sat ~10
    points under what the league drafts. `DraftSimulator.mustFill` now confines
    a seat with no more picks than empty slots to those positions. Neutral on the
    held-out seasons (RoomFidelity within 0.2 of baseline at every position).

67. **A "collapse" rule that was really a disagreement rule.** First version:
    any man whose projection rank sat 60 places below his ADP rank was re-slotted
    to the price of his projection. It fixed Jacobs and made QB timing on the
    held-out seasons 2.5 points worse - beyond the noise floor - because that
    disagreement is systematic (the feed underrates rookies, the league pays six
    for passing touchdowns) and the positional terms already carry it. Rewritten
    as `RecentCollapse`: a drop of 30% or more inside the last two weeks in the
    projection archive, which is news in time, not opinion. Fires on Jacobs alone
    today; inert on history because the archive begins 2026-08-25. Fidelity
    unchanged. A rule is named by what it detects, not by the case that motivated it.

68. **An A/B whose two arms agree to the decimal is one arm.** The off-arm passed
    its flags through an unquoted zsh variable; zsh does not word-split, so
    `-DnoNeed=true -Dcollapse=0` arrived as ONE property named noNeed with the
    value "true -Dcollapse=0" - false to Boolean.getBoolean - and both arms ran
    with the rules on. Caught because the outputs matched exactly, which real
    arms never do. Pass flags literally, and read identical arms as a harness
    fault before reading them as a result.

69. **A player lookup inside the choice loop.** The first `mustFill` resolved
    each candidate's position through `Player.getPlayerFromSIDV2` - a regex
    match and an index check - for up to sixty candidates at every late pick of
    every simulated draft. The unit suite went from 13 minutes to 4 hours 32
    (HeldManRankTest 104s to 5565s) and nothing failed, so nothing said why.
    Positions are now resolved once per simulator. Per-class times are the
    check's second output; read them, because a green check that took twenty
    times longer is telling you something.

70. **A kill loop that matched its own shell.** `ps | grep RoomFidelity` inside a
    script whose own command line contains the word RoomFidelity finds the shell
    running it; `kill -9` on that list ends the script before its first real
    line and the task reports exit 1 with an empty log. Twice in one evening,
    once as `pgrep -f DraftExpectation`. Match the java process, not the word:
    `awk '$2 ~ /(^|\/)java$/ && /RoomFidelity/'`.

71. **Three settings that agreed to the decimal because the lever was not
    connected.** The floor-as-prior was measured at weights 0, 0.05 and 0.2 on
    three held-out seasons and every number matched exactly, which I read as
    "the model already gives an early defence no probability". The historical
    fidelity board had no defences on it at all - `DraftSimulator.forSeason`
    kept skill positions only, the nine-round game's rule - so the simulated
    room could not draft one on any past season and `RoomFidelity` had never
    printed a DEF row. With defences on the sixteen-round board the arms differ
    (DEF gap 23.8 / 22.4 / 21.2). Identical arms are a harness question first
    (TRAPS #68); when the harness is clean, the next question is whether the
    thing being varied can reach the thing being measured.

72. **A report pinned in place of a feed.** To hold the suite on one board I
    pointed it at the AdpSnapshot archive - the CSV rows the daily job writes.
    Six fixtures broke: "the board must carry defences", "the fixture needs a
    projected man past ADP 300", a null sleeper id. The archive is a REPORT of
    the drafted pool (ADP 250 and under, skill positions in the ADP file); the
    feed is every man Sleeper knows, defences and the undrafted included, and
    the fixtures were written against the feed. The pin is now the raw
    draft-night response, served through the same fixture directory as the
    league snapshot. A report derived from a feed is not the feed.

73. **A counterfactual that hands the owner his own stars back.** "Seat alone"
    was built as "this owner keeps nobody, everyone else as declared" - and his
    two men went back on the board, where he was the seat best placed to
    redraft them. jerem9604's slot 9 read a peak (Taylor and Bowers available
    to him), Hamrliks' 10 a lift (Chase Brown), tommyrads' 11 a 24-point hole
    (Warren and Caleb Williams returned, worth little). Justin read the column
    and asked whether the bumps were noise or the room's dispersion; they were
    neither. The planner already had the right primitive - phantomOwnKeepers:
    off the board, no credit, no slot burned - and the column smooths once it is
    used. A per-owner counterfactual must not vary in what it puts back on the
    board, or the column measures the owners' rosters and not their seats.

74. **A pair priced one man at a time.** The ladder's "best pair" took the
    ledger's two highest standalone deltas and handed them to the planner as
    two keepers - Watson r10 and Stafford r10 for BHier. The league does not
    allow two keepers at one round: the ruleset moves the LOWER-ADP man - the
    more valuable of the two - a round dearer (KeeperPricing, applied by
    KeeperChooser.priceHypothetical; the one case on record, 2025 Jeudy and
    Daniels, went the other way and is carried as a known exception). Priced
    separately the pair shared one slot, the second man burned nothing, and
    the gain read 30.7 instead of 27.5. Justin remembered the rule from the
    table alone. Pairs are priced as pairs, and the planner now says so out
    loud when two keepers of one manager arrive at the same round.

75. **The two highest standalone values are not the best pair.** "Best pair"
    took the ledger's two largest one-man deltas. Standalone values do not add:
    beside Watson, Stafford is worth +2 on the ladder's yardstick while Pitts -
    the man BHier actually kept - is worth +15 and costs round 13 with no
    same-round collision. Justin read the table and asked whether Watson and
    Pitts was the pair. Rung 3 now searches every legal pair among the owner's
    top ledger candidates and his kept men, each priced as a pair, and takes
    the best. A pair is a joint object; rank pairs, not men.

76. **Phantom the others, not the man being valued.** The first sixteen-round
    keeper ladder valued each man "alone" by keeping him and asking the planner
    to phantom the owner's keepers - and the flag phantoms ALL of that owner's
    keepers, the candidate included. His credit still arrived through the
    scored roster, so the number was not zero; it was the man plus a free
    round-12 pick, because a phantomed man burns no slot. Tuten read +75 with
    a spare pick inside it. The planner now takes an explicit set of men to
    phantom (`forCurrentSeasonAs(..., phantomIDs, ...)`), the candidate is kept
    at his priced round, and the season-total ladder's alone and pair worlds
    use the same primitive - they had been EXCLUDING the other declared men,
    which puts them back on a board where their own owner is the seat best
    placed to redraft them (#73 again, one rung down).

77. **A silent pipeline is not a hang.** A one-owner probe printed nothing for
    ten minutes and was killed as too slow; the objective was then profiled at
    0.2 ms a roster and the search for the missing nine minutes began. There
    were none: stdout went through `grep -v | head`, and grep block-buffers
    when its output is a pipe, so every line sat in a buffer until the kill
    took them with it. Written straight to a file the same run finished in 57
    seconds. Before profiling a program that prints nothing, check that it
    could have printed anything.



78. **The declared copy of a keeper beat the priced copy.** A ladder prices a
    man for the world it is building - alone at his own round, or as one of a
    searched pair with the same-round bump applied - and hands him to the
    planner as an extra keeper. The planner skipped any extra whose man was
    already declared, so a declared man kept his DECLARED round in every
    counterfactual: Renteez's Javonte Williams was charged the bumped round 5
    while the report printed round 6, and when a pair partner had been priced
    onto the declared round the two shared one slot, the second burned nothing,
    and the owner drafted fifteen live men plus two keepers - seventeen against
    sixteen everywhere else. A collision now throws instead of printing; extras
    replace declared entries; and every scored roster is counted.

79. **A fixed random sample is a bias, not noise.** Each man's outcome scenarios
    were drawn once at construction from his position:tier cell, independently
    per man, and reused in every trial. The trial-to-trial error saw none of
    that: the man being valued sits in the ALONE arm only, so the sampling
    error of his own sixty draws was a constant offset the +/- could not
    contain, unchanged on rerun because the seed was fixed. Draws are now a
    shuffled copy of the cell walked in order, so the sample mean is the cell
    mean. Two more in the same lines: the weekly spread divided by
    `max(1e-6, meanWhenPlaying)`, which is a hundred-thousand-point week for a
    season that scored below zero (the algebra needs no division: sd x
    projection / tier season); and the week was floored at zero, which lifts
    the high-variance positions above their own mean - defences most - while
    the constant wire they compete against gets no lift.

80. **A rank counter that skipped the unmatched.** Pool tiers came from a
    counter advanced only after a historical name joined to a Sleeper id, so
    every unmatched man above a player pulled him up a place and a cell was
    computed over better seasons than the tier it is applied to. Fixing the
    pool alone was tried and reverted within the hour: PlanBacktest, the
    predictability tools and WireRateStress built their boards from the
    matched men too, and a pool ranked one way against boards ranked the other
    is worse than both ranked wrong together. Fixed properly the same day by
    ranking every playable source row everywhere - the pool (which now prints
    who failed the join), the historical board (which carries each man's
    source rank; `PlanBacktest.Board.rankOf`, `tiersOf`, and
    `expectedFromRank(board, pool)` read it), the defence-wire loader and the
    two predictability tools. Who had been dropping out inside the first three
    tiers: Aaron Rodgers 2023 (QB11), the Washington Football Team 2021 (DEF3),
    J.J. McCarthy 2024 (QB21), Gabriel Davis 2022 (WR25), Joe Mixon 2025 (RB31).
    Measured effect: skill cells moved by at most 0.3 points a game (RB 25-36
    n 60 to 58, TE 13-24 60 to 58, TE 25-36 55 to 49, QB 60 to 59); the defence
    wire moved more - holding the best undrafted defence 6.44 to 6.98 a week,
    streaming on form 7.69 to 7.73, so the streaming-over-holding ratio fell
    from 1.19 to 1.11 and the hindsight premium from 1.06 to 1.02 a week (both
    moved again under #84's regrade, to 1.11 and 0.98)
    (`data/outcome-distributions-2026-09-04.txt`,
    `data/wire-rate-stress-2026-09-04.txt`). PlanBacktest's strategy table did
    not move at all (`data/plan-backtest-2026-09-04.txt` against 2026-08-29):
    it scores fixed sequences on real outcomes and takes only the streamed
    defence's price from the pool, 8.7 to 8.8 a week. Defence bands moved:
    DEF10-12 129.5 to 127.2 a season, so `LateRoundValue.DEF_WORST_BAND` was a
    week-old number and is now read back out of the report by
    `BandRegressionTest`, as the wire rate already was. The change also exposed
    a second population fault one layer up (below).

    One thing this did NOT reach: the era/nflverse ingest (`EraBoards`,
    `DetectionLag`, `LateHalf`, and `BoardValue` reading them) still ranks
    after its own join. The other leftover - the live board tiering by
    projection rank while the pool keyed its cells by ADP rank - was settled by
    measurement instead of by patching one to match the other (#82).

81. **The same seasons on both sides.** WireRateStress's drafted-against-
    streamed table required a season's top twelve defences to be twelve joined
    men. Under source ranks 2021 has eleven - the Washington Football Team
    never joined, at rank 4 - so that season silently left the two held columns
    while the streamed column kept it, and the mean line subtracted a
    four-season average from a five-season one under a header reading "same
    seasons". The table now averages what joined, prints how many, and takes
    every mean over the seasons present on both sides. A guard that drops a row
    from one column of a comparison has to drop it from the other.

82. **Which order is the right key is a question with an answer.** The live
    board assigned today's men to outcome cells by projected points; the pool
    keyed those cells by draft position. Two orders, and the instinct was to
    pick one and make the other match. `RankKeyChoice` asks instead which key
    PREDICTS: leave one season out, take the mean realised season of the men
    who shared a man's rank band in the other four, and see which band mean
    lands closer. Both orders come from one feed, so they rank the same men and
    differ only in the key. Projections win by 6.2 +/- 0.8 points of mean
    absolute error against the FantasyPros board the pool was built from,
    positive in all five seasons separately and worth 7 to 11 points at
    quarterback, back and receiver; tight ends and defences are a tie. So the
    live board was right and the pool was wrong, and the pool now keys by
    projection - which also deletes the name join, because the projection feed
    and the weekly actuals are both by player id. The keeper answer did not
    move: Tuten and Purdy stay the best pair, every man keeps his order, and
    the levels shift about ten points. The downstream check is the one that
    matters and it agrees: the greedy policy that DRAFTS off this valuation,
    scored on real outcomes leave-one-season-out, gains 84 points a season on
    the projection-keyed pool (1851 against 1767) and is ahead in all five
    seasons separately - and its rosters stop hoarding backs at the end and
    start taking a tight end and a defence. That first measurement had the
    boards still tiering by ADP rank (#83); with the boards moved onto the same
    order it was 226 points, 1767 to 1993. Both of those were graded in the
    feed's points. In the shipped configuration - boards agreeing, outcomes in
    the league's own points (#84) - it is 172 points a season, 1789 to 1961,
    ahead in all five. Both arms in
    `data/policy-backtest-poolkey-2026-09-04.txt`. Switching the key also widens the
    pool from 1466 player-seasons to 2896, because ranking by projection covers
    everyone projected rather than only the men on a draft board: the deep
    tiers the live board actually assigns finally have their own data instead
    of falling back a tier, and every replacement level moves with them
    (quarterback wire 15.9 to 12.9 a week). That widening rides along with the
    key and is not separately measured; the policy backtest judges the pair of
    them together. `-PpoolKey=adp` keeps the old pool for comparison. The first A/B of the two printed identical tables, because
    OutcomeDistributions' own report built its season list instead of calling
    `all()` - the lever was not connected to the measurement, which is #68
    again.

83. **Fixing one half of a disagreement makes a new one.** #82 moved the
    outcome pool onto projection ranks because that key predicts better, and
    the LIVE board already tiered that way, so the live path came out
    consistent. The HISTORICAL boards did not: `PlanBacktest.Board.tiersOf()`
    returns the ADP source ranks #80 had just given it, so every backtest that
    draws from the pool - `PolicyBacktest`, `PowerBacktest`, `PositionWeights` -
    now keys its cells one way and its men the other. That is the state #80
    itself calls worse than both wrong together, recreated one layer up by the
    fix for #82, in the same commit that quotes the rule. The A/B behind #82 is
    still a fair paired comparison, since both arms carry the same board, and
    the projection pool won while carrying the mismatch; but the consistent
    configuration has not been measured and the 84 points should not be quoted
    as if it had. FIXED the same day: `Board.poolRanks()` answers in whichever
    order the pool is keyed on - ADP source ranks under `-PpoolKey=adp`, that
    season's league-scored projection ranks under the default - and `tiersOf`
    and `expectedFromRank(board, pool)` both read it. Measured, and it was not
    small: the greedy policy goes from 1851 a season with the boards mismatched
    to 1993 with them agreeing, another 142 points, and it stops trailing the
    committed RUNBOOK plan by 147 to sit 4 behind it. So #82's real size was
    226 points, not 84, and most of what was missing was this. (All three of
    those figures are in the feed's points, measured before #84 flipped the
    grading; the shipped pair reads 1789 against 1961.)
    A change that makes two things agree has to be checked against everything
    else that reads either of them.

    And it cost a full check 17 minutes to two and three quarter hours before
    anyone noticed, because `poolRanks` asks `HistoricalProjections` for a
    season's projections every time a board is asked for a tier, and that read
    and parsed 2.5MB of JSON on every call. Exactly #69 - an expensive lookup
    moved inside a loop - committed by the person who had written #69 down that
    morning. The feed is now parsed once a season. A green check whose duration
    jumped by an order of magnitude is a failing check that happens to pass.

84. **Grading a plan in points the league does not pay.** Every graded outcome
    in this repo came from the feed's `pts_half_ppr`, which pays 4 for a passing
    touchdown, charges nothing for a fumble after 2022, and pays a defence
    nothing for holding a team to 14-20. This league pays 6, charges 1, and pays
    1. Projections were already recomputed under the league's own settings, so
    for two seasons of work a plan was CHOSEN on 6-point quarterbacks and SCORED
    on 4-point ones, and everything downstream - when to take a quarterback, what
    a defence is worth - inherited the lean. `LeagueActuals` fixed it in August
    and shipped it switched off, which was right while other tables were
    half-built in the old unit and became a lie about the league once they were
    not. `ScoringImpactReport` existed to measure the flip and had never been
    run; run, it says the correction is worth 30-45 points a season on the level,
    changes no strategy's rank, and leaves the best round for a quarterback at 3
    - but it is worth 56 points to an early quarterback against 35 to a late one,
    exactly the direction the mis-scoring predicted. Flipped on by Justin's call
    2026-09-04, before the season's own weeks could start arriving in the old
    unit. What moved with it: the honest defence wire 7.73 to 8.03 a week, the
    held wire 6.98 to 7.21 (the ratio barely moves, since both sides are
    re-graded together), the defence bands 135.8/127.2 to 140.3/131.9, and the
    ADP-versus-projection verdict from 6.2 to 6.5 points in favour of
    projections. An opt-in correction that everyone agrees is correct is a
    decision deferred, not a decision made; it needs a date.

85. **"Kept forever" and "asked too early" are a bad pair.** Every in-season
    tool needs this week's projections and last week's results, and the repo had
    one cache for both: `getCachedForever`, written for a finished season that
    can never change. Pointed at a live week it freezes Thursday's projection
    into December. Worse, pointed at a week that has not happened it freezes
    NOTHING and calls it data: on 2026-09-04, five days before kickoff,
    /v1/stats/nfl/regular/2026/1 returned `{}` and the 2026 defence stats
    endpoint returned `[]`. Either would have been the answer for the whole
    season - every defence scoring zero, every week-1 result missing - with no
    symptom but wrong numbers. Nothing had run yet, so nothing was poisoned.
    `getCachedForever` now refuses to write an empty payload and throws instead,
    and `LeagueWeek` reads a finished week and a live week through deliberately
    different cache names so neither can be served through the other's policy.

86. **Ask the question somebody actually faces.** A lineup call needed a bar:
    how far apart must two men be projected before the difference is a decision
    rather than a coin flip? Measured over five seasons, the first curve came
    back backwards - a gap under one point flipped 17% of the time against 36%
    for a gap of two, so the tightest calls looked like the most certain ones.
    Two faults, both about population. It paired every man the feed projects, so
    almost every pair was the ninetieth receiver against the ninety-first, two
    men who both scored nothing; and a 0-0 tie was counted as the projection
    being right. Restricted to the depth this league actually rosters (its own
    draft history: QB 21, RB 61, WR 81, TE 19) and with ties excluded, the curve
    is monotonic and starts where it should: 0.475 under a point, 0.43 at one to
    two, 0.26 at five to seven, 0.09 past twelve. The tool now prints that
    measured probability beside each bench call instead of a bar somebody chose.

87. **A policy is only measured for the manager it was measured on.** The live
    defence tool reproduces WireRateStress's streaming rule, which picks the
    best UNDRAFTED defence - because the manager that backtest models never
    drafts one. Applied literally to a manager who did, on the real 2026 week-1
    board where every defence better than his was rostered, "the best free
    defence" was the Lions at ADP 174.7 and the tool advised dropping his own
    Ravens at 132.4 to start them. The rule was faithfully implemented and the
    advice was nonsense: the streaming edge comes from switching ON FORM later,
    not from trading down in week 1. His own defence belongs in the choice set,
    and the report now says out loud that the measured 8.03 covers a pure
    streamer and not him. Reproducing a policy exactly is necessary for its
    number to apply and not sufficient for its ADVICE to; check what population
    the policy was measured over before handing its output to somebody outside
    it. Caught by running the tool, not by reading the design.

88. **A guard that cannot tell "not yet" from "none".** #85 stopped
    `getCachedForever` freezing an empty payload, because the 2026 week-1 stats
    endpoint answered `{}` before kickoff and that would have been the season's
    data. Correct for a stats or projection feed, where empty means the question
    was asked too early - and wrong for a week of transactions, where empty
    means a quiet week and week 18 of every finished season will say so forever.
    The guard turned a normal empty week into a fatal error for
    `LeagueTransactions` on any machine without the files already on disk: a
    fix for one caller breaking another, in the same class, four commits later.
    Nothing in the response distinguishes the two, so the CALLER declares it -
    `getCachedForeverAllowingEmpty` - and the strict default stays where the
    real failure was.

89. **A verdict that changes every Tuesday is a verdict somebody shopped for.**
    The bench test was pre-registered precisely so its answer could not be
    chosen after the fact, and then the ledger rendered one of its three final
    readings after every append - so a good week 1 would have printed "THE BENCH
    PAID" off a single Sunday, and a bad week 3 would have unprinted it. The
    test is about where he FINISHES; before the regular season is complete the
    tool now prints a STANDING and says plainly that it is not the verdict.
    Writing a test down in advance is worth nothing if it is read continuously
    until it says something pleasant.

90. **A static fact fetched from a feed that has not happened.** The weekly
    league-scoring path asked the SEASON stats endpoint which player ids were
    defences - a fact that never changes, from a feed that does not exist until
    games are played. Before kickoff it answered `[]`, so #85's guard fired and
    every in-season tool would have thrown from week 2 on; after kickoff it
    answers partially and `getCachedForever` would have frozen that instead. The
    id already carries the fact: Sleeper names a defence by its team. "Not a
    number" is NOT the test, which testing said and reading would not have - a
    week's stats also carry TEAM_SEA rows, 28 of them in 2024 week 5, and those
    are team lines. A bare two- or three-letter abbreviation is, and it agrees
    with the old feed-derived set on 6,766 ids across five seasons.

91. **A row is not a projection.** StartSit promised to tell a bye from a bench
    call, on the rule that a man absent from the week's feed is not playing.
    Sleeper publishes a row for everyone it knows - 9,419 for 2026 week 1 -
    mostly carrying draft ranks and nothing else, and league-scoring those gave
    8,554 men a tidy 0.0. So nobody was ever absent, the promised distinction
    silently did not exist, and a man on bye would have been started with a
    straight face. Only the 866 rows with an actual `pts_half_ppr` are men who
    are playing. Week 1 hid it completely, because every man on the roster had a
    real projection.

92. **Legality is a property of the lineup, not of two men.** "Which starter is
    this benched man really competing with" took three tries. Same position only
    ignored the two FLEX slots, so a benched receiver was measured against a
    receiver when he was really competing with a back. Any flex-eligible starter
    over-corrected, matching him against the only tight end, whose slot he
    cannot fill. The rule is not a relation between the two men at all: make the
    swap, rebuild the lineup, and see whether ten slots still fill - which the
    tool that built the lineup already does. The first test of the fixed version
    passed on a two-man lineup, where no slot is required and therefore nothing
    was being tested; a rule about slots needs a fixture with slots.
