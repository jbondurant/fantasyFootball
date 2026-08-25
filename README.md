# fantasyFootball

Draft and trade tooling for a 12 team, half PPR, 2 keeper Sleeper league.

## Configuring a season

`AAAConfigurationSleeperLeague` holds the league id and the Sleeper username,
and nothing else. Sleeper creates a new league id every August; everything
else - the draft, last season's draft, the other managers, the scoring
settings, the season - is read back from the API.

## Running

```
./gradlew run -Pmain=TradeFinder          # trades worth proposing
./gradlew run -Pmain=SleeperLiveDraft     # draft-day advice
./gradlew run -Pmain=KeeperChooser        # which keepers to declare
./gradlew run -Pmain=KeeperAudit          # check keeper costs before the draft
./gradlew run -Pmain=AAAConfiguration     # what league am I pointed at
```

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
