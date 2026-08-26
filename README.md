# fantasyFootball

Draft and trade tooling for a 12 team, half PPR, 2 keeper Sleeper league.

## Configuring a season

`AAAConfigurationSleeperLeague` holds the league id and the Sleeper username,
and nothing else. Sleeper creates a new league id every August; everything
else - the draft, last season's draft, the other managers, the scoring
settings, the season - is read back from the API.

## Running

```
./gradlew run -Pmain=LeagueOutlook        # every seat optimized: rankings, keeper deltas, slot value
./gradlew run -Pmain=ProjectionSources    # projection feeds: status, and where they disagree
./gradlew run -Pmain=KeeperPlan           # the keeper decision, whole-draft optimized
./gradlew run -Pmain=KeeperPlan -Pprojections=borischen   # ...valued on another source's numbers
./gradlew run -Pmain=DraftPlanner         # position to take each round, with risk + snipe odds
./gradlew run -Pmain=DraftPlanner -Ptrials=500 -Prisk=0.5   # ...risk-averse, tighter error bars
./gradlew run -Pmain=TradeFinder          # trades worth proposing
./gradlew run -Pmain=SleeperLiveDraft     # draft-day advice
./gradlew run -Pmain=KeeperValuation      # which keepers are worth a slot
./gradlew run -Pmain=KeeperEligibility    # who is keeping whom, and for how long
./gradlew run -Pmain=WaitOrTake           # take him now, or gamble he lasts a round
./gradlew run -Pmain=AdpSnapshot          # record today's ADP; run often before the draft
./gradlew run -Pmain=MockDraftReader -PdraftId=<link>   # archive a shared mock before it vanishes
./gradlew run -Pmain=KeeperChooser        # the same question by simulation
./gradlew run -Pmain=KeeperChooser -Psims=200   # ...with tighter error bars
./gradlew run -Pmain=KeeperAudit          # check keeper costs before the draft
./gradlew run -Pmain=AAAConfiguration     # what league am I pointed at
```

### What a keeper is worth

`KeeperValuation` optimises the nine skill starting slots - QB, RB, RB, WR, WR,
WR, TE, FLEX, FLEX. The defense is left out on purpose: it comes from a late
pick every year, it never competes for the picks that decide a season, and the
whole position spans 19 points from best to twelfth.

Nine slots means nine picks fill them, so keeping a player frees the round-nine
pick whatever round he nominally costs. He is worth a keeper slot only if he
beats what that pick returns. Players are compared to replacement at their own
position, never to each other: quarterbacks outscore receivers by 150 points a
season, but QB12 already projects near the top of the position, so a big raw
projection at quarterback is worth far less than it looks.

### Keeper costs

Sleeper has no keeper-cost setting - the league carries `max_keepers` and a
deadline and nothing else - so the commissioner places each keeper onto a round
of the draft board by hand. `KeeperAudit` prices them from the ruleset and
compares the two, which is worth running once the board is set and before the
draft, while anything wrong can still be changed. Six seasons of history contain
at least three entries that do not follow the rules.

Each entry point caches its API responses to the project root as
`<name><today>.txt` and reuses them for the rest of the day. Delete them to
force a refresh.

## Tests

```
./gradlew test        # fast, offline, no API calls
./gradlew smokeTest   # hits the live Sleeper and FantasyPros APIs
```

`test` covers the logic: keeper pricing, lineup construction, scoring, name
matching, HTML extraction, draft round arithmetic.

`smokeTest` covers the assumptions that rot between seasons - a field vanishing
from a FantasyPros page, a stale league id, a projections endpoint changing
shape. None of those fail loudly on their own; the numbers just go quietly
wrong. Worth running before the draft and any time the output looks off.

### Smoke testing against a mock draft

The real draft is empty until draft day, so the code that reads picks, tracks
which round we are in and strips drafted players out of the pool has nothing to
run against. Start a mock draft on Sleeper, let it run a few rounds, then:

```
./gradlew smokeTest -PdraftId=<mock draft id>
```

The id is the last path segment of the mock draft's URL. Without it, the
mock draft tests skip and the rest still run.
