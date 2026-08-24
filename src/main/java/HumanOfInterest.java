import java.util.ArrayList;
import java.util.Map;

/**
 * The humans in the league. The roster of names used to be typed in by hand and
 * drifted every time somebody joined, left or renamed themselves; it now comes
 * from /league/{id}/users.
 */
public class HumanOfInterest {

    /** My own sleeper user id, resolved from the configured username. */
    public static String humanID(){
        return AAAConfiguration.getInstance().getMyID();
    }

    public static ArrayList<String> getAllUserIDs(){
        return new ArrayList<>(AAAConfiguration.getInstance().getUserIDToDisplayName().keySet());
    }

    public static String getHumanFromID(String userID){
        Map<String, String> names = AAAConfiguration.getInstance().getUserIDToDisplayName();
        String displayName = names.get(userID);
        if(displayName == null){
            return "user not found";
        }
        return displayName;
    }

    public static void main(String[] args){
        for(String userID : getAllUserIDs()){
            System.out.println(userID + "\t" + getHumanFromID(userID));
        }
        System.out.println("me:\t" + humanID());
    }

}
