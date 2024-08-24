package PlayerImportAndSetup;

import java.util.*;

public enum Position {
    QB,
    RB,
    WR,
    TE,
    DEF,
    OTHER;

    public static boolean isStandardPosition(String p){
        boolean isStandard = false;
        if(p.equals("QB") || p.equals("RB") || p.equals("WR") || p.equals("TE") || p.equals("DEF")){
            isStandard = true;
        }
        return isStandard;
    }

    public static List<Position> getCopy(List<Position> positions){
        List<Position> copy = new ArrayList<>();
        for(Position position : positions){
            copy.add(position);
        }
        return copy;
    }


    public static String getSubIdForPositions(HashSet<Position> positions){
        ArrayList<Position> positionsList =new ArrayList<>(); //Creation of ArrayList
        positionsList.addAll(positions);
        Collections.sort(positionsList);
        StringBuilder subId = new StringBuilder();
        for (Position position : positions) {
            subId.append(position.name());
        }
        return subId.toString();
    }






}
