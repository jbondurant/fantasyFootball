import PlayerImportAndSetup.Position;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks the live feeds still look the way the code expects.
 *
 * These are the assumptions that quietly rotted between 2024 and 2026: a field
 * disappeared from a FantasyPros page, a league id went stale, a projections
 * endpoint changed shape. None of it failed loudly - the numbers just went
 * wrong. Run with `./gradlew smokeTest`.
 */
@Tag("smoke")
class LeagueSmokeTest {

    private static final AAAConfiguration CONFIG = AAAConfiguration.getInstance();

    @Test
    void theConfiguredLeagueIsThisSeason(){
        String season = CONFIG.getSeason();
        Assertions.assertNotNull(season, "the league did not report a season");

        int configured = Integer.parseInt(season);
        int thisYear = java.time.LocalDate.now().getYear();
        Assertions.assertTrue(configured == thisYear || configured == thisYear - 1,
                "AAAConfigurationSleeperLeague points at the " + configured + " league, but it is "
                        + thisYear + " - sleeper rolls the league into a new id every August, so this "
                        + "wants updating from the current league's previous_league_id chain");
    }

    @Test
    void theLeagueLooksLikeTheLeague(){
        Assertions.assertNotNull(CONFIG.getDraftID(), "no draft on the league");
        Assertions.assertEquals(12, CONFIG.getUserIDToDisplayName().size(), "expected 12 managers");
        Assertions.assertNotNull(CONFIG.getPreviousLeagueID(), "no previous season to price keepers from");
        Assertions.assertNotNull(CONFIG.getPreviousDraftID(), "the previous season has no draft");
    }

    @Test
    void myUserResolvesAndIsInTheLeague(){
        String myID = CONFIG.getMyID();
        Assertions.assertNotNull(myID);
        Assertions.assertTrue(CONFIG.getUserIDToDisplayName().containsKey(myID),
                "the configured username is not a manager in this league");
    }

    @Test
    void everyRosterIsAccountedFor(){
        JsonArray rosters = JsonParser.parseString(CONFIG.getTodaysRosterWebPageSerious()).getAsJsonArray();
        Assertions.assertEquals(12, rosters.size());
    }

    @Test
    void lastSeasonsDraftIsCompleteEnoughToPriceKeepers(){
        JsonArray picks = JsonParser.parseString(CONFIG.getPreviousSeasonDraftPicks()).getAsJsonArray();
        Map<String, Integer> rounds = KeeperPricing.roundsByPlayerID(picks);

        Assertions.assertTrue(rounds.size() > 150,
                "only " + rounds.size() + " picks in last season's draft, expected a full 16 rounds");
    }

    @Test
    void whateverKeepersHaveBeenDeclaredPriceCleanly(){
        // Declarations trickle in, so the count is not fixed. What matters is
        // that each one resolves to a real player at a price the rules allow.
        KeeperPricing.PricedKeepers priced = CONFIG.priceTodaysKeepers();

        System.out.println("keepers declared so far: " + priced.keepers.size());
        for(Keeper keeper : priced.keepers){
            Assertions.assertNotNull(keeper.player, "a keeper resolved to no player");
            Assertions.assertNotNull(keeper.humanWhoCanKeep, "a keeper belongs to nobody");
            Assertions.assertTrue(keeper.roundCanBeKept >= 1 && keeper.roundCanBeKept <= Keeper.MAX_ROUND_COST,
                    keeper.player.lastName + " priced at round " + keeper.roundCanBeKept);
            System.out.println("  " + HumanOfInterest.getHumanFromID(keeper.humanWhoCanKeep)
                    + "\t" + keeper.player.firstName + " " + keeper.player.lastName
                    + "\tround " + keeper.roundCanBeKept);
        }
        for(String rejection : priced.rejected){
            System.out.println("  not a legal keeper: " + rejection);
        }
        Assertions.assertTrue(priced.keepers.size() <= 24, "12 teams keeping at most 2 each");
    }

    @Test
    void nobodyIsKeepingAPlayerTheRulesDoNotAllow(){
        // A declared keeper the rules reject is worth knowing about before the
        // draft, not during it.
        KeeperPricing.PricedKeepers priced = CONFIG.priceTodaysKeepers();
        Assertions.assertTrue(priced.rejected.isEmpty(),
                "declared keepers that the ruleset does not permit: " + priced.rejected);
    }

    @Test
    void theHistoryGoesBackFarEnoughToPriceConsecutiveKeepers(){
        // A player held three years running needs three seasons of drafts
        // behind them to be priced and to hit the limit.
        List<com.google.gson.JsonArray> history = CONFIG.getPreviousDraftPicks();
        Assertions.assertTrue(history.size() >= KeeperPricing.MAX_CONSECUTIVE_YEARS,
                "only " + history.size() + " previous drafts reachable through previous_league_id");
        for(com.google.gson.JsonArray draft : history){
            Assertions.assertTrue(draft.size() > 0, "a previous draft came back empty");
        }
    }

    @Test
    void sleeperStillProjectsTheStatsTheScoringNeeds(){
        HashMap<String, Double> scores = SleeperProjections.parseTodaysWebPage();

        Assertions.assertTrue(scores.size() > 400,
                "only " + scores.size() + " players projected - the projections endpoint may have changed");

        long scoringPlayers = scores.values().stream().filter(score -> score > 50.0).count();
        Assertions.assertTrue(scoringPlayers > 150,
                "only " + scoringPlayers + " players projected above 50 points, which is not a real season");
    }

    @Test
    void aStartingQuarterbackOutscoresABackupRunningBack(){
        // A blunt sanity check on the whole projection pipeline: if the joins
        // or the scoring break, this ordering is the first thing to go.
        HashMap<String, Double> scores = SleeperProjections.parseTodaysWebPage();
        double best = scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);

        Assertions.assertTrue(best > 250.0 && best < 600.0,
                "the best projected season came out at " + best + ", which is not a plausible total");
    }

    @Test
    void fantasyProsRankingsStillMatchUpToSleeperPlayers(){
        List<FantasyProsEcrData.Entry> entries = FantasyProsEcrData.parse(
                InOutUtilities.getTodaysWebPage(FantasyProsADP.webURL, FantasyProsADP.filepathStart));

        int matched = 0;
        List<String> unmatchedTopHundred = new ArrayList<>();
        for(FantasyProsEcrData.Entry entry : entries){
            if(entry.resolvePlayer() != null){
                matched++;
            }
            else if(entry.rankEcr <= 100){
                unmatchedTopHundred.add(entry.rankEcr + " " + entry.playerName + " " + entry.position);
            }
        }

        System.out.println("FantasyProsrankings matched: " + matched + "/" + entries.size());
        Assertions.assertTrue(unmatchedTopHundred.isEmpty(),
                "top-100 players that did not match a sleeper player: " + unmatchedTopHundred);

        double matchRate = matched / (double) entries.size();
        Assertions.assertTrue(matchRate > 0.90,
                "only " + Math.round(matchRate * 100) + "% of ranked players matched; the name join may have broken");
    }

    @Test
    void everyRosteredPlayerInTheLeagueResolves(){
        JsonArray rosters = JsonParser.parseString(CONFIG.getTodaysRosterWebPageSerious()).getAsJsonArray();

        List<String> unresolved = new ArrayList<>();
        int total = 0;
        for(com.google.gson.JsonElement rosterElement : rosters){
            com.google.gson.JsonElement players = rosterElement.getAsJsonObject().get("players");
            if(players == null || players.isJsonNull()){
                continue;
            }
            for(com.google.gson.JsonElement playerElement : players.getAsJsonArray()){
                total++;
                String sleeperID = playerElement.getAsString();
                if(Player.getPlayerFromSIDV2(sleeperID) == null){
                    unresolved.add(sleeperID);
                }
            }
        }

        Assertions.assertTrue(total > 100, "expected a rostered player count in the hundreds, got " + total);
        Assertions.assertTrue(unresolved.isEmpty(),
                "rostered players that resolved to nothing, so they score zero: " + unresolved);
    }

    @Test
    void everyRosteredPlayerHasAProjection(){
        HashMap<String, Double> scores = SleeperProjections.parseTodaysWebPage();
        JsonArray rosters = JsonParser.parseString(CONFIG.getTodaysRosterWebPageSerious()).getAsJsonArray();

        List<String> unprojected = new ArrayList<>();
        for(com.google.gson.JsonElement rosterElement : rosters){
            com.google.gson.JsonElement players = rosterElement.getAsJsonObject().get("players");
            if(players == null || players.isJsonNull()){
                continue;
            }
            for(com.google.gson.JsonElement playerElement : players.getAsJsonArray()){
                String sleeperID = playerElement.getAsString();
                Player player = Player.getPlayerFromSIDV2(sleeperID);
                if(player == null || scores.containsKey(sleeperID)){
                    continue;
                }
                if(player.position.equals(Position.QB) || player.position.equals(Position.RB)
                        || player.position.equals(Position.WR) || player.position.equals(Position.TE)
                        || player.position.equals(Position.DEF)){
                    unprojected.add(player.firstName + " " + player.lastName + " (" + player.position + ")");
                }
            }
        }

        // Some are genuinely unprojected - rookies, the long-term injured - so
        // this is a ceiling, not a demand for zero.
        System.out.println("rostered players with no projection: " + unprojected);
        Assertions.assertTrue(unprojected.size() < 25,
                unprojected.size() + " rostered players have no projection: " + unprojected);
    }

    @Test
    void thereIsOnlyOneScoringPathAndEverythingAgreesWithIt(){
        // Trades and the draft simulator used to score through two separate
        // implementations of the same arithmetic. They agreed until a category
        // was added to one and not the other, at which point nothing failed and
        // the two simply disagreed. Now both come from here.
        LeagueScoringSettings settings =
                SleeperLeague.getSeriousLeague().league.leagueScoringSettings;

        Map<String, Double> byID = SleeperProjections.parseTodaysWebPage();
        List<Score> asList = SleeperProjections.getScoreList(settings);

        Assertions.assertFalse(asList.isEmpty());
        for(Score score : asList){
            Double fromMap = byID.get(score.player.sleeperIDString);
            Assertions.assertNotNull(fromMap, score.player.lastName + " is scored in one path and not the other");
            Assertions.assertEquals(fromMap, score.score, 0.0001,
                    score.player.firstName + " " + score.player.lastName + " scores differently in the two paths");
        }
    }

    @Test
    void theLeagueScoresTwoPointConversions(){
        // Not universal across leagues, so worth noticing if it ever changes.
        LeagueScoringSettings settings =
                SleeperLeague.getSeriousLeague().league.leagueScoringSettings;

        Assertions.assertEquals(2.0, settings.passTwoPoint, 0.0001);
        Assertions.assertEquals(2.0, settings.rushTwoPoint, 0.0001);
        Assertions.assertEquals(2.0, settings.receivingTwoPoint, 0.0001);
        Assertions.assertEquals(6.0, settings.passTD, 0.0001, "six point passing touchdowns");
        Assertions.assertEquals(0.5, settings.reception, 0.0001, "half ppr");
    }

    @Test
    void theWholeTradeFinderPipelineProducesScoredRosters(){
        ArrayList<ScoredRoster> rosters =
                TradeFinder.getProjPointsRosters(CONFIG, ProjectionSource.SLEEPER);

        Assertions.assertEquals(12, rosters.size());
        for(ScoredRoster roster : rosters){
            double best = roster.scoreBestROSStartingLineup();
            Assertions.assertTrue(best > 500.0,
                    HumanOfInterest.getHumanFromID(roster.userID) + " scored " + best
                            + ", which is too low for a full roster - a join is probably dropping players");
        }
    }
}
