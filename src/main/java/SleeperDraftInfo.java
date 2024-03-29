import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Set;

public class SleeperDraftInfo {

    public static String myUserID = HumanOfInterest.humanID;

    public static String filepathStartSeriousDraft = "seriousDraftSleeper";
    public static String webURLSeriousDraft = "https://api.sleeper.app/v1/draft/980889732034994177"; //todo 2023

    public ArrayList<User> usersInfo;

    //todo these get___draft things are wrong and like return empty lists
    public SleeperDraftInfo(ArrayList<User> uInfo){
        usersInfo = uInfo;
    }

    public static SleeperDraftInfo getHardcodedDraft(String draftID){
        String filepathHardcodedDraft = "hardcodedDraft";
        String webURL = "https://api.sleeper.app/v1/draft/" + draftID;
        String draftWebsite = InOutUtilities.getTodaysWebPage(webURL , filepathHardcodedDraft);
        SleeperDraftInfo sdi = parseWebsite(draftWebsite);
        return sdi;
    }

    public static SleeperDraftInfo getSeriousDraft(){
        String seriousDraftWebsite = getTodaysWebPageSerious();
        SleeperDraftInfo seriousDraft = parseWebsite(seriousDraftWebsite);
        return seriousDraft;
    }
    private static String getTodaysWebPageSerious(){
        return InOutUtilities.getTodaysWebPage(webURLSeriousDraft, filepathStartSeriousDraft);
    }

    public static SleeperDraftInfo parseWebsite(String websiteData){

        JsonParser jp = new JsonParser();
        JsonElement jsonElementSDI= jp.parse(websiteData);
        JsonObject jsonObjectSDI = jsonElementSDI.getAsJsonObject();

        JsonObject draftOrder = jsonObjectSDI.getAsJsonObject("draft_order");


        Set<String> keySet = draftOrder.keySet();
        ArrayList<User> users = new ArrayList<User>();
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
        SleeperDraftInfo b = getSeriousDraft();
    }

}
