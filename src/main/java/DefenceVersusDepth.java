import PlayerImportAndSetup.Position;
import java.io.File;
import java.util.*;

/**
 * Justin's question: "aren't the odds of my round 8,9,10 bench picks having
 * more points than my starters better than the advantage I gain by drafting a
 * starting defense at 8 instead of 13-16?"
 *
 * It is a trade, so both halves have to be on one scale. Spending round 8 on a
 * defence buys a better defence and costs a skill pick; BenchValue already
 * measured the skill side from what this league really did - 44.0 points over
 * the wire for a rounds 8-9 pick against 31.2 for a rounds 13-16 pick. This
 * measures the other half: what an EARLIER defence is actually worth.
 *
 * The whole question turns on whether preseason ordering of defences predicts
 * anything. If it does not, taking one early buys nothing at all, because the
 * defence you would have had at round 16 is as good in expectation. So this
 * does not assume it - it prints realised points by preseason ADP rank and
 * lets the gradient, or its absence, be the answer.
 *
 *   ./gradlew run -Pmain=DefenceVersusDepth
 */
public class DefenceVersusDepth {

    /** From BenchValue, measured on this league's own rounds 8-16 picks. */
    static final double SKILL_ROUNDS_8_9 = 44.0;
    static final double SKILL_ROUNDS_13_16 = 31.2;

    public static void main(String[] args) throws Exception {
        List<PlanBacktest.Board> boards = new ArrayList<>();
        for(File file : new File("data").listFiles()){
            if(file.getName().matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                boards.add(PlanBacktest.board(file, file.getName().split("-")[3]));
            }
        }
        boards.sort(Comparator.comparing(PlanBacktest.Board::season));

        System.out.printf("%nWHAT AN EARLIER DEFENCE ACTUALLY BUYS%n%n");
        System.out.printf("%-8s", "SEASON");
        String[] bands = {"DEF1-3", "DEF4-6", "DEF7-9", "DEF10-12"};
        for(String band : bands){
            System.out.printf(" %9s", band);
        }
        System.out.printf("  %9s%n", "SPREAD");

        double[] bandTotals = new double[4];
        int seasons = 0;
        List<double[]> perSeason = new ArrayList<>();
        for(PlanBacktest.Board board : boards){
            // banded by the SOURCE's defence order (TRAPS #80): a defence that
            // failed the name join holds its rank instead of promoting the next
            double[] band = new double[4];
            int[] counted = new int[4];
            for(String id : board.ids()){
                if(board.positionOf().get(id) != Position.DEF){
                    continue;
                }
                int rank = board.rankOf().getOrDefault(id, Integer.MAX_VALUE);
                if(rank >= 12){
                    continue;
                }
                double total = 0;
                for(Map<String, Double> week : board.weekly()){
                    total += week.getOrDefault(id, 0.0);
                }
                band[rank / 3] += total;
                counted[rank / 3]++;
            }
            System.out.printf("%-8s", board.season());
            double best = -1e9;
            double worst = 1e9;
            for(int b = 0; b < 4; b++){
                double mean = counted[b] == 0 ? 0 : band[b] / counted[b];
                band[b] = mean;
                bandTotals[b] += mean;
                best = Math.max(best, mean);
                worst = Math.min(worst, mean);
                System.out.printf(" %9.1f", mean);
            }
            System.out.printf("  %9.1f%n", best - worst);
            perSeason.add(band);
            seasons++;
        }
        System.out.printf("%-8s", "mean");
        for(int b = 0; b < 4; b++){
            System.out.printf(" %9.1f", bandTotals[b] / seasons);
        }
        System.out.printf("%n%nranked by PRESEASON adp. if taking a defence early bought a"
                + " better one,%nDEF1-3 would beat DEF10-12 consistently.%n");

        int agree = 0;
        for(double[] band : perSeason){
            if(band[0] > band[3]){ agree++; }
        }
        System.out.printf("DEF1-3 beat DEF10-12 in %d of %d seasons"
                + " (coin flip would give %.1f).%n", agree, seasons, seasons / 2.0);

        double top = bandTotals[0] / seasons;
        double bottom = bandTotals[3] / seasons;
        System.out.printf("%nthe gradient is %+.1f points a season, top band to bottom.%n",
                top - bottom);

        System.out.printf("%n%s%nTHE TRADE, BOTH HALVES ON ONE SCALE%n%s%n",
                "=".repeat(64), "=".repeat(64));
        double skillCost = SKILL_ROUNDS_8_9 - SKILL_ROUNDS_13_16;
        System.out.printf("   spend round 8 on a skill man instead of round 13-16:"
                + " %+6.1f%n", skillCost);
        System.out.printf("   spend round 8 on a defence instead of round 13-16:"
                + "  %+6.1f%n", top - bottom);
        System.out.printf("%n   depth wins by %.1f points a season.%n", skillCost - (top - bottom));
        System.out.printf("%n   and that understates it: the defence figure above is the gap%n"
                + "   between the BEST and WORST preseason bands, which you only collect%n"
                + "   if preseason ranking picks the right defence. the season-by-season%n"
                + "   count above says whether it does.%n");
    }
}
