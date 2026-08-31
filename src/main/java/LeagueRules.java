import com.google.gson.JsonElement;
import java.util.*;

/**
 * What this league actually starts, straight from Sleeper.
 *
 * Written because a summary claimed "dropping the starting TE entirely scores
 * +11" and Justin said that makes no sense. He was right: a tight end is a
 * required slot, and an empty slot scores zero. The claim turned out to be a
 * misreading - the shape kept a tight end at round 11 - but the check belongs
 * in the repo rather than in a comment, because ShapeSensitivity.legal() tests
 * only for a defence and would wave through a roster with no tight end at all.
 */
public class LeagueRules {

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        List<String> slots = new ArrayList<>();
        for(JsonElement slot : configuration.getLeagueJson()
                .getAsJsonArray("roster_positions")){
            slots.add(slot.getAsString());
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for(String slot : slots){
            counts.merge(slot, 1, Integer::sum);
        }
        System.out.printf("roster is %d spots%n%n", slots.size());
        System.out.println("SLOT        HOW MANY   MUST IT BE FILLED TO SCORE?");
        for(Map.Entry<String, Integer> entry : counts.entrySet()){
            boolean bench = entry.getKey().equals("BN");
            System.out.printf("%-11s %6d   %s%n", entry.getKey(), entry.getValue(),
                    bench ? "no - bench, scores nothing either way"
                            : "yes - empty scores 0 every week");
        }
        int starters = slots.size() - counts.getOrDefault("BN", 0);
        System.out.printf("%n%d starting slots, %d bench.%n", starters,
                counts.getOrDefault("BN", 0));
        System.out.printf("A roster with no TE fields %d starters and takes a zero at TE.%n",
                starters - 1);
        System.out.printf("StartingLineup optimises the %d skill slots and leaves DEF out;%n"
                + "ShapeSensitivity.legal() checks only for DEF, so it would call a%n"
                + "TE-less or QB-less shape legal. That is a gap, not the +11.%n",
                StartingLineup.SKILL_SLOTS);
    }
}
