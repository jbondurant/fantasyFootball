import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks the keeper costs the commissioner entered against what the rules say.
 *
 * Sleeper has no notion of what a keeper ought to cost - the league carries
 * max_keepers and a deadline and nothing else - so somebody places each keeper
 * onto a round of the draft board by hand. Six seasons of history contain at
 * least three entries that do not follow the ruleset, including Joe Burrow's
 * escalation being missed in 2023 and made up in 2024, so this is worth a look
 * while the board can still be changed.
 *
 * Run it once the keepers are on the board and before the draft:
 *
 *     ./gradlew run -Pmain=KeeperAudit
 */
public class KeeperAudit {

    public static class Finding {
        public final String owner;
        public final String player;
        public final int enteredRound;
        public final int ruleRound;

        Finding(String owner, String player, int enteredRound, int ruleRound){
            this.owner = owner;
            this.player = player;
            this.enteredRound = enteredRound;
            this.ruleRound = ruleRound;
        }

        @Override
        public String toString(){
            String direction = enteredRound > ruleRound ? "cheaper than" : "dearer than";
            return String.format("%-14s %-24s board says round %-2d, rules say round %-2d  (%s the rules)",
                    owner, player, enteredRound, ruleRound, direction);
        }
    }

    public static class Report {
        public final List<Finding> disagreements = new ArrayList<>();
        public final List<String> notOnTheBoard = new ArrayList<>();
        public int agreed;

        public boolean boardIsEmpty(){
            return agreed == 0 && disagreements.isEmpty();
        }
    }

    public static Report audit(AAAConfiguration configuration){
        Report report = new Report();

        Map<String, Integer> entered = enteredKeeperRounds(configuration.getTodaysDraftPicks());
        for(Keeper keeper : configuration.getTodaysKeepers()){
            String owner = HumanOfInterest.getHumanFromID(keeper.humanWhoCanKeep);
            String player = keeper.player.firstName + " " + keeper.player.lastName;
            Integer onBoard = entered.get(keeper.player.sleeperIDString);

            if(onBoard == null){
                report.notOnTheBoard.add(owner + "  " + player);
            }
            else if(onBoard == keeper.roundCanBeKept){
                report.agreed++;
            }
            else {
                report.disagreements.add(new Finding(owner, player, onBoard, keeper.roundCanBeKept));
            }
        }
        return report;
    }

    /** Player id -> the round the commissioner placed them in, for keeper picks only. */
    public static Map<String, Integer> enteredKeeperRounds(String draftPicksJson){
        Map<String, Integer> rounds = new HashMap<>();
        JsonArray picks = JsonParser.parseString(draftPicksJson).getAsJsonArray();
        for(JsonElement pickElement : picks){
            JsonObject pick = pickElement.getAsJsonObject();
            JsonElement isKeeper = pick.get("is_keeper");
            if(isKeeper == null || isKeeper.isJsonNull() || !isKeeper.getAsBoolean()){
                continue;
            }
            rounds.put(pick.get("player_id").getAsString(), pick.get("round").getAsInt());
        }
        return rounds;
    }

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        Report report = audit(configuration);

        System.out.println("Keeper audit for the " + configuration.getSeason() + " draft\n");

        if(report.boardIsEmpty()){
            System.out.println("No keepers have been placed on the draft board yet, so there is");
            System.out.println("nothing to check. Run this again once the commissioner has set them,");
            System.out.println("while there is still time to correct anything.\n");
        }
        else {
            System.out.println("matching the rules:\t" + report.agreed);
            System.out.println("disagreeing:\t\t" + report.disagreements.size());
            for(Finding finding : report.disagreements){
                System.out.println("   " + finding);
            }
        }

        if(!report.notOnTheBoard.isEmpty()){
            System.out.println("\ndeclared but not yet placed on the board:");
            for(String pending : report.notOnTheBoard){
                System.out.println("   " + pending);
            }
        }

        System.out.println("\nWhat the rules make these cost:");
        for(Keeper keeper : configuration.getTodaysKeepers()){
            System.out.printf("   %-14s %-24s round %d%n",
                    HumanOfInterest.getHumanFromID(keeper.humanWhoCanKeep),
                    keeper.player.firstName + " " + keeper.player.lastName,
                    keeper.roundCanBeKept);
        }
    }

}
