import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Scanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class FantasyProsADP {


    public static String filepathStart = "fantasyProsADP";
    public static String webURL = "https://www.fantasypros.com/nfl/rankings/half-point-ppr-cheatsheets.php";


    private static final ArrayList<Rank> rankingFPADP;

    static{
        rankingFPADP = parseTodaysWebPage();
    }

    public static ArrayList<Rank> getRankingFPECR(){
        return rankingFPADP;
    }

    private static String getTodaysWebPage(){
        return InOutUtilities.getTodaysWebPage(webURL, filepathStart);
    }


    private static ArrayList<Rank> parseTodaysWebPage(){
        String entireHTML = getTodaysWebPage();
        String ecrDataStart = entireHTML.split("var ecrData = ")[1].split("\"players\":")[1];
        String ecrData = ecrDataStart.split("var sosData")[0].split(",\"experts_available\":")[0];

        JsonParser jp = new JsonParser();
        JsonElement jsonElement = jp.parse(ecrData);
        JsonArray jsonPlayers = jsonElement.getAsJsonArray();

        ArrayList<Rank> todaysRankings = new ArrayList<Rank>();
        for (JsonElement jsonPlayer : jsonPlayers) {
            JsonObject apiObject = jsonPlayer.getAsJsonObject();

            String sportRadarID = "";
            if(!apiObject.get("sportsdata_id").isJsonNull()) {
                sportRadarID = apiObject.get("sportsdata_id").getAsString();
            }

            int ecr = apiObject.get("rank_ecr").getAsInt();
            Player player = Player.getPlayer(sportRadarID);
            Rank rank = new Rank(ecr, player);
            todaysRankings.add(rank);
        }
        return todaysRankings;
    }


    /*
    private static void downloadTodaysWebPage() throws FileNotFoundException {
        InOutUtilities.downloadTodaysWebPage(webURL, filepathStart);
    }*/

    public static void main(String[] args){
        ArrayList<Rank> xyz = parseTodaysWebPage();
        int eee = 1;
    }



}




