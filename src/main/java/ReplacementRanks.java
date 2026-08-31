import PlayerImportAndSetup.Position;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What an unfilled slot is really worth: three derivations, one of which the
 * objective has been using without anybody checking it against the league.
 *
 * RiskDiscountedValue scores an empty slot at the replacement-rank player's
 * discounted projection. The rank came from InsuranceTest.replacementRanks -
 * "how many at this position does the league draft, plus one" - with a
 * hard-coded 13 for DEF and 24 for anything the count missed. That answers the
 * question "who is the best man at this position that nobody rosters".
 *
 * That is the WRONG question, and the error is not small. The objective uses
 * the number as the value of NOT taking the position now, and what you get for
 * not taking it now is not the best unrostered man - it is the best man still
 * on the board AT YOUR LAST PICK, because that is when you would actually fill
 * the slot. For a running back those are the same number, because backs are
 * gone by then either way. For a DEFENCE they are nothing alike: this league
 * does not start drafting defences until round 15, so at pick 186 the board
 * still holds most of the position, and the objective has been pricing an
 * empty defence slot at a median defence when in truth it is worth a good one.
 * Under-price the fallback and the position looks worth an early pick - which
 * is exactly the round-8 defence the model kept taking.
 *
 *   ./gradlew run -Pmain=ReplacementRanks
 */
public class ReplacementRanks {

    /** Slots the league starts, before the flex: QB 1, RB 2, WR 3, TE 1, DEF 1. */
    static final Map<Position, Integer> STARTERS = new EnumMap<>(Position.class);
    static {
        STARTERS.put(Position.QB, 1);
        STARTERS.put(Position.RB, 2);
        STARTERS.put(Position.WR, 3);
        STARTERS.put(Position.TE, 1);
        STARTERS.put(Position.DEF, 1);
    }

    static final Position[] ALL = {Position.QB, Position.RB, Position.WR, Position.TE,
            Position.DEF};

    /** Teams in the league, from the league object rather than from memory. */
    public static int teams(AAAConfiguration configuration){
        JsonElement rosters = configuration.getLeagueJson().get("total_rosters");
        return rosters == null || rosters.isJsonNull() ? 12 : rosters.getAsInt();
    }

    /** Roster spots per team, counting the bench. */
    public static int rosterSpots(AAAConfiguration configuration){
        JsonArray slots = configuration.getLeagueJson().getAsJsonArray("roster_positions");
        int spots = 0;
        for(JsonElement slot : slots){
            // Sleeper lists an unused injured-reserve slot as "IR"; it is not a
            // draftable spot and counting it would inflate every rank.
            if(!slot.getAsString().equals("IR")){
                spots++;
            }
        }
        return spots;
    }

    /**
     * The lower bound from the lineup alone: the men the league MUST roster.
     *
     * Twelve teams starting QB/RB/RB/WR/WR/WR/TE/FLEX/FLEX/DEF need 12 QBs, 24
     * backs, 36 receivers, 12 tight ends and 12 defences, plus 24 flex bodies
     * that go to backs and receivers. That is 120 of the 192 roster spots; the
     * other 72 are bench, and where the bench goes is a behaviour, not a rule -
     * which is why this is a floor and not the answer.
     */
    public static Map<Position, Integer> mandatory(AAAConfiguration configuration){
        int teams = teams(configuration);
        Map<Position, Integer> out = new EnumMap<>(Position.class);
        for(Position position : ALL){
            out.put(position, teams * STARTERS.get(position));
        }
        return out;
    }

    /** Every previous draft's picks, newest first, as (pickNumber, position). */
    record Taken(int pickNumber, Position position){}

    static List<List<Taken>> previousDrafts(AAAConfiguration configuration){
        List<List<Taken>> drafts = new ArrayList<>();
        for(JsonArray picks : configuration.getPreviousDraftPicks()){
            List<Taken> draft = new ArrayList<>();
            for(JsonElement element : picks){
                JsonObject pick = element.getAsJsonObject();
                JsonElement id = pick.get("player_id");
                JsonElement number = pick.get("pick_no");
                if(id == null || id.isJsonNull() || number == null || number.isJsonNull()){
                    continue;
                }
                Player player = Player.getPlayerFromSIDV2(id.getAsString());
                if(player != null){
                    draft.add(new Taken(number.getAsInt(), player.position));
                }
            }
            if(draft.size() > 100){
                drafts.add(draft);
            }
        }
        return drafts;
    }

    /**
     * What the objective uses today: the best man nobody rosters.
     *
     * Counted over every previous draft and averaged, plus one. Defences are
     * excluded from InsuranceTest's version because StartingLineup does not
     * call DEF a skill position, so DEF silently takes the hard-coded 13; this
     * counts them, which is the first thing worth knowing.
     */
    public static Map<Position, Integer> undrafted(AAAConfiguration configuration){
        return countedBefore(configuration, Integer.MAX_VALUE);
    }

    /**
     * The defensible one: the best man left at MY LAST PICK.
     *
     * An empty slot is filled at the end of the draft, so the fallback is
     * whoever survives that long. My last pick is 186 of 192 in 2026; past
     * drafts are not all the same length, so the cutoff travels as a fraction
     * of the board rather than as a raw pick number.
     */
    public static Map<Position, Integer> atMyLastPick(AAAConfiguration configuration){
        int myLast = PlanBacktest.MY_PICKS[PlanBacktest.MY_PICKS.length - 1];
        int board = teams(configuration) * rosterSpots(configuration);
        return countedBefore(configuration, myLast, (double) myLast / board);
    }

    static Map<Position, Integer> countedBefore(AAAConfiguration configuration, int cutoff){
        return countedBefore(configuration, cutoff, 1.0);
    }

    /**
     * Average count at each position taken strictly before the cutoff, plus one.
     *
     * The +1 lands on exactly the right man: if the league takes 21 quarterbacks
     * and I take none, twenty go to the other eleven teams and QB21 is the one
     * left for me. valueOf indexes rank-1, so 21 reads the 21st best.
     */
    static Map<Position, Integer> countedBefore(AAAConfiguration configuration, int cutoff,
                                                double fraction){
        List<List<Taken>> drafts = previousDrafts(configuration);
        Map<Position, Integer> ranks = new EnumMap<>(Position.class);
        if(drafts.isEmpty()){
            return ranks;
        }
        Map<Position, Integer> totals = new EnumMap<>(Position.class);
        for(List<Taken> draft : drafts){
            int limit = cutoff == Integer.MAX_VALUE ? Integer.MAX_VALUE
                    : (int) Math.round(fraction * draft.size());
            for(Taken taken : draft){
                if(taken.pickNumber() <= limit){
                    totals.merge(taken.position(), 1, Integer::sum);
                }
            }
        }
        for(Position position : ALL){
            ranks.put(position, totals.getOrDefault(position, 0) / drafts.size() + 1);
        }
        return ranks;
    }

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int teams = teams(configuration);
        int spots = rosterSpots(configuration);
        List<List<Taken>> drafts = previousDrafts(configuration);
        int myLast = PlanBacktest.MY_PICKS[PlanBacktest.MY_PICKS.length - 1];

        System.out.printf("%nREPLACEMENT RANKS: WHAT AN UNFILLED SLOT IS WORTH%n%n");
        System.out.printf("%d teams x %d roster spots = %d players rostered."
                + " My last pick is %d.%n", teams, spots, teams * spots, myLast);
        System.out.printf("%d previous drafts read, sizes %s%n%n", drafts.size(),
                drafts.stream().map(List::size).toList());

        Map<Position, Integer> mandatory = mandatory(configuration);
        Map<Position, Integer> undrafted = undrafted(configuration);
        Map<Position, Integer> lastPick = atMyLastPick(configuration);
        Map<Position, Integer> shipped = InsuranceTest.replacementRanks(configuration);

        System.out.printf("%-5s %12s %12s %14s %14s %10s%n", "POS", "mandatory",
                "SHIPPED", "all drafted", "at my last", "shift");
        for(Position position : ALL){
            int ship = shipped.getOrDefault(position,
                    position == Position.DEF ? 13 : 24);
            System.out.printf("%-5s %12d %12d %14d %14d %+10d%n", position,
                    mandatory.get(position), ship, undrafted.get(position),
                    lastPick.get(position), lastPick.get(position) - ship);
        }

        int rostered = 0;
        for(Position position : ALL){
            rostered += undrafted.get(position) - 1;
        }
        System.out.printf("%nthe all-drafted counts sum to %d against %d roster spots"
                + " - %s%n", rostered, teams * spots,
                Math.abs(rostered - teams * spots) <= 6
                        ? "they reconcile, so the count is measuring what it claims"
                        : "THEY DO NOT RECONCILE, so something is being miscounted");
        System.out.println("\nThe only position where the two questions give different"
                + " answers is the one\nthe league drafts last. Everywhere else a slot"
                + " left empty is empty because\nthe position is exhausted; at defence"
                + " it is empty because nobody has started.");
    }
}
