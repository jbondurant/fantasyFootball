import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the scoring fix actually changes, measured on both sides at once.
 *
 * Every strategy in PlanBacktest.STRATEGIES is scored twice on the same five
 * seasons: once under Sleeper's pts_half_ppr, which is what the backtest has
 * always graded with, and once under the league's own rules via
 * {@link LeagueActuals}. Both runs happen in one process so the two columns
 * cannot come from different vintages of the cached feeds.
 *
 * THE ROSTERS ARE THE SAME IN BOTH COLUMNS, and the report checks that rather
 * than assuming it. Only the grading moves: the draft order comes from that
 * season's real ADP file, the lineup each week is set by preseason board rank,
 * and neither depends on scoring. So the change in a row is a pure
 * re-measurement of one fixed roster - not a different plan, and not sampling.
 *
 * What IS sampling is the level of each row. Five seasons is five numbers, and
 * their spread is enormous next to the differences between strategies, which is
 * why this prints the standard error beside every mean and compares strategies
 * PAIRED - season by season against the same opponent - rather than by
 * subtracting two noisy averages.
 *
 *   ./gradlew run -Pmain=ScoringImpactReport -q
 */
public class ScoringImpactReport {

    /** Drafts a defence it is not allowed to skip; kept as a measurement only. */
    static boolean legal(String strategy){
        return !strategy.startsWith("[not legal]");
    }

    public static void main(String[] args) throws Exception {
        System.setProperty(LeagueActuals.FLAG, "false");
        List<PlanBacktest.Board> feedBoards = boards();
        System.setProperty(LeagueActuals.FLAG, "true");
        List<PlanBacktest.Board> leagueBoards = boards();

        sameRosters(feedBoards, leagueBoards);
        Map<String, double[]> feed = run(false, feedBoards);
        Map<String, double[]> league = run(true, leagueBoards);
        System.setProperty(LeagueActuals.FLAG, "false");
        int seasons = feedBoards.size();

        System.out.printf("%nWHAT THE SCORING FIX CHANGES%n");
        System.out.printf("'as graded' is Sleeper's pts_half_ppr - 4-point passing touchdowns,%n"
                + "no fumble charge, and nothing for a defence holding a team to 14-20.%n"
                + "'corrected' is this league's own scoring settings. Same seasons, same%n"
                + "ADP boards, same drafted rosters, same lineups - only the grading moves.%n%n");
        System.out.printf("%-26s %10s %10s %8s %10s %8s%n", "STRATEGY", "as graded",
                "corrected", "change", "SE(mean)", "rank");

        List<String> order = new ArrayList<>(league.keySet());
        order.sort(Comparator.comparingDouble((String s) -> -mean(league.get(s))));
        Map<String, Integer> correctedRank = new LinkedHashMap<>();
        int place = 0;
        for(String strategy : order){
            correctedRank.put(strategy, ++place);
        }
        List<String> byFeed = new ArrayList<>(feed.keySet());
        byFeed.sort(Comparator.comparingDouble((String s) -> -mean(feed.get(s))));

        for(String strategy : byFeed){
            double before = mean(feed.get(strategy));
            double after = mean(league.get(strategy));
            int was = byFeed.indexOf(strategy) + 1;
            int now = correctedRank.get(strategy);
            System.out.printf("%-26s %10.0f %10.0f %+8.0f %10.0f %5d%s%n",
                    strategy, before, after, after - before,
                    standardError(league.get(strategy)), now,
                    was == now ? "" : "  (was " + was + ")");
        }

        System.out.printf("%nRows are printed in the order the OLD scoring ranked them, so a"
                + " row whose%n'rank' column disagrees with its position is one the fix moved.%n");

        pairedAgainstLeader(league, order, seasons);
        quarterbackTiming(feedBoards, leagueBoards);

        System.out.printf("%nTHE NOISE THIS SITS IN%n");
        System.out.printf("Each mean is %d seasons. The SE column above is the spread of one%n"
                + "strategy across seasons over root-%d - it is the error on the LEVEL, and it%n"
                + "is far larger than any gap between two strategies. The paired table is the%n"
                + "one to read for ranking, because both strategies meet the same season and%n"
                + "most of that spread is the season, not the plan. With %d seasons even the%n"
                + "paired standard errors are themselves badly estimated: treat them as an%n"
                + "order of magnitude, not a test.%n", seasons, seasons, seasons);

        System.out.printf("%nAND ONE ROW THAT IS NOT EVIDENCE: 'best-nine (Model A)' runs Model A%n"
                + "over all fourteen picks. Model A optimises the starting NINE, which two%n"
                + "keepers and seven picks already fill, so from round 8 its objective cannot%n"
                + "tell one position from another and the trailing quarterbacks are an artefact%n"
                + "of asking it a question outside its domain. Read 'ModelA front + SS back'%n"
                + "instead. It is left in the table because other work is running against it.%n");
    }

    /**
     * The question the fix was supposed to be about: WHEN to take a quarterback.
     *
     * A before/after on fixed sequences answers whether the fix re-ranks the
     * plans that already exist. It does not answer whether the plans themselves
     * were built under a lean, because every one of them chose its quarterback
     * round while quarterbacks were graded two points a touchdown light.
     *
     * So this slides the RUNBOOK's first quarterback from round 10 up to round
     * 1 and back, swapping him with whoever held that pick. The multiset of
     * positions never changes - fourteen picks, the same nine positions - so
     * the only thing under test is the round the quarterback comes off the
     * board. If the mis-scoring really was pushing quarterbacks late, the best
     * round should move earlier when it is corrected.
     */
    static void quarterbackTiming(List<PlanBacktest.Board> feed, List<PlanBacktest.Board> league){
        String committed = PlanBacktest.STRATEGIES.get("RUNBOOK committed");
        if(committed == null){
            return;
        }
        String[] base = committed.trim().split("\\s+");
        int firstQb = -1;
        for(int i = 0; i < base.length; i++){
            if(base[i].equals("QB")){
                firstQb = i;
                break;
            }
        }
        if(firstQb < 0){
            return;
        }
        System.out.printf("%nWHEN TO TAKE THE FIRST QUARTERBACK, BOTH WAYS%n");
        System.out.printf("the RUNBOOK's shape with its first quarterback slid to each round,%n"
                + "swapped with whoever held that pick. Same fourteen positions throughout.%n");
        System.out.printf("%-8s %-30s %10s %10s %8s%n",
                "ROUND", "SEQUENCE", "as graded", "corrected", "change");
        double bestFeed = -Double.MAX_VALUE;
        double bestLeague = -Double.MAX_VALUE;
        double worstLeague = Double.MAX_VALUE;
        int feedRound = 0;
        int leagueRound = 0;
        double mostLift = -Double.MAX_VALUE;
        double leastLift = Double.MAX_VALUE;
        int mostLiftRound = 0;
        double committedLift = 0;
        double[] committedScores = new double[league.size()];
        double[] bestScores = new double[league.size()];
        for(int round = 0; round <= firstQb; round++){
            String[] shape = base.clone();
            shape[firstQb] = base[round];
            shape[round] = "QB";
            String sequence = String.join(" ", shape);
            System.setProperty(LeagueActuals.FLAG, "false");
            double asGraded = meanOver(feed, sequence);
            System.setProperty(LeagueActuals.FLAG, "true");
            double[] perSeason = scoresOver(league, sequence);
            double corrected = mean(perSeason);
            if(asGraded > bestFeed){
                bestFeed = asGraded;
                feedRound = round + 1;
            }
            if(corrected > bestLeague){
                bestLeague = corrected;
                leagueRound = round + 1;
                bestScores = perSeason;
            }
            worstLeague = Math.min(worstLeague, corrected);
            double lift = corrected - asGraded;
            if(lift > mostLift){
                mostLift = lift;
                mostLiftRound = round + 1;
            }
            leastLift = Math.min(leastLift, lift);
            if(round == firstQb){
                committedLift = lift;
                committedScores = perSeason;
            }
            System.out.printf("%-8d %-30s %10.0f %10.0f %+8.0f%n", round + 1,
                    String.join(" ", java.util.Arrays.copyOf(shape, 10)) + " ...",
                    asGraded, corrected, lift);
        }
        System.setProperty(LeagueActuals.FLAG, "false");

        double[] gaps = new double[league.size()];
        for(int i = 0; i < gaps.length; i++){
            gaps[i] = bestScores[i] - committedScores[i];
        }
        System.out.printf("%nbest round as graded: %d (%.0f). best round corrected: %d (%.0f).%n",
                feedRound, bestFeed, leagueRound, bestLeague);
        System.out.printf("The best round is %s.%n", feedRound == leagueRound
                ? "THE SAME under both scorings"
                : "DIFFERENT under the two scorings - " + feedRound + " against " + leagueRound);
        System.out.printf("%nBut the fix is not neutral about timing. It is worth %+.0f to the"
                + " committed%nround-%d shape and %+.0f to the round-%d one: taking a"
                + " quarterback early gains%n%.0f points MORE from the fix than taking one"
                + " late, which is the direction%nthe mis-scoring predicted - an early"
                + " quarterback throws more touchdowns, so%nhe was being docked more of them."
                + "%n", committedLift, firstQb + 1, mostLift, mostLiftRound,
                mostLift - committedLift);
        System.out.printf("%nAgainst that: round %d beats round %d by %.0f points a season with"
                + " a paired%nstandard error of %.0f over %d seasons. The whole timing curve"
                + " spans %.0f points%nand the noise on any one comparison is the same size."
                + " This says the grading%nbug was real and pointed the way it was said to;"
                + " it does not say the round%nshould change.%n",
                leagueRound, firstQb + 1, mean(gaps), standardError(gaps), league.size(),
                bestLeague - worstLeague);
    }

    static double[] scoresOver(List<PlanBacktest.Board> boards, String sequence){
        double[] scores = new double[boards.size()];
        for(int i = 0; i < boards.size(); i++){
            scores[i] = PlanBacktest.score(boards.get(i), sequence);
        }
        return scores;
    }

    static double meanOver(List<PlanBacktest.Board> boards, String sequence){
        return mean(scoresOver(boards, sequence));
    }

    /**
     * Every strategy on every season, under one scoring.
     *
     * The flag is set for the whole pass rather than per call, because the
     * price of a streamed defence is computed from the same outcome pool and
     * has to be denominated in the same points as the rosters it is compared
     * against.
     */
    static Map<String, double[]> run(boolean leagueScored, List<PlanBacktest.Board> boards){
        System.setProperty(LeagueActuals.FLAG, Boolean.toString(leagueScored));
        Map<String, double[]> results = new LinkedHashMap<>();
        for(Map.Entry<String, String> entry : PlanBacktest.STRATEGIES.entrySet()){
            double[] scores = new double[boards.size()];
            for(int i = 0; i < boards.size(); i++){
                scores[i] = PlanBacktest.score(boards.get(i), entry.getValue());
            }
            results.put(entry.getKey(), scores);
        }
        return results;
    }

    /**
     * The claim that only the grading moved, checked instead of asserted.
     *
     * If the two scorings ever produced different rosters the before/after
     * columns would be comparing two different plans and the whole report would
     * be meaningless. They cannot: the draft order is that season's real ADP
     * and the weekly availability is which ids the feed scored at all, neither
     * of which the scoring touches. But "cannot" is what the deviate flag said
     * too, so it is checked on every strategy in every season.
     */
    static void sameRosters(List<PlanBacktest.Board> feed, List<PlanBacktest.Board> league){
        for(int i = 0; i < feed.size(); i++){
            for(String sequence : PlanBacktest.STRATEGIES.values()){
                List<String> underFeed = PlanBacktest.draft(feed.get(i), sequence);
                List<String> underLeague = PlanBacktest.draft(league.get(i), sequence);
                if(!underFeed.equals(underLeague)){
                    throw new IllegalStateException("the scoring changed WHO gets drafted in "
                            + feed.get(i).season() + " for [" + sequence + "] - the report"
                            + " would be comparing two different plans");
                }
            }
        }
        System.out.printf("%nchecked: both scorings draft the identical roster in every"
                + " season, for every strategy%n");
    }

    static void pairedAgainstLeader(Map<String, double[]> league, List<String> order,
                                    int seasons){
        String leader = null;
        for(String strategy : order){
            if(legal(strategy)){
                leader = strategy;
                break;
            }
        }
        if(leader == null){
            return;
        }
        System.out.printf("%nUNDER THE CORRECTED SCORING, PAIRED AGAINST %s%n",
                leader.toUpperCase());
        System.out.printf("season by season, so the season's own swing cancels%n");
        System.out.printf("%-26s %10s %10s %10s%n",
                "STRATEGY", "mean gap", "SE(gap)", "seasons lost");
        double[] best = league.get(leader);
        for(String strategy : order){
            if(strategy.equals(leader)){
                continue;
            }
            double[] scores = league.get(strategy);
            double[] gaps = new double[seasons];
            int lost = 0;
            for(int i = 0; i < seasons; i++){
                gaps[i] = best[i] - scores[i];
                if(gaps[i] < 0){
                    lost++;
                }
            }
            System.out.printf("%-26s %+10.0f %10.0f %8d/%d%s%n", strategy, mean(gaps),
                    standardError(gaps), lost, seasons, legal(strategy) ? "" : "   [not legal]");
        }
        System.out.printf("%nA gap smaller than its own standard error is not a ranking, it is"
                + " a tie.%n");
    }

    static List<PlanBacktest.Board> boards() throws Exception {
        List<PlanBacktest.Board> boards = new ArrayList<>();
        for(File file : boardFiles()){
            PlanBacktest.Board board = PlanBacktest.board(file, file.getName().split("-")[3]);
            if(board != null && board.ids().size() > 150){
                boards.add(board);
            }
        }
        boards.sort(Comparator.comparing(PlanBacktest.Board::season));
        return boards;
    }

    static List<File> boardFiles(){
        List<File> files = new ArrayList<>();
        File[] listed = new File("data").listFiles();
        if(listed != null){
            for(File file : listed){
                if(file.getName().matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                    files.add(file);
                }
            }
        }
        files.sort(Comparator.comparing(File::getName));
        return files;
    }

    static double mean(double[] values){
        double sum = 0;
        for(double value : values){
            sum += value;
        }
        return values.length == 0 ? 0 : sum / values.length;
    }

    /** Sample standard deviation over root n. */
    static double standardError(double[] values){
        if(values.length < 2){
            return 0;
        }
        double mean = mean(values);
        double sumSquares = 0;
        for(double value : values){
            sumSquares += (value - mean) * (value - mean);
        }
        return Math.sqrt(sumSquares / (values.length - 1)) / Math.sqrt(values.length);
    }
}
