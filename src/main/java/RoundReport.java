import java.util.ArrayList;

public class RoundReport {

    byte[] partialArrangement;
    byte[] tiers;
    double endScore;
    String idForDB;

    public RoundReport(ArrayList<Position> pa, int[] t){
        partialArrangement = encodePartialArrangement(pa);
        tiers = encodeTiers(t);
        idForDB = encodeID(partialArrangement, tiers);
    }

    public static String encodeID(byte[] pa, byte[] t){
        //order important!!
        //tiers before partialArrangements!
        String idString = "";
        for(int i=0; i<t.length; i++){
            idString += t[i];
        }
        for(int i=0; i<pa.length; i++){
            idString += pa[i];
        }
        return idString;
    }

    public void setEndScore(double es){
        endScore = es;
    }

    //todo later decode stuff for db perhaps
    public static byte[] encodePartialArrangement(ArrayList<Position> pa){
        //QB = 1, RB = 2, WR = 3, TE = 4, DEF = 5
        byte[] partialArrangement = new byte[pa.size()];
        for(int i=0; i<pa.size(); i++){
            Position pos = pa.get(i);
            if(pos.equals(Position.QB)){
                partialArrangement[i] = 1;
            }
            else if(pos.equals(Position.RB)){
                partialArrangement[i] = 2;
            }
            else if(pos.equals(Position.WR)){
                partialArrangement[i] = 3;
            }
            else if(pos.equals(Position.TE)){
                partialArrangement[i] = 4;
            }
            else if(pos.equals(Position.DEF)){
                partialArrangement[i] = 5;
            }
        }
        return partialArrangement;
    }

    public static byte[] encodeTiers(int[] t){
        byte[] tiers = new byte[t.length];
        for(int i=0; i<t.length; i++){
            tiers[i] = (byte) t[i];
        }
        return tiers;
    }


    public static String encodeStringPartialArrangement(ArrayList<Position> pa){
        //QB = 1, RB = 2, WR = 3, TE = 4, DEF = 5
        String partialArrangement = "";
        for(int i=0; i<pa.size(); i++){
            Position pos = pa.get(i);
            if(pos.equals(Position.QB)){
                partialArrangement += "1";
            }
            else if(pos.equals(Position.RB)){
                partialArrangement += "2";
            }
            else if(pos.equals(Position.WR)){
                partialArrangement += "3";
            }
            else if(pos.equals(Position.TE)){
                partialArrangement += "4";
            }
            else if(pos.equals(Position.DEF)){
                partialArrangement += "5";
            }
        }
        return partialArrangement;
    }
}
