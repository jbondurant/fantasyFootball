import PlayerImportAndSetup.Position;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * Works out which keepers to declare, by simulating the draft that follows each
 * choice.
 *
 * Replaces runDraftsToChooseMyKeeperHardcoded, which had stopped working. That
 * version scored each keeper on its own and ranked them, which answers the
 * wrong question in a two-keeper league: the pair matters, because both cost
 * picks out of the same draft, and two keepers landing on the same round push
 * one of them a round dearer. It also priced candidates straight off last
 * season's round, so it offered up players who cannot legally be kept and
 * ignored the escalation on anyone kept before. And it crashed on the first
 * candidate regardless, running its position plan off the end.
 *
 *     ./gradlew run -Pmain=KeeperChooser
 */
public class KeeperChooser {

    public static class Option {
        public final List<Keeper> keepers;
        public double averageDraftScore;
        /**
         * Standard error of that average. Two options closer together than
         * roughly twice this are a tie, not an ordering - the simulation is
         * random and forty drafts is a small sample.
         */
        public double standardError;

        Option(List<Keeper> keepers){
            this.keepers = keepers;
        }

        public int totalRoundCost(){
            int cost = 0;
            for(Keeper keeper : keepers){
                cost += keeper.roundCanBeKept;
            }
            return cost;
        }

        public String describe(){
            StringBuilder builder = new StringBuilder();
            for(Keeper keeper : keepers){
                if(builder.length() > 0){
                    builder.append(" + ");
                }
                builder.append(keeper.player.firstName).append(" ").append(keeper.player.lastName)
                        .append(" (r").append(keeper.roundCanBeKept).append(")");
            }
            return builder.toString();
        }
    }

    /**
     * Prices one hypothetical set of keepers through the real rules, so
     * eligibility and the same-round bump are applied exactly as they would be
     * on the board. Returns null if the league would not allow the set.
     */
    public static List<Keeper> priceHypothetical(AAAConfiguration configuration,
                                                 String myID,
                                                 List<String> playerIDs){
        JsonArray keepers = new JsonArray();
        for(String playerID : playerIDs){
            keepers.add(playerID);
        }
        JsonObject roster = new JsonObject();
        roster.addProperty("owner_id", myID);
        roster.add("keepers", keepers);
        roster.add("players", keepers);

        JsonArray rosters = new JsonArray();
        rosters.add(roster);

        KeeperPricing.PricedKeepers priced = KeeperPricing.price(
                rosters, configuration.getPreviousDraftPicks(),
                Player::getPlayerFromSIDV2, SleeperProjections::adpOf);

        if(!priced.rejected.isEmpty() || priced.keepers.size() != playerIDs.size()){
            return null;
        }
        return priced.keepers;
    }

    /** Every player on my roster the rules would let me keep, with what they cost. */
    public static List<Keeper> eligibleCandidates(AAAConfiguration configuration, String myID){
        List<Keeper> candidates = new ArrayList<>();
        for(String playerID : configuration.getMyRosterPlayerIDs(myID)){
            List<Keeper> priced = priceHypothetical(configuration, myID, List.of(playerID));
            if(priced != null){
                candidates.add(priced.get(0));
            }
        }
        return candidates;
    }

    /**
     * Surplus is the pick I would spend minus where the player actually goes.
     * Only used to decide which candidates are worth simulating; the ranking
     * itself comes from the drafts.
     */
    public static double adpSurplus(AAAConfiguration configuration, Keeper keeper){
        double adp = SleeperProjections.adpOf(keeper.player.sleeperIDString);
        return configuration.pickNumberFor(keeper.roundCanBeKept) - adp;
    }

    public static List<Option> rank(AAAConfiguration configuration,
                                    int simulationsPerOption,
                                    int candidatesToSimulate,
                                    int qbADPChange) {
        String myID = configuration.getMyID();
        int maxKeepers = configuration.getMaxKeepers();
        int draftRounds = configuration.getDraftRounds();

        List<Keeper> candidates = eligibleCandidates(configuration, myID);
        candidates.sort(Comparator.comparingDouble((Keeper k) -> adpSurplus(configuration, k)).reversed());
        if(candidates.size() > candidatesToSimulate){
            candidates = candidates.subList(0, candidatesToSimulate);
        }

        List<List<String>> combinations = new ArrayList<>();
        for(int i = 0; i < candidates.size(); i++){
            if(maxKeepers == 1){
                combinations.add(List.of(candidates.get(i).player.sleeperIDString));
                continue;
            }
            for(int j = i + 1; j < candidates.size(); j++){
                combinations.add(List.of(candidates.get(i).player.sleeperIDString,
                        candidates.get(j).player.sleeperIDString));
            }
        }

        ArrayList<Keeper> leagueWideKeepers = configuration.getTodaysKeepers();
        ArrayList<Player> alreadyDrafted = new ArrayList<>();

        List<Option> options = new ArrayList<>();
        for(List<String> combination : combinations){
            List<Keeper> priced = priceHypothetical(configuration, myID, combination);
            if(priced == null){
                continue;
            }
            Option option = new Option(priced);
            option.averageDraftScore = simulate(priced, leagueWideKeepers, alreadyDrafted,
                    draftRounds, simulationsPerOption, qbADPChange, maxKeepers);
            option.standardError = lastStandardError;
            options.add(option);
        }

        options.sort(Comparator.comparingDouble((Option o) -> o.averageDraftScore).reversed());
        return options;
    }

    /**
     * What each candidate is worth on their own: the average season that
     * follows from keeping them, over simulated drafts.
     *
     * Each is simulated as a single keeper, so the number answers "how good is
     * this player to keep" without a partner's value folded in. Pair them up
     * with {@link #rank} once you have a shortlist.
     */
    public static List<Option> rankIndividually(AAAConfiguration configuration,
                                                int simulationsPerCandidate,
                                                int qbADPChange) {
        String myID = configuration.getMyID();
        ArrayList<Keeper> leagueWideKeepers = configuration.getTodaysKeepers();
        int draftRounds = configuration.getDraftRounds();

        List<Option> options = new ArrayList<>();
        for(Keeper candidate : eligibleCandidates(configuration, myID)){
            List<Keeper> only = List.of(candidate);
            Option option = new Option(only);
            option.averageDraftScore = simulate(only, leagueWideKeepers, new ArrayList<>(),
                    draftRounds, simulationsPerCandidate, qbADPChange, 1);
            option.standardError = lastStandardError;
            options.add(option);
        }
        options.sort(Comparator.comparingDouble((Option o) -> o.averageDraftScore).reversed());
        return options;
    }

    /** What I have kept in every season before this one, newest first. */
    public static void printMyKeeperHistory(AAAConfiguration configuration){
        String myID = configuration.getMyID();
        List<JsonArray> drafts = configuration.getPreviousDraftPicks();
        List<String> seasons = configuration.getPreviousSeasons();

        System.out.println("\nMy keepers in previous seasons:");
        boolean foundAny = false;
        for(int i = 0; i < drafts.size(); i++){
            List<String> mine = new ArrayList<>();
            for(JsonElement pickElement : drafts.get(i)){
                JsonObject pick = pickElement.getAsJsonObject();
                JsonElement isKeeper = pick.get("is_keeper");
                JsonElement pickedBy = pick.get("picked_by");
                if(isKeeper == null || isKeeper.isJsonNull() || !isKeeper.getAsBoolean()){
                    continue;
                }
                if(pickedBy == null || pickedBy.isJsonNull() || !pickedBy.getAsString().equals(myID)){
                    continue;
                }
                JsonObject meta = pick.getAsJsonObject("metadata");
                mine.add(String.format("%s %s (r%d)",
                        meta.get("first_name").getAsString(),
                        meta.get("last_name").getAsString(),
                        pick.get("round").getAsInt()));
            }
            String season = i < seasons.size() ? seasons.get(i) : "?";
            if(mine.isEmpty()){
                System.out.printf("   %-6s none%n", season);
            }
            else {
                foundAny = true;
                System.out.printf("   %-6s %s%n", season, String.join(",  ", mine));
            }
        }
        if(!foundAny){
            System.out.println("   (none on record)");
        }
    }

    private static double simulate(List<Keeper> keepers,
                                   ArrayList<Keeper> leagueWideKeepers,
                                   ArrayList<Player> alreadyDrafted,
                                   int draftRounds,
                                   int simulations,
                                   int qbADPChange,
                                   int maxKeepers){
        HashSet<Keeper> mine = new HashSet<>(keepers);

        // The keepers already fill their rounds, so plan for the rest.
        int picksToPlan = Math.max(draftRounds - keepers.size(), 0);
        double total = 0.0;
        double totalSquares = 0.0;
        for(int run = 0; run < simulations; run++){
            ArrayList<Position> plan = draftPlan(keepers, picksToPlan);
            double score = SimulationDraft.getSimulationPermPartialWithHardcodedKeepers(
                    mine, plan, alreadyDrafted, draftRounds, qbADPChange, leagueWideKeepers).scoreDraft();
            total += score;
            totalSquares += score * score;
        }
        double mean = total / simulations;
        double variance = Math.max(totalSquares / simulations - mean * mean, 0.0);
        lastStandardError = Math.sqrt(variance / simulations);
        return mean;
    }

    /** Set by the most recent simulate() call; read immediately after. */
    private static double lastStandardError;

    /**
     * A starting lineup first, then depth, shuffled so the simulation explores
     * orderings rather than always drafting the same shape.
     *
     * The defense is appended near the end rather than shuffled in, because
     * that is what the league does - the average first defense goes in round
     * 15, and none has ever been kept. It has to be in the plan at all:
     * nonPermutedPositions only emits QB/RB/WR/TE, so leaving it to that helper
     * meant the simulated team never drafted a defense, started nobody in the
     * DEF slot, and scored a permanent zero there. Keeping a defense then
     * looked like the best move on the board, which is how this was found.
     */
    private static ArrayList<Position> draftPlan(List<Keeper> keepers, int picks){
        ArrayList<Position> needed = HumanStrategy.nonPermutedPositions(1, 2, 3, 1);
        boolean needDefense = true;
        for(Keeper keeper : keepers){
            needed.remove(keeper.player.position);
            if(keeper.player.position.equals(Position.DEF)){
                needDefense = false;
            }
        }
        java.util.Collections.shuffle(needed);

        ArrayList<Position> depth = HumanStrategy.nonPermutedPositions(1, 3, 3, 1);
        java.util.Collections.shuffle(depth);

        ArrayList<Position> plan = new ArrayList<>(needed);
        plan.addAll(depth);
        while(plan.size() > picks){
            plan.remove(plan.size() - 1);
        }
        if(needDefense && !plan.isEmpty()){
            // Late, where this league actually takes one.
            plan.set(Math.max(plan.size() - 2, 0), Position.DEF);
        }
        return plan;
    }

    public static void main(String[] args) {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        String myID = configuration.getMyID();
        int simulations = Integer.getInteger("sims", 40);
        int candidates = Integer.getInteger("candidates", 8);
        int qbADPChange = Integer.getInteger("qbADPChange", 18);

        System.out.println("Keeper options for " + configuration.getSeason()
                + ", keeping " + configuration.getMaxKeepers()
                + " of " + configuration.getDraftRounds() + " rounds\n");

        List<Keeper> eligible = eligibleCandidates(configuration, myID);
        eligible.sort(Comparator.comparingDouble((Keeper k) -> adpSurplus(configuration, k)).reversed());
        System.out.println("eligible on my roster, best value first:");
        for(Keeper keeper : eligible){
            System.out.printf("   %-24s r%-3d  pick %-4d  adp %-7.1f  surplus %+.0f%n",
                    keeper.player.firstName + " " + keeper.player.lastName,
                    keeper.roundCanBeKept,
                    configuration.pickNumberFor(keeper.roundCanBeKept),
                    SleeperProjections.adpOf(keeper.player.sleeperIDString),
                    adpSurplus(configuration, keeper));
        }

        // My own pair is always placed, so only other managers skew the pool.
        List<String> waiting = new ArrayList<>(configuration.getManagersWithoutKeepers());
        waiting.remove(HumanOfInterest.getHumanFromID(myID));
        if(!waiting.isEmpty()){
            System.out.println("\nnote: " + String.join(", ", waiting)
                    + " has not declared keepers yet, so the simulation has them drafting every"
                    + " round. Expect them to take a couple more players out of the pool here than"
                    + " they really will.");
        }

        System.out.println("\nsimulating " + simulations + " drafts for each candidate on its own...\n");
        List<Option> individually = rankIndividually(configuration, simulations, qbADPChange);

        System.out.println("expected season if I keep this player, best first:");
        System.out.printf("   %-24s %-6s %-10s %s%n", "PLAYER", "ROUND", "EXPECTED", "+/-");
        double best = individually.get(0).averageDraftScore;
        for(Option option : individually){
            Keeper keeper = option.keepers.get(0);
            double margin = 2 * (option.standardError + individually.get(0).standardError);
            String tie = (option.averageDraftScore != best
                    && best - option.averageDraftScore < margin) ? "  (tied with the best)" : "";
            System.out.printf("   %-24s r%-5d %-10.1f +/-%.0f%s%n",
                    keeper.player.firstName + " " + keeper.player.lastName,
                    keeper.roundCanBeKept,
                    option.averageDraftScore,
                    2 * option.standardError,
                    tie);
        }

        System.out.println("\nsimulating " + simulations + " drafts for each pair of the top "
                + candidates + "...\n");
        List<Option> ranked = rank(configuration, simulations, candidates, qbADPChange);

        System.out.println("best pairs by average simulated season:");
        for(int i = 0; i < Math.min(10, ranked.size()); i++){
            Option option = ranked.get(i);
            double margin = 2 * (option.standardError + ranked.get(0).standardError);
            String tie = (i > 0 && ranked.get(0).averageDraftScore - option.averageDraftScore < margin)
                    ? "  (tied with the best)" : "";
            System.out.printf("   %6.1f +/-%-4.0f %s%s%n",
                    option.averageDraftScore, 2 * option.standardError, option.describe(), tie);
        }

        printMyKeeperHistory(configuration);
    }

}
