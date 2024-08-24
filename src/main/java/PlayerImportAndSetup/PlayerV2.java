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
        this.customID = firstName + lastName + teamFull.name() + Position.getSubIdForPositions(positions);
    }

    private static HashMap<String, PlayerV2> fantasyProsIdToPlayerV2 = new HashMap<>();
    private static HashMap<String, PlayerV2> sleeperIdToPlayerV2 = new HashMap<>();











}
