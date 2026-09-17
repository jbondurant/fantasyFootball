# COMPONENTS.md — the map of this repo, and the plan for each part

One home for *which tool belongs to what, and is it live*. Every runnable class in
`src/main/java` (a `public static void main`) is listed exactly once below.
`ComponentMapTest` fails when a main appears, disappears or is renamed without this
file changing, so the list cannot drift in silence.

Tiers:

- **live** — in the weekly loop; Justin acts on its output this week.
- **seasonal** — live for one window a year (the draft in September, the keeper declaration in July/August).
- **diagnostic** — a measurement whose result is quoted in the docs or pinned by a test; kept for the reasoning, not run.
- **archive** — superseded or referenced by nothing. Proposed for `src/archive/java` (a separate source set that still compiles, out of `run` and out of this list). **Nothing is moved until Justin says yes to the enumeration at the end.**

Counts on 2026-09-13: **214 runnable tools, 59,768 lines in their own files** (70,082 in `src/main/java` overall).
live 26 (8,060 lines) · seasonal 36 (16,618) · diagnostic 55 (20,802) · archive 97 (14,288).

How this was produced. Six classifiers read every tool's javadoc, main and callers and
graded liveness against RUNBOOK/README; 28 lens agents (siblings, correctness, tests,
docs — four per component) produced 443 candidate findings; the 84 high/medium candidates
that fit the quota went to independent refuters (two for a high, one for a medium) and
**74 survived, 10 were refuted**; 359 lows and overflow are carried *unverified*. Every
confirmed finding with its quoted code, scenario and fix is in
[`data/audit-2026-09-13.md`](data/audit-2026-09-13.md); the tool-by-tool classification
evidence is in `data/audit-2026-09-13-tools.tsv`. Six of the classifiers' calls are
overridden here and say so in italics. Two findings were made inline before the workflow
ran and one of those was retracted before it reached this file (see 4, Keepers16).

Justin's goals, in order, are what every fix and improvement is tied to: **(G1) win 2026;
(G2) win 2027 — two keepers under KeeperPricing; (G3) be seen as a good trading partner by
the same eleven managers every year.** Standing rules: every number he sees comes from
committed code; the artifact he reads is the console page plus the RUNBOOK's terminal
reports; upkeep stays under 30 minutes a week (the shrink rule — a plan that adds a weekly
command has to earn it).

---

## 0. Cross-cutting workstreams

### A. League facts have one home each

Justin, 2026-09-11: *"I don't want to have to fix the 6pts per throw TD league modification
everywhere."*

| fact | home today | copies found | state |
|---|---|---|---|
| scoring (pass TD = 6) | `LeagueScoringSettings.fromSleeperScoringSettings` built once in `SleeperLeague:60`; 13 readers | `MarketMovers:183` (`2 *` fallback), `ScoringAudit:29` (`4.0`) | **one home; two literals to derive** |
| starting lineup QB RB RB WR WR WR TE FLEX FLEX DEF | `RosterRules.live()` ← `roster_positions`; tested against the feed | **four fillers** carry their own counts: `TeamRankings.FIXED` (the whole in-season path), `StartingLineup.FIXED` (25 draft-model consumers), `WeeklyStarterValue.fill` (the objective), `ScoredRoster` (legacy); plus `LeagueConsole:104,:947`, `TradeMarket:746`, twelve research copies | **authority exists; nobody in the live path uses it** |
| teams, my slot, pick arithmetic | `AAAConfiguration` + `RosterRules` (delegates) | `TEAMS = 12` in EraBoards, PromotionBehaviour, PowerBacktest (historical harvest) | fine |
| my keepers | `AAAConfiguration.getTodaysKeepers()` ← feed | `RosterRules.justinsKeepers()` = Tuten r12 / Purdy r13, typed; seeds every REFUSED line on the draft screen | **changes every year; derive with fallback** |
| my fourteen pick numbers | `DraftSimulator.pickNumbersOf`, `RosterRules.livePicks` | `{7, 18, 31, …, 186}` typed in 14 main files and 2 tests (`LiveBoard:962`, `PlanBacktest:86`, `DefenceTiming:21`, …) | **derive** |
| scored weeks (17, not the NFL's 18) | league `last_scored_leg` | `WeeklyActuals.WEEKS = 18`; `OutcomeDistributions`, `PlanBacktest`, `WeeklyStarterValue.wireRates` all count week 18, which this league has never scored | **read from the league** |
| next-year keeper cost | `NextYearKeepers.forThisLeague` (in season) / `KeeperChooser.eligibleCandidates` (pre-draft) | correct in both families — but *which* is correct depends on the calendar and nothing encodes that | **one calendar-aware entry point** |
| the wire (replacement rank per position) | `InsuranceTest.replacementRanks` | six copies: `ReplacementRanks`, `TightEndTiming.wireLevel` (RB 80, twenty ranks too deep), `WeeklyStarterValue.forCurrentBoard`, `BenchValue`, `StartSit`, `BustBoomValue`; and the basis is the 2021-2025 mean, not the finished 2026 draft | **one Wire class** |

Fixes, in order:

- **A1.** `TeamRankings.bestLineup` takes its slots from `RosterRules` (a parameter; `live()` at the call sites). `LeagueConsole.SLOTS`, `LeagueConsole:947`, `TradeMarket:746` → `RosterRules.live().startersAt(p)`.
- **A2.** `WeeklyStarterValue` takes a `RosterRules`; `fill` counts come from it.
- **A3.** `StartingLineup.FIXED` derived from `RosterRules` minus DEF; `SKILL_SLOTS`/`FLEX_SLOTS` derived.
- **A4.** `MarketMovers`, `ScoringAudit` literals → `LeagueScoringSettings.halfPprFeed().passTD`.
- **A13.** (found 2026-09-16, TRAPS #141) the FAAB demand model's next feature is a teammate's injury - the leader at his team and position by ppg missing the week - which needs team-by-week from nflverse (`NflverseWeekly` has team and opponent per row, ids need the boards' name match).
- **A12.** (found 2026-09-15, TRAPS #139) `AdpSnapshot` archives the merged map, so an archived source is Sleeper's number for the men it did not cover; archive each source's own rows plus a fill-in flag (with B2-1), and drop fill-ins in any comparison (`ProjectionShootout` does).
- **A11.** (found 2026-09-14, TRAPS #138) the season feed's DEF line is a four-category stub ~25 pts/season low under league scoring; price defences from the sum of the weekly DEF rows wherever the season feed is read for one (`TradeMarket`, the wire, `SeasonOutlook`, `MarketMovers`).
- **A10.** (found 2026-09-14, TRAPS #137) `LeagueWeek.projected` freezes a finished week from its first post-game read; freeze it from the newest live read dated before the week's first game instead (needs the kickoff date - with B2-7).
- **A5.** `RosterRules.justinsKeepers()` ← `planner.myKeepers` / `getTodaysKeepers()` with the literal as fallback and a feed-agreement test (the `FALLBACK_SLOTS` pattern).
- **A6.** `KeeperBasis.forNextDeclaration(configuration)` — reads the draft object's `status`; IN_SEASON → `NextYearKeepers`, PRE_DRAFT → `eligibleCandidates`; the four in-season callers use it; the August tools refuse to run in season. Pure `moment(String)` tested on both arms.
- **A7.** One `Wire` class: basis (the finished 2026 draft once `status == complete`, else the historical mean, printed either way), rank, per-game price, DEF depth, scored-week count. Absorbs `replacementRanks` and the six copies.
- **A8.** `LeagueFactsTest` (source, comments stripped): no `Position.QB, 1`-shaped literal outside `RosterRules`; no `pass_td` arithmetic outside `LeagueScoringSettings`; no typed pick list outside `RosterRules`/`DraftSimulator`.
- **A9.** `LineupFillersAgreeTest`: on the fixture league the fillers start the same men for the same roster.

### B. Sibling discipline

Ten of this month's bugs, and **eleven more confirmed by this audit**, are one shape: a fix
lands in the artifact Justin reads and not in the terminal tool computing the same thing.
This audit found the pattern still open in: `TradeMarket:672` (rival keepers position-blind
— ac62628 deleted `keeperValue`'s two-arg overload and not `lossAverseOnKeepers`'s),
`TradeStability:80` (pool 6 — `SearchWidthTest` named two files, not three),
`TradeStability:116` (keeper curve from rostered men only — the defect `KeeperDriftCheck`
documents fixing for itself), the undrafted-costs-a-tenth rule (console only), the 6.8
floor (retired for trades, still judging the wire), `SeasonOutlook:132` (the median game
added to simulated weeks, not banked ones), and every draft-night fix of 2026-09-01
(`minePicks`, the drift detectors, `branchWith`, the legality gate) landing in `LiveBoard`
and not in `LiveLateRounds`, `DraftNight.answer`, `LiveCommittee`, `LiveDraft`.

The structural answer, per component below: one row builder shared by page and terminal;
one banked-wins function; one attribution function; one warm path. And the tests that
catch the shape when it recurs: `KeeperBasisTest`/`SearchWidthTest`-style source rules
with their file lists complete (both were incomplete), plus one behavioural sibling test
per pair on the fixture.

### C. Test discipline

Confirmed across components: `build.gradle` declares the five `.md` files as test inputs
and none of the `data/` artifacts thirteen tests parse, so a regenerated page leaves
`test` UP-TO-DATE (TRAPS #45, half-swept). The `test` task has no `testLogging`, so the
RUNBOOK's instruction to read the count and *0 skipped* from `check-wire.log` cannot be
followed (only `smokeTest` logs). Tests that cannot fail: `AppetiteCapTripwireTest:76`,
`TwoObjectivesTest:54` (asserts a file contains "only"), `HindsightRegressionTest:371`
(asserts a property the test JVM never sets), `KeeperAuditSmokeTest:33` (a final field is
non-null), `KeeperChooserSmokeTest:50` (`round > 2 || round >= 1`),
`NextYearKeepersTest:65` (the "edge" case is the first case repeated),
`SeasonOutlookTest:24` (a column that sums to 600 by construction),
`WireRateStressTest:97`, `RosterRulesTest:411` (skips on the very failure it checks),
`ProjectionDriftTest:54` (oldest-vs-newest can never see an in-season freeze),
`KeeperSlotScanTest:59` (re-implements the loop it guards). Untested load-bearing paths:
the draft verdict chain and committee, `SeasonLedger`'s week loop, the Sunday break-even
bar, the ONE_STARTER pair rule, the 20-minute roster expiry, the three-year keeper cap.

Fixes: declare `data/` (narrowly: the console and the weekly reports) and the fixture
directory as inputs; add `testLogging` to `test`; convert each listed test to one that
can fail or delete it; add the missing tests with the fixes they guard.

### D. Cache policy

Every one of the eleven confirmed feed findings is two files reading one URL under
different lifetimes: the league object through five names and three lifetimes
(`AAAConfiguration:119` day, `SleeperLeague:50` day, `LeagueTransactions:62` forever,
`PlayoffOdds:56` forever, `DraftDates:30` forever); rosters through three (20 min, 20 min,
forever at `TradePartners:77`); the week feed through two (`LeagueWeek:56` gated,
`WeeklyProjections:31` ungated). Consequences confirmed on disk: the live league is frozen
forever at `status pre_draft` so 2026 can never become a completed season for the trade
log, FAAB history and playoff pool; `SeasonOutlook` joins roster ids through a copy of the
live league's users frozen 2026-09-07; injury tags on the Sunday tab are day-cached while
the roster beside them expires in 20 minutes; `mostRecentCached` matches on prefix so a
failed week-1 fetch is served week 14's file.

Fix: one `Feeds` registry (url → cache name → live / minutes / day / forever-once-complete)
that `AAAConfiguration` prints on demand with each file's age; every reader goes through
it; the head of the league chain is day-cached until its status is complete.

---

## 1. Configuration & feeds

**Purpose.** League configuration; the fetch and cache layer; every external feed
(Sleeper, ESPN, CBS, nflverse, FFC, FantasyPros); projection sources and the scoring
bridge; player metadata; the data stamp.

**State of health.** The scoring bridge and the fetch layer are sound in the small and
tested for the pure parts, and there is no single statement of cache policy (workstream D).
The daily four-feed projection archive has been **silently dead since 2026-09-02**: CBS now
answers its season URL with the current week's page, `CbsProjections:65` throws, and
`AdpSnapshot:157-169` buffers all four sources and writes nothing — while the ADP half
keeps being committed (`5d54626`, 2026-09-11). The archive that exists labels Sleeper's
numbers as ESPN/CBS/borischen for every player those feeds never covered (102 defence rows
a day, measured).

**Fixes** (11 confirmed; evidence file §1):

1. [high/small] Archive each feed as it resolves; never lose a day to one broken source — `AdpSnapshot:157-169`, `tools/adp_snapshot_daily.sh` (`set -o pipefail`, commit only when both files gained a row).
2. [high/small] CBS: detect the in-season week page and say so; drop `cbs` from `automaticSources()` in season, printed every run — `CbsProjections:21,65`, `ProjectionSources:47`.
3. [medium/trivial] Anchor `mostRecentCached` to the date so `w1` cannot serve `w14` — `InOutUtilities:202`.
4. [medium/trivial] `WeeklyProjections` uses `LeagueWeek`'s `finished()` gate; the week-1 file on disk is the 09-05 copy — `WeeklyProjections:31`.
5. [medium/small] Refuse an empty or error-wrapper payload in the day cache too (`getCachedForever` already does) — `InOutUtilities:151,264`; behavioural test.
6. [medium/small] Injury tags expire on game day; `DataStamp` carries the fetch minute, not just the date — `SleeperProjections:34`, `DataStamp:28`.
7. [medium/small] Archive the raw feed maps, not the Sleeper-backfilled merge — `ProjectionSources:121`, `AdpSnapshot:158`.
8. [medium/small] One cache name per URL; the fixture pin covers every name the suite reads — `AAAConfiguration:119` vs `SleeperLeague:50`.
9. [medium/trivial] Give `/state/nfl` a short expiry so Tuesday's week flip is seen the day it happens — `LeagueWeek:35` (*verify on 2026-09-15 first*).
10. [medium/trivial] Write defences into the ADP archive (`-PadpSnapshot` pins every DEF at 100% survival today) — `AdpSnapshot:93`.
11. [medium/small] `ProjectionDriftTest` asserts on the newest pair, not oldest-to-newest — `ProjectionDriftTest:54`.

**Improvements.** (G1/G2/G3 through upkeep) the `Feeds` registry printed on demand (D). (G1) the page prints when its injury tags were fetched. (G2) an `ArchiveIntegrity` check in `check` before the 2027 keeper decision is priced on the archive. (G3) 2026 joins the league's history the day Sleeper marks it complete (D).

**Tools.**

**live** (12)

- `AAAConfiguration` — Single source of truth for which league/season/draft is configured; reads draft id, prior league and humans…
- `AdpSnapshot` — Appends today's Sleeper ADP and projections to committed CSV archives, one row per player per day; the arch…
- `CbsProjections` — Scrapes CBS Sports per-position season stat-line tables and scores them under league settings via the share…
- `EspnProjections` — ESPN season projections via the fantasy API (X-Fantasy-Filter header, own fetch, day cache), scored under l…
- `FFCalculatorSD` — Fantasy Football Calculator per-season half-PPR ADP and per-player ADP standard deviation, centred on the s…
- `HumanOfInterest` — League user-id to display-name map read from /league/{id}/users; main() dumps every id/name pair plus "me".
- `InOutUtilities` — HTTP fetch and per-day/forever cache layer (getTodaysWebPage, getCachedForever) under every Sleeper/Fantasy…
- `PlayerRawData` — Downloads/caches Sleeper's /v1/players/nfl metadata (sleeperDataPlayerAPI.json) and cleans it into Player o…
- `ProjectionBridge` — Adapts any site's projected points (or props/stat sheets in data/external-projections/<name>.csv) to this l…
- `ProjectionSources` — Registry of named projection slots (sleeper, borischen, paywalled CSVs, blend:...) with resolve(name) and a…
- `SleeperDraftInfo` — Reads the Sleeper draft object (draft order, users); main prints pick-by-user. Carries a '//todo these get_… *(library: SleeperLeague.getSeriousLeague needs it)*
- `SleeperProjections` — Sleeper season projections feed: raw points map plus league-scored projections via scoreStatLine; the proje…

**diagnostic** (7)

- `DraftDates` — Reads the exact draft date/time of every season from the league chain's /drafts collections so ADP-capture…
- `EraIngest` — Fetches, caches forever and joins every season Sleeper stats and FFC ADP still serve, printing join rates;…
- `MarketMovers` — Report-only: who the market moved on in the last N days from the daily sleeperProjections<season><date>.txt…
- `WeeklyFeedAudit` — Do the eighteen weekly projections sum to the season number, does the week-2 projection lean on the week-1 result (slope by position), and did any live week's projection move between cached days. Report to data/weekly-feed-audit-<date>.txt.
- `ProjectionShootout` — After the games: each archived source (sleeper, espn, cbs, borischen, and sleeper's pre-game week feed) against the week's actuals - per player (r, rho, MAE, paired |error| vs sleeper) and per roster (each source's best lineup and started ten vs the league's points, rank correlation over twelve). Report to data/projection-shootout-<season>-w<week>.txt.
- `MockDraftReader` — Archives a shared Sleeper mock draft (by id/URL) under data/mocks/ before Sleeper prunes it and prints reac…
- `NflverseBoards` — Joins FFC draft boards 2010-2025 to nflverse outcomes by name with a per-season match-rate gate (usable() r…
- `NflverseWeekly` — Reads data/nflverse/stats_player_week_YYYY.csv and scores every player-week from components under this leag…
- `ScoringRuleAudit` — Walks all 34 league scoring_settings categories against what pts_half_ppr measurably applies and prices eac…

**archive** (8)

- `CSVProjectionsFP` — Loads hand-downloaded FantasyPros rest-of-season CSV exports (FantasyPros_<season>_Ros_<POS>_Rankings.csv)…
- `EraExport` — Writes the harvested FFC PPR era boards to disk under their own ffc-adp-ppr-* name so no tool silently glob…
- `FantasyProsADP` — Scrapes FantasyPros expert-consensus half-PPR ranking and matches rows to Sleeper players by name/team/posi…
- `FantasyProsReports` — Prints a Serious-vs-FantasyPros-ECR comparison table (sve()).
- `InSeasonProjectionsFP` — FantasyPros rest-of-season pages; getRosRanking() returns expert-consensus ranks, playerToScoreProjFPROS()…
- `ScoringAudit` — Measures the gap between Sleeper pts_half_ppr (4-pt pass TD) and the league's 6-pt scoring in the backtest…
- `SleeperADP` — Builds half-PPR ADP rank lists (playerRankSerious) from the Sleeper projection feed; main prints the count. *(with the legacy cluster (StrategyBot -> SimulationDraft))*
- `WeeklyHarvest` — Phase 0: pull every week of five seasons into the forever cache and gate that weekly points sum to the seas…

---

## 2. Draft tool

**Purpose.** Draft night: the live board, the room model, the planner, survival, wait-or-take, the late-round screen, the committee, roster legality, keeper pricing at the table. Live one night a year; nothing here reaches this week's console.

**State of health.** The path Justin read in rounds 1-7 (`Draft2026 → LiveBoard.answer`)
received every draft-night fix on 2026-09-01. **None of them were swept into the siblings**,
and the rounds-8-16 tool the RUNBOOK names (`LiveLateRounds`) got none: it still attributes
the roster by `takenAtOf` (four picks in the real 2026 draft were off the 250-ADP board, so
from pick 168 every such caller was a seat behind), prints no drift detector, has no
legality gate, and its survival loop lets the room model spend Justin's own pick on the very
man being priced. Underneath: the live RB/WR wire reads 0.0 (`BoardValue.replacement`
indexes past the curve `LiveBoard.thisYear` fills), and the room model's pair/wait features
are computed on a nine-round pick list while it is trained and served on sixteen. The
verdict chain and the committee have no test.

**Fixes** (10 confirmed; before August):

1. [high/trivial] Train the pair/wait features on the sixteen-round pick list the simulator serves — `SelectionModel:583`.
2. [high/small] `LiveLateRounds` prints both drift detectors; `stateAfter` stops drifting on off-board picks — `LiveLateRounds:53`, `DraftSimulator:643`.
3. [high/small] Survival must not let simulated-Justin take the man being priced (`branchWith`, as `WaitCheck` does) — `LiveLateRounds:215`, `LateSurvival:88`.
4. [high/small] One roster attribution: every sibling calls `LiveBoard.minePicks` — `DraftNight:107`, `LiveLateRounds:60`, `LiveCommittee:58`, `LiveDraft:76`, `LiveBoard:1770`.
5. [high/small] The live RB/WR wire is 0.0: size or clamp the curve — `BoardValue:1227`, `LiveBoard:1461`.
6. [medium/small] `LiveLateRounds` refuses what the rules and `MOST` refuse — `LiveLateRounds:90`.
7. [medium/small] The four hand-warmed mains go through `LiveSetup.forTonight` or lose their mains (they ignore `-Pkeepers` and the sixteen-round schedule) — `DraftNight:41`, `WaitCheck:41`, `LiveCommittee:43`, `LiveDraft:50`.
8. [medium/small] END TEAM's tail walks the simulator's seats, not a typed 2026 pick list — `LiveBoard:962` (A).
9. [medium/trivial] Three tests that cannot fail — `AppetiteCapTripwireTest`, `TwoObjectivesTest`, `HindsightRegressionTest` (C).
10. [medium/small] One next-live-pick scan, called by every tool and by `KeeperSlotScanTest` — `DraftSimulator`, `LiveBoard:180,1653`, `Draft2026:196`.
11. [medium/small] `smokeTest -PdraftId` exercises `LiveDraft.livePicks`/`stateAfter`/`LiveBoard`, not the superseded `SleeperLiveDraft` path — `MockDraftSmokeTest:116`.
12. [medium/medium] Golden replay of the real 2026 draft pins the verdict chain and the committee — new `LiveReplay2026Test`.
13. RUNBOOK's draft sections say `DraftNight`; the ladder, README and DRAFT-READY say `Draft2026`; DRAFT-CARD contradicts all three — retire or date the prose.

**Improvements.** (G2) price the keeper option on the late-round screen with the rule that will value him in 2027. (G1/G2) calibrate SURVIVES against the six real drafts, not the simulator. (G3) show who is on the clock between my picks and what each seat still needs. (upkeep) one warm path and one replay test are the whole off-season maintenance.

**Tools.**

**seasonal** (18)

- `BenchValue` — Measures what this league's real rounds 8-16 picks returned over the waiver wire, floored at zero, by band/… *(draft night; live one night a year)*
- `BoardValue` — The board model: marginal lineup points from empirical rank curves joined with survival, roster state and t… *(draft night; live one night a year)*
- `Draft2026` — The one draft-night screen: warms both engines once and prints Model A plus the board model side by side fo… *(draft night; live one night a year)*
- `DraftNight` — Draft-night console that warms the engine once and re-answers on enter: uncached live board, committee vote… *(draft night; live one night a year)*
- `DraftPlanner` — Model A: expectimax over positions at each of my picks with DraftSimulator rollouts, best-legal-nine object… *(draft night; live one night a year)*
- `DraftSimulator` — Full drafts sampled from the fitted selection model through the league's real serpentine order with keeper… *(draft night; live one night a year)*
- `LiveBoard` — The board model at the table: prices every legal candidate on the live board by marginal lineup points over… *(draft night; live one night a year)*
- `LiveCommittee` — Runs four engines (lookahead-2, lookahead-1, hindsight, vorp-greedy) on the live board from one state and r… *(draft night; live one night a year)*
- `LiveDraft` — Reads the live Sleeper board (livePicks/livePickOwners/freeze), replays it into the simulator and recommend… *(draft night; live one night a year)*
- `LiveLateRounds` — Rounds 8-16 live pricer: V(roster + him) - V(roster) on WeeklyStarterValue for the best available at each p… *(draft night; live one night a year)*
- `PairwiseOdds` — P(later-drafted man outscores earlier, same position): isotonic + log-smooth strength curve fitted on nflve… *(draft night; live one night a year)*
- `PolicyTournament` — Races pick-selection policies for my seat in one shared game; forLiveArbitration/RankingSelection is the ar… *(draft night; live one night a year)*
- `PositionPredictability` — Within-position rank correlation of preseason board vs season outcome; reliability() is the trust coefficie… *(draft night; live one night a year)*
- `PreFlight` — Reads Sleeper live and checks the tool is pointed at tonight's draft: right draft id, status, settings, 14… *(draft night; live one night a year)*
- `RosterRules` — Single authority on legal Justin rosters and pick cost: a Roster type that refuses illegal picks, ceilings… *(draft night; live one night a year)*
- `SelectionModel` — Conditional-logit room model P(manager takes player | board, roster); fitShipped() is the single definition… *(draft night; live one night a year)*
- `TimingPlanner` — Full-rules planner: (qbAt x teAt) timing heads over nine live picks, other picks chosen live by roster-awar… *(draft night; live one night a year)*
- `WaitCheck` — Simulates forward from the live board to my next pick and reports per-position best-available survival and… *(draft night; live one night a year)*

**diagnostic** (5)

- `BoardSanity` — Pre-draft stale-data check: flags players whose projection and ADP disagree violently.
- `CycleTiming` — Times one Draft2026 cycle with output swallowed on the fixed first-six-picks 2026 board.
- `LateRoundValue` — Outcome-measured base rates for late picks: backup-skill points over the wire (BenchValue) and drafted defe… *(keep and RERUN after the grader fixes; archive once PlanBacktest carries their strategies)*
- `LateSurvival` — Runs the fitted board out to round 16 and records which stash targets are still available at each of my pic…
- `StashValue` — One model for rounds 8-16: value = max(0, this season - wire) + max(0, next season - wire - price of round-…

**archive** (9)

- `HumanStrategy` — Fixed position-order drafting strategy for the old SimulationDraft; main() calls nonPermutedPositions(1,3,5… *(with the legacy cluster)*
- `LateWaitOrTake` — Rounds 8-16 combiner: StashValue (this season + keeper base rate) times LateSurvival's P(gone) gives expect…
- `LiveInsurance` — Model B live: once the starting nine is full, values each bench candidate by promotion chance from fog draw…
- `SeatPlan` — Pre-draft table of what the model expects at each of Justin's fourteen seats, to eyeball the shape and gaps…
- `SleeperLiveDraft` — Older draft-day advice: reads live picks (uncached) and runs 300 on-the-fly simulations per pick.
- `StarterRisk` — Is my starting nine more injury-exposed than an average nine, per position, as a multiplier on the bench ba…
- `TightEndWait` — Can I wait on a tight end this year: teams already holding a TE, projection vs market price, survival curve…
- `Tomorrow` — One draft-night screen: the committed plan per round, LiveBoard's opinion, and PairwiseOdds' cost of waiting. *(main only; move Tomorrow.PLAN into LiveBoard first)*
- `WaitOrTake` — Take him now or gamble he lasts a round, on AvailabilityModel with league bias fitted from ManagerProfiles.

---

## 3. Draft research & diagnostics

**Purpose.** August's measurement program — backtests, tournaments, labs, audits — that
settled the draft plan. Kept for the reasoning in MODEL.md / DIAGNOSTIC.md / DRAFT-READY.md.

**State of health.** The decisions survive; the numbers do not reproduce. The A/B/C tight-end
table, the +53.2 streaming margin, the defence-round table and the "do not reach" paragraph
are measured by a parallel scorer (`TightEndTiming.fill/wireLevel/join`) that grades in the
retired feed points, prices the RB wire twenty ranks too deep (zero in 2022), mixes
per-game and per-season units and loses nicknamed men at the join — or come from a
`DefenceTiming` that `8486580` replaced. **Two faults leak into this week's numbers:**
`replacementRanks` prices the in-season wire off 2021-2025 drafts, never the finished 2026
one (RB credited five ranks too rich, TE three, on every "with him / without him" figure on
the console); and the outcome pool and every weekly scorer count NFL week 18, which this
league has never scored.

**Fixes** (11 confirmed):

1. [high/small] **This week.** Price the in-season wire off the finished 2026 draft once `status == complete`, else the historical mean, printing which — `InsuranceTest:47` (→ A7).
2. [medium/medium] **This week.** Score and count over the league's 17 scored weeks — `WeeklyActuals:29`, `OutcomeDistributions:107,131`, `PlanBacktest:631`, `WeeklyStarterValue:412`, `DefenceVersusDepth:64` (→ A).
3. [medium/trivial] `LateRoundValue` prices the streamed defence over 17 weeks at an 18-week rate — `LateRoundValue:62`, `WireRateStress:99`.
4. [medium/trivial] `TightEndTiming.join` grades in feed points after the 2026-09-04 flip — `TightEndTiming:635`; add it to `ProseDriftTest`'s dispatcher sweep.
5. [medium/trivial] `DefenceRound` slides the defence through the strawman plan — `DefenceRound:26` (derive from `PlanBacktest.STRATEGIES`).
6. [medium/small] `TightEndTiming.wireLevel` RB 80 → `replacementRanks` (RB 61); refuse rather than return 0 on a shallow board — `TightEndTiming:601`.
7. [medium/small] `DefenceVersusDepth`/`LateRoundValue` hold pre-flip feed-point bands (44.0/32.8/31.2) against post-flip defence bands — derive from `BenchValue.overWireByBand`.
8. [medium/small] Wire priced per game the way rostered men are — `TightEndTiming:428`, `StarterContribution:407,486`.
9. [medium/small] Board loaders join through `Player.getPlayerFromNameAndPos` (aliases), not `normalise()` — `PlanBacktest:328`, `TightEndTiming:678`.
10. [medium/medium] `PlanBacktest.seasonPoints` scores an empty skill slot at zero while the objective floors it at the wire — decide, quantify with `ScorerHonestyAudit`, then state it once.
11. [medium/medium] In `PlanBacktest.draft` the other eleven never draft a defence, so the "flattens after round 14" plateau is by construction — `PlanBacktest:542`.
12. [medium/trivial] `OutcomeDistributions.CACHE` keyed on `poolKey` only (*verify first*) — `OutcomeDistributions:57`.
13. Seven tests that cannot fail; `build.gradle` does not declare the data files three tests read (C).
14. RUNBOOK numbers no committed tool produces (445-451, 301-317, 239) — rewrite after the reruns, once.

**Improvements.** (G1) one `Wire` class (A7). (G1) grader lint: any raw-feed reader outside `LeagueActuals` is allow-listed with its reason. (G2) re-express the tight-end and defence research as `PlanBacktest` strategies and retire the parallel scorer. (G2) a sixth-season harvest checklist for 2027. (G3) print the wire basis and the scoring window at the top of the console, `TuesdaySwap` and `TradeMarket`.

**Tools.**

**seasonal** (13)

- `BoardSourceCheck` — Runs the board model at pick 7 under sleeper, espn, cbs and a blend to see if the verdict flips with the pr… *(pre-flight diagnostics; decide before August which the 2027 pre-flight reruns)*
- `DriftAlarmCheck` — Replays a clean draft and counts how often the schedule-drift warning fires; must be zero. *(pre-flight diagnostics; decide before August which the 2027 pre-flight reruns)*
- `FragilityBinding` — Walks my seats on a simulated draft and counts how often the shipped BoardValue.tooFragile predicate (the 1… *(pre-flight diagnostics; decide before August which the 2027 pre-flight reruns)*
- `LivePathStress` — Replays a full sixteen-round draft calling the real LiveBoard.answer at every one of my seats; any throw or… *(pre-flight diagnostics; decide before August which the 2027 pre-flight reruns)*
- `MidDraftRank` — Scores the survival rule's unconditional approximation vs the exact conditional P(gone by p | survived to k… *(pre-flight diagnostics; decide before August which the 2027 pre-flight reruns)*
- `OpinionCount` — At how many of the fourteen seats the board model's top-two verdict is separated (paired 2 s.e.) rather tha… *(pre-flight diagnostics; decide before August which the 2027 pre-flight reruns)*
- `PositionTrust` — Per-position regression of realised bucket spread on projected spread (trust in [0,1]) so no position gets… *(pre-flight diagnostics; decide before August which the 2027 pre-flight reruns)*
- `RankIndexCheck` — Measures how fast outcome scatter moves with positional rank to decide whether draftable-vs-full-board rank… *(pre-flight diagnostics; decide before August which the 2027 pre-flight reruns)*
- `RealMidDraft` — Scores three expectedRank rules (prior-only vs room-observed blends) against the league's real 2024/2025 dr… *(pre-flight diagnostics; decide before August which the 2027 pre-flight reruns)*
- `RoomFidelity` — Fits the choice model on prior seasons and simulates a held-out season's own board with its keepers, compar… *(pre-flight diagnostics; decide before August which the 2027 pre-flight reruns)*
- `TailLegality` — Does the rollout tail ever finish a roster without a defence (which made 'take the defence now' look mandat… *(pre-flight diagnostics; decide before August which the 2027 pre-flight reruns)*
- `TightEndHabit` — Rank correlation between which managers really take a TE early and which simulated managers do, per held-ou… *(pre-flight diagnostics; decide before August which the 2027 pre-flight reruns)*
- `TrainingRows` — Counts the selections the shipped room model actually trains on (857, 2021-2025, sixteen rounds) after '435… *(pre-flight diagnostics; decide before August which the 2027 pre-flight reruns)*

**diagnostic** (33)

- `BenchCalibration` — Sweeps BoardValue's lostBelow threshold and a blend lambda against BenchValue's measured over-wire bench re… *(a test pins a number RUNBOOK still quotes)*
- `BenchValueGap` — Compares what WeeklyStarterValue prices the best free bench man at pick 127 against BenchValue's measured o…
- `DefenceReality` — Checks three claims about defences: when the league really drafts them, whether the 2026 curve plateaus, an…
- `DefenceRound` — Holds a plan's other thirteen picks fixed and slides the defence through every slot, scoring all variants o… *(keep and RERUN after the grader fixes; archive once PlanBacktest carries their strategies)*
- `DefenceVersusDepth` — Realised defence points by preseason ADP band, the other half of Justin's 'defence at 8 vs bench depth' tra… *(keep and RERUN after the grader fixes; archive once PlanBacktest carries their strategies)*
- `DetectionLag` — Measures the week at which season-to-date scoring first beats the preseason ADP curve as a predictor (the b…
- `DraftBacktest` — Fit the selection model on prior seasons, replay a held-out draft, report pick-prediction rank and availabi…
- `DryRun` — Plays the whole 2026 draft with the model picking for me and fitted opponents, looking for absurd shapes an…
- `EraSlate` — Transplants the whole league's 24-keeper slate onto a historical board by positional ADP rank and removes t… *(a test pins a number RUNBOOK still quotes)*
- `FogFit` — Fits projection-residual constants (mean ratio, sd, bust and boom rates) by position and projection tier ov…
- `KeeperSlateImpact` — Scores every strategy on the 2-keeper board, the 2-keeper board with real ranks, and the full 24-keeper boa…
- `ObjectiveAudit` — Runs RiskDiscountedValue against alternative trust coefficients, games-missed models and replacement ranks;… *(a test pins a number RUNBOOK still quotes)*
- `OddsFamilies` — Bake-off of four pre-registered model families (boosted strength, boosted surface, etc.) against PairwiseOd… *(a test pins a number RUNBOOK still quotes)*
- `OddsSurfaces` — Eight further odds-surface families (BT-MLE, logit d, ...) refitted fold-by-fold on one protocol against th… *(a test pins a number RUNBOOK still quotes)*
- `PickDisplacement` — Empirical availability/residual distribution learned from this league's own drafts (per-position offset, pe… *(a test pins a number RUNBOOK still quotes)*
- `PlanBacktest` — Phase 4 backtest of fixed position sequences on real past ADP boards scored on real weekly outcomes; its Bo… *(a test pins a number RUNBOOK still quotes)*
- `PolicyBacktest` — Fair Phase-4: the pick POLICY drafts each past season's own board with leave-one-out outcome pools, scored… *(a test pins a number RUNBOOK still quotes)*
- `PositionWeights` — Fits four per-position multipliers on the objective's marginal by coordinate ascent on 2021-2023, judged on…
- `PowerBacktest` — The measuring instrument: how big a strategy gap must be to mean anything, over 12 slots x seasons x pertur… *(a test pins a number RUNBOOK still quotes)*
- `RankDraft` — A draft rule built solely from the pairwise preseason-rank outcome matrix (no intraseason modelling); score…
- `RankKeyChoice` — Leave-one-season-out test of whether ADP or projection rank should key a man's historical outcome cell.
- `RankPrediction` — Scores how well expectedRank (hard ADP cutoff vs survival table) predicts the rank that arrives at each of…
- `RealDraftSurvival` — The non-circular test of the survival table: scores expectedRank against the league's real 2024/2025 drafts…
- `RegimeShift` — Tests whether 2013-2018 seasons are exchangeable with 2021-2025 before pooling them into the backtest; pric… *(a test pins a number RUNBOOK still quotes)*
- `ReplacementRanks` — Three derivations of what an unfilled slot is worth, checking the replacement rank RiskDiscountedValue uses… *(a test pins a number RUNBOOK still quotes)*
- `ScorerHonestyAudit` — Checks whether PlanBacktest.seasonPoints fills its lineup by expectation or hindsight, scoring each roster… *(a test pins a number RUNBOOK still quotes)*
- `ScoringImpactReport` — Scores every PlanBacktest strategy under pts_half_ppr and under LeagueActuals in one process to show what t… *(a test pins a number RUNBOOK still quotes)*
- `ShapeSearch` — Leave-one-season-out shape picker to test whether the RUNBOOK plan's 1998 is an out-of-sample number or a t… *(a test pins a number RUNBOOK still quotes)*
- `ShapeSensitivity` — Maps the neighbourhood of the fourteen-slot committed shape to tell a plateau from a spike. *(a test pins a number RUNBOOK still quotes)*
- `SourceSensitivity` — Does the best committed draft sequence flip when projections come from ESPN or CBS instead of Sleeper/Rotow…
- `StarterContribution` — Sweeps a pick's contribution to weekly starting lineups across bust/injury rates to show where TE-early vs… *(keep and RERUN after the grader fixes; archive once PlanBacktest carries their strategies)*
- `TightEndTiming` — Joins five seasons of dated pre-season ADP to realised points and scores TE-early/WR-late vs WR-early/TE-la… *(keep and RERUN after the grader fixes; archive once PlanBacktest carries their strategies)*
- `TrustCoefficient` — Estimates the projection-trust slope (realised gap on projected gap) in the shape RiskDiscountedValue uses… *(a test pins a number RUNBOOK still quotes)*

**archive** (67)

- `AccuracyShootout` — Scores every stored projection/ADP/ECR feed as a predictor of actual season points per season (spearman, to… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `AdaptiveCeiling` — Measures headroom above the best tournament entry by growing inner rollouts of oldschool-2 lookahead to fin… *(referenced by nothing)*
- `AdaptivePremium` — Races shipped/timing/vorp/adaptive policies at Justin's seat under full rules on paired seeds to price the… *(referenced by nothing)*
- `AdpProvenanceAudit` — Checks whether Sleeper's stored per-season ADP is a preseason snapshot by rank-correlating it against FFC p… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `AdrProvenance` — Compares Abusing Draft Rankings sheet capture dates with real draft dates (from the league chain) to judge… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `AllenCheck` — Prints survival of top QBs (>330 projected points) at Justin's picks under the shipped selection model. *(referenced by nothing)*
- `AppetiteAudit` — Per season, each manager's first in-draft QB (or -Ppos) round, league mean, and kept-QB count, to separate… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `AutodraftImpact` — Measures survival of Justin's targets at each pick with JFMarino human vs autodrafting, same seeds. *(referenced by nothing)*
- `AutodraftScenario` — Runs Justin's seat with JFMarino human vs autodraft and compares plan, value and round-2 target survival. *(referenced by nothing)*
- `BaselinePlanAudit` — Prints every manager's keeperless baseline staged plan and where QB landed, to test whether the valley trap… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `BaselineQbTimingCheck` — Re-inserts the baseline plan's QB at each later round and prices variants at high trial counts to bound how… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `BenchWindow` — Checks whether BenchValue.overWireByPosition's per-position ordering changes with the rounds window (1-9 vs… *(referenced by nothing)*
- `BlockingTest` — Tests whether taking player A vs B at pick 7 changes the availability distribution at the next pick (the bl… *(referenced by nothing)*
- `BoostLab` — Model-class experiment: gradient-boosted scorer over all features vs the conditional logit on the survival… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `BustBoomRates` — Per-game bust/boom rates by position and draft-round tier over 13 seasons with season-bootstrapped error bars. *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `BustBoomSweep` — Sweeps BustBoomValue's bust/boom/detection-lag parameters at picks 79-127 to bound whether a bust/boom chan… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `CensoredDisplacement` — Fits a split-normal availability model by censored maximum likelihood including undrafted players, fixing P… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `CommitteeRobustness` — Tests the depth-2 VORP-tail live engine against greedy across wrong-model worlds (drone, chaotic, QB-hungry). *(referenced by nothing)*
- `ConfigMatrix` — Runs BoardValue as a subprocess under every flag combination and tabulates results so recommendations becom… *(referenced by nothing)*
- `CrossoverTable` — Over five real seasons, how often a late-tier player outscores an early-tier one at the same position and b… *(referenced by nothing)*
- `CurveCheck` — Prints 2026 projections, isotonic fit and detected steps per position so the board, not a threshold, settle… *(referenced by nothing)*
- `DecisionSensitivity` — Re-runs the timing search under fog draws from FogFit constants to count which timing heads survive and the… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `DefenceHistory` — Per era-board season, counts defences on the board and joins to LeagueActuals.seasonDefencePoints to check… *(referenced by nothing)*
- `DefenceSurvey` — Sixteen-round survey of where each model variant places its defence on its own, after the forced-placement… *(referenced by nothing)*
- `DefenceTiming` — Prices the cost of taking the defence in each round with the roster shape held fixed, against the 125-point… *(after RUNBOOK 445-451 is rewritten; DRAFT-READY carries the one table)*
- `Draft16` — Model A's expectimax run out to sixteen rounds with the starter-sum objective swapped in via RosterValue, t… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `DraftRehearsal` — Generates mid-draft boards with the fitted opponents, stops at each of my picks and times the live decision… *(referenced by nothing)*
- `DrainPrediction` — Scores how many men of a position go between my pick and my next under the retired room+ADP blend versus th… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `EraSample` — Reports how many seasons the harvest reached and what that does (and does not do) to the season-level error… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `FancyAddendum` — Evaluates hindsight optimisation (hop), SAA replan/plan and expert-iteration policies on the tournament's e… *(referenced by nothing)*
- `FeatureLab` — Gates every candidate selection-model feature: fit through 2023 with shipped set plus candidate, simulate 2… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `FeatureScales` — Checks whether the CLIFF_CAP-scaled features f9/f29 are alive at every position or dead for small-scale pos… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `FeedResemblance` — Walks every real draft in pick order scoring each pick's rank on every dated feed to find which sheet the r… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `FluxDraft` — Dynamic flux-balance-analysis draft policy: re-solve a small position-allocation LP each round and take the… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `FormatProxyAudit` — Measures the bias of using PPR era boards as a proxy for half-PPR on the eight seasons where FFC publishes… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `GapCertificate` — Information-relaxation upper bound: mean of per-scenario clairvoyant optima minus the best measured policy,… *(referenced by nothing)*
- `InsuranceTest` — Rounds 8-9 insurance study (frequency vs severity of promotion by position) over fixed 1-7 plans; also owns… *(main only, after the wire basis moves into one Wire class; replacementRanks stays)*
- `KnLiveProbe` — Measures Kim-Nelson selection per pick along a realistic draft: rollouts spent, whether it proves a selecti… *(referenced by nothing)*
- `LandmineCheck` — Regresses the Abusing Draft Rankings 'landmine score' on platform-rank minus consensus gaps already held, t… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `LateHalf` — Compares what each strategy picks from round 8 on and what those picks delivered in weeks they genuinely st… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `MarginalTrace` — Prints the sixteen-round objective's view of a roster at one pick (every slot, what fills it, each position… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `MismatchArena` — Matrix of true opponent worlds x policies (fixed-model, Bayes/robust hedgers, lookahead, PaceVorp) scored M… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `ModelAAudit` — Adversarial audit of Model A / the DraftNight path (setup, stateAfter alignment, boundary, committee, clock… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `NewEngines` — Four experimental pick engines (depth-3, two-stage stochastic, regret-match, mean/p25 blend) scored on the… *(referenced by nothing)*
- `ObservationCount` — Counts fitted observations (not seasons) behind Model A vs the 1-16 attempts to argue five seasons was neve… *(referenced by nothing)*
- `OddsConsistency` — Checks whether BoardValue's draw-implied same-position odds reproduce PairwiseOdds' fitted surface. *(referenced by nothing)*
- `PlanShowdown` — Prices the RB-heavy vs WR-heavy seven-round plans at high precision on paired seeds alongside the live engine. *(after RUNBOOK records its +1.8 provenance and standard error)*
- `PositionCurve` — Prints mean best-available projection per position at each of my picks and the 2nd/3rd-best falloff, to ans… *(referenced by nothing)*
- `PredictabilityProfile` — Per position and tier: bias, spread and skew of preseason ADP vs outcome over five seasons. *(referenced by nothing)*
- `QBMarket` — Empirical QB market per season: who held a kept QB, when each manager took his first in-draft QB, raw QB-ru… *(superseded by Keepers16 / NextYearKeepers / KeeperAudit)*
- `QbCountTest` — How many extra QBs a keeper-holding (Purdy) roster should draft; re-examines the round-3-QB finding with ke… *(referenced by nothing)*
- `RankStdGate` — Gate test of whether FantasyPros rank_std predicts out-of-sample deviation from slot better than tier alone. *(referenced by nothing)*
- `RankingSelectionTest` — Tests Kim-Nelson sequential ranking-and-selection budget allocation against the committee's equal fixed rol… *(referenced by nothing)*
- `ReachAudit` — D2 reach-size distribution (chosen player's rank among still-available Sleeper-default feed) per manager an… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `RecencyGate` — Gate for recency-weighted (half-life) QB earliness in the room model: chosen on 2024, confirmed on held-out… *(referenced by nothing)*
- `RiskDial` — Reports whether a seat below the playoff line should draft for variance via DraftPlanner's lambda dial (ava… *(referenced by nothing)*
- `RookieMarket` — Pick-minus-ADP for rookies vs veterans, split at the nine-round boundary, to test whether the keeper league… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `RoundTable` — Prints what the fixed plan and the lookahead model take each round across sixteen rounds, as a distribution.
- `ScenarioTree` — Non-anticipative scenario tree solved by backward induction to bound how much of the clairvoyant certificat… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `SequenceSearch` — Hill-climbs whole fourteen-position draft sequences scored on real outcomes, leave-one-season-out, to try t… *(referenced by nothing)*
- `SlotValue` — Pure serpentine slot curve: every seat valued on a world with no keepers so bumps in the real landscape are… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `SniperFit` — M1 sniper mixture: per-manager reach rate eps_m fitted on 2022-2024 with a held-out 2025 gate, reaches meas… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `StarterSumCheck` — Checks that WeeklyStarterValue values a tenth bench man positively where the old best-nine-of-season-totals… *(referenced by nothing)*
- `StrayDiagnostics` — M2 triage: raw within-position reach signals for rookies, youth, faded names, homers and 6-pt display QBs o… *(referenced by nothing)*
- `TeOrDepth` — Marginal starter-sum value of the best available TE vs another RB/WR at each pick, against a roster that ne… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `TierCliffs` — Empirical tier drops per season and position in the projections managers saw, motivating the drop-off selec… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*
- `WorldsRace` — Six simulated worlds (boosted brain, linear brain at three temperatures, QB-pressure/QB-lazy) racing the co… *(one-off August measurement; verdict recorded in MODEL.md / DIAGNOSTIC.md)*

---

## 4. Keeper selector

**Purpose.** Which two to keep, what each is worth, the rules (cost = round in the most
recent draft, +1 a year, three-year cap, rounds 1-2 unkeepable, undrafted = r10), the
commissioner audit, next-year (2027) valuation.

**State of health.** The 2027 basis is right on the page (`NextYearKeepers.forThisLeague`,
the three-year cap, rival picks at their own slot). The component is still four
hand-assembled copies of the same pricing and every copy except the console misses at least
one of this month's fixes. **The page's own KEEP marks are position-blind** — the top two
surpluses — while every trade row on the same page is priced on the legal pair; today the
top two are an RB and a QB, so it is right by luck, and the day Tuten is traded the panel
names Nix + Purdy while the rows charge Nix + Ravens. Four selectors are listed as peers in
README (`KeeperPlan`, `KeeperChooser`, `KeeperValuation`, `Keepers16`); `Keepers16` — the
sixteen-round objective, real rounds, defences — is the one to keep, for July 2027.
*Inline finding retracted:* `Keepers16:278` uses `eligibleCandidates`, and for a pre-draft
tool that is the right basis; the hazard is calendar, not sibling (A6).

**Fixes** (10 confirmed; the first five reach this week's page or the weekly reports):

1. [high/trivial] `TradeMarket:672` → `lossAverseOnKeepers(season, surplus, everyPosition)`; delete the two-arg overload; test the ONE_STARTER rule on the rival side.
2. [high/small] Console KEEP flags and the keeper note come from the pair `keeperValue` chooses (`TradeMarket.bestKeeperPair`) — `LeagueConsole:939`; test that no two KEEP rows share a one-starter position.
3. [medium/trivial] `TradeStability:80` → `TradeMarket.DEFAULT_POOL`; add it to `SearchWidthTest`.
4. [medium/small] `TradeStability` builds its keeper curve from the whole feed (`everyPosition`), like its three siblings — `TradeStability:116`; lift the loop into `TradeMarket`.
5. [medium/small] Undrafted men cost a tenth everywhere: move the default into `NextYearKeepers.roundsForThisLeague` — `LeagueConsole:516`, `TradeMarket:663`, `TradeStability:119`, `KeeperDriftCheck:99`.
6. [medium/trivial] `KeeperAudit` says "no keepers on the board" when it is the rosters' `keepers` field Sleeper cleared on 2026-09-02; audit the board's `is_keeper` picks directly — `KeeperAudit:53`.
7. [medium/small] `KeeperOrigin` walks back to the first non-keeper pick; the runbook's 0.88 capture rate is really 0.71 — `KeeperOrigin:72`, RUNBOOK 401-412, `StashValue:44`.
8. [medium/small] The three-year cap and `consecutiveYears` get a behavioural test; the "edge" assertion is fixed — `NextYearKeepersTest:65`.
9. [medium/small] `KeeperDriftCheckTest` tests the code, not the newest artifact — `KeeperDriftCheckTest:22`.
10. [medium/medium] The QB drift is computed from `QbMarketGap` on the band the keeper sits in, not a typed 11.7 measured over QB1-8 — `KeeperDriftCheck:43`.
11. [medium/small] `NextYearKeepers` and `KeeperEligibility` apply the first-two-rounds rule to the escalated cost; `KeeperPricing` applies it to the original draft round — reconcile on `KeeperPricing`'s reading — `NextYearKeepers:123`.
12. [medium/medium] The same-round bump is applied by `KeeperPricing` and the league, never by `NextYearKeepers` or the pair search — `NextYearKeepers:140`.
13. [low] `TradeStability`'s `[KEEPER]` marker fires on a bare 25 (a copy of `HIS_KEEPER_POINTS`); a pricing failure ships an unmarked report — `TradeStability:127,243`.
14. [low] `KeeperBasisTest` omits `KeeperDriftCheck`; `KeeperChooserSmokeTest`'s assertion is a tautology; `KeeperAudit` is owner-blind.

**Improvements.** (G2) show the pair and the fallback pair on the keeper panel ("if Tuten goes: Nix + Ravens"). (G2) audit the board's `is_keeper` picks so `KeeperAudit` works after Sleeper clears the declarations. (G3) a rival keeper map: each manager's best legal 2027 pair and the men who are keeper-dead. (G2) decide once whether a round-2 keeper cost is legal or never-paid (six seasons contain none). (G1) one behavioural sibling test on a fixture instead of six source tests. (G2) put the QB drift inside the page's surplus, or drop the side report.

**Tools.**

**live** (2)

- `KeeperAudit` — Checks the commissioner's hand-entered keeper rounds on the Sleeper draft board against the ruleset (escala…
- `KeeperDriftCheck` — Prices every 2027 keeper candidate on the ADP curve as-is and shifted by the measured QB market gap (~12 pi…

**seasonal** (2)

- `KeeperChooser` — Chooses the keeper pair by simulating the following draft (SimulationDraft) for each legal pair; owns eligi… *(library (eligibleCandidates, priceHypothetical); its main/rank() ride the legacy simulator)*
- `Keepers16` — Every owner's keeper candidates and best pair valued on the sixteen-round WeeklyStarterValue objective (inj… *(THE 2027 selector: run July 2027, before the draft)*

**diagnostic** (2)

- `KeeperOrigin` — For every declared keeper, did the keeping manager also draft him? Yields captureRate (0.88) that discounts…
- `QbMarketGap` — Keeper-adjusted surplus and drone-gap statistics measuring how far this league lets QBs fall relative to Sl…

**archive** (6)

- `KeeperEligibility` — Lists who is keeping whom, how many consecutive years each has been kept, and how many years remain under t… *(superseded by Keepers16 / NextYearKeepers / KeeperAudit)*
- `KeeperLedger` — Every team's standalone keeper values from the full nine-round expectimax at reduced rollouts (search/evalu… *(superseded by Keepers16 / NextYearKeepers / KeeperAudit)*
- `KeeperPlan` — V(keeper) - V(none) for every eligible keeper, each branch a DraftPlanner-optimised nine-round plan (Model A). *(superseded by Keepers16 / NextYearKeepers / KeeperAudit)*
- `KeeperValuation` — Values a keeper against the nine skill starting slots: any keeper really costs the round-nine pick; defence… *(superseded by Keepers16 / NextYearKeepers / KeeperAudit)*
- `KeeperWhy` — Decomposes one keeper's V(K)-V(none) by lineup slot group from any owner's seat, showing who mans QB in eac… *(superseded by Keepers16 / NextYearKeepers / KeeperAudit)*
- `WhoIsKept` — Lists every keeper declared in the league, not just mine. *(superseded by Keepers16 / NextYearKeepers / KeeperAudit)*

---

## 5. League analyzer

**Purpose.** Rosters ranked; the season ledger and its frozen bar; playoff odds and the
week-7 buy/sell call; manager profiles; who actually trades; owner ladders.

**State of health.** The live pieces are young and carry this month's fault family.
`SeasonOutlook` plays two results a week in the simulated half since `81e616c` and **one in
the banked half**; its tiebreak never sees a real point; its roster-to-name join is a
forever-cached copy of the live league's users; and the one parameter every probability
scales with is a typed literal — **the 24.9 spread no committed code measures**, and it is
the pooled SD across teams (24.95) rather than the within-team noise the simulation applies
it as (about 23.7). `TradePartners` drops the one FAAB-only trade (report 50, log 51,
`TradeMarket.realisedTrades` already says 51). `LeagueTransactions` and `PlayoffOdds`
freeze the live league's meta forever at `pre_draft`. None of it changes today's verdict
("Too early"); every one of them lands before week 7.

**Fixes** (11 confirmed; the first five before week 7):

1. [high/small] Bank two results per played week and seed banked points — `SeasonOutlook:132,164`; extract the banking into a static, test on twelve known scores.
2. [high/trivial] Join roster ids through the same roster JSON `LeagueOwners.today` reads (`SeasonLedger.managerByRoster`), not `TradePartners.managersOf`'s forever cache — `SeasonOutlook:99`.
3. [high/small] Measure the spread: within-team residual SD over completed seasons' team-weeks, printed with its n; `measuredSpread()` reads it, `-PteamSpread` stays as a labelled what-if — `SeasonOutlook:56` (patch drafted).
4. [high/small] The head of the league chain is day-cached until `status == complete` — `LeagueTransactions:61`, `PlayoffOdds:56`, `DraftDates:30`. *Needs three untracked cache files deleted from the project root — listed under Open items.*
5. [medium/trivial] `LeagueTransactions.Move` carries `roster_ids`; `TradePartners.participants` uses it — `TradePartners:94`.
6. [medium/small] `SeasonOutlookTest` tests the extracted simulation on synthetic input, not the 600-by-construction column — `SeasonOutlookTest:24`.
7. [medium/trivial] Ternary precedence drops the spread sentence from the header when the median game is on — `SeasonOutlook:214`.
8. [medium/small] `testLogging` on `test`; `data/` as a test input (C).
9. [low/trivial] A head-to-head tie banks a tie — `SeasonOutlook:133`.
10. [low] `TeamRankings` ranks the *draft* by `draft_slot`; README calls it "every roster" — repoint at `LeagueOwners.today`.
11. [low] Verify `LineupPromotion`'s `players_points` claim; `LeagueOutlook` reads keepers the feed no longer carries.

**Improvements.** (G1, fewer weekly commands) the outlook table on the console page from week 7 through the one extracted `simulate()`. (G1) one matchup cache for the three in-season tools. (G2/G3) `TradePartners` by *when* each manager trades — the sell-for-keepers rule needs a market in weeks 8-12. (all) one roster-id-to-name join with a test that no tool builds its own. (G1) `SeasonLedger`'s week loop gets a synthetic test before week 1 is appended.

**Tools.**

**live** (4)

- `SeasonLedger` — Appends each finished week (points by starters and bench, standings) and judges the bench question against…
- `SeasonOutlook` — Plays out the remaining real schedule from the measured 24.9 weekly spread to give playoff odds, the trigge…
- `TeamRankings` — Every roster's best legal lineup (QB,2RB,3WR,TE,DEF,2FLEX exactly as BoardValue.lineup) scored on league pr…
- `TradePartners` — Who actually trades: per-manager completed-trade rates and pair counts from the league log, joined on roste…

**seasonal** (3)

- `DraftExpectation` — What each seat should have expected from its slot and keepers (room model drafts every seat from the pre-dr… *(post-draft one-shots; keep their statics (evaluate, man, rungTrials))*
- `LeagueOutlook` — Every manager's seat optimised by the expectimax from their slot with their keepers: ranking, slot-group br… *(post-draft one-shots; keep their statics (evaluate, man, rungTrials))*
- `OwnerLadder` — Every owner's four-rung ladder (seat alone, keepers as declared, best ledger pair, roster drafted) on the D… *(post-draft one-shots; keep their statics (evaluate, man, rungTrials))*

**diagnostic** (1)

- `ManagerProfiles` — League positional bias vs ADP plus per-manager shrunk offsets, fitted through a season cutoff for leakage-f…

**archive** (3)

- `LeagueRules` — Prints the league's roster_positions from Sleeper with which slots must be filled to score (written to refu…
- `ManagerFeedTable` — Per-manager mean log2 rank of their picks on each candidate feed, to guess which sheet each manager reads.
- `PlayoffOdds` — Bootstraps P(top six) as a function of a team's points deviation from the season league mean, from the leag… *(with RiskDial, its only reader)*

---

## 6. Trade finder

**Purpose.** The trade search and valuation, the matching market for rivals' outside
options, optics and the SEND/ASK/NO tiers, uneven trades, chains, per-trade error bars,
stability.

**State of health.** The page's numbers are mostly right after this month's sweep; the two
terminal siblings still disagree with it about the same trades on the same afternoon
(`TradeMarket:672` position-blind on the rival side; `TradeStability` at pool 6, keeper
curve from rostered men, verdicts against the retired 6.8). Two structural defects reach
the page: the `Trade` record never carries the forced cut an uneven trade was priced with,
so all 28 uneven rows compute the rival's season/simple/hole on a 17-man roster; and the
chain re-searches unsorted rosters after step one, so no shipped chain ever moves an
acquired man. `TradeFinder` — the legacy enumerator on `ScoredRoster` — is still the
build's default main.

**Fixes** (12 confirmed, plus 2 found in season):

1. [high/small] Delete the two-arg `lossAverseOnKeepers`; pass `everyPosition` at `TradeMarket:672` (= keeper fix 1).
2. [medium/trivial] `TradeStability` pool → `DEFAULT_POOL`; `SearchWidthTest` lists it (= keeper fix 3).
3. [medium/trivial] `data/` artifacts as gradle inputs (C).
4. [medium/trivial] KEEP marks from the legal pair (= keeper fix 2).
5. [medium/trivial] The Trades tab defaults to SEND-only as the RUNBOOK says, with an empty-state line — `LeagueConsole:1076`.
6. [medium/small] `TradeStability` keeper curve from the whole feed (= keeper fix 4).
7. [medium/small] Undrafted-costs-a-tenth in one home (= keeper fix 5).
8. [medium/small] The chain re-sorts by projection after every swap — `TradeMarket:1190,1234`.
9. [medium/small] One "is it bigger than the noise" rule (the page's `low <= 0`) shared with `TradeStability` and the chain; retire 6.8 from `TradeStability:81,231`, `TradeMarket:766`, `LeagueConsole:619`.
10. [medium/small] `TradeStability` rethrows on a keeper-pricing failure, uses `HIS_KEEPER_POINTS`, matches keepers by id, and prints the rival spread it already computes.
11. [medium/medium] Carry the forced cut on the `Trade` record; one `TradeMarket.after(roster, trade, side)` for every consumer — `TradeMarket:495,509`, `LeagueConsole:681`, `TradeStability:170`.
12. [low] Stale "size-balanced" prose; the unfloored-chain paragraph; `realisedTrades`/`outsideOption` dead; `KeeperBasisTest:105` reads raw source; `TradeStabilityTest:68` asserts at the rounding bound.
13. [low/trivial] `build.gradle:115` default main → `LeagueConsole`; README line 37.
14. [high/small] (found 2026-09-15) The board prices a man on his projection whatever his injury tag: on `-Pprojections=posterior` its top rows ask KevinDA for Kyler Murray a day after his concussion, because a 0.6-point game reads as a bad week. Read `SleeperProjections.injuryStatusOf` for every man in a row and mark Out/IR/Doubtful in HOW IT READS, on both pricings; `WeekReaction` already overrides its verdict with HURT.

**Improvements.** (G1/G3) one row builder shared by page and terminal board. (upkeep/G1) fold `TradeStability` into the console's per-trade error bars, or make it read the shipped rows. (G3) print the rival's per-seed spread. (G2) pin the RUNBOOK's "What never to trade" paragraph to the page's KEEP rows. (G1) after the re-sort, re-measure reach and decide `chainPool` on a measurement.

**Tools.**

**live** (2)

- `TradeMarket` — Every size-balanced swap with all eleven rivals priced on WeeklyStarterValue, shown only where both sides g…
- `TradeStability` — Re-values the board's top trades under several seeds and reports the spread, so a recommended margin can be…

**archive** (3)

- `GivenTradeAnalyzer` — Finds one --give/--take trade inside the tier .txt files TradeFinder writes.
- `TradeFinder` — Enumerates single/double/triple swaps with every rival on projected starters, files them by how much they h… *(superseded by TradeMarket; change the build default first, delete when its tests are judged redundant)*
- `TradeFinderThreeTeams` — Three-team trade permutation search over ScoredRoster (permutations of a-f built in a static block), no jav…

---

## 7. Waiver finder & weekly lineup

**Purpose.** Tuesday: the wire (swap search, completion pricing, FAAB bids, wire survival).
Sunday: the lineup (start/sit, break-evens, defence). The console page; the weekly-starter
objective and its noise; projection drift.

**State of health.** The wiring is better than a week ago (one `TuesdaySwap.price`, one
keeper basis, one pool). **The numbers on the wire tab are wrong in unit, wrong in
yardstick, and drawn from a contaminated pool.** The page bids FAAB on a 17-week worth
while the report beside it says to scale by weeks-left/17 (a man worth 20 in week 8 bids
$3 on the page against $1 at the 8.2 the report calls collectable). The "inside the noise"
tag and the CLAIM/DO NOTHING verdict use a 6.8 floor measured on one starter's marginal on
a 13-man 2025 roster, applied to an (add, drop) pair on the 16-man 2026 roster — the
trades tab retired that exact instrument. Every cell the objective draws from has been
keyed since `57e6559` by Sleeper's season-END feed, which MODEL.md itself declares unusable
because it already knows who got hurt. The Sunday odds column is calibrated on 4-pt-TD
gaps and looked up with 6-pt gaps — it moves only the QB pair, which is the one real
start/sit on this roster. IR men are active in every roster join. Today's board is quiet
(Mayfield +1.19, DO NOTHING), so no wrong verdict has yet been visible — TRAPS #101's
after-the-fact shape, and the reason to fix it before the wire gets busy.

**Fixes** (9 confirmed):

1. [high/small] One horizon for a swap's worth, and bid on it — `TuesdaySwap:270`, `LeagueConsole:440`, `FaabBid:252`; ship `worthFromHere`; the regime read from `ProjectionDrift`'s verdict, not assumed.
2. [high/small] Calibrate the flip curve in the units it is looked up in — `StartSit:310`, `LeagueWeek:93`; print the unit in the flip header.
3. [high/small] Reserve (IR) men are not active men — `LeagueOwners:31`; `LeagueWeek:118`, `LeagueConsole:246`, `TuesdaySwap:228`, `StartSit:183`.
4. [high/medium] Judge each wire row by its own seed bar, as trades are; retire 6.8 from `TuesdaySwap:217`, `LeagueConsole:418,471` — `TuesdaySwap.recommend` requires the low bound to clear zero.
5. [high/large] Key the outcome pool by a right-vintage order (the week-1 endpoint or the dated snapshots), not the season-end feed; rerun `RankKeyChoice`; retract TRAPS #82 in a commit — `OutcomeDistributions:102`, `HistoricalProjections`. *Moves every number in the repo; measure before and after at one data stamp.*
6. [medium/small] `ProjectionDrift` reads the committed archive; its test can fail on the freeze — `ProjectionDrift:65`, `ProjectionDriftTest:59`.
7. [medium/small] `DefenceThisWeek` must not start a defence on bye; regenerate at the measured lag — `DefenceThisWeek:86`.
8. [medium/small] Fetch the NFL week fresh, not from the day cache — `LeagueWeek:35`.
9. [medium/small] A quiet wire must not turn `check` red — `LeagueConsole:436`, `LeagueConsoleTest`, `ConsoleRendersTest`.
10. [medium/small] FAAB budget from the league, never a silent 100 — `LeagueConsole:347`.
11. [medium/medium] The Sunday break-even bar: one semantics (the RUNBOOK, TRAPS #116 and the code comment describe a `taken` set `81e616c` removed), one re-deriving test — `LeagueConsole:312`.
12. [low] `TuesdaySwap`/`LeagueConsole` shared candidate list (`EnumMap` vs `HashMap` orders ties differently); `FaabBid:340` prints the second-largest as max; the committed win ladder predates the tie fix.

**Improvements.** (G1) fold the defence into the console's Tuesday view; retire the standalone report. (G1) stop pricing hurt men on their August number — blend in-season actuals once `ProjectionDrift` says the feed is frozen. (G1) condition the FAAB win chance on how long the man has sat (`WireSurvival`'s sevenfold hazard decline). (G3) before you cut him, see who would trade for him. (G2) one `LeagueShape` read from the league JSON (A). (G1) show the add's own injury status on the wire.

**Tools.**

**live** (6)

- `DefenceThisWeek` — Which defence to start this week by reproducing the streaming policy WireRateStress measured (free defence…
- `FaabBid` — What to bid from this league's own settled waiver contests (including failed claims): P(win|bid) times wort…
- `LeagueConsole` — The weekly HTML page: lineup, defence, wire, precomputed FAAB bids and TradeMarket swaps shipped as data; t…
- `ProjectionDrift` — Compares archived daily season-projection snapshots to report whether the feed still moves once games are p…
- `StartSit` — The best legal ten from league-scored week projections, with the measured coin-flip band (-Pcalibrate write…
- `TuesdaySwap` — Tuesday waiver search over (add, drop) pairs on WeeklyStarterValue with DO NOTHING as default; prints the d…
- `WaiverLog` — This season's waiver claims from the transactions feed, live: each contest's bids by manager and the winner, claims that died for room marked as such, FAAB spent per manager against the roster feed's counter, and my own claims. Report to data/waiver-log-<season>-w<week>.txt.
- `FaabDemand` — The claim harvest cut by the weekday it cleared (the big run after the games vs the rest: contests, dollars, prices, win ladder); P(any bid) over the WHOLE wire at every big run (rosters from the matchups feed) from touches, points, ppg so far, ADP, snap share and jump, a drop and position, leave-one-season-out, read by decile; the price by ADP and ppg band among the claimed; this week's wire ranked by P(bid) with the big-run bid to win 50/75/90, and a backtest of the run that just cleared. Report to data/faab-demand-<date>.txt.
- `WeekReaction` — After the games: every rostered skill man's week against his preseason prior, the share the measured update rule keeps (InSeasonLearning's kappa, refit per run), whether Sleeper's season projection moved, and a sell-high / buy-low verdict that names which half is assumed. Report to data/week-reaction-<season>-w<week>.txt.

**diagnostic** (7)

- `InSeasonLearning` — Tests whether a conjugate-normal in-season update of the preseason ranking beats preseason at pick level an…
- `RosBands` — The update rule with a band: posterior variance plus game noise, level- vs rate-scaled scatter, normal vs empirical quantiles, judged by coverage and the 80% interval score leave-one-season-out; the chosen recipe is what WeekReaction prints; and Sleeper's next-week projection against the posterior as an estimate of the rate from week k+2 on. Report to data/ros-bands-<date>.txt.
- `UsageSignal` — Does usage say more than the score: targets, carries, pass attempts, air yards and red-zone touches from Sleeper's weekly rows (2013-2025) against the points-only rule's residual - per-stat correlation, a leave-one-season-out ridge term's MAE and flip-accuracy gain - and the all-season fit applied to this week's rostered men. Report to data/usage-signal-<date>.txt.
- `LineupPromotion` — Replays five seasons of real rosters under PRESEASON / FORM / ACTUAL / PERFECT lineup rules to value the bu…
- `ObjectiveStability` — Builds WeeklyStarterValue under several seeds and prints per-man marginals and the worst seed-to-seed sprea…
- `OutcomeDistributions` — Per-player week-by-week season outcome cells (availability and scoring separated), leave-one-out pool keyed…
- `PromotionBehaviour` — Reads five seasons of the league transaction log to measure how fast real managers promote bench boomers /…
- `WireRateStress` — Measures what a hindsight-free defence streaming policy actually yields versus wireRates' top-quartile-of-r…
- `WireSurvival` — Hazard of a dropped man being claimed as a function of days sat unclaimed, from drop/add timestamps, to tes… *(its hazard table is the FAAB-conditioning evidence; endOfSeason unused (fix))*

**archive** (1)

- `LateRoundTargets` — Ranked 2027-keeper-option stash list: projected value minus round-R pick return, times measured hit rate, r… *(superseded by LiveLateRounds; keep LateRoundToolTest)*

---

## Order of work

One full `check` per batch, never two at once, artifacts regenerated back to back at one
data stamp, the before/after diff written into TRAPS with the numbers. This week's numbers
first; the draft tool before August; the archive after Justin's yes.

| batch | what | reaches |
|---|---|---|
| **1** | the sibling and in-season fixes: keeper 1-5, trade 1-2/8-10, weekly 1/3/9/10, league 1-2/5/7, feeds 3-4; plus `SeasonOutlook` spread measurement (league 3) and `KeeperBasis` (A6); `TradeFinder` off the build default | the console, `TuesdaySwap`, `TradeMarket`, `TradeStability`, `SeasonOutlook` this week |
| **2** | the yardstick and unit fixes: weekly 2/4, research 1-2 (wire basis, 17 weeks), feeds 1-2/6 (archive per source, CBS, injury freshness), league 4 (chain head) | every "with him / without him", every flip odds, the FAAB archive |
| **3** | workstream A (one home for league facts) + workstream C (test discipline) + D (`Feeds` registry) | nothing numerically; everything structurally |
| **4** | weekly 5 (right-vintage outcome pool) — measured on its own, alone in its batch | every number in the repo |
| **5** | the draft tool (component 2, all) + the research reruns (component 3) + the prose pass | next August |
| **6** | the archive move, after the yes | the file list; `run`'s menu |

## Archive proposal — for Justin's yes, enumerated

97 runnable tools (14,288 lines in their own files), plus the legacy
support classes below. Mechanism: `src/archive/java` as a second Gradle source set with
`main` on its compile classpath, so everything still compiles and can be run with
`-Parchive=true`, but nothing in it appears in `run`'s menu, the docs, or this map. The
Java default package cannot be imported from a named package, so this is a source-set move,
not a subpackage; git history keeps every hash, and MODEL.md records each archived name
with its last hash so a 2027 question can `git show` it. Tests that reference an archived
main move with it or get a live twin (listed per component in the evidence file).

Legacy cluster (the 2022-2026-08 codebase), archived as one unit: mains `TradeFinder`,
`TradeFinderThreeTeams`, `GivenTradeAnalyzer`, `SleeperLiveDraft`; support classes
`ScoredRoster` (408 lines), `SimulationDraft` (303), `StrategyBot` (63), `HumanStrategy`
(66), `OnTheFlySimulationRunner` (428), `RunDraftWithKeepersTask` (35),
`TradePreviewSerious` (186), `TradePreviewSerious3T` (323),
`FPRosterSeriousStartingQBComparator` (36), `LiveDraftInfo` (87), `SleeperADP` (64),
`Keepers` (pre-2026 keeper class). Stays: `Score`, `User`, `Roster` (shared with live code),
`KeeperChooser`'s pricing statics. Cut first: `SleeperLeague.scoreSleeperDraft` (only
`SimulationDraft` calls it) moves with the cluster; `KeeperChooser.main`/`rank()` too.
Tests coupled: `BestLineupTest` (needs a `TeamRankings.bestLineup` twin), `LeagueSmokeTest`,
`TradeEnumerationTest`, `TradeEvaluationTest`, `TradeFilterTest`, `DraftStrategyTest`,
`KeeperChooserSmokeTest`, `KeeperBaselineSmokeTest`.

The full per-tool list with the reason for each is the **archive** rows in every component
table above. Nothing moves until the yes.

## Open items Justin owns

- **`master` is 38 commits, 129 files and 14,781 lines behind `keeper-rules`** (last merge PR #6, 2026-09-02). DIAGNOSTIC.md's fix-list item 9 ("PR keeper-rules → master, due 2026-09-03") is still open. Not mine to do.
- **Three untracked cache files in the project root** must be deleted for league fix 4 to take effect: `sleeperLeagueMeta1390416723210952704.txt`, `leagueChain1390416723210952704.txt`, `rostersChain1390416723210952704.txt`. They are day-old copies of feeds the code re-fetches; listing them here is the ask.
- **CBS.** Its season endpoint now serves the week page in season. The archive can drop `cbs` from week 1 onward (printed every run) or someone finds a rest-of-season table; the plan assumes the former.
- **The mario-kart-violin benchmark** (PID 48328) is still at 100% of a core after two and a half days and makes the 30-minute check a three-hour one.
