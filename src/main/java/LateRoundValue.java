import PlayerImportAndSetup.Position;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rounds 10-16: the seven picks the nine-round objective is silent about,
 * and the least-analysed part of the whole draft despite the fact that TUTEN
 * CAME FROM ROUND 12 and became the most valuable keeper in the league.
 *
 * Under Model A a late pick is worth ~0 this season - it cannot crack the
 * starting nine. Its value is an OPTION on next year: a player taken in
 * round R is keepable in 2027 at round R, so a round-13 stash who breaks out
 * is a round-13 price on a first-round asset. That is exactly the Tuten
 * trade, and it is worth measuring rather than guessing.
 *
 * Measured from five seasons of the league's own drafts joined to actual
 * outcomes: for every historical pick in rounds 10-16, what did he score the
 * FOLLOWING season, and would keeping him at his draft round have been
 * profitable? The hit rate, by position and by attribute, is the base rate
 * this year's late picks should be ranked against.
 *
 *   ./gradlew run -Pmain=LateRoundValue
 */
public class LateRoundValue {

    /** A late pick and what became of him. */
    record Stash(String name, Position position, int round, String season,
                 double nextSeasonPoints, boolean rookie, boolean young){}

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        List<JsonArray> drafts = configuration.getPreviousDraftPicks();
        List<String> seasons = configuration.getPreviousSeasons();

        List<Stash> stashes = new ArrayList<>();
        Map<String, Map<Position, Double>> starterBar = new HashMap<>();
        for(int i = 0; i < drafts.size() && i < seasons.size(); i++){
            String season = seasons.get(i);
            if(season == null){
                continue;
            }
            String nextSeason = String.valueOf(Integer.parseInt(season) + 1);
            Map<String, Double> nextActuals;
            try {
                nextActuals = HistoricalActuals.pointsBySleeperID(nextSeason);
            }
            catch(Exception missing){
                continue;
            }
            if(nextActuals.isEmpty()){
                continue;
            }
            // the bar a keeper must clear: what a starter at that position
            // actually scored the following year (12-team, so QB12/RB24/WR36/TE12)
            Map<Position, Double> bar = new EnumMap<>(Position.class);
            Map<Position, List<Double>> byPosition = new EnumMap<>(Position.class);
            for(Map.Entry<String, Double> entry : nextActuals.entrySet()){
                Player player = Player.getPlayerFromSIDV2(entry.getKey());
                if(player != null && StartingLineup.isSkillPosition(player.position)){
                    byPosition.computeIfAbsent(player.position, u -> new ArrayList<>())
                            .add(entry.getValue());
                }
            }
            int[] ranks = {12, 24, 36, 12};
            Position[] positions = {Position.QB, Position.RB, Position.WR, Position.TE};
            for(int p = 0; p < positions.length; p++){
                List<Double> values = byPosition.getOrDefault(positions[p],
                        new ArrayList<>());
                values.sort(Comparator.reverseOrder());
                bar.put(positions[p], values.size() > ranks[p]
                        ? values.get(ranks[p] - 1) : 0.0);
            }
            starterBar.put(season, bar);

            java.util.Set<String> rookies = HistoricalProjections.rookiesForSeason(
                    configuration, season);
            java.util.Set<String> young = HistoricalProjections.youngForSeason(
                    configuration, season, 2);
            for(JsonElement element : drafts.get(i)){
                JsonObject pick = element.getAsJsonObject();
                int round = pick.get("round").getAsInt();
                JsonElement keeper = pick.get("is_keeper");
                if(round < 10 || round > 16
                        || (keeper != null && !keeper.isJsonNull()
                            && keeper.getAsBoolean())){
                    continue;
                }
                String id = pick.get("player_id").getAsString();
                Player player = Player.getPlayerFromSIDV2(id);
                if(player == null || !StartingLineup.isSkillPosition(player.position)){
                    continue;
                }
                stashes.add(new Stash(player.firstName + " " + player.lastName,
                        player.position, round, season,
                        nextActuals.getOrDefault(id, 0.0), rookies.contains(id),
                        young.contains(id)));
            }
        }

        System.out.printf("%d late picks (rounds 10-16) across %d seasons, joined to "
                + "the FOLLOWING season's actual points.%n%n", stashes.size(),
                starterBar.size());

        // hit rate by position
        System.out.printf("%-6s %6s %10s %10s %10s%n", "POS", "n", "hit rate",
                "mean next", "best next");
        for(Position position : new Position[]{Position.QB, Position.RB, Position.WR,
                Position.TE}){
            List<Stash> group = stashes.stream()
                    .filter(s -> s.position() == position).toList();
            if(group.isEmpty()){
                continue;
            }
            long hits = group.stream().filter(s ->
                    s.nextSeasonPoints() >= starterBar.get(s.season())
                            .getOrDefault(position, 0.0)).count();
            double mean = group.stream().mapToDouble(Stash::nextSeasonPoints)
                    .average().orElse(0);
            double best = group.stream().mapToDouble(Stash::nextSeasonPoints)
                    .max().orElse(0);
            System.out.printf("%-6s %6d %9.0f%% %10.1f %10.1f%n", position, group.size(),
                    100.0 * hits / group.size(), mean, best);
        }

        // hit rate by attribute
        System.out.printf("%n%-18s %6s %10s %10s%n", "GROUP", "n", "hit rate", "mean next");
        attribute("rookies", stashes.stream().filter(Stash::rookie).toList(), starterBar);
        attribute("young (<=2yr)", stashes.stream().filter(Stash::young).toList(),
                starterBar);
        attribute("veterans", stashes.stream().filter(s -> !s.young()).toList(),
                starterBar);
        attribute("rounds 10-12", stashes.stream().filter(s -> s.round() <= 12).toList(),
                starterBar);
        attribute("rounds 13-16", stashes.stream().filter(s -> s.round() >= 13).toList(),
                starterBar);

        System.out.printf("%nthe ten best late stashes this league ever made:%n");
        stashes.stream()
                .sorted(Comparator.comparingDouble(Stash::nextSeasonPoints).reversed())
                .limit(10)
                .forEach(s -> System.out.printf("   %-24s %-4s r%-3d %s -> %.1f pts in %d%n",
                        s.name(), s.position(), s.round(), s.season(),
                        s.nextSeasonPoints(), Integer.parseInt(s.season()) + 1));
        System.out.println("\n'hit' = scored at or above a starter's line the FOLLOWING"
                + "\nseason (QB12/RB24/WR36/TE12), i.e. would have been worth keeping.");
    }

    static void attribute(String label, List<Stash> group,
                          Map<String, Map<Position, Double>> bar){
        if(group.size() < 5){
            System.out.printf("%-18s %6d   (too few)%n", label, group.size());
            return;
        }
        long hits = group.stream().filter(s -> s.nextSeasonPoints()
                >= bar.get(s.season()).getOrDefault(s.position(), 0.0)).count();
        System.out.printf("%-18s %6d %9.0f%% %10.1f%n", label, group.size(),
                100.0 * hits / group.size(),
                group.stream().mapToDouble(Stash::nextSeasonPoints).average().orElse(0));
    }
}
