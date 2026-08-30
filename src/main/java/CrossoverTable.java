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
        System.out.printf("(every pair at the same position, within a season,"
                + " %d seasons)%n%n", seasons.size());
        System.out.printf("%-4s %-9s %-9s %7s %9s %11s %11s   %s%n", "POS", "EARLY",
                "LATE", "pairs", "late wins", "avg margin", "value", "");

        for(Position position : new Position[]{Position.RB, Position.WR, Position.TE}){
            for(int early = 0; early < 2; early++){
                for(int late = early + 1; late < 6; late++){
                    int pairs = 0;
                    int wins = 0;
                    double margin = 0;
                    for(List<PositionPredictability.Seen> season : seasons){
                        List<PositionPredictability.Seen> earlyMen = tier(season,
                                position, early);
                        List<PositionPredictability.Seen> lateMen = tier(season,
                                position, late);
                        for(PositionPredictability.Seen a : earlyMen){
                            for(PositionPredictability.Seen b : lateMen){
                                pairs++;
                                if(b.actual() > a.actual()){
                                    wins++;
                                    margin += b.actual() - a.actual();
                                }
                            }
                        }
                    }
                    if(pairs < 100){
                        continue;
                    }
                    double rate = (double) wins / pairs;
                    double when = wins == 0 ? 0 : margin / wins;
                    System.out.printf("%-4s %-9s %-9s %7d %8.0f%% %11.0f %11.1f   %s%n",
                            position, band(early), band(late), pairs, 100 * rate, when,
                            rate * when, note(rate));
                }
            }
        }

        System.out.println("\nlate wins = share of pairs where the later man outscored"
                + " the earlier one that\nseason. avg margin = by how much, in the"
                + " seasons he did. value = the two\nmultiplied, which is what a bench"
                + " pick is worth over the man ahead of him.");
        System.out.println("\nThis needs no model. Both outcomes are in the same season,"
                + " so the comparison\nis direct - which is the answer to how a round-4"
                + " bust and a round-10 boom can\nbe priced together: they already"
                + " happened together, five times over.");
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
