import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.*;
import java.util.*;

/**
 * BEFORE YOU SIT DOWN: is the tool pointed at tonight's draft?
 *
 * The draft id is read live from the league rather than hardcoded, so it
 * follows the league automatically - but nothing has ever CHECKED that what it
 * follows is the draft about to happen, in the state it should be in, with the
 * settings every model here assumes. A tool aimed at last year's draft, or at a
 * mock, would look completely normal and be completely wrong.
 *
 * Everything below is read from Sleeper at the moment you run it.
 *
 *   ./gradlew run -Pmain=PreFlight -Pkeepers=Tuten,Purdy -q
 */
public class PreFlight {
    public static void main(String[] args) throws Exception {
        // Assembled the ONE way the live path is assembled, so this cannot
        // check a different draft configuration than Draft2026 runs. Its first
        // version built its own and reported "9 live seats" - a complaint about
        // itself. See LiveSetup.
        LiveSetup setup = LiveSetup.forTonight();
        AAAConfiguration configuration = setup.configuration;
        JsonObject draft = configuration.getDraftJson();
        List<String> complaints = new ArrayList<>();

        System.out.printf("%nPRE-FLIGHT, read from Sleeper just now%n%n");
        System.out.printf("   league season      %s%n", configuration.getSeason());
        System.out.printf("   draft id           %s%n", configuration.getDraftID());

        String status = text(draft, "status");
        System.out.printf("   draft status       %s%n", status);
        if(!"pre_draft".equals(status) && !"drafting".equals(status)){
            complaints.add("the draft status is '" + status + "'. If it is"
                    + " 'complete' the tool is pointed at a FINISHED draft.");
        }

        JsonElement start = draft.get("start_time");
        if(start != null && !start.isJsonNull()){
            long millis = start.getAsLong();
            ZonedDateTime when = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault());
            System.out.printf("   starts             %s%n",
                    when.format(java.time.format.DateTimeFormatter
                            .ofPattern("yyyy-MM-dd HH:mm z")));
            long hours = Duration.between(ZonedDateTime.now(), when).toHours();
            System.out.printf("   that is            %s%n", hours >= 0
                    ? hours + "h from now" : (-hours) + "h ago");
            if(Math.abs(hours) > 36){
                complaints.add("the start time is " + Math.abs(hours)
                        + "h away, which is not tonight.");
            }
        }

        JsonObject settings = draft.getAsJsonObject("settings");
        if(settings != null){
            int teams = number(settings, "teams");
            int rounds = number(settings, "rounds");
            System.out.printf("   teams / rounds     %d / %d%n", teams, rounds);
            if(teams != 12){
                complaints.add("this draft has " + teams + " teams; every model"
                        + " here is built for 12.");
            }
            if(rounds != 16){
                complaints.add("this draft has " + rounds + " rounds; the pick"
                        + " schedule and the 16-man roster assume 16.");
            }
        }
        System.out.printf("   draft type         %s%n", text(draft, "type"));

        // Where he sits, and what he already owns.
        DraftSimulator.Slot first = null;
        DraftPlanner planner = setup.planner;
        DraftSimulator simulator = setup.simulator;
        List<Integer> seats = new ArrayList<>();
        for(int p = 1; p <= 200; p++){
            DraftSimulator.Slot slot = simulator.slotAt(p);
            if(slot != null && planner.me().equals(slot.manager()) && !slot.keeperSlot()){
                seats.add(p);
                if(first == null){
                    first = slot;
                }
            }
        }
        System.out.printf("   my first pick      %s%n",
                first == null ? "NONE FOUND" : "pick " + first.pickNumber()
                        + ", round " + first.round());
        System.out.printf("   my live picks      %d: %s%n", seats.size(), seats);
        if(seats.size() != 14){
            complaints.add("found " + seats.size() + " live seats; two keepers"
                    + " out of sixteen rounds should leave 14.");
        }

        System.out.printf("   my keepers         ");
        for(String id : planner.myKeeperIDs()){
            Player player = Player.getPlayerFromSIDV2(id);
            System.out.printf("%s ", player == null ? id
                    : player.firstName + " " + player.lastName);
        }
        System.out.println();
        System.out.printf("   keepers league-wide %d%n",
                LiveBoard.kept(configuration).size());
        if(LiveBoard.kept(configuration).size() != 24){
            complaints.add("found " + LiveBoard.kept(configuration).size()
                    + " keepers league-wide; twelve teams keeping two is 24.");
        }

        List<String> picksIn = LiveDraft.livePicks(configuration.getDraftID());
        System.out.printf("   picks already in   %d%n", picksIn.size());

        System.out.println();
        if(complaints.isEmpty()){
            System.out.printf("ALL CLEAR. Every assumption the models make holds"
                    + " for this draft.%n");
        }
        else {
            System.out.printf("*** %d THING%s TO LOOK AT BEFORE YOU START:%n",
                    complaints.size(), complaints.size() == 1 ? "" : "S");
            for(String complaint : complaints){
                System.out.printf("   *** %s%n", complaint);
            }
        }
    }

    private static String text(JsonObject object, String field){
        JsonElement element = object == null ? null : object.get(field);
        return element == null || element.isJsonNull() ? "(absent)" : element.getAsString();
    }

    private static int number(JsonObject object, String field){
        JsonElement element = object == null ? null : object.get(field);
        return element == null || element.isJsonNull() ? -1 : element.getAsInt();
    }
}
