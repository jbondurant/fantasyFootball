import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The pure serpentine slot curve: every seat valued on a world with NO
 * keepers anywhere - full board, no occupied slots - so the only differences
 * left are draft position and the fitted behavior of the neighbors around
 * it. Printed beside each seat's keeperless value from the real keeper
 * landscape, whose bumps are then attributable: pass-through from
 * neighbors' in-game keeper slots, and each seat's own returned players.
 *
 *     ./gradlew run -Pmain=SlotValue [-Ptrials=60]
 */
public class SlotValue {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int rollouts = Integer.getInteger("trials", 60);

        int lastCompleted = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, lastCompleted);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, lastCompleted,
                earliness);

        Set<String> everyKeeper = new HashSet<>();
        for(Keeper keeper : configuration.getTodaysKeepers()){
            everyKeeper.add(keeper.player.sleeperIDString);
        }
        for(Keeper keeper : DraftPlanner.keepersFromProperty(configuration)){
            everyKeeper.add(keeper.player.sleeperIDString);
        }

        JsonObject draftOrder = configuration.getDraftJson().getAsJsonObject("draft_order");
        Map<Integer, String> bySlot = new TreeMap<>();
        for(Map.Entry<String, JsonElement> entry : draftOrder.entrySet()){
            bySlot.put(entry.getValue().getAsInt(), entry.getKey());
        }

        java.util.List<Keeper> justinPair = DraftPlanner.keepersFromProperty(configuration);
        Map<String, Double> points = SleeperProjections.parseTodaysWebPage();
        java.util.List<Keeper> declared = new java.util.ArrayList<>(
                configuration.getTodaysKeepers());
        for(Keeper extra : justinPair){
            if(declared.stream().noneMatch(keeper ->
                    keeper.player.sleeperIDString.equals(extra.player.sleeperIDString))){
                declared.add(extra);
            }
        }

        System.out.printf("slot value, Justin's mock-room construction, %d rollouts%n"
                + "(noise ~+/-2.6): every keeper pinned to a draft slot - own round if%n"
                + "inside the game, else the team's round 9 (second one round 8) - ONE%n"
                + "shared world, keepers count in lineups, projections subtracted after:%n%n",
                rollouts);
        // Replacement at each seat's round-9 pick, for the VORP-subtracted
        // residual: raw projections punish QB keepers by the ~300-point
        // baseline any team gets from any starting QB.
        Map<String, Double> draftable = new java.util.HashMap<>(points);
        for(Keeper keeper : declared){
            draftable.remove(keeper.player.sleeperIDString);
        }
        Map<PlayerImportAndSetup.Position, Double> leagueBias = ManagerProfiles
                .fitThroughSeason(configuration, lastCompleted).leagueBiasMap();
        AvailabilityModel availability = AvailabilityModel.build(draftable, leagueBias)
                .withOccupiedPicks(configuration.keeperOccupiedPickNumbers());
        int teams = bySlot.size();

        System.out.printf("   %-4s %-16s %8s %8s %8s %8s %8s%n",
                "SLOT", "MANAGER", "clean", "mock", "kpr proj", "residual", "res-vorp");
        for(Map.Entry<Integer, String> seat : bySlot.entrySet()){
            String manager = seat.getValue();
            java.util.List<Keeper> extras = manager.equals(configuration.getMyID())
                    ? justinPair : java.util.List.of();
            double clean = DraftPlanner.forCurrentSeasonAs(configuration, manager,
                            java.util.List.of(), everyKeeper, model, earliness)
                    .plan(rollouts, 0, 0.10, DraftSimulator.SEED).mean();
            double mock = DraftPlanner.forCurrentSeasonAs(configuration, manager,
                            extras, Set.of(), false, true, model, earliness)
                    .plan(rollouts, 0, 0.10, DraftSimulator.SEED).mean();
            double keeperProjections = 0;
            double keeperVorp = 0;
            int lastStarterPick = AAAConfiguration.pickNumber(
                    SelectionModel.GAME_ROUNDS, seat.getKey(), teams);
            for(Keeper keeper : declared){
                if(!manager.equals(keeper.humanWhoCanKeep)){
                    continue;
                }
                double projection = points.getOrDefault(keeper.player.sleeperIDString, 0.0);
                keeperProjections += projection;
                keeperVorp += projection - availability.expectedBestAvailable(
                        keeper.player.position, lastStarterPick, 300, DraftSimulator.SEED);
            }
            System.out.printf("   %-4d %-16s %8.1f %8.1f %8.1f %8.1f %8.1f%n", seat.getKey(),
                    HumanOfInterest.getHumanFromID(manager), clean, mock,
                    keeperProjections, mock - keeperProjections, mock - keeperVorp);
        }
        System.out.println("\n   residual = mock - raw keeper projections (Justin's spec);");
        System.out.println("   res-vorp subtracts keeper value over replacement at the seat's");
        System.out.println("   round-9 pick instead, so QB keepers stop dragging their seats");
        System.out.println("   down by the baseline any starting QB provides.");
    }

}