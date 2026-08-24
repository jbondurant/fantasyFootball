import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Set;

public class SleeperDraftInfo {

    public static String filepathStartSeriousDraft = "seriousDraftSleeper";

    public ArrayList<User> usersInfo;

    //todo these get___draft things are wrong and like return empty lists
    public SleeperDraftInfo(ArrayList<User> uInfo){
        usersInfo = uInfo;
    }

    public static SleeperDraftInfo getDraft(String draftID){
        String draftWebsite = InOutUtilities.getTodaysWebPage(
                AAAConfiguration.draftWebURL(draftID), "draft" + draftID);
        return parseWebsite(draftWebsite);
    }

    /** This season's draft for the configured league. */
    public static SleeperDraftInfo getSeriousDraft(){
        String seriousDraftWebsite = getTodaysWebPageSerious();
        SleeperDraftInfo seriousDraft = parseWebsite(seriousDraftWebsite);
        return seriousDraft;
    }
    private static String getTodaysWebPageSerious(){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        String draftID = configuration.getDraftID();
        return InOutUtilities.getTodaysWebPage(AAAConfiguration.draftWebURL(draftID),
                filepathStartSeriousDraft + draftID);
    }

    public static SleeperDraftInfo parseWebsite(String websiteData){
        JsonElement jsonElementSDI= JsonParser.parseString(websiteData);
        JsonObject jsonObjectSDI = jsonElementSDI.getAsJsonObject();

        JsonObject draftOrder = jsonObjectSDI.getAsJsonObject("draft_order");

        ArrayList<User> users = new ArrayList<User>();
        if(draftOrder == null){
            // Sleeper leaves draft_order null until the commissioner sets it.
            return new SleeperDraftInfo(users);
        }
        Set<String> keySet = draftOrder.keySet();
        for(String key : keySet){
            String userID = key;
            int userDraftPosition = draftOrder.get(key).getAsInt();
            User user = new User(userID, userDraftPosition);
            users.add(user);
        }

        SleeperDraftInfo sleeperDraft = new SleeperDraftInfo(users);
        return sleeperDraft;
    }

    public static void main(String[] args){
        SleeperDraftInfo seriousDraft = getSeriousDraft();
        for(User user : seriousDraft.usersInfo){
            System.out.println("pick " + user.draftPosition + "\t" + HumanOfInterest.getHumanFromID(user.userID));
        }
    }

}
