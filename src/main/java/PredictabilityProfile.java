import PlayerImportAndSetup.Position;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * For each position AND tier: what predicts, what does not, and what failure
 * looks like when it comes.
 *
 * One reliability number per position was too coarse to act on. A top-twelve
 * back and a thirty-sixth back are not equally knowable, and "unpredictable"
 * covers three different things that want different treatment:
 *
 *   BIAS      the tier systematically beats or misses its billing, which a
 *             shrinkage factor cannot fix - it needs the centre moved
 *   SPREAD    symmetric noise, which is what shrinkage is actually for
 *   SKEW      mostly-downside or mostly-upside, which changes whether a pick is
 *             a floor or a lottery ticket, and shrinkage handles it badly
 *
 * Measured on five seasons of dated ADP joined to actual outcomes.
 *
 *   ./gradlew run -Pmain=PredictabilityProfile
 */
public class PredictabilityProfile {

    record Seen(Position position, int rank, double actual){}

    static final int TIER = 12;

    public static void main(String[] args) throws Exception {
        List<Seen> all = new ArrayList<>();
        Map<Position, double[]> curve = new EnumMap<>(Position.class);
        Map<Position, int[]> counts = new EnumMap<>(Position.class);
        int depth = 96;
        for(File file : new File("data").listFiles()){
            if(!file.getName().matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                continue;
            }
            List<PositionPredictability.Seen> season =
                    PositionPredictability.load(file, file.getName().split("-")[3]);
            for(PositionPredictability.Seen s : season){
                if(s.rank() >= depth){
                    continue;
                }
                all.add(new Seen(s.position(), s.rank(), s.actual()));
                curve.computeIfAbsent(s.position(), u -> new double[depth])[s.rank()]
                        += s.actual();
                counts.computeIfAbsent(s.position(), u -> new int[depth])[s.rank()]++;
            }
        }
        if(all.isEmpty()){
            System.out.println("no seasons joined");
            return;
        }

        System.out.printf("%nWHAT PREDICTS, WHAT DOES NOT, AND HOW FAILURE LOOKS%n");
        System.out.printf("(ratio = actual / what that draft slot historically"
                + " returned)%n%n");
        System.out.printf("%-4s %-8s %5s %9s %8s %8s %8s %8s   %s%n", "POS", "TIER", "n",
                "predicts", "bias", "spread", "bust", "boom", "how to account for it");

        for(Position position : new Position[]{Position.RB, Position.WR, Position.TE,
                Position.QB, Position.DEF}){
            for(int tier = 0; tier * TIER < depth; tier++){
                final int t = tier;
                List<Seen> group = all.stream()
                        .filter(s -> s.position() == position && s.rank() / TIER == t)
                        .toList();
                if(group.size() < 20){
                    continue;
                }
                double spearman = spearman(group);
                List<Double> ratios = new ArrayList<>();
                for(Seen s : group){
                    double expected = smooth(curve.get(position), counts.get(position),
                            s.rank(), depth);
                    if(expected > 0){
                        ratios.add(s.actual() / expected);
                    }
                }
                if(ratios.size() < 20){
                    continue;
                }
                double mean = ratios.stream().mapToDouble(Double::doubleValue)
                        .average().orElse(0);
                double sd = Math.sqrt(ratios.stream()
                        .mapToDouble(r -> (r - mean) * (r - mean)).sum()
                        / (ratios.size() - 1));
                double bust = ratios.stream().filter(r -> r < 0.5).count()
                        / (double) ratios.size();
                double boom = ratios.stream().filter(r -> r > 1.5).count()
                        / (double) ratios.size();
                System.out.printf("%-4s %-8s %5d %9.3f %8.2f %8.2f %7.0f%% %7.0f%%   %s%n",
                        position, (tier * TIER + 1) + "-" + (tier + 1) * TIER,
                        group.size(), spearman, mean, sd, 100 * bust, 100 * boom,
                        advice(spearman, sd, bust, boom));
            }
        }

        System.out.println("\npredicts = rank correlation with the outcome INSIDE that"
                + " tier - whether the\nboard can tell these twelve men apart at all."
                + " A position can look predictable\noverall and be noise within every"
                + " tier, which only means the tiers are ordered\nright.");
        System.out.println("\nbias = mean ratio. Above 1.0 the tier beats its billing"
                + " and shrinkage toward\nthe positional mean is the wrong move - the"
                + " CENTRE needs moving, not the spread.");
        System.out.println("\nbust / boom = share returning under half or over one and a"
                + " half times billing.\nWhen bust far exceeds boom the pick is a floor"
                + " and its downside should be\npriced; when boom exceeds bust it is a"
                + " lottery ticket and averaging hides the\nonly reason to take it.");
    }

    static String advice(double spearman, double sd, double bust, double boom){
        if(spearman < 0.15){
            return "board is blind here - take the cheapest";
        }
        if(boom > bust * 1.4){
            return "upside-skewed - do not average it away";
        }
        if(bust > boom * 1.6){
            return "downside-skewed - price the floor";
        }
        return sd > 0.55 ? "noisy but fair - shrink toward the mean" : "trust the board";
    }

    static double smooth(double[] sums, int[] counts, int rank, int depth){
        double total = 0;
        int n = 0;
        for(int near = Math.max(0, rank - 2); near <= Math.min(depth - 1, rank + 2); near++){
            total += sums[near];
            n += counts[near];
        }
        return n == 0 ? 0 : total / n;
    }

    static double spearman(List<Seen> group){
        List<Seen> byActual = new ArrayList<>(group);
        byActual.sort(Comparator.comparingDouble(Seen::actual).reversed());
        Map<Seen, Integer> actualRank = new java.util.HashMap<>();
        for(int i = 0; i < byActual.size(); i++){
            actualRank.put(byActual.get(i), i);
        }
        List<Seen> byBoard = new ArrayList<>(group);
        byBoard.sort(Comparator.comparingInt(Seen::rank));
        double sum = 0;
        int n = byBoard.size();
        for(int i = 0; i < n; i++){
            double d = i - actualRank.get(byBoard.get(i));
            sum += d * d;
        }
        return 1 - 6 * sum / ((double) n * (n * n - 1));
    }
}
