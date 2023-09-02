import java.util.ArrayList;

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

    public static ArrayList<Position> getCopy(ArrayList<Position> positions){
        ArrayList<Position> copy = new ArrayList<>();
        for(Position position : positions){
            copy.add(position);
        }
        return copy;
    }



}
