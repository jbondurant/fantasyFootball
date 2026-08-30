import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Does the new objective do the one thing the old one could not?
 *
 * LiveInsurance reported STARTS = 0% for every bench candidate, and the cause
 * was structural: a best-nine-of-season-totals score cannot represent a bench
 * player, because a season total has already absorbed the weeks a starter
 * missed. If WeeklyStarterValue is right, adding a tenth man to a full nine
 * should be worth ZERO under the old rule and something positive under the new
 * one. If it is not, the redesign has not fixed anything and should be said so.
 *
 *   ./gradlew run -Pmain=StarterSumCheck [-Pscenarios=2000]
 */
public class StarterSumCheck {

    public static void main(String[] args) throws Exception {
        int scenarios = Integer.getInteger("scenarios", 2000);
        Map<String, Double> projections = SleeperProjections.parseTodaysWebPage();

        // position and tier for every player on the current board
        Map<String, Position> positionOf = new HashMap<>();
        Map<String, Integer> tierOf = new HashMap<>();
        Map<Position, List<String>> byPosition = new EnumMap<>(Position.class);
        for(String id : projections.keySet()){
            Player player = Player.getPlayerFromSIDV2(id);
            // DEF too: the league starts one, and gating this on
            // isSkillPosition left rostered defences with no sampled outcomes
            // at all, so they were silently treated as never available.
            if(player != null && (StartingLineup.isSkillPosition(player.position)
                    || player.position == Position.DEF)){
                positionOf.put(id, player.position);
                byPosition.computeIfAbsent(player.position, u -> new ArrayList<>()).add(id);
            }
        }
        for(Map.Entry<Position, List<String>> entry : byPosition.entrySet()){
            List<String> ids = entry.getValue();
            ids.sort(Comparator.comparingDouble(id -> -projections.get(id)));
            for(int rank = 0; rank < ids.size(); rank++){
                tierOf.put(ids.get(rank), rank / WeeklyStarterValue.TIER);
            }
        }

        Map<String, List<OutcomeDistributions.Season>> pool = WeeklyStarterValue.pool();
        System.out.printf("outcome pool: %d position-tier cells, %d player-seasons%n",
                pool.size(), pool.values().stream().mapToInt(List::size).sum());

        Map<Position, Integer> replacement =
                InsuranceTest.replacementRanks(AAAConfiguration.getInstance());
        Map<Position, Double> wire = new EnumMap<>(Position.class);
        for(Map.Entry<Position, List<String>> entry : byPosition.entrySet()){
            List<String> ids = entry.getValue();
            int rank = replacement.getOrDefault(entry.getKey(),
                    entry.getKey() == Position.DEF ? 13 : 24);
            int index = Math.min(Math.max(0, rank - 1), ids.size() - 1);
            double rate = projections.getOrDefault(ids.get(index), 0.0) / 17.0;
            wire.put(entry.getKey(), rate);
            System.out.printf("   wire %-3s = %s%d -> %.1f pts/week (same units as the"
                    + " players)%n", entry.getKey(), entry.getKey(), rank, rate);
        }

        // a plausible starting nine off the current board: Model A's shape
        Position[] shape = {Position.RB, Position.WR, Position.RB, Position.WR,
                Position.WR, Position.WR, Position.TE, Position.QB, Position.RB};
        List<String> nine = new ArrayList<>();
        Map<Position, Integer> used = new EnumMap<>(Position.class);
        for(Position position : shape){
            int index = used.merge(position, 1, Integer::sum) - 1;
            nine.add(byPosition.get(position).get(index));
        }

        RosterValue season = new SeasonTotalValue(projections);
        RosterValue weekly = new WeeklyStarterValue(positionOf, tierOf, pool, wire,
                projections, scenarios, 424_242L);

        System.out.printf("%n%nTHE TEST: what is a TENTH man worth on a full nine?%n");
        System.out.printf("(the nine is %s)%n%n", names(nine));
        System.out.printf("%-26s %-4s %6s %16s %16s%n", "TENTH MAN", "POS", "tier",
                "season totals", "starter sum");
        double baseSeason = season.of(nine);
        double baseWeekly = weekly.of(nine);
        System.out.printf("%-26s %-4s %6s %16.1f %16.1f%n", "(nobody)", "-", "-",
                baseSeason, baseWeekly);

        for(Position position : new Position[]{Position.RB, Position.WR, Position.TE,
                Position.QB, Position.DEF}){
            for(int depth : new int[]{6, 18, 30}){
                List<String> ids = byPosition.get(position);
                if(ids == null || depth >= ids.size()){
                    continue;
                }
                String id = ids.get(depth);
                if(nine.contains(id)){
                    continue;
                }
                List<String> ten = new ArrayList<>(nine);
                ten.add(id);
                Player player = Player.getPlayerFromSIDV2(id);
                System.out.printf("%-26s %-4s %6d %+16.1f %+16.1f%n",
                        player.firstName + " " + player.lastName, position,
                        tierOf.getOrDefault(id, -1),
                        season.of(ten) - baseSeason, weekly.of(ten) - baseWeekly);
            }
        }

        System.out.println("\nThe DEF rows answer a different question from the rest."
                + " The nine has no defence,\nso a drafted one FILLS AN EMPTY STARTING"
                + " SLOT rather than sitting behind\nsomebody - and what it adds is"
                + " only what it beats the wire defence by. If that\nis small, the"
                + " search has no reason to ever spend a pick there, which is what\nthe"
                + " 0.277 rank correlation would predict.");
        System.out.println("\nUnder season totals a tenth man is worth exactly nothing"
                + " unless he outscores\na starter outright - that is the blindness that"
                + " made LiveInsurance report\nSTARTS = 0% for everyone. Under the"
                + " starter sum he is worth what he adds in\nthe weeks he is up and"
                + " better than whoever else is, which is the thing a\nbench player"
                + " actually does.");
    }

    static String names(List<String> ids){
        List<String> out = new ArrayList<>();
        for(String id : ids){
            Player player = Player.getPlayerFromSIDV2(id);
            out.add(player == null ? id : player.lastName);
        }
        return String.join(", ", out);
    }
}
