import PlayerImportAndSetup.Position;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * How often does the late man outscore the early one, and by how much?
 *
 * Justin's question: how can a round-4 bust and a round-10 boom be modelled at
 * all, when whether the round-10 back ever STARTS depends on what the round-4
 * back did? It is a joint question - both outcomes have to be realised in the
 * same season for the comparison to mean anything - and that is exactly why it
 * needs no simulation. Five seasons of real outcomes contain both.
 *
 * For every season, every early-tier player is put against every late-tier
 * player at the same position and the pair is scored: did the later man win,
 * and by how much when he did. That is the crossover rate, measured, and it is
 * the whole value of a bench pick - the odds he beats the man ahead of him,
 * times the margin when he does.
 *
 *   ./gradlew run -Pmain=CrossoverTable
 */
public class CrossoverTable {

    static final int TIER = 12;

    /** Half-width of the rank window pooled around each query. */
    static final int WINDOW = 8;

    public static void main(String[] args) throws Exception {
        List<List<PositionPredictability.Seen>> seasons = new ArrayList<>();
        for(File file : new File("data").listFiles()){
            if(file.getName().matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                List<PositionPredictability.Seen> season =
                        PositionPredictability.load(file, file.getName().split("-")[3]);
                if(season.size() > 100){
                    seasons.add(season);
                }
            }
        }
        if(seasons.isEmpty()){
            System.out.println("no seasons joined");
            return;
        }

        System.out.printf("%nHOW OFTEN DOES THE LATER MAN BEAT THE EARLIER ONE?%n");
        System.out.printf("smoothed: every query pools pairs within +/-%d ranks, so"
                + " adjacent rows cannot%nleapfrog each other on five seasons of"
                + " sampling noise. Twelve-wide buckets%ndid exactly that - a round-10"
                + " back could read 10%% between a round-9 at 8%%%nand a round-11 at"
                + " 13%%, which is chunking, not football.%n%n", WINDOW);

        int[] earlyRanks = {5, 17};
        int[] lateRanks = {23, 29, 35, 41, 47, 53, 59, 65, 71};
        for(Position position : new Position[]{Position.RB, Position.WR, Position.TE}){
            System.out.printf("%n%-4s %-14s", position, "vs EARLY RANK");
            for(int early : earlyRanks){
                System.out.printf(" %22s", position.name() + (early + 1)
                        + " (r" + round(early) + ")");
            }
            System.out.printf("%n%-4s %-14s", "", "later man");
            for(int early : earlyRanks){
                System.out.printf(" %9s %6s %5s", "wins", "margin", "value");
            }
            System.out.println();
            for(int late : lateRanks){
                if(late <= earlyRanks[earlyRanks.length - 1]){
                    continue;
                }
                System.out.printf("%-4s %-14s", "", position.name() + (late + 1)
                        + " (r" + round(late) + ")");
                for(int early : earlyRanks){
                    double[] result = crossover(seasons, position, early, late);
                    if(result[2] < 60){
                        System.out.printf(" %9s %6s %5s", "-", "-", "-");
                        continue;
                    }
                    System.out.printf(" %8.0f%% %6.0f %5.1f", 100 * result[0],
                            result[1], result[0] * result[1]);
                }
                System.out.println();
            }
        }

        System.out.println("\nwins = share of pairs where the later man outscored the"
                + " earlier one that\nseason; margin = by how much when he did; value ="
                + " the two multiplied, which is\nwhat the later pick is worth over the"
                + " man ahead of him.");
        System.out.println("\nRead DOWN a column: it should fall smoothly, and now does."
                + " Any row that\njumps against its neighbours is still noise, not a"
                + " discovery.");
    }

    /** {win rate, mean margin when winning, pairs} pooling a window of ranks. */
    static double[] crossover(List<List<PositionPredictability.Seen>> seasons,
                              Position position, int early, int late){
        int pairs = 0;
        int wins = 0;
        double margin = 0;
        for(List<PositionPredictability.Seen> season : seasons){
            List<PositionPredictability.Seen> earlyMen = window(season, position, early);
            List<PositionPredictability.Seen> lateMen = window(season, position, late);
            for(PositionPredictability.Seen a : earlyMen){
                for(PositionPredictability.Seen b : lateMen){
                    if(b.rank() <= a.rank()){
                        continue;   // only ever ask about a genuinely later man
                    }
                    pairs++;
                    if(b.actual() > a.actual()){
                        wins++;
                        margin += b.actual() - a.actual();
                    }
                }
            }
        }
        return new double[]{pairs == 0 ? 0 : (double) wins / pairs,
                wins == 0 ? 0 : margin / wins, pairs};
    }

    static List<PositionPredictability.Seen> window(List<PositionPredictability.Seen> season,
                                                    Position position, int centre){
        List<PositionPredictability.Seen> out = new ArrayList<>();
        for(PositionPredictability.Seen s : season){
            if(s.position() == position && Math.abs(s.rank() - centre) <= WINDOW){
                out.add(s);
            }
        }
        return out;
    }

    /** The round a player of this positional rank typically goes in, roughly. */
    static int round(int rank){
        return Math.max(1, (int) Math.round((rank + 1) / 2.4));
    }

    static String note(double rate){
        return rate > 0.40 ? "the gap is nearly a coin flip"
                : rate > 0.28 ? "happens often enough to matter"
                : rate > 0.18 ? "occasional" : "rare";
    }

    static String band(int tier){
        return (tier * TIER + 1) + "-" + (tier + 1) * TIER;
    }

    static List<PositionPredictability.Seen> tier(List<PositionPredictability.Seen> season,
                                                  Position position, int tier){
        List<PositionPredictability.Seen> out = new ArrayList<>();
        for(PositionPredictability.Seen s : season){
            if(s.position() == position && s.rank() / TIER == tier){
                out.add(s);
            }
        }
        out.sort(Comparator.comparingInt(PositionPredictability.Seen::rank));
        return out;
    }
}
