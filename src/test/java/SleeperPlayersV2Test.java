import PlayerImportAndSetup.SleeperPlayerV2;
import PlayerImportAndSetup.SleeperPlayersV2;
import PlayerImportAndSetup.TeamName;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashSet;


import static org.junit.jupiter.api.Assertions.*;

class SleeperPlayersV2Test {

    @Test
    void intializeAllPlayersTest() {
        HashSet<SleeperPlayerV2> sleeperPlayersV2 = SleeperPlayersV2.intializeAllPlayers();
        Assertions.assertTrue(sleeperPlayersV2.size() > 10000);
    }

    @Test
    void containsAllTeams(){
        HashSet<SleeperPlayerV2> sleeperPlayersV2 = SleeperPlayersV2.intializeAllPlayers();
        HashSet<TeamName> teams = new HashSet<>();
        for(SleeperPlayerV2 sleeperPlayerV2 : sleeperPlayersV2){
            teams.add(sleeperPlayerV2.getPlayerV2().teamFull);
        }
        Assertions.assertTrue(teams.size() == 33);
    }



}