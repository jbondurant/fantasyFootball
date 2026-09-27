# Can a model make money against the people betting on sports apps?

Justin, 2026-09-26: keep working until there is a model suitable for arbitrage
against the weighted average of people betting on sports apps, and say so if that
is not achievable. This file is the answer so far. It is updated as evidence
arrives, and every number in it comes from a tool in this repo.

Nothing here is financial or tax advice, and no tool here places a bet. Whether
to bet, where, and with what is Justin's decision.

## The short answer

**Beating the main NFL lines with a model built from public data: not
achievable, on the evidence.** The market measured on its own prices
(`MarketEfficiency`, 2010-2025, 4,174 games):

- The closing line is unbiased. Home margin minus spread averages +0.08, +0.19 and
  -0.07 points across 1999-2009, 2010-2017 and 2018-2025, each inside one
  standard error.
- None of 16 obvious strategies pays in both eras at the prices offered: every
  favourite or dog ATS, home or road, overs, unders, windy unders, primetime
  unders, divisional dogs, and moneyline favourites, dogs and longshots.
  Underdogs covered 51.1% over 4,170 games, which loses at the standard 52.4%
  break-even. Longshot moneylines lost 10%, the classic favourite-longshot bias.
- A public ratings model, refitted every week on past results only, misses the
  home margin by 10.58 points on average against the line's 10.08. Its
  disagreement with the line predicts nothing about what the line missed
  (slope -0.04 +- 0.05).
- Separately, the feature screen (`FeatureScreen`, TRAPS #149) found that 46
  public factors cannot beat even Sleeper's weekly projection, which is itself
  a weaker forecaster than the betting market.

**Riskless arbitrage inside one venue: tried, and the book is kept coherent.**
Kalshi prices each player stat three ways: thresholds (a probability), ladders
(linear, so the price is the expected stat) and escalators (a convex payoff).
Like option strikes, these have to fit together. `KalshiArb` checks all of it:
thresholds against each other, ladders and escalators against the threshold
strip that bounds them, and a QB's passing yards against his receivers'
ladders. On 2026-09-26 it checked 5,852 open contracts (median bid-ask spread
2 cents). One gap survived fees: 0.2 cents a contract, inside fee rounding.
Someone keeps that book consistent.

**What is left, and is being tested honestly: statistical edges against the
crowd on player props.**

1. *The shape of prop outcomes* (`PropShape`). Across every prop stat, outcomes
   fell below Rotowire's projected stat line 56-63% of the time, stable in every
   season 2018-2025. Rotowire's projection is Sleeper's, and the likely source
   many apps start from. The projections are close to unbiased means (mean
   outcome over mean projection 0.91-1.00), so this is skew: the median sits
   below the mean. A line placed at the projection is an under that wins well
   above the 52.4% break-even. Books know about skew; whether any venue still
   leans that way is the question.
2. *A calibrated prop model* (`PropModel`). Each stat's distribution for a man
   projected m comes from the 600 nearest projections at his position,
   2018-2025. Priced one season at a time from the other seasons, its largest
   miss in any cell is 2.0 points, and the pooled reliability runs 5.9 to 5.8%,
   25.8 to 26.0%, 54.6 to 54.9% and 84.2 to 84.0%. That is necessary before
   trading against anyone, but it is not an edge.
3. *The model against Kalshi* (`KalshiFair`, week 3, 4,601 contracts). The
   market prices YES richer than the model on receptions (+3 to +7 points
   around the projection), touchdowns (+3 to +4), receiving yards (+1 to +3)
   and passing TDs (+1 to +3). It prices them cheaper on rushing yards and
   interceptions. That is the retail lean toward overs Justin described, if
   the model is right. The largest single gaps (20-33 cents: Deshaun Watson's
   rushing, backup backs) are almost certainly news the market has and a
   Saturday projection does not.

**The test that decides it.** Every week the ledger of model and market prices
is committed before kickoff (data/kalshi-ledger/). `KalshiSettle` then scores it
under rules fixed before the first game: the model's Brier score against the
market midpoint, and the profit of taking one contract at the ask wherever the
edge after fees beat 3, 5 or 10 cents, clustered by player. One week is noise.
**Until several weeks show the model at least matching the market's Brier
score and the gaps paying beyond two standard errors, there is no model to bet
with, and this file will say so.**

## What the research established (verified against primary sources)

- **Historical prop odds** exist to buy only from May 2023 (The Odds API; about
  one month of its $119 plan covers 2023-2025). Game-line history is free
  (nflverse, from 1999 with prices from 2006). No free source of prop history
  exists.
- **Kalshi's and Polymarket's market data are public** with no account, prices
  and trade history included. Kalshi has NFL player props, ladders and
  escalators this season.
- **Pinnacle closed its public API on 23 July 2025.** The sharp reference line
  is no longer free to read.
- **FanDuel's and OddsPortal's terms forbid scraping.** Nothing here reads a
  sportsbook site.

**Where Justin can legally bet, living in Quebec** (verified 2026-09-26; 33 of 33
load-bearing claims confirmed against primary sources):

- **Kalshi: no.** Its member agreement lists Canada as a restricted jurisdiction
  and bars anyone domiciled or located there. Domicile counts, so living in
  Quebec excludes him even while he is in the US.
- **Polymarket: no.** Quebec is on its restricted list (added March 2026 after the
  AMF, Quebec's financial regulator, acted). The US site requires US residency.
- **Betfair: no.** It does not take Canadian residents.
- **Sporttrade and Novig:** only while physically inside an eligible US state.
  Whether their identity checks accept a Canadian home address is unverified.
  ProphetX's terms are unclear.
- **Quebec's only licensed sportsbook is Loto-Quebec's Mise-o-jeu,** with stakes
  of $1-100 a bet. It does not clearly offer NFL player props.
- **Offshore books are a grey area.** The Criminal Code targets operators, not
  players, but that is a gap in the law, not a right. Quebec's law requiring
  ISPs to block them was struck down in 2018.
- **Ontario-licensed books** (Pinnacle's Ontario site among them) take bets only
  while the bettor is physically in Ontario. **US books** take them only while
  he is physically in a legal state; Vermont and New York border Quebec.
- **Books limit winners, props hardest.** Sharp books cap NFL props at roughly
  $250-500; recreational books cut the limits of winning accounts
  (Massachusetts data: 0.64% of accounts limited, most to under a quarter of
  the default).

So **the venue where this model is being tested is closed to him.** Kalshi's
prices are still useful as a signal and as a scoring benchmark. Money would
have to be made at a book he can use while physically in Ontario or a US state,
at that book's prices, which this repo does not yet read (The Odds API, $30-119
a month, covers US and Canadian books; its prop history starts May 2023).

**Tax** (research, not advice; a cross-border tax professional should confirm):

- As a US citizen he owes US tax on gambling winnings wherever he bets.
- From 2026, only 90% of gambling losses are deductible, only up to winnings,
  and only if he itemizes (H.R. 10357 would restore 100% and passed committee
  on 2026-09-16, but is not law).
- Counted bet by bet, a high-volume, thin-edge bettor can owe tax on more than
  he made. Example: $1M in winning bets against $980k in losing bets is a $20k
  real profit taxed as about $118k. How sports bets group into "sessions" is
  unsettled.
- How event contracts (Kalshi-type) are taxed is unresolved: gambling, capital
  gain, section 1256 or ordinary income.
- Canada does not tax a recreational bettor's winnings. Organised, model-driven,
  systematic betting risks being treated as business income.

**The published evidence agrees with the measurements here.** NFL spreads are
efficient: Moskowitz (Journal of Finance, 2021) finds strategy returns flat to
the vig. The best-known published angle, unders on totals of 47.5 or more,
won 58.7% (1979-2000) and 59.7% (2001-2009), then lost at 47.1% (2010-2018)
once it was known. Kalshi's contracts show a favourite-longshot bias:
contracts at 10 cents or less lose over 60% after fees (Burgi, Deng and Whelan,
2026). There is no peer-reviewed study of NFL player-prop pricing; the
right-skew mechanism is the credible part.

## The honest verdict, as of 2026-09-26

**A money-making model for Justin, in the form asked, is not achievable now.**
Main lines are efficient; in-venue arbitrage is taken; and the venue with the
richest prop menu is closed to a Quebec resident. What is being built instead
is the one thing worth building first: a calibrated prop model and a
pre-registered, week-by-week record of it against a live market. Three things
would change the verdict. First, the record showing the model's gaps paying
beyond noise over many weeks. Second, the same gaps appearing at a book he
can legally use (from Ontario or a US state, which needs that book's prices).
Third, a tax treatment that does not tax gross wins (the 90% rule reversed, or
event contracts treated as capital).

## How to run it

```
./gradlew run -Pmain=MarketEfficiency        # the main-line market on its own prices
./gradlew run -Pmain=PropShape               # prop outcomes around the projection
./gradlew run -Pmain=PropModel               # the prop model's calibration
./gradlew run -Pmain=KalshiArb               # static arbitrage across Kalshi's contracts (live)
./gradlew run -Pmain=KalshiFair -Pweek=N     # model vs Kalshi + the ledger (before kickoff; commit it)
./gradlew run -Pmain=KalshiSettle -Pweeks=N  # score the ledgers after the games
```
