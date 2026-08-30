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
            if(player != null && StartingLineup.isSkillPosition(player.position)){
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

        // The wire is the best man this league leaves UNDRAFTED, which
        // InsuranceTest measures from full sixteen-round histories: QB21, RB61,
        // WR81, TE19. Taking the deepest tier the pool happens to hold instead
        // put the WR wire at rank ~133 and 0.4 points a week, which is not a
        // waiver wire but a man who barely plays - and it inflated every
        // marginal below, because anyone at all beats nothing.
        Map<Position, Integer> replacement =
                InsuranceTest.replacementRanks(AAAConfiguration.getInstance());
        Map<Position, Double> wire = new EnumMap<>(Position.class);
        for(Position position : new Position[]{Position.QB, Position.RB, Position.WR,
                Position.TE}){
            int rank = replacement.getOrDefault(position, 24);
            int tier = rank / WeeklyStarterValue.TIER;
            List<OutcomeDistributions.Season> cell = pool.get(position + ":" + tier);
            while(cell == null && tier > 0){
                cell = pool.get(position + ":" + (--tier));
            }
            double rate = cell == null ? 0 : cell.stream()
                    .mapToDouble(s -> s.meanWhenPlaying() * s.games() / 18.0)
                    .average().orElse(0);
            wire.put(position, rate);
            System.out.printf("   wire %-3s = %s%d, tier %d -> %.1f pts/week%n", position,
                    position, rank, tier, rate);
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
                scenarios, 424_242L);

        System.out.printf("%n%nTHE TEST: what is a TENTH man worth on a full nine?%n");
        System.out.printf("(the nine is %s)%n%n", names(nine));
        System.out.printf("%-26s %-4s %6s %16s %16s%n", "TENTH MAN", "POS", "tier",
                "season totals", "starter sum");
        double baseSeason = season.of(nine);
        double baseWeekly = weekly.of(nine);
        System.out.printf("%-26s %-4s %6s %16.1f %16.1f%n", "(nobody)", "-", "-",
                baseSeason, baseWeekly);

        for(Position position : new Position[]{Position.RB, Position.WR, Position.TE,
                Position.QB}){
            for(int depth : new int[]{6, 18, 30}){
                List<String> ids = byPosition.get(position);
                if(depth >= ids.size()){
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
