import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Set;

public class SleeperDraftInfo {

    public static String myUserID = HumanOfInterest.humanID;

    public static String filepathStartFunDraft = "funDraftSleeper";
    public static String webURLFunDraft = "https://api.sleeper.app/v1/draft/707299245186691073";

    public static String filepathStartSeriousDraft = "seriousDraftSleeper";
    public static String webURLSeriousDraft = "https://api.sleeper.app/v1/draft/725192044087148544";

    public ArrayList<User> usersInfo;

    public SleeperDraftInfo(ArrayList<User> uInfo){
        usersInfo = uInfo;
    }

    public static SleeperDraftInfo getFunDraft(){
        String funDraftWebsite = getTodaysWebPageFun();
        SleeperDraftInfo funDraft = parseWebsite(funDraftWebsite);
        return funDraft;
    }
    private static String getTodaysWebPageFun(){
        return InOutUtilities.getTodaysWebPage(webURLFunDraft, filepathStartFunDraft);
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
        SleeperDraftInfo a = getFunDraft();
        SleeperDraftInfo b = getSeriousDraft();
    }

}
