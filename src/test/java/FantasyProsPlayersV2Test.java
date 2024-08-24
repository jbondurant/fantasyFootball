import PlayerImportAndSetup.FantasyProsPlayerV2;
import PlayerImportAndSetup.FantasyProsPlayersV2;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class FantasyProsPlayersV2Test {

    @Test
    void testGetMapFantasyProsIDtoPlayerV2_hasAtLeast150Players() {
        String entireHTML = FantasyProsPlayersV2.getTodaysWebPage();
        HashSet<FantasyProsPlayerV2> playersV2 = FantasyProsPlayersV2.intializeAllPlayers(entireHTML);
        Assertions.assertTrue(playersV2.size() > 150);

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

}