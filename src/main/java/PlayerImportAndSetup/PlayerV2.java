package PlayerImportAndSetup;

import java.util.HashMap;
import java.util.HashSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class PlayerV2 {

    public String customID;
    public String firstName;
    public String lastName;
    public TeamName teamFull;
    public HashSet<Position> positions;

    private static final Logger logger = LogManager.getLogger(PlayerV2.class);
    public PlayerV2(String firstName,
                     String lastName,
                     TeamName teamFull,
                     HashSet<Position> positions){
        this.firstName = firstName;
        this.lastName = lastName;
        this.teamFull = teamFull;
        this.positions = positions;
        String customID = firstName.replaceAll("[^a-zA-Z]", "").toLowerCase()
                + lastName.replaceAll("[^a-zA-Z]", "").toLowerCase()
                + teamFull.name()
                + Position.getSubIdForPositions(positions);
        customID = correctedCustomIDForDefense(teamFull, positions, customID);
        customID = correctedCustomID(customID);
        this.customID = customID;
    }

    public String correctedCustomIDForDefense(TeamName teamName, HashSet<Position> positions, String customID){
        if(positions.size()==1 && positions.contains(Position.DEF)){
            return teamName.toString() + Position.DEF.toString();
        }
        return customID;
    }
    public String correctedCustomID(String customID){
        if(customID.equals("amonrastDETROITWR")){
            return "amonrastbrownDETROITWR";
        }
        if(customID.equals("taysomhillNEW_ORLEANSTE")){
            return "taysomhillNEW_ORLEANSQBTE";
        }
        else return customID;
    }

    public String getCustomID() {
        return customID;
    }

    private static HashMap<String, PlayerV2> fantasyProsIdToPlayerV2 = new HashMap<>();
    private static HashMap<String, PlayerV2> sleeperIdToPlayerV2 = new HashMap<>();











}
