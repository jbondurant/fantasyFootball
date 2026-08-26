import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Every team's keeper ledger at high precision: for the top candidates on
 * each roster (prescreened by projection over positional replacement),
 * the standalone value of having kept him - seat value with just that keeper
 * minus the keeperless seat - sorted per team, the actually-kept players
 * marked with **. Every value comes from the full expectimax at reduced
 * rollouts (a greedy shortcut was tried and removed: it drafts QB round one,
 * a blunder the branching exists to prevent, which inflated every QB-keeper
 * scenario). Standalone values ignore pair interaction: a kept pair's joint
 * worth is less than the sum, since freed-pick cascades overlap.
 *
 * Search/evaluate split: the plan is found at SEARCH_ROLLOUTS, then priced
 * with -Ptrials rollouts on separate seeds - so 10,000-rollout precision
 * costs hours, not days.
 *
 *     ./gradlew run -Pmain=KeeperLedger [-Ptrials=10000] [-Pcandidates=8]
 */
public class KeeperLedger {

    static final int SEARCH_ROLLOUTS = 300;
    static final long EVAL_SEED = DraftSimulator.SEED + 1_000_000L;

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int rollouts = Integer.getInteger("trials", 10000);
        int perTeam = Integer.getInteger("candidates", 8);
        String me = configuration.getMyID();

        int lastCompleted = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, lastCompleted);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, lastCompleted,
                earliness);
        Map<String, Double> points = SleeperProjections.parseTodaysWebPage();
        List<Keeper> justinPair = DraftPlanner.keepersFromProperty(configuration);

        JsonObject draftOrder = configuration.getDraftJson().getAsJsonObject("draft_order");
        Map<Integer, String> bySlot = new TreeMap<>();
        for(Map.Entry<String, JsonElement> entry : draftOrder.entrySet()){
            bySlot.put(entry.getValue().getAsInt(), entry.getKey());
        }

        List<Keeper> declared = configuration.getTodaysKeepers();
        Map<String, Double> replacementDraftable = new java.util.HashMap<>(points);
        for(Keeper keeper : declared){
            replacementDraftable.remove(keeper.player.sleeperIDString);
        }
        AvailabilityModel availability = AvailabilityModel.build(replacementDraftable,
                ManagerProfiles.fitThroughSeason(configuration,
                        Integer.parseInt(configuration.getSeason()) - 1).leagueBiasMap())
                .withOccupiedPicks(configuration.keeperOccupiedPickNumbers());
        System.out.printf("standalone keeper deltas, top %d per team by VORP prescreen;%n"
                + "plans searched at %d rollouts, valued at %d on separate seeds%n"
                + "(** = actually kept; noise ~+/-%.1f):%n",
                perTeam, SEARCH_ROLLOUTS, rollouts, 30.0 / Math.sqrt(rollouts) * 2);

        for(Map.Entry<Integer, String> seat : bySlot.entrySet()){
            String manager = seat.getValue();
            Set<String> keptIDs = new java.util.HashSet<>();
            for(Keeper keeper : declared){
                if(manager.equals(keeper.humanWhoCanKeep)){
                    keptIDs.add(keeper.player.sleeperIDString);
                }
            }
            if(manager.equals(me)){
                for(Keeper keeper : justinPair){
                    keptIDs.add(keeper.player.sleeperIDString);
                }
            }
            List<Keeper> worldExtras = manager.equals(me) ? List.of() : justinPair;

            DraftPlanner basePlanner = DraftPlanner.forCurrentSeasonAs(configuration,
                    manager, worldExtras, keptIDs, model, earliness);
            double base = basePlanner.evaluate(
                    basePlanner.plan(SEARCH_ROLLOUTS, 0, 0.10, DraftSimulator.SEED).positions(),
                    rollouts, EVAL_SEED);

            // Prescreen: top candidates by projection over positional
            // replacement at this seat's round-9 pick - cheap, deterministic,
            // and it keeps the r10 zero-pile out of the expensive runs.
            int seatSlot = seat.getKey();
            int lastStarterPick = AAAConfiguration.pickNumber(
                    SelectionModel.GAME_ROUNDS, seatSlot, bySlot.size());
            List<Keeper> screened = new ArrayList<>();
            for(Keeper candidate : KeeperChooser.eligibleCandidates(configuration, manager)){
                if(StartingLineup.isSkillPosition(candidate.player.position)
                        && points.getOrDefault(candidate.player.sleeperIDString, 0.0) > 0.0){
                    screened.add(candidate);
                }
            }
            screened.sort(Comparator.comparingDouble((Keeper candidate) ->
                    points.getOrDefault(candidate.player.sleeperIDString, 0.0)
                            - availability.expectedBestAvailable(candidate.player.position,
                                    lastStarterPick, 300, DraftSimulator.SEED)).reversed());
            // Kept players always make the list, whatever the prescreen says.
            List<Keeper> chosen = new ArrayList<>();
            for(Keeper candidate : screened){
                if(keptIDs.contains(candidate.player.sleeperIDString)){
                    chosen.add(candidate);
                }
            }
            for(Keeper candidate : screened){
                if(chosen.size() >= perTeam){
                    break;
                }
                if(!chosen.contains(candidate)){
                    chosen.add(candidate);
                }
            }

            record Line(Keeper candidate, double delta){}
            List<Line> lines = new ArrayList<>();
            for(Keeper candidate : chosen){
                List<Keeper> extras = new ArrayList<>(worldExtras);
                extras.add(candidate);
                DraftPlanner planner = DraftPlanner.forCurrentSeasonAs(configuration, manager,
                        extras, keptIDs, model, earliness);
                List<PlayerImportAndSetup.Position> searched = planner
                        .plan(SEARCH_ROLLOUTS, 0, 0.10, DraftSimulator.SEED).positions();
                double value = planner.evaluate(searched, rollouts, EVAL_SEED);
                lines.add(new Line(candidate, value - base));
                System.out.printf("      ...%s valued%n", candidate.player.lastName);
            }
            lines.sort(Comparator.comparingDouble((Line line) -> line.delta()).reversed());

            System.out.printf("%n%s (slot %d, keeperless seat %.1f):%n",
                    HumanOfInterest.getHumanFromID(manager), seat.getKey(), base);
            for(Line line : lines){
                boolean kept = keptIDs.contains(line.candidate().player.sleeperIDString);
                String name = line.candidate().player.firstName + " "
                        + line.candidate().player.lastName;
                System.out.printf("   %-34s %-3s r%-3d %+7.1f%n",
                        kept ? "**" + name + "**" : name,
                        line.candidate().player.position,
                        line.candidate().roundCanBeKept, line.delta());
            }
        }
    }

}
