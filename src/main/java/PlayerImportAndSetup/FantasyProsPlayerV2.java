package PlayerImportAndSetup;

import com.mongodb.util.Hash;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;

public class FantasyProsPlayerV2 {
    private PlayerV2 playerV2;

    public String getFantasyProsId() {
        return fantasyProsId;
    }

    private String fantasyProsId;

    private Double rankAverage;

    private static final Logger logger = LogManager.getLogger(FantasyProsPlayerV2.class);


    public FantasyProsPlayerV2(String fantasyProsId, Double rankAverage, PlayerV2 playerV2){
        this.playerV2 = playerV2;
        this.fantasyProsId = fantasyProsId;
        this.rankAverage = rankAverage;
    }

    public PlayerV2 getPlayerV2(){
        return playerV2;
    }
    public static FantasyProsPlayerV2 playerFromFP(String fantasyProsID,
                                        String playerName,
                                        String playerShortName,
                                        String fantasyProsTeamName,
                                        String playerPositions,
                                        Double rankAverage) {
        PlayerV2 playerV2 = player2FromFantasyPros(playerName, playerShortName, fantasyProsTeamName, playerPositions);

        return new FantasyProsPlayerV2(fantasyProsID, rankAverage, playerV2);
    }

    private static PlayerV2 player2FromFantasyPros(String playerName, String playerShortName, String fantasyProsTeamName, String playerPositions) {
        String lastNameFromShortName = "";
        if(playerShortName.split(" ").length > 1){
            lastNameFromShortName= playerShortName.split(" ")[1];
        }
        else{
            logger.error("Can't get last name for the following player"
                    + " playerName:\t" + playerName
                    + " playerShortName:\t" + playerShortName);
        }
        String firstNameCalculated = playerName.split(lastNameFromShortName)[0].strip();
        TeamName teamFull = TeamName.shortTeamNameToFullTeamName(fantasyProsTeamName);
        HashSet<Position> positions = getPositionsFromPositionsString(playerPositions);
        PlayerV2 player = new PlayerV2(firstNameCalculated, lastNameFromShortName, teamFull, positions);
        return player;
    }

    public static HashSet<Position> getPositionsFromPositionsString(String positionsString){
        if(positionsString.length()>3){
            throw new RuntimeException("unrecognized position");
        }
        HashSet<Position> positions = new HashSet<>();
        if(positionsString.equals("DST")){
            positionsString = "DEF";
        }
        if(!Position.isStandardPosition(positionsString)){
            positionsString="OTHER";
        }
        positions.add(Position.valueOf(positionsString));
        return positions;
    }

    public Double getRankAverage() {
        return rankAverage;
    }
}
