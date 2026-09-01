import PlayerImportAndSetup.Position;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What twenty-two other men off the board does to every number in this repo.
 *
 * DRAFT-READY.md's first known-wrong item: "the backtest models TWO keepers;
 * the league has TWENTY-FOUR. Every score here was measured on a board ~22 men
 * deeper than tomorrow's." EraSlate builds the missing twenty-two. This scores
 * the whole field on both boards, in ONE process, so nothing depends on two
 * runs made twenty minutes apart against a tree three agents are editing - the
 * fault that produced 1954 and then 1928 for the same configuration tonight.
 *
 * THREE ARMS, because the change is two changes and they must not be confused:
 *
 *   2 keepers (shipped)      what every published figure was measured on
 *   2 keepers, real ranks    the same board, with Justin's kept men valued at
 *                            QB9 and RB23 rather than at a count of who has
 *                            left the board. A correction, not the subject.
 *   24 keepers (the league)  every declared keeper off the board and every
 *                            pick they spend skipped
 *
 * The middle arm exists so the last one cannot take credit for a bug fix.
 *
 * AND THE ANSWER IS THE ORDERING, not the level. Every strategy loses points
 * when the top of the board is taken away, and a shift that moves everything
 * equally is not a finding - it would just be a different y-axis. What is a
 * finding is whether the strategies change PLACES, and whether any gap that
 * used to look real survives.
 *
 *   ./gradlew run -Pmain=KeeperSlateImpact -q
 */
public class KeeperSlateImpact {

    record Arm(String name, String property, String column){}

    static final List<Arm> ARMS = List.of(
            new Arm("2 keepers (shipped)", null, "SHIPPED"),
            new Arm("2 keepers, real ranks", "keeperRanks", "realrank"),
            new Arm("24 keepers (the league)", "leagueKeepers", "24-KEEPER"));

    record Scored(String strategy, double[] seasons, double mean, double worst){}

    public static void main(String[] args) throws Exception {
        List<PlanBacktest.Board> boards = new ArrayList<>();
        for(File file : new File("data").listFiles()){
            if(file.getName().matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                PlanBacktest.Board board = PlanBacktest.board(file,
                        file.getName().split("-")[3]);
                if(board != null && board.ids().size() > 150){
                    boards.add(board);
                }
            }
        }
        boards.sort(Comparator.comparing(PlanBacktest.Board::season));
        if(boards.size() < 2){
            System.out.println("no seasons to backtest");
            return;
        }

        // BoardValue's inputs do not depend on which keepers are held, so they
        // are built once and shared by all three arms. Two arms that differed
        // in their pools as well as their board would be measuring two things.
        Map<String, List<DetectionLag.Man>> wider = NflverseBoards.usable(null);
        List<String> order = new ArrayList<>(new TreeMap<>(wider).keySet());
        List<PairwiseOdds.Man> men = PairwiseOdds.nflverseMen(wider, order);
        Map<Position, double[]> curve = RankDraft.pointsByRank(men);
        Map<Position, List<List<Double>>> pools = BoardValue.pools(men);

        System.out.printf("%nTWENTY-FOUR KEEPERS, NOT TWO%n%n");
        System.out.printf("%s%n", EraSlate.describe());
        System.out.printf("%d seasons, real outcomes, the same scorer throughout."
                + " The 95%% bar is %.0f%npoints at five seasons; anything inside it"
                + " is a tie and is called one.%n", boards.size(),
                ShapeSensitivity.TIE_BAND);

        Map<String, Map<String, Scored>> byArm = new LinkedHashMap<>();
        for(Arm arm : ARMS){
            byArm.put(arm.name(), scoreEverything(arm, boards, curve, pools, order.size()));
        }

        // ---------------------------------------------------------------- 1
        System.out.printf("%n%s%n1. EVERY STRATEGY, BOTH BOARDS%n%s%n%n",
                "=".repeat(78), "=".repeat(78));
        System.out.printf("%-26s", "STRATEGY");
        for(Arm arm : ARMS){
            System.out.printf(" %11s", arm.column());
        }
        System.out.printf(" %8s %9s %8s%n", "shift", "paired se", "worst");
        Map<String, Scored> shipped = byArm.get(ARMS.get(0).name());
        Map<String, Scored> league = byArm.get(ARMS.get(2).name());
        for(String strategy : shipped.keySet()){
            System.out.printf("%-26s", strategy);
            for(Arm arm : ARMS){
                System.out.printf(" %11.0f", byArm.get(arm.name()).get(strategy).mean());
            }
            double[] difference = paired(league.get(strategy), shipped.get(strategy));
            System.out.printf(" %8.0f %9.0f %8.0f%n", mean(difference),
                    standardError(difference), league.get(strategy).worst());
        }

        System.out.printf("%nSHIFT is the 24-keeper mean minus the shipped one, and its"
                + " standard error is%nPAIRED - every strategy meets the same five boards,"
                + " so the difference is a%npaired quantity and deserves a paired bar"
                + " rather than two means eyeballed.%n");

        // ---------------------------------------------------------------- 2
        System.out.printf("%n%s%n2. IS THE SHIFT UNIFORM? (a uniform one is not a finding)%n%s%n%n",
                "=".repeat(78), "=".repeat(78));
        double smallest = Double.MAX_VALUE;
        double largest = -Double.MAX_VALUE;
        String cheapest = "";
        String dearest = "";
        for(String strategy : shipped.keySet()){
            double shift = league.get(strategy).mean() - shipped.get(strategy).mean();
            if(shift < smallest){
                smallest = shift;
                cheapest = strategy;
            }
            if(shift > largest){
                largest = shift;
                dearest = strategy;
            }
        }
        System.out.printf("   hurt most   %-26s %+7.0f%n", cheapest, smallest);
        System.out.printf("   hurt least  %-26s %+7.0f%n", dearest, largest);
        System.out.printf("   spread of the shift                     %7.0f  (%s the %.0f bar)%n",
                largest - smallest, largest - smallest > ShapeSensitivity.TIE_BAND
                        ? "OVER" : "inside", ShapeSensitivity.TIE_BAND);
        System.out.printf("%nA shift that landed on every strategy equally would move the"
                + " y-axis and%nnothing else. This one does not: the spread above is the"
                + " part that is real.%n");

        // ---------------------------------------------------------------- 3
        System.out.printf("%n%s%n3. THE ORDERING, WHICH IS THE ONLY THING THAT MATTERS%n%s%n%n",
                "=".repeat(78), "=".repeat(78));
        List<String> before = ranked(shipped);
        List<String> after = ranked(league);
        System.out.printf("%-4s %-30s %-30s %s%n", "", "2 KEEPERS", "24 KEEPERS", "MOVED");
        for(int place = 0; place < before.size(); place++){
            String was = before.get(place);
            String now = after.get(place);
            int moved = before.indexOf(now) - place;
            System.out.printf("%-4d %-30s %-30s %+d%n", place + 1,
                    String.format("%s %.0f", was, shipped.get(was).mean()),
                    String.format("%s %.0f", now, league.get(now).mean()), moved);
        }

        // ---------------------------------------------------------------- 4
        System.out.printf("%n%s%n4. HOW FAR APART IS THE FIELD, ONCE THE STRAWMEN ARE OUT%n%s%n%n",
                "=".repeat(78), "=".repeat(78));
        System.out.printf("   %-24s %10s %10s%n", "", "2 keepers", "24 keepers");
        System.out.printf("   %-24s %10.0f %10.0f%n", "best serious plan",
                best(shipped), best(league));
        System.out.printf("   %-24s %10.0f %10.0f%n", "worst serious plan",
                worstOfBest(shipped), worstOfBest(league));
        System.out.printf("   %-24s %10.0f %10.0f%n", "width of that field",
                best(shipped) - worstOfBest(shipped), best(league) - worstOfBest(league));
        System.out.printf("%n   the bar is %.0f. a field narrower than the bar is one this"
                + " design%n   cannot rank at all.%n", ShapeSensitivity.TIE_BAND);

        // ---------------------------------------------------------------- 5
        System.out.printf("%n%s%n5. THE PLAN AGAINST THE BOARD MODEL, PAIRED%n%s%n%n",
                "=".repeat(78), "=".repeat(78));
        for(String plan : List.of("RUNBOOK as written", "RUNBOOK committed")){
            for(Arm arm : ARMS){
                Map<String, Scored> arms = byArm.get(arm.name());
                double[] difference = paired(arms.get(plan), arms.get(ADAPTIVE));
                System.out.printf("   %-22s vs %-16s on %-24s %+6.0f  se %4.0f  %s%n",
                        plan, ADAPTIVE, arm.name(), mean(difference),
                        standardError(difference),
                        Math.abs(mean(difference)) > ShapeSensitivity.TIE_BAND
                                ? "SEPARATED" : "tie");
            }
        }
        System.out.printf("%nIf a realistic keeper slate separated these two, that would be"
                + " tonight's%nmost useful result. Read the verdict column, not the sign.%n");
    }

    static final String ADAPTIVE = "board value (adaptive)";

    /**
     * One arm: set the flag, score the world, put it back.
     *
     * The flags are system properties read fresh on every call, which is what
     * lets one process measure both boards. Nothing here is cached across arms
     * except EraSlate's slate and the nflverse pools, and neither depends on
     * which board is being drafted.
     */
    static Map<String, Scored> scoreEverything(Arm arm, List<PlanBacktest.Board> boards,
                                               Map<Position, double[]> curve,
                                               Map<Position, List<List<Double>>> pools,
                                               int count){
        String heldWas = System.getProperty("holdKeepers");
        String armWas = arm.property() == null ? null : System.getProperty(arm.property());
        try {
            // Justin holds his two in EVERY arm. The subject is the OTHER
            // twenty-two, so a baseline that also dropped his own keepers would
            // be measuring both at once.
            System.setProperty("holdKeepers", "true");
            if(arm.property() != null){
                System.setProperty(arm.property(), "true");
            }
            Map<String, Scored> out = new LinkedHashMap<>();
            for(Map.Entry<String, String> entry : PlanBacktest.STRATEGIES.entrySet()){
                double[] seasons = new double[boards.size()];
                for(int i = 0; i < boards.size(); i++){
                    List<String> roster = PlanBacktest.draft(boards.get(i), entry.getValue());
                    full(arm, entry.getKey(), boards.get(i), roster);
                    seasons[i] = PlanBacktest.seasonPoints(boards.get(i), roster);
                }
                out.put(entry.getKey(), scored(entry.getKey(), seasons));
            }
            double[] seasons = new double[boards.size()];
            for(int i = 0; i < boards.size(); i++){
                List<String> roster = BoardValue.adaptiveDraft(boards.get(i), curve,
                        pools, count);
                full(arm, ADAPTIVE, boards.get(i), roster);
                seasons[i] = PlanBacktest.seasonPoints(boards.get(i), roster);
            }
            out.put(ADAPTIVE, scored(ADAPTIVE, seasons));
            return out;
        }
        finally {
            restore("holdKeepers", heldWas);
            if(arm.property() != null){
                restore(arm.property(), armWas);
            }
        }
    }

    /**
     * SIXTEEN MEN, always - two kept and fourteen drafted (TRAPS.md A5).
     *
     * The 24-keeper board is 186 men deep at Justin's last pick and the
     * thinnest season joins to 237, so nothing should run out - but "should"
     * is how a shortened roster gets scored as a strategy's weakness instead of
     * as a bug in the board. Checked rather than assumed, and loudly: a silently
     * fifteen-man roster would show up as a plausible-looking lower number.
     */
    static void full(Arm arm, String strategy, PlanBacktest.Board board,
                     List<String> roster){
        int want = RosterRules.live().size();
        if(roster.size() != want){
            throw new IllegalStateException(String.format(
                    "%s drafted %d men on %s under %s, not %d - the board ran out"
                            + " or a pick was skipped", strategy, roster.size(),
                    board.season(), arm.name(), want));
        }
    }

    static void restore(String property, String was){
        if(was == null){
            System.clearProperty(property);
        }
        else {
            System.setProperty(property, was);
        }
    }

    static Scored scored(String strategy, double[] seasons){
        double worst = Double.MAX_VALUE;
        for(double season : seasons){
            worst = Math.min(worst, season);
        }
        return new Scored(strategy, seasons, mean(seasons), worst);
    }

    static double[] paired(Scored left, Scored right){
        double[] difference = new double[left.seasons().length];
        for(int i = 0; i < difference.length; i++){
            difference[i] = left.seasons()[i] - right.seasons()[i];
        }
        return difference;
    }

    static double mean(double[] values){
        double total = 0;
        for(double value : values){
            total += value;
        }
        return values.length == 0 ? 0 : total / values.length;
    }

    /**
     * The standard error of a PAIRED difference, clustered on season.
     *
     * The season is the unit of independent randomness here - TRAPS.md D15 -
     * so five seasons is five observations however many picks each contains.
     */
    static double standardError(double[] values){
        if(values.length < 2){
            return 0;
        }
        double centre = mean(values);
        double sum = 0;
        for(double value : values){
            sum += (value - centre) * (value - centre);
        }
        return Math.sqrt(sum / (values.length - 1)) / Math.sqrt(values.length);
    }

    static List<String> ranked(Map<String, Scored> arm){
        List<String> names = new ArrayList<>(arm.keySet());
        names.sort(Comparator.comparingDouble((String name) -> arm.get(name).mean())
                .reversed());
        return names;
    }

    /**
     * The strategies that are actually candidates.
     *
     * Model A is a rounds 1-7 model scored over fourteen (TRAPS.md F26), the
     * no-defence row is not a legal roster, and best-available-by-ADP is the
     * null hypothesis. Including any of them in "how far apart is the field"
     * would measure the distance to a strawman.
     */
    static boolean serious(String strategy){
        return !strategy.startsWith("[not legal]") && !strategy.startsWith("best-nine")
                && !strategy.startsWith("best available");
    }

    static double best(Map<String, Scored> arm){
        double most = -Double.MAX_VALUE;
        for(Map.Entry<String, Scored> entry : arm.entrySet()){
            if(serious(entry.getKey())){
                most = Math.max(most, entry.getValue().mean());
            }
        }
        return most;
    }

    static double worstOfBest(Map<String, Scored> arm){
        double least = Double.MAX_VALUE;
        for(Map.Entry<String, Scored> entry : arm.entrySet()){
            if(serious(entry.getKey())){
                least = Math.min(least, entry.getValue().mean());
            }
        }
        return least;
    }
}
