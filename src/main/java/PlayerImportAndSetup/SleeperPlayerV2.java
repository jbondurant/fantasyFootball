package PlayerImportAndSetup;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.HashSet;

public class SleeperPlayerV2 {
    private PlayerV2 playerV2;
    private String sleeperId;

    private static final Logger logger = LogManager.getLogger(SleeperPlayerV2.class);


    public SleeperPlayerV2(String sleeperId, PlayerV2 playerV2){
        this.playerV2 = playerV2;
        this.sleeperId = sleeperId;
    }

    public PlayerV2 getPlayerV2(){
        return playerV2;
    }
    public static SleeperPlayerV2 playerFromSleeper(String sleeperId,
                                                   String firstName,
                                                   String lastName,
                                                   String teamName,
                                                   HashSet<Position> playerPositions) {
        PlayerV2 playerV2 = player2Sleeper(firstName, lastName, teamName, playerPositions);
        return new SleeperPlayerV2(sleeperId, playerV2);
    }

    private static PlayerV2 player2Sleeper(String firstName, String lastName, String teamName, HashSet<Position> playerPositions) {
        return new PlayerV2(firstName, lastName, TeamName.shortTeamNameToFullTeamName(teamName), playerPositions);
    }

    public String getSleeperId() {
        return sleeperId;
    }
}
