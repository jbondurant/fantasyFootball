import PlayerImportAndSetup.Position;
import java.util.*;

/**
 * IS THE BOARD ITSELF SANE, a few hours before the draft?
 *
 * Every model here is downstream of two numbers per player: a projection and an
 * ADP. Both are fetched and cached, and neither has ever been checked for the
 * failure that matters on draft day - STALE DATA. A man who tore something in
 * August still carries an August projection if the feed has not caught up, and
 * the model will happily recommend him. Nothing in the repo would notice,
 * because nothing in the repo looks at a player and asks whether the two
 * numbers about him agree.
 *
 * They should agree loosely: a man drafted early is projected well, and a man
 * projected well goes early. Where they disagree VIOLENTLY, one of them is
 * wrong, and it is worth a human looking before the pick rather than after.
 *
 *   ./gradlew run -Pmain=BoardSanity -Pkeepers=Tuten,Purdy -q
 */
public class BoardSanity {
    public static void main(String[] args) throws Exception {
        LiveSetup setup = LiveSetup.forTonight();
        DraftPlanner planner = setup.planner;
        Set<String> kept = setup.kept;

        // Rank every draftable man by projection and by ADP, within position.
        Map<Position, List<String>> byProjection = new EnumMap<>(Position.class);
        for(Map.Entry<String, Double> entry : planner.points().entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player == null || kept.contains(entry.getKey())){
                continue;
            }
            double adp = SleeperProjections.adpOf(entry.getKey());
            if(adp <= 0 || adp > 200){
                continue;               // not really on this board
            }
            byProjection.computeIfAbsent(player.position, u -> new ArrayList<>())
                    .add(entry.getKey());
        }

        List<String> loud = new ArrayList<>();
        int checked = 0;

        // THE MOST DIRECT STALENESS SIGNAL: a man the room drafts early who
        // carries no projection at all, or a nonsensical one. Rank comparisons
        // can miss this - if a whole position's projections vanished, every
        // rank within it still looks orderly.
        int earlyMen = 0;
        for(String id : new TreeSet<>(planner.points().keySet())){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player == null || kept.contains(id)){
                continue;
            }
            double adp = SleeperProjections.adpOf(id);
            double points = planner.points().getOrDefault(id, 0.0);
            if(adp > 0 && adp <= 120){
                earlyMen++;
            }
            if(adp > 0 && adp <= 120 && points <= 1.0){
                loud.add(String.format("%-4s %-24s ADP %.0f but projected %.1f points"
                                + " - NO USABLE PROJECTION",
                        player.position, player.firstName + " " + player.lastName,
                        adp, points));
            }
        }
        for(Map.Entry<Position, List<String>> entry : byProjection.entrySet()){
            List<String> men = entry.getValue();
            List<String> byPoints = new ArrayList<>(men);
            byPoints.sort(Comparator.comparingDouble(
                    (String id) -> planner.points().getOrDefault(id, 0.0)).reversed());
            List<String> byAdp = new ArrayList<>(men);
            byAdp.sort(Comparator.comparingDouble(SleeperProjections::adpOf));
            Map<String, Integer> projectionRank = new HashMap<>();
            Map<String, Integer> adpRank = new HashMap<>();
            for(int i = 0; i < byPoints.size(); i++){
                projectionRank.put(byPoints.get(i), i + 1);
            }
            for(int i = 0; i < byAdp.size(); i++){
                adpRank.put(byAdp.get(i), i + 1);
            }
            for(String id : men){
                checked++;
                int projection = projectionRank.get(id);
                int adp = adpRank.get(id);
                int gap = Math.abs(projection - adp);
                // A man the room drafts inside the top twelve of his position
                // while the projections rank him outside the top forty (or the
                // reverse) is not a difference of opinion, it is a data problem.
                boolean drafted = adp <= 12;
                boolean projected = projection <= 12;
                if((drafted && projection > 40) || (projected && adp > 40)){
                    Player who = Player.getPlayerFromSIDV2(id);
                    loud.add(String.format("%-4s %-24s ADP rank %3d, projection rank %3d"
                                    + "  (%.0f pts, ADP %.0f)",
                            entry.getKey(), who.firstName + " " + who.lastName,
                            adp, projection, planner.points().getOrDefault(id, 0.0),
                            SleeperProjections.adpOf(id)));
                }
            }
        }

        // SAY WHAT WAS ACTUALLY EXAMINED. A check that silently looks at
        // nothing reports "clean" exactly as loudly as one that looked at
        // everything.
        System.out.printf("%n%d draftable men rank-checked, %d of them inside ADP 120"
                + " checked for a missing projection.%n%s%n%n",
                checked, earlyMen, setup.rule());
        if(earlyMen < 50){
            throw new IllegalStateException("only " + earlyMen + " men inside ADP 120 -"
                    + " the board is not loaded, so 'clean' would mean nothing");
        }
        if(loud.isEmpty()){
            System.out.printf("NOTHING VIOLENTLY INCONSISTENT. No man is drafted inside%n"
                    + "the top twelve of his position while projected outside the top%n"
                    + "forty, or the reverse. That is not proof the projections are%n"
                    + "right - it is proof they and the room are telling the same%n"
                    + "story, which is what a stale feed would break.%n");
        }
        else {
            System.out.printf("*** %d MEN WHERE THE ROOM AND THE PROJECTIONS DISAGREE"
                    + " VIOLENTLY:%n", loud.size());
            for(String line : loud){
                System.out.printf("   *** %s%n", line);
            }
            System.out.printf("%nOne of the two numbers is wrong for each of these. If it%n"
                    + "is the projection - an injury the feed has not caught - the model%n"
                    + "will recommend him and be badly wrong. Worth a look before the%n"
                    + "draft, not after.%n");
        }
    }
}
