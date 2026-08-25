import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every projection source Justin might draw numbers from, registered as a
 * named slot. Two are automatic (Sleeper is the default feed; Boris Chen's
 * tiers fetch free and map onto the points curve); the paywalled ones become
 * live the moment a subscriber CSV export lands in
 * data/external-projections/<name>.csv - points sheets get the scoring
 * bridge, props/stat sheets score directly (see ProjectionBridge for both
 * formats). Accuracy comparisons come later, once seasons of archived feeds
 * exist; for now every source is simply selectable:
 *
 *     ./gradlew run -Pmain=KeeperPlan -Pprojections=etr
 *     ./gradlew run -Pmain=DraftPlanner -Pprojections=blend:sleeper,borischen,etr
 *
 *     ./gradlew run -Pmain=ProjectionSources     # slot status + diffs
 */
public class ProjectionSources {

    record Slot(String name, String what, String how){}

    static final List<Slot> SLOTS = List.of(
            new Slot("sleeper", "Rotowire stat lines via Sleeper (the default)", "automatic"),
            new Slot("borischen", "Boris Chen tiers mapped onto the points curve", "automatic"),
            new Slot("etr", "Establish The Run projections", "subscriber CSV export"),
            new Slot("fantasypoints", "Fantasy Points projections", "subscriber CSV export"),
            new Slot("pff", "Pro Football Focus projections", "subscriber CSV export"),
            new Slot("draftsharks", "Draft Sharks projections", "subscriber CSV export"),
            new Slot("4for4", "4for4 projections", "subscriber CSV export"),
            new Slot("footballguys", "Footballguys projections", "subscriber CSV export"),
            new Slot("ftn", "FTN Fantasy projections", "subscriber CSV export"),
            new Slot("rotoviz", "RotoViz projections", "subscriber CSV export"),
            new Slot("unexpectedpoints", "Unexpected Points (Substack) numbers", "hand-keyed CSV"),
            new Slot("fantasyomatic", "FantasyOmatic projections", "subscriber CSV export"),
            new Slot("actionnetwork", "The Action Network projections", "subscriber CSV export"),
            new Slot("numberfire", "NumberFire/FanDuel Research projections", "hand-keyed CSV (page is app-rendered)"),
            new Slot("rotogrinders", "RotoGrinders (DFS-oriented) numbers", "hand-keyed CSV"),
            new Slot("props", "Sportsbook season props as stat counts", "hand-keyed props CSV"));

    /** The planner's feed resolver, blends included. */
    public static Map<String, Double> resolve(String source){
        if(source != null && source.startsWith("blend:")){
            List<Map<String, Double>> feeds = new ArrayList<>();
            for(String part : source.substring(6).split(",")){
                feeds.add(resolve(part.trim()));
            }
            Map<String, Double> blended = new LinkedHashMap<>();
            for(String sleeperID : feeds.get(0).keySet()){
                double total = 0;
                int counted = 0;
                for(Map<String, Double> feed : feeds){
                    Double value = feed.get(sleeperID);
                    if(value != null){
                        total += value;
                        counted++;
                    }
                }
                blended.put(sleeperID, total / Math.max(counted, 1));
            }
            return blended;
        }
        if("borischen".equals(source)){
            Map<String, Double> merged = new LinkedHashMap<>(
                    SleeperProjections.parseTodaysWebPage());
            merged.putAll(BorisChenTiers.leaguePointsBySleeperID());
            return merged;
        }
        return ProjectionBridge.pointsForSource(source);
    }

    public static void main(String[] args){
        Map<String, Double> sleeper = SleeperProjections.parseTodaysWebPage();
        System.out.println("projection sources (pick with -Pprojections=<name>, or");
        System.out.println("-Pprojections=blend:<a>,<b>,... to average feeds):\n");
        System.out.printf("   %-18s %-10s %s%n", "NAME", "STATUS", "SOURCE");
        for(Slot slot : SLOTS){
            String status;
            if(slot.how().equals("automatic")){
                status = "ready";
            }
            else {
                status = Files.exists(ProjectionBridge.EXTERNAL.resolve(slot.name() + ".csv"))
                        ? "loaded" : "empty";
            }
            System.out.printf("   %-18s %-10s %s - %s%n", slot.name(), status,
                    slot.what(), slot.how());
        }
        System.out.println("\nempty slots: export the site's projections to");
        System.out.println("data/external-projections/<name>.csv - a points sheet with its");
        System.out.println("scoring declared (# passTD=4 rec=0.5) or a stat sheet with Sleeper");
        System.out.println("stat keys as headers. Files there stay out of git on purpose.");

        // The live second opinion: where Boris Chen's tiers disagree with the
        // default feed's ordering, on the decision-relevant board.
        Map<String, Double> chen = BorisChenTiers.leaguePointsBySleeperID();
        record Gap(String name, String position, double chenPoints, double sleeperPoints){}
        List<Gap> gaps = new ArrayList<>();
        for(Map.Entry<String, Double> entry : chen.entrySet()){
            Double base = sleeper.get(entry.getKey());
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(base == null || player == null
                    || SleeperProjections.adpOf(entry.getKey()) > 120){
                continue;
            }
            gaps.add(new Gap(player.firstName + " " + player.lastName,
                    player.position.toString(), entry.getValue(), base));
        }
        gaps.sort(java.util.Comparator.comparingDouble(
                (Gap gap) -> -Math.abs(gap.chenPoints() - gap.sleeperPoints())));
        System.out.println("\nborischen vs sleeper, largest value disagreements (ADP <= 120):\n");
        for(int i = 0; i < 12 && i < gaps.size(); i++){
            Gap gap = gaps.get(i);
            System.out.printf("   %-24s %-3s  chen %6.1f  sleeper %6.1f  %+7.1f%n",
                    gap.name(), gap.position(), gap.chenPoints(), gap.sleeperPoints(),
                    gap.chenPoints() - gap.sleeperPoints());
        }
    }

}
