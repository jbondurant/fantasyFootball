import PlayerImportAndSetup.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class FantasyProsPlayersV2Test {

    @Test
    void testGetMapFantasyProsIDtoPlayerV2_hasAtLeast150Players() {
        String entireHTML = FantasyProsPlayersV2.getTodaysWebPage();
        HashSet<FantasyProsPlayerV2> playersV2 = FantasyProsPlayersV2.intializeAllPlayers(entireHTML);
        // FantasyPros publishes a shade under 900 ranked players; this only
        // passed at 1500 because the loader used to double-count.
        Assertions.assertTrue(playersV2.size() > 500, "only got " + playersV2.size());
    }
    @Test
    void testGetMapFantasyProsIDtoPlayerV2_teamNamesMapCorrectly() {
        String entireHTML = FantasyProsPlayersV2.getTodaysWebPage();
        HashSet<FantasyProsPlayerV2> playersV2 = FantasyProsPlayersV2.intializeAllPlayers(entireHTML);
        HashSet<String> allTeams = new HashSet<>();
        for(FantasyProsPlayerV2 p : playersV2){
            allTeams.add(p.getPlayerV2().teamFull.name());
        }
        Assertions.assertTrue(allTeams.size() == 33);
    }

    @Test
    void everyFantasyProsPlayerCanBeMappedToSleepersPlayer() throws Exception {
        String entireHTML = FantasyProsPlayersV2.getTodaysWebPage();
        HashSet<FantasyProsPlayerV2> fantasyProsPlayersV2 = FantasyProsPlayersV2.intializeAllPlayers(entireHTML);
        HashSet<SleeperPlayerV2> sleeperPlayersV2 = SleeperPlayersV2.intializeAllPlayers();
        HashSet<String> sleeperIds = (HashSet<String>) sleeperPlayersV2.stream()
                .map(sleeperPlayer -> sleeperPlayer.getPlayerV2().getCustomID())
                .collect(Collectors.toSet());
        int unmatchedFantasyProsIDsUnder200rank = 0;
        for(FantasyProsPlayerV2 fantasyProsPlayerV2 : fantasyProsPlayersV2){
            String fantasyProsId = fantasyProsPlayerV2.getPlayerV2().customID;
            if(!sleeperIds.contains(fantasyProsId)){
                if(fantasyProsPlayerV2.getRankAverage() < 200.0) {
                    unmatchedFantasyProsIDsUnder200rank++;
                    System.out.println(fantasyProsPlayerV2.getRankAverage() + "\t" + fantasyProsId);
                }

                for(String sleeperId : sleeperIds){
                    if (sleeperId.contains("taysom")
                        && sleeperId.contains("hill")){
                            //&& sleeperId.contains("OTHER")){
                        int x = 1;
                        if (fantasyProsId.contains("taysom")
                                && fantasyProsId.contains("hill")){
                            int y = 1;
                        }
                    }

                }
            }

        }
        Assertions.assertTrue(unmatchedFantasyProsIDsUnder200rank < 1);

    }

}