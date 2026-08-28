import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The actionable half of the late-round work: WHO to stash on Tuesday.
 *
 * LateRoundValue measured the base rates - QBs hit 41% against 15-19% for
 * every other position, young beats veteran 24% to 15%, and the round band
 * barely matters. This turns that into a ranked list of 2026 candidates,
 * scored by what a late pick is actually worth:
 *
 *   2027 keeper option - a player taken in round R is keepable at round R
 *   next year, so the prize is his projected value MINUS what a round-R pick
 *   returns. That is the Tuten trade priced directly.
 *   x  the measured hit rate for his position and age group
 *
 * Restricted to players who will actually still be there: anyone the
 * simulator says survives the nine-round game with high probability.
 *
 *   ./gradlew run -Pmain=LateRoundTargets [-Ptrials=300]
 */
public class LateRoundTargets {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 300);

        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, List.of(),
                model, earliness);
        DraftSimulator simulator = planner.simulator();
        Map<String, Double> points = planner.points();
        java.util.Set<String> young = SleeperProjections.youngPlayers(2);

        // hit rates measured in LateRoundValue
        Map<Position, Double> hitRate = new java.util.EnumMap<>(Position.class);
        hitRate.put(Position.QB, 0.41);
        hitRate.put(Position.RB, 0.16);
        hitRate.put(Position.WR, 0.15);
        hitRate.put(Position.TE, 0.19);

        // With -PdraftId this runs from the LIVE board instead of the
        // pre-draft one. That matters because LiveCommittee now sends bench
        // picks here: at pick 90 there are 89 players gone, and a list that
        // cannot see them will confidently name someone drafted an hour ago.
        String draftID = System.getProperty("draftId");
        DraftSimulator.SimState live = null;
        java.util.Collection<String> pool = simulator.players();
        if(draftID != null){
            try {
                live = simulator.stateAfter(LiveDraft.livePicks(draftID));
                pool = new ArrayList<>(live.boardView());
                System.out.printf("live board: %d players gone, %d left%n",
                        simulator.players().size() - pool.size(), pool.size());
            }
            catch(Exception unreachable){
                System.out.println("could not read draft " + draftID
                        + " - falling back to the pre-draft board: "
                        + unreachable.getMessage());
            }
        }

        // survival: who is still there after nine rounds?
        Map<String, Integer> survived = new HashMap<>();
        Random random = new Random(DraftSimulator.SEED + 777);
        for(int t = 0; t < trials; t++){
            if(live == null){
                Map<String, Integer> takenAt = simulator.simulateOnce(random);
                for(String id : pool){
                    if(!takenAt.containsKey(id)){
                        survived.merge(id, 1, Integer::sum);
                    }
                }
            }
            else {
                DraftSimulator.SimState branch = live.copy();
                while(simulator.slotOf(branch) != null){
                    simulator.simulateOneFrom(branch, random);
                }
                for(String id : pool){
                    if(branch.takenAtOf(id) == null){
                        survived.merge(id, 1, Integer::sum);
                    }
                }
            }
        }

        List<Object[]> rows = new ArrayList<>();
        for(String id : pool){
            double survival = survived.getOrDefault(id, 0) / (double) trials;
            if(survival < 0.60){
                continue;   // will not reach the late rounds
            }
            Player player = Player.getPlayerFromSIDV2(id);
            if(player == null || !StartingLineup.isSkillPosition(player.position)){
                continue;
            }
            double projected = points.getOrDefault(id, 0.0);
            boolean isYoung = young.contains(id);
            // the keeper option: his value against what a late pick returns.
            // A round 10-16 keeper costs essentially nothing in the nine-round
            // game, so the whole projection is surplus if he becomes startable.
            double score = projected * hitRate.getOrDefault(player.position, 0.15)
                    * (isYoung ? 1.6 : 1.0);
            rows.add(new Object[]{player.firstName + " " + player.lastName,
                    player.position, projected, survival, isYoung, score});
        }
        rows.sort(Comparator.comparingDouble(r -> -(Double) r[5]));

        System.out.printf("2026 late-round stash targets - ranked by projected points x "
                + "measured%nposition hit rate x youth bonus, among players who survive "
                + "nine rounds%nin at least 60%% of simulations (%d trials).%n%n", trials);
        System.out.printf("   %-24s %-4s %8s %9s %6s %8s%n", "PLAYER", "POS", "proj",
                "survives", "young", "score");
        for(int i = 0; i < 24 && i < rows.size(); i++){
            Object[] row = rows.get(i);
            System.out.printf("   %-24s %-4s %8.0f %8.0f%% %6s %8.1f%n", row[0], row[1],
                    (Double) row[2], (Double) row[3] * 100,
                    (Boolean) row[4] ? "yes" : "", (Double) row[5]);
        }
        System.out.println("\nThe base rates say this list should be QB-heavy, and that is"
                + "\nnot a bug: nine of the ten best late stashes in league history were"
                + "\nquarterbacks, because the room will not draft them.");
    }
}
