import PlayerImportAndSetup.Position;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What do the models actually do after round 7, and does any of it matter?
 *
 * Rounds 1-7 fill the starting nine and every strategy spends them similarly:
 * backs and receivers, in some order. The back half is where they disagree, and
 * where nobody has ever checked whether the disagreement is worth anything.
 *
 * Two questions, both answered on real outcomes:
 *
 *   what each strategy CHOOSES from round 8 on, side by side;
 *   how much of the season those picks actually delivered - counted as the
 *   points they scored in weeks they genuinely started, with the lineup set by
 *   preseason rank so nothing is chosen with hindsight.
 *
 *   ./gradlew run -Pmain=LateHalf
 */
public class LateHalf {

    /** The first seven picks fill the starting nine; everything after is the back half. */
    static final int EARLY_PICKS = 7;

    public static void main(String[] args) throws Exception {
        Map<String, PlanBacktest.Board> boards = new LinkedHashMap<>();
        for(File file : new File("data").listFiles()){
            if(file.getName().matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                String season = file.getName().split("-")[3];
                PlanBacktest.Board board = PlanBacktest.board(file, season);
                if(board != null && board.ids().size() > 150){
                    boards.put(season, board);
                }
            }
        }
        List<String> seasons = new ArrayList<>(boards.keySet());
        seasons.sort(Comparator.naturalOrder());

        System.out.printf("%nWHAT EACH STRATEGY DOES FROM ROUND 8 ON%n%n");
        System.out.printf("%-24s %-28s %s%n", "STRATEGY", "rounds 1-7", "rounds 8-16");
        for(Map.Entry<String, String> entry : PlanBacktest.STRATEGIES.entrySet()){
            if(entry.getValue() == null){
                System.out.printf("%-24s %-28s %s%n", entry.getKey(),
                        "(whatever ADP says)", "(whatever ADP says)");
                continue;
            }
            String[] tokens = entry.getValue().split("\\s+");
            System.out.printf("%-24s %-28s %s%n", entry.getKey(),
                    String.join(" ", java.util.Arrays.copyOfRange(tokens, 0, EARLY_PICKS)),
                    String.join(" ", java.util.Arrays.copyOfRange(tokens, EARLY_PICKS,
                            tokens.length)));
        }

        System.out.printf("%n%nAND WHAT THE BACK HALF ACTUALLY DELIVERED%n");
        System.out.printf("(points scored in weeks those players genuinely started,"
                + " lineup by preseason rank)%n%n");
        System.out.printf("%-24s %10s %12s %10s %12s%n", "STRATEGY", "season",
                "from r8-16", "share", "starts r8-16");
        for(Map.Entry<String, String> entry : PlanBacktest.STRATEGIES.entrySet()){
            double season = 0;
            double late = 0;
            double starts = 0;
            for(String key : seasons){
                double[] split = split(boards.get(key), entry.getValue());
                season += split[0];
                late += split[1];
                starts += split[2];
            }
            int n = seasons.size();
            System.out.printf("%-24s %10.0f %12.0f %9.0f%% %12.0f%n", entry.getKey(),
                    season / n, late / n, 100 * late / Math.max(1, season), starts / n);
        }

        System.out.println("\nThe back half is nine of fourteen picks. If its share of"
                + " the season is small,\nthen the rounds everybody argues about are the"
                + " rounds that matter least - and\nthe tight end and defence questions"
                + " are being asked in the cheapest part of\nthe draft.");
    }

    /** {season points, points from picks 8+, starts by picks 8+}. */
    static double[] split(PlanBacktest.Board board, String sequence){
        List<Position> wanted = new ArrayList<>();
        if(sequence != null){
            for(String token : sequence.split("\\s+")){
                wanted.add(Position.valueOf(token));
            }
        }
        Set<String> gone = new HashSet<>();
        List<String> mine = new ArrayList<>();
        Set<Integer> myPicks = new HashSet<>();
        for(int pick : PlanBacktest.MY_PICKS){
            myPicks.add(pick);
        }
        int taken = 0;
        for(int pick = 1; pick <= 200 && taken < PlanBacktest.MY_PICKS.length; pick++){
            if(myPicks.contains(pick)){
                String choice = wanted.isEmpty()
                        ? PlanBacktest.bestAvailable(board, gone, null)
                        : PlanBacktest.bestAvailable(board, gone, wanted.get(taken));
                if(choice == null){
                    choice = PlanBacktest.bestAvailable(board, gone, null);
                }
                if(choice != null){
                    mine.add(choice);
                    gone.add(choice);
                }
                taken++;
            }
            else {
                String other = PlanBacktest.bestAvailableSkill(board, gone);
                if(other != null){
                    gone.add(other);
                }
            }
        }
        Set<String> latePicks = new HashSet<>(
                mine.subList(Math.min(EARLY_PICKS, mine.size()), mine.size()));

        Map<String, Integer> boardRank = new HashMap<>();
        for(int i = 0; i < board.ids().size(); i++){
            boardRank.put(board.ids().get(i), i);
        }
        double total = 0;
        double late = 0;
        double starts = 0;
        for(int week = 0; week < WeeklyActuals.WEEKS; week++){
            Map<String, Double> points = board.weekly().get(week);
            Map<Position, List<String>> up = new EnumMap<>(Position.class);
            for(String id : mine){
                if(points.get(id) != null){
                    up.computeIfAbsent(board.positionOf().get(id),
                            u -> new ArrayList<>()).add(id);
                }
            }
            for(List<String> ids : up.values()){
                ids.sort(Comparator.comparingInt(
                        id -> boardRank.getOrDefault(id, Integer.MAX_VALUE)));
            }
            List<String> started = new ArrayList<>();
            List<String> flex = new ArrayList<>();
            take(up.get(Position.QB), 1, started, null);
            take(up.get(Position.RB), 2, started, flex);
            take(up.get(Position.WR), 3, started, flex);
            take(up.get(Position.TE), 1, started, flex);
            take(up.get(Position.DEF), 1, started, null);
            flex.sort(Comparator.comparingInt(
                    id -> boardRank.getOrDefault(id, Integer.MAX_VALUE)));
            for(int slot = 0; slot < 2 && slot < flex.size(); slot++){
                started.add(flex.get(slot));
            }
            for(String id : started){
                double scored = points.getOrDefault(id, 0.0);
                total += scored;
                if(latePicks.contains(id)){
                    late += scored;
                    starts++;
                }
            }
        }
        return new double[]{total, late, starts};
    }

    static void take(List<String> available, int slots, List<String> started,
                     List<String> flex){
        int size = available == null ? 0 : available.size();
        for(int slot = 0; slot < slots && slot < size; slot++){
            started.add(available.get(slot));
        }
        if(flex != null){
            for(int extra = slots; extra < size; extra++){
                flex.add(available.get(extra));
            }
        }
    }
}
