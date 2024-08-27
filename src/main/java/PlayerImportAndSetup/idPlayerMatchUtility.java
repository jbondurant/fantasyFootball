package PlayerImportAndSetup;

import java.util.HashMap;
import java.util.HashSet;

public class idPlayerMatchUtility {

    public static HashSet<FantasyProsPlayerV2> fantasyProsPlayerV2s = FantasyProsPlayersV2.getFantasyProsPlayersV2();


    public static HashMap<String, String> sleeperIdToFantasyProsId =  new HashMap<>();
    public static HashMap<String, String> fantasyProsIdToSleeperId =  new HashMap<>();

    static{
        initializeMaps();
    }

    public static void initializeMaps(){
        HashMap<String, SleeperPlayerV2> customIdToSleeperPlayerV2 = SleeperPlayersV2.getSleeperPlayersV2AsMap();

        for(FantasyProsPlayerV2 fantasyProsPlayerV2 : fantasyProsPlayerV2s){
            String fantasyProsCustomID = fantasyProsPlayerV2.getPlayerV2().customID;
            String fantasyProsID = fantasyProsPlayerV2.getFantasyProsId();
            if(customIdToSleeperPlayerV2.containsKey(fantasyProsID)){
                String sleeperID = customIdToSleeperPlayerV2.get(fantasyProsCustomID).getSleeperId();
                sleeperIdToFantasyProsId.put(sleeperID, fantasyProsID);
                fantasyProsIdToSleeperId.put(fantasyProsID, sleeperID);
            }
        }
    }




}
