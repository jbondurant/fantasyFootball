import java.util.ArrayList;
import java.util.List;

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



}
