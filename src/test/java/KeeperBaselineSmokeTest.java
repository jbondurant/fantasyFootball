import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a keeper is priced AGAINST, on this season's real board.
 *
 * A keeper's worth is V(seat with him) - V(seat without him), so the second
 * world has to actually be without him. DraftPlanner.forCurrentSeasonAs
 * applies its exclude set to DECLARED keepers only - an explicit extra
 * always lands, deliberately, so KeeperLedger can float a kept player back
 * in as his own hypothetical. Name the same keeper in both lists and the two
 * cancel: nothing is excluded, the extra re-lands, and the "without" world
 * is the "with" world bit for bit. Every keeper on that seat then prices at
 * exactly +0.0, and the zero looks like a finding rather than a bug.
 *
 * Only a seat holding UNDECLARED keepers - the -Pkeepers path, which in 2026
 * was Justin's alone - can reach that state, so it hid in one row of a
 * twelve-row table for two runs. These tests take the same path deliberately.
 */
@Tag("smoke")
class KeeperBaselineSmokeTest {

    /** Uniform over the choice set. The assertions here are about which
     *  players and picks exist in a world, not about who gets drafted, so
     *  the test does not pay for the boosted fit. */
    private static final ChoiceModel UNIFORM = features -> {
        double[] probabilities = new double[features.length];
        Arrays.fill(probabilities, features.length == 0 ? 0 : 1.0 / features.length);
        return probabilities;
    };

    private static final int ROLLOUTS = 6;

    private record World(AAAConfiguration configuration, Map<Integer, String> bySlot,
                         Map<String, Double> earliness, List<Keeper> declared,
                         String me, List<Keeper> extras){}

    /**
     * This season, with two undeclared keepers standing in for whatever
     * -Pkeepers holds on the day. They are chosen from the me-seat's own
     * eligible candidates, so the test keeps working after the commissioner
     * enters a declaration and into later seasons.
     */
    private static World world(){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int lastCompleted = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, lastCompleted);
        List<Keeper> declared = configuration.getTodaysKeepers();
        String me = configuration.getMyID();

        JsonObject draftOrder = configuration.getDraftJson().getAsJsonObject("draft_order");
        Map<Integer, String> bySlot = new TreeMap<>();
        for(Map.Entry<String, JsonElement> entry : draftOrder.entrySet()){
            bySlot.put(entry.getValue().getAsInt(), entry.getKey());
        }

        Set<String> alreadyDeclared = new HashSet<>();
        for(Keeper keeper : declared){
            alreadyDeclared.add(keeper.player.sleeperIDString);
        }
        Map<String, Double> points = SleeperProjections.parseTodaysWebPage();
        List<Keeper> candidates = new ArrayList<>();
        for(Keeper candidate : KeeperChooser.eligibleCandidates(configuration, me)){
            if(!alreadyDeclared.contains(candidate.player.sleeperIDString)
                    && StartingLineup.isSkillPosition(candidate.player.position)
                    && points.getOrDefault(candidate.player.sleeperIDString, 0.0) > 0.0){
                candidates.add(candidate);
            }
        }
        candidates.sort(Comparator.comparingDouble((Keeper candidate) ->
                points.getOrDefault(candidate.player.sleeperIDString, 0.0)).reversed());
        return new World(configuration, bySlot, earliness, declared, me,
                List.copyOf(candidates.subList(0, Math.min(2, candidates.size()))));
    }

    private static Set<String> idsOf(List<Keeper> keepers){
        Set<String> ids = new HashSet<>();
        for(Keeper keeper : keepers){
            ids.add(keeper.player.sleeperIDString);
        }
        return ids;
    }

    /**
     * The contract, structurally: whatever route a keeper took into the
     * world, the keeperless baseline must give him up - off the roster and
     * back onto the board for everyone to draft.
     */
    @Test
    void theKeeperlessBaselineGivesUpTheSeatsOwnKeepersHoweverTheyArrived(){
        World world = world();
        Assertions.assertFalse(world.extras().isEmpty(),
                "no eligible keeper candidates on the me-seat; nothing to price");
        Set<String> ids = idsOf(world.extras());

        DraftPlanner with = DraftPlanner.forCurrentSeasonAs(world.configuration(), world.me(),
                world.extras(), Set.of(), UNIFORM, world.earliness());
        DraftPlanner without = LeagueOutlook.baseline(world.configuration(), world.me(),
                world.extras(), ids, UNIFORM, world.earliness());

        Assertions.assertTrue(with.myKeeperIDs().containsAll(ids),
                "the with-keepers world should credit the seat with both: "
                        + with.myKeeperIDs());
        for(Keeper keeper : world.extras()){
            String id = keeper.player.sleeperIDString;
            Assertions.assertFalse(without.myKeeperIDs().contains(id),
                    keeper.player.lastName + " is still on the roster of the world "
                            + "that is supposed to be without him");
            Assertions.assertFalse(with.simulator().players().contains(id),
                    keeper.player.lastName + " should be off the board when kept");
            Assertions.assertTrue(without.simulator().players().contains(id),
                    keeper.player.lastName + " should be back on the board when not kept");
        }
    }

    /**
     * The symptom the structural test exists to prevent. Two different worlds
     * rolled out cannot agree to the last bit; identical ones cannot disagree.
     * So an exactly-zero keeper delta is never a finding about football - it
     * is the baseline having silently collapsed onto the with-keepers world.
     */
    @Test
    void noSeatPricesItsKeepersAtExactlyZero(){
        World world = world();
        List<Keeper> theirs = new ArrayList<>(world.extras());
        for(Keeper keeper : world.declared()){
            if(world.me().equals(keeper.humanWhoCanKeep)){
                theirs.add(keeper);
            }
        }

        LeagueOutlook.Seat seat = LeagueOutlook.evaluate(world.configuration(), world.me(),
                theirs, world.extras(), UNIFORM, world.earliness(), ROLLOUTS, 0.10);

        Assertions.assertNotEquals(seat.plan().mean(), seat.keeperless(),
                "best-nine and the keeperless seat came out bit-identical, so the seat "
                        + "was priced against itself");
        for(Keeper keeper : theirs){
            Double delta = seat.keeperDeltas().get(keeper.player.lastName);
            Assertions.assertNotNull(delta, "no delta priced for " + keeper.player.lastName);
            Assertions.assertNotEquals(0.0, delta,
                    keeper.player.lastName + " priced at exactly +0.0, which no rollout "
                            + "average does by chance - his baseline still contained him");
        }
    }

    /**
     * The accounting the zero was mistaken for: a keeper costs a live pick
     * only if his price lands inside the nine-round game. Cheap late keepers
     * are additions, not swaps - the seat still drafts nine - which is real,
     * and is exactly why their worth is small rather than zero.
     */
    @Test
    void onlyAKeeperPricedInsideTheNineRoundGameCostsALivePick(){
        World world = world();
        System.out.printf("%n   %-16s %4s %8s %10s %8s %8s%n", "MANAGER", "SLOT",
                "KEEPERS", "IN-GAME", "PICKS", "ROSTER");
        for(Map.Entry<Integer, String> seat : world.bySlot().entrySet()){
            String manager = seat.getValue();
            List<Keeper> theirs = new ArrayList<>();
            for(Keeper keeper : world.declared()){
                if(manager.equals(keeper.humanWhoCanKeep)){
                    theirs.add(keeper);
                }
            }
            List<Keeper> extras = manager.equals(world.me()) ? world.extras() : List.of();
            theirs.addAll(extras);

            int inGame = 0;
            for(Keeper keeper : theirs){
                if(keeper.roundCanBeKept >= 1 && keeper.roundCanBeKept <= SelectionModel.GAME_ROUNDS){
                    inGame++;
                }
            }

            DraftPlanner planner = DraftPlanner.forCurrentSeasonAs(world.configuration(),
                    manager, extras, Set.of(), UNIFORM, world.earliness());
            int livePicks = planner.simulator().pickNumbersOf(manager).length;

            System.out.printf("   %-16s %4d %8d %10d %8d %8d%n",
                    HumanOfInterest.getHumanFromID(manager), seat.getKey(), theirs.size(),
                    inGame, livePicks, livePicks + theirs.size());

            Assertions.assertEquals(SelectionModel.GAME_ROUNDS - inGame, livePicks,
                    HumanOfInterest.getHumanFromID(manager) + " keeps " + theirs.size()
                            + " (" + inGame + " priced inside round "
                            + SelectionModel.GAME_ROUNDS + ") so should hold "
                            + (SelectionModel.GAME_ROUNDS - inGame) + " live picks");
            Assertions.assertEquals(theirs.size(), planner.myKeeperIDs().size(),
                    HumanOfInterest.getHumanFromID(manager)
                            + " should be credited with every keeper he holds, "
                            + "whether it cost him a pick or not");
        }
    }
}
