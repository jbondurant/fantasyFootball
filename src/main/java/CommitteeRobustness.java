import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MismatchArena tested a simple depth-1 lookahead across wrong-model worlds.
 * The stack that will actually drive on Tuesday is depth-2 with VORP tails,
 * arbitrated by KN. This asks the question that matters: when the opponent
 * model is WRONG - a drone league, a chaotic one, a QB-hungry one - does the
 * live engine degrade gracefully, or does it degrade worse than the dumb
 * fallback it replaced?
 *
 * A tool that beats greedy by 9 in the world it was fitted to, but loses to
 * greedy when the world is wrong, is not a tool worth trusting on draft night.
 *
 *   ./gradlew run -Pmain=CommitteeRobustness [-Ptrials=300]
 */
public class CommitteeRobustness {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 300);

        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel boosted = BoostedSelectionModel.fitShipped(configuration, last,
                earliness);
        SelectionModel linear = SelectionModel.fitShipped(configuration, last, earliness);
        Map<String, Double> hungry = new java.util.HashMap<>(earliness);
        for(String manager : earliness.keySet()){
            if(!manager.equals(configuration.getMyID())){
                hungry.merge(manager, 0.9, Double::sum);
            }
        }

        Map<String, ChoiceModel> brains = new LinkedHashMap<>();
        Map<String, Map<String, Double>> earlinessOf = new LinkedHashMap<>();
        brains.put("base", boosted);            earlinessOf.put("base", earliness);
        brains.put("linear", linear);           earlinessOf.put("linear", earliness);
        brains.put("drones", OpponentVariants.sharpen(boosted, 6.0));
        earlinessOf.put("drones", earliness);
        brains.put("chaos", OpponentVariants.chaos(boosted, 0.35));
        earlinessOf.put("chaos", earliness);
        brains.put("all-autodraft", OpponentVariants.autodraft());
        earlinessOf.put("all-autodraft", earliness);
        brains.put("qb-hungry", boosted);       earlinessOf.put("qb-hungry", hungry);

        System.out.printf("%-16s %14s %14s %14s   %s%n", "TRUE WORLD", "committee",
                "greedy-vorp", "committed", "engine vs greedy");
        for(Map.Entry<String, ChoiceModel> world : brains.entrySet()){
            PolicyTournament tournament = PolicyTournament.forCurrentGame(configuration,
                    200, world.getValue(), earlinessOf.get(world.getKey()));
            double committee = PolicyTournament.mean(tournament.evaluate(
                    FluxDraft.named(seed -> tournament.new Lookahead(2, 16,
                            PolicyTournament.Tail.VORP, seed)), trials));
            double greedy = PolicyTournament.mean(tournament.evaluate(
                    FluxDraft.named(seed -> tournament.new GreedyVorp()), trials));
            List<PlayerImportAndSetup.Position> plan = List.of(
                    PlayerImportAndSetup.Position.RB, PlayerImportAndSetup.Position.RB,
                    PlayerImportAndSetup.Position.RB, PlayerImportAndSetup.Position.WR,
                    PlayerImportAndSetup.Position.WR, PlayerImportAndSetup.Position.WR,
                    PlayerImportAndSetup.Position.TE);
            double committed = PolicyTournament.mean(tournament.evaluate(
                    FluxDraft.named(seed -> tournament.new SequencePolicy(plan)), trials));
            System.out.printf("%-16s %14.1f %14.1f %14.1f %14.1f%n", world.getKey(),
                    committee, greedy, committed, committee - greedy);
        }
        System.out.println("\nThe engine must beat greedy in EVERY world, not just the"
                + "\none it was fitted to. A tool that wins by 9 at home and loses when"
                + "\nthe model is wrong is not safe to trust on draft night.");
    }
}
