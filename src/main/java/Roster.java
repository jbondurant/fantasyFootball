import java.util.ArrayList;

public class Roster {

    ArrayList<Player> draftedPlayers;

    int numSlotsQB;
    int numSlotsRB;
    int numSlotsWR;
    int numSlotsTE;
    int numSlotsFLEX;
    int numSlotsDEF;
    int numSlotsBN;

    public Roster(){
        ArrayList<Player> dp = new ArrayList<Player>();
        draftedPlayers = dp;
    }

    public Roster(ArrayList<Player> dp){
        draftedPlayers = dp;
    }

    public void addPlayer(Player p){
        draftedPlayers.add(p);
    }

    public int numDraftedQB(){
        int numQB = 0;
        for(Player player : draftedPlayers){
            if(player.position.equals(Position.QB)){
                numQB++;
            }
        }
        return numQB;
    }
    public int numDraftedRB(){
        int numRB = 0;
        for(Player player : draftedPlayers){
            if(player.position.equals(Position.RB)){
                numRB++;
            }
        }
        return numRB;
    }
    public int numDraftedWR(){
        int numWR = 0;
        for(Player player : draftedPlayers){
            if(player.position.equals(Position.WR)){
                numWR++;
            }
        }
        return numWR;
    }
    public int numDraftedTE(){
        int numTE = 0;
        for(Player player : draftedPlayers){
            if(player.position.equals(Position.TE)){
                numTE++;
            }
        }
        return numTE;
    }
    public int numDraftedDEF(){
        int numDEF = 0;
        for(Player player : draftedPlayers){
            if(player.position.equals(Position.DEF)){
                numDEF++;
            }
        }
        return numDEF;
    }




    public int remainingStartingQB(){
        return Math.max(numSlotsQB - numDraftedQB(), 0);
    }
    public int remainingStartingRB(){
        return Math.max(numSlotsRB - numDraftedRB(), 0);
    }
    public int remainingStartingWR(){
        return Math.max(numSlotsWR - numDraftedWR(), 0);
    }
    public int remainingStartingTE(){
        return Math.max(numSlotsTE - numDraftedTE(), 0);
    }

    public int remainingStartingFLEX(){
        //int totalFlexSlots = numSlotsRB + numSlotsWR + numSlotsTE + numSlotsFLEX;
        //ugh complicated cause you can have someone pick 3 te
        int numFlexUsedByRB = Math.max(numDraftedRB() - numSlotsRB, 0);
        int numFlexUsedByWR = Math.max(numDraftedWR() - numSlotsWR, 0);
        int numFlexUsedByTE = Math.max(numDraftedTE() - numSlotsTE, 0);
        int numFlexUsedUp = numFlexUsedByRB + numFlexUsedByWR + numFlexUsedByTE;
        int numFlexAvailable = Math.max(numSlotsFLEX - numFlexUsedUp, 0);

        return numFlexAvailable;
    }

    public int remainingStartingDEF(){
        return Math.max(numSlotsDEF - numDraftedRB(), 0);
    }


}
