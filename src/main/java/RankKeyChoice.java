import PlayerImportAndSetup.Position;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * ADP or projections: which preseason order should a man's outcome cell be
 * keyed by?
 *
 * The outcome pool hands a man the seasons that men of HIS STANDING have had,
 * and the whole model rests on what "his standing" means. The historical pool
 * keys its cells by draft position; the live board assigns today's men to those
 * cells by projected points. Those two orders are not the same - the gap
 * between them is where value comes from - so at least one of the two is asking
 * a man to draw from the wrong cell (TRAPS #80's leftover).
 *
 * This settles which. Both orders come from ONE feed - Sleeper's own preseason
 * projections for a completed season carry `adp_half_ppr` and a stat line the
 * league's own scoring is applied to - so the two rankings cover exactly the
 * same men and differ only in the key. The test is the one the pool actually
 * performs: predict a man's realised season from the mean of the men who shared
 * his rank band in the OTHER seasons, and see which key predicts better.
 * Leave-one-season-out, so no key is scored on a season it learned from.
 *
 *   ./gradlew run -Pmain=RankKeyChoice [-Pdepth=48] [-Pband=12]
 *
 * `depth` is how far down each position to judge (the pool's first four tiers
 * by default); `band` is the tier width, 12 as WeeklyStarterValue.TIER.
 */
public class RankKeyChoice {

    /**
     * One man in one season: where the market had him (Sleeper's ADP and the
     * FantasyPros ADP the outcome pool is actually built from), where the
     * projections had him, and what he did.
     */
    public record Man(String season, String id, Position position,
                      double adp, double fpAdp, double projection, double realised) {}

    /** Which preseason order a run is judging. */
    public enum Key { SLEEPER_ADP, FP_ADP, PROJECTION }

    /** Rank within position, 0-based: earliest ADP first, or highest projection first. */
    public static Map<String, Integer> rank(List<Man> men, Key key){
        Map<Position, List<Man>> byPosition = new EnumMap<>(Position.class);
        for(Man man : men){
            byPosition.computeIfAbsent(man.position(), u -> new ArrayList<>()).add(man);
        }
        Comparator<Man> order = switch(key){
            case SLEEPER_ADP -> Comparator.comparingDouble(Man::adp);
            case FP_ADP -> Comparator.comparingDouble(Man::fpAdp);
            case PROJECTION -> Comparator.comparingDouble((Man man) -> -man.projection());
        };
        Map<String, Integer> out = new HashMap<>();
        for(List<Man> group : byPosition.values()){
            group.sort(order);
            for(int i = 0; i < group.size(); i++){
                out.put(group.get(i).id(), i);
            }
        }
        return out;
    }

    /** The FantasyPros half-PPR board for a season, by sleeper id - the pool's own ADP source. */
    static Map<String, Double> fantasyProsAdp(String season) throws Exception {
        Map<String, Double> out = new HashMap<>();
        java.io.File[] files = new java.io.File("data").listFiles();
        if(files == null){
            return out;
        }
        for(java.io.File file : files){
            if(!file.getName().matches("fp-adp-halfppr-" + season + "-\\d{8}\\.csv")){
                continue;
            }
            PlanBacktest.Board board = PlanBacktest.board(file, season);
            if(board == null){
                continue;
            }
            // the board's own ADP order, as a per-position-blind overall rank; the
            // per-position ranking happens in rank() like every other key
            for(int i = 0; i < board.ids().size(); i++){
                out.putIfAbsent(board.ids().get(i), (double) i);
            }
        }
        return out;
    }

    /**
     * The band's mean realised points over every season BUT this man's, which is
     * the number the pool would hand him. Empty when no other season has anyone
     * in that band.
     */
    static Double bandMean(Map<String, List<Man>> bySeason, Map<String, Map<String, Integer>> ranks,
                           Man man, int band){
        int mine = ranks.get(man.season()).get(man.id()) / band;
        double total = 0;
        int n = 0;
        for(Map.Entry<String, List<Man>> entry : bySeason.entrySet()){
            if(entry.getKey().equals(man.season())){
                continue;   // leave one season out
            }
            for(Man other : entry.getValue()){
                if(other.position() == man.position()
                        && ranks.get(other.season()).get(other.id()) / band == mine){
                    total += other.realised();
                    n++;
                }
            }
        }
        return n == 0 ? null : total / n;
    }

    /** Mean and standard error of a list. */
    static double[] meanAndError(List<Double> values){
        if(values.isEmpty()){
            return new double[]{0, 0};
        }
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum()
                / Math.max(1, values.size() - 1);
        return new double[]{mean, Math.sqrt(variance / values.size())};
    }

    /** Spearman between two orderings of the same men. */
    static double spearman(List<Man> men, Map<String, Integer> key, Map<String, Double> realised){
        List<Man> byKey = new ArrayList<>(men);
        byKey.sort(Comparator.comparingInt(man -> key.get(man.id())));
        List<Man> byOutcome = new ArrayList<>(men);
        byOutcome.sort(Comparator.comparingDouble((Man man) -> -realised.get(man.id())));
        Map<String, Integer> outcomeRank = new HashMap<>();
        for(int i = 0; i < byOutcome.size(); i++){
            outcomeRank.put(byOutcome.get(i).id(), i);
        }
        int n = byKey.size();
        if(n < 3){
            return 0;
        }
        double sum = 0;
        for(int i = 0; i < n; i++){
            double d = i - outcomeRank.get(byKey.get(i).id());
            sum += d * d;
        }
        return 1 - 6 * sum / ((double) n * (n * n - 1));
    }

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int depth = Integer.getInteger("depth", 48);
        int band = Integer.getInteger("band", WeeklyStarterValue.TIER);
        int thisSeason = Integer.parseInt(configuration.getSeason());

        // every completed season whose draft-night feed is frozen
        Map<String, List<Man>> bySeason = new TreeMap<>();
        for(int year = thisSeason - 5; year < thisSeason; year++){
            String season = String.valueOf(year);
            Map<String, Double> adp = HistoricalProjections.adpBySleeperID(configuration, season);
            Map<String, Double> projected = HistoricalProjections.leaguePointsBySleeperID(configuration, season);
            Map<String, Double> realised = new HashMap<>(LeagueActuals.seasonPoints(season));
            realised.putAll(LeagueActuals.seasonDefencePoints(season));
            Map<String, Double> fpAdp = fantasyProsAdp(season);
            List<Man> men = new ArrayList<>();
            for(Map.Entry<String, Double> entry : adp.entrySet()){
                String id = entry.getKey();
                // BOTH keys must cover the same men, or this compares populations
                // and not orderings: a man needs an ADP, a projection and an outcome
                Double points = projected.get(id);
                Double actual = realised.get(id);
                Double fp = fpAdp.get(id);
                if(entry.getValue() == null || entry.getValue() <= 0 || points == null
                        || actual == null || fp == null){
                    continue;   // every key must cover him, or this compares populations
                }
                Player player = Player.getPlayerFromSIDV2(id);
                if(player == null || player.position == null || player.position == Position.OTHER){
                    continue;
                }
                men.add(new Man(season, id, player.position, entry.getValue(), fp, points, actual));
            }
            if(men.size() > 100){
                bySeason.put(season, men);
            }
        }

        StringBuilder out = new StringBuilder();
        out.append(String.format("WHICH PRESEASON ORDER KEYS A MAN'S OUTCOMES  %s%n", LocalDate.now()));
        out.append(String.format("%d seasons: %s. One feed (Sleeper's preseason projections for a finished season),%n",
                bySeason.size(), bySeason.keySet()));
        out.append("so ADP and projection rank the SAME men and differ only in the key. Outcomes are\n");
        out.append("league-scored. Judged over the first ").append(depth)
           .append(" men of each position, in bands of ").append(band).append(".\n\n");
        if(bySeason.size() < 2){
            out.append("not enough frozen seasons to leave one out\n");
            System.out.print(out);
            return;
        }

        Map<Key, Map<String, Map<String, Integer>>> ranked = new EnumMap<>(Key.class);
        for(Key key : Key.values()){
            Map<String, Map<String, Integer>> perSeason = new HashMap<>();
            for(Map.Entry<String, List<Man>> entry : bySeason.entrySet()){
                perSeason.put(entry.getKey(), rank(entry.getValue(), key));
            }
            ranked.put(key, perSeason);
        }
        Map<String, Map<String, Integer>> adpRanks = ranked.get(Key.FP_ADP);
        Map<String, Map<String, Integer>> projectionRanks = ranked.get(Key.PROJECTION);

        // 1. does the order itself track the season?
        out.append("HOW WELL EACH ORDER TRACKS THE FINISHED SEASON (spearman, within position)\n");
        out.append(String.format("%-6s %-8s %8s %8s %8s   %s%n", "POS", "SEASON", "FP ADP", "SLP ADP", "PROJ", "better"));
        Map<Position, List<Double>> adpRho = new EnumMap<>(Position.class);
        Map<Position, List<Double>> projectionRho = new EnumMap<>(Position.class);
        for(Position position : new Position[]{Position.QB, Position.RB, Position.WR, Position.TE, Position.DEF}){
            for(Map.Entry<String, List<Man>> entry : bySeason.entrySet()){
                List<Man> group = new ArrayList<>();
                Map<String, Double> realised = new HashMap<>();
                for(Man man : entry.getValue()){
                    if(man.position() == position
                            && adpRanks.get(man.season()).get(man.id()) < depth){
                        group.add(man);
                        realised.put(man.id(), man.realised());
                    }
                }
                if(group.size() < 8){
                    continue;
                }
                double a = spearman(group, adpRanks.get(entry.getKey()), realised);
                double s = spearman(group, ranked.get(Key.SLEEPER_ADP).get(entry.getKey()), realised);
                double p = spearman(group, projectionRanks.get(entry.getKey()), realised);
                adpRho.computeIfAbsent(position, u -> new ArrayList<>()).add(a);
                projectionRho.computeIfAbsent(position, u -> new ArrayList<>()).add(p);
                out.append(String.format("%-6s %-8s %8.3f %8.3f %8.3f   %s%n", position, entry.getKey(), a, s, p,
                        Math.abs(a - p) < 0.02 ? "tie" : a > p ? "FP ADP" : "PROJ"));
            }
        }
        out.append("\n");

        // 2. THE DECISION: which key predicts a man's season better, out of sample?
        out.append("PREDICTING A MAN'S SEASON FROM HIS BAND'S OTHER-SEASON MEAN (leave one season out)\n");
        out.append("The ADP column is the FantasyPros board the outcome pool is built from.\n");
        out.append(String.format("%-6s %6s %10s %10s %12s%n", "POS", "n", "ADP err", "PROJ err", "ADP - PROJ"));
        Map<Position, List<Double>> perManDifference = new EnumMap<>(Position.class);
        List<Double> allDifferences = new ArrayList<>();
        Map<Position, List<Double>> adpError = new EnumMap<>(Position.class);
        Map<Position, List<Double>> projectionError = new EnumMap<>(Position.class);
        for(Map.Entry<String, List<Man>> entry : bySeason.entrySet()){
            for(Man man : entry.getValue()){
                if(adpRanks.get(man.season()).get(man.id()) >= depth
                        && projectionRanks.get(man.season()).get(man.id()) >= depth){
                    continue;   // deeper than either order judges
                }
                Double byAdp = bandMean(bySeason, adpRanks, man, band);
                Double byProjection = bandMean(bySeason, projectionRanks, man, band);
                if(byAdp == null || byProjection == null){
                    continue;
                }
                double a = Math.abs(man.realised() - byAdp);
                double p = Math.abs(man.realised() - byProjection);
                adpError.computeIfAbsent(man.position(), u -> new ArrayList<>()).add(a);
                projectionError.computeIfAbsent(man.position(), u -> new ArrayList<>()).add(p);
                // PAIRED: the same man judged by both keys, so the spread of the
                // difference is the error bar that matters
                perManDifference.computeIfAbsent(man.position(), u -> new ArrayList<>()).add(a - p);
                allDifferences.add(a - p);
            }
        }
        for(Position position : new Position[]{Position.QB, Position.RB, Position.WR, Position.TE, Position.DEF}){
            List<Double> differences = perManDifference.get(position);
            if(differences == null || differences.isEmpty()){
                continue;
            }
            double[] gap = meanAndError(differences);
            out.append(String.format("%-6s %6d %10.1f %10.1f %+8.1f +/- %.1f  %s%n", position, differences.size(),
                    meanAndError(adpError.get(position))[0], meanAndError(projectionError.get(position))[0],
                    gap[0], gap[1],
                    Math.abs(gap[0]) < 2 * gap[1] ? "tie" : gap[0] > 0 ? "PROJ predicts better" : "ADP predicts better"));
        }
        // per season, so nobody has to take one pooled number on trust
        out.append(String.format("%n%-8s %6s %10s %10s %12s%n", "SEASON", "n", "ADP err", "PROJ err", "ADP - PROJ"));
        for(String season : bySeason.keySet()){
            List<Double> differences = new ArrayList<>();
            List<Double> a = new ArrayList<>();
            List<Double> p = new ArrayList<>();
            for(Man man : bySeason.get(season)){
                if(adpRanks.get(man.season()).get(man.id()) >= depth
                        && projectionRanks.get(man.season()).get(man.id()) >= depth){
                    continue;
                }
                Double byAdp = bandMean(bySeason, adpRanks, man, band);
                Double byProjection = bandMean(bySeason, projectionRanks, man, band);
                if(byAdp == null || byProjection == null){
                    continue;
                }
                a.add(Math.abs(man.realised() - byAdp));
                p.add(Math.abs(man.realised() - byProjection));
                differences.add(Math.abs(man.realised() - byAdp) - Math.abs(man.realised() - byProjection));
            }
            if(differences.isEmpty()){
                continue;
            }
            double[] gap = meanAndError(differences);
            out.append(String.format("%-8s %6d %10.1f %10.1f %+8.1f +/- %.1f%n", season, differences.size(),
                    meanAndError(a)[0], meanAndError(p)[0], gap[0], gap[1]));
        }

        double[] overall = meanAndError(allDifferences);
        out.append(String.format("%-6s %6d %10.1f %10.1f %+8.1f +/- %.1f%n", "ALL", allDifferences.size(),
                meanAndError(allDifferences.isEmpty() ? List.of() : flatten(adpError))[0],
                meanAndError(allDifferences.isEmpty() ? List.of() : flatten(projectionError))[0],
                overall[0], overall[1]));
        out.append(String.format("%nA POSITIVE gap means the projection-keyed band predicted the man's season%n"
                + "better than the ADP-keyed band. The error bar is over men, who share seasons,%n"
                + "so read a gap under two of them as nothing.%n"));
        out.append(String.format("%nVERDICT: %s%n", Math.abs(overall[0]) < 2 * overall[1]
                ? "the two keys are not separated - either is defensible, so keep whichever the pool already uses"
                : overall[0] > 0 ? "key the cells by PROJECTION rank" : "key the cells by ADP rank"));

        System.out.print(out);
        Path target = Path.of("data", "rank-key-choice-" + LocalDate.now() + ".txt");
        Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
        System.out.println("written to " + target);
    }

    private static List<Double> flatten(Map<Position, List<Double>> byPosition){
        List<Double> all = new ArrayList<>();
        for(List<Double> values : byPosition.values()){
            all.addAll(values);
        }
        return all;
    }
}
