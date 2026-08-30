import PlayerImportAndSetup.Position;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Night 1 of the reality program: how accurate was anything, actually?
 * Every source the repo holds - Sleeper's stored projections, and every
 * dated feed harvested near each draft (ADPs, ECR, Sleeper defaults) - is
 * scored as a PREDICTOR of actual season half-PPR points, per season:
 *
 *   spearman   rank correlation with the actual outcome ranking, over the
 *              source's top 150
 *   top24hit   of the source's top-24 skill players, how many finished
 *              top-24 (the "did your early rounds exist" measure)
 *   rb/wr/qb/te subgroup spearman - where predictability actually lives
 *
 * The point is not to crown a site; it is to measure the fog: the gap
 * between every number this project optimizes and what the season did.
 *
 *   ./gradlew run -Pmain=AccuracyShootout
 */
public class AccuracyShootout {

    record Source(String label, Map<String, Double> valueBySleeperID, boolean lowerIsBetter){}

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        Map<String, double[]> weekOneBySeason = new LinkedHashMap<>();
        Map<String, double[]> bestAdpBySeason = new LinkedHashMap<>();
        Map<String, String> bestAdpName = new LinkedHashMap<>();
        // experiment 2: season -> family -> capture date -> spearman
        Map<String, Map<String, java.util.TreeMap<String, Double>>> vintages =
                new LinkedHashMap<>();
        for(String season : new String[]{"2021", "2022", "2023", "2024", "2025"}){
            Map<String, Double> actual = HistoricalActuals.pointsBySleeperID(season);
            List<Source> sources = new ArrayList<>();
            try {
                // NOT a forecast. The season endpoint serves rest-of-season
                // values frozen at season end, so this source knows the
                // outcome - which is why it has topped this table every year.
                // Kept, renamed, so the leak stays visible instead of being
                // quietly deleted and rediscovered later.
                sources.add(new Source("sleeper-season-LEAKED",
                        HistoricalProjections.rawPointsBySleeperID(configuration, season),
                        false));
                sources.add(new Source("sleeper-stored-adp",
                        HistoricalProjections.adpBySleeperID(configuration, season), true));
            }
            catch(Exception missing){ /* keep going */ }
            try {
                Map<String, Double> week1 = WeeklyProjections.pointsBySleeperID(season, 1);
                if(!week1.isEmpty()){
                    sources.add(new Source("sleeper-week1-proj", week1, false));
                }
            }
            catch(Exception missing){ /* keep going */ }
            Map<String, Double> ffc = FFCalculatorSD.adpBySleeperID(season);
            if(!ffc.isEmpty()){
                sources.add(new Source("ffc-adp", ffc, true));
            }
            for(File file : new File("data").listFiles()){
                String name = file.getName();
                if(name.matches("sleeper-adp-dated-" + season + "-\\d{8}\\.csv")){
                    sources.add(new Source("sleeper-dated-" + name.substring(
                            name.length() - 12, name.length() - 4),
                            csvValues(file, "sleeper_adp"), true));
                }
                else if(name.matches("sleeper-defaults-" + season + "-\\d{8}\\.csv")){
                    // dated in the label: experiment 2 compares vintages of the
                    // SAME source, so they cannot all be called the same thing
                    sources.add(new Source("sleeper-defaults-" + name.substring(
                            name.length() - 12, name.length() - 4),
                            csvValues(file, "sleeper_rank"), true));
                }
                else if(name.matches("fp-adp-halfppr-" + season + "-\\d{8}\\.csv")){
                    Map<String, Double> avg = csvValues(file, "AVG");
                    if(!avg.isEmpty()){
                        sources.add(new Source("fp-consensus-adp", avg, true));
                    }
                }
            }

            System.out.printf("%n%s: source, n, spearman, top24 hits, QB, RB, WR, TE%n",
                    season);
            for(Source source : sources){
                double[] score = score(source, actual);
                if(score == null){
                    continue;
                }
                // Experiment 1: the honest preseason projection against the
                // best honest market rank. The leaked source is excluded from
                // the comparison entirely - it is not a forecast.
                if(source.label().equals("sleeper-week1-proj")){
                    weekOneBySeason.put(season, score);
                }
                // experiment 2 collects any source whose label carries a date,
                // so the SAME feed can be compared against itself at two ages
                java.util.regex.Matcher dated = java.util.regex.Pattern
                        .compile("^(.*)-(\\d{8})$").matcher(source.label());
                if(dated.matches()){
                    vintages.computeIfAbsent(season, u -> new LinkedHashMap<>())
                            .computeIfAbsent(dated.group(1), u -> new java.util.TreeMap<>())
                            .put(dated.group(2), score[1]);
                }
                if(!source.label().contains("LEAKED")){
                    double[] best = bestAdpBySeason.get(season);
                    if(best == null || score[1] > best[1]){
                        bestAdpBySeason.put(season, score);
                        bestAdpName.put(season, source.label());
                    }
                }
                System.out.printf("   %-26s %4.0f %8.3f %8.0f/24 %7.2f %6.2f %6.2f %6.2f%n",
                        source.label(), score[0], score[1], score[2], score[3], score[4],
                        score[5], score[6]);
            }
        }
        System.out.printf("%n%nEXPERIMENT 1: does a week-1 projection beat the market"
                + " rank?%n(the leaked season feed is excluded - it is not a"
                + " forecast)%n%n");
        System.out.printf("%-8s %14s %14s %10s   %-22s%n", "SEASON", "week-1 proj",
                "best ADP rank", "delta", "which ADP");
        double totalDelta = 0;
        int seasons = 0;
        double[] positionDelta = new double[4];
        int[] positionCount = new int[4];
        for(String season : weekOneBySeason.keySet()){
            double[] week = weekOneBySeason.get(season);
            double[] adp = bestAdpBySeason.get(season);
            if(adp == null){
                continue;
            }
            System.out.printf("%-8s %14.3f %14.3f %+10.3f   %-22s%n", season, week[1],
                    adp[1], week[1] - adp[1], bestAdpName.get(season));
            totalDelta += week[1] - adp[1];
            seasons++;
            for(int p = 0; p < 4; p++){
                if(!Double.isNaN(week[3 + p]) && !Double.isNaN(adp[3 + p])){
                    positionDelta[p] += week[3 + p] - adp[3 + p];
                    positionCount[p]++;
                }
            }
        }
        System.out.printf("%nmean delta %+.3f over %d seasons - week-1 projection %s%n",
                totalDelta / Math.max(1, seasons), seasons,
                totalDelta > 0 ? "WINS" : "loses");
        System.out.printf("%nby position (week-1 minus best ADP):%n");
        String[] names = {"QB", "RB", "WR", "TE"};
        for(int p = 0; p < 4; p++){
            if(positionCount[p] == 0){
                continue;
            }
            double delta = positionDelta[p] / positionCount[p];
            System.out.printf("   %-4s %+7.3f   %s%n", names[p], delta,
                    delta > 0.05 ? "week-1 projection is better"
                            : delta < -0.05 ? "MARKET RANK IS BETTER" : "no difference");
        }
        System.out.printf("%n%nEXPERIMENT 2: does a later capture predict better?%n"
                + "(the same feed compared against itself at two ages - the only"
                + " comparison%nthat isolates vintage from the source's own"
                + " quality)%n%n");
        System.out.printf("%-7s %-18s %11s %9s %11s %9s %9s %7s%n", "SEASON", "FEED",
                "early", "spearman", "late", "spearman", "delta", "days");
        double vintageDelta = 0;
        int pairs = 0;
        int laterWins = 0;
        for(Map.Entry<String, Map<String, java.util.TreeMap<String, Double>>> season
                : vintages.entrySet()){
            for(Map.Entry<String, java.util.TreeMap<String, Double>> family
                    : season.getValue().entrySet()){
                java.util.TreeMap<String, Double> byDate = family.getValue();
                if(byDate.size() < 2){
                    continue;
                }
                String early = byDate.firstKey();
                String late = byDate.lastKey();
                double delta = byDate.get(late) - byDate.get(early);
                // Two captures scoring identically to six decimals are not two
                // captures. The sleeper-defaults files carry FABRICATED dates -
                // commit 2bd97be extracted them all from the same mid-July ADR
                // workbooks and named them after draft dates - so a "September"
                // file is July content wearing a September name. Excluded here
                // and reported, rather than averaged into an answer.
                if(Math.abs(delta) < 1e-6){
                    System.out.printf("%-7s %-18s %11s %9.3f %11s %9.3f   SAME DATA -"
                            + " not two captures%n", season.getKey(), family.getKey(),
                            early, byDate.get(early), late, byDate.get(late));
                    continue;
                }
                long days = java.time.temporal.ChronoUnit.DAYS.between(
                        java.time.LocalDate.parse(early,
                                java.time.format.DateTimeFormatter.BASIC_ISO_DATE),
                        java.time.LocalDate.parse(late,
                                java.time.format.DateTimeFormatter.BASIC_ISO_DATE));
                System.out.printf("%-7s %-18s %11s %9.3f %11s %9.3f %+9.3f %7d%n",
                        season.getKey(), family.getKey(), early, byDate.get(early),
                        late, byDate.get(late), delta, days);
                vintageDelta += delta;
                pairs++;
                if(delta > 0){
                    laterWins++;
                }
            }
        }
        if(pairs > 0){
            System.out.printf("%nmean delta %+.3f over %d paired vintages;"
                    + " the later capture won %d of them.%n",
                    vintageDelta / pairs, pairs, laterWins);
            System.out.println("A month of preseason news is worth roughly this much"
                    + " rank-correlation.\nIf it is near zero, the vintage discipline"
                    + " costs more than it buys and the\n2024 ADP hole stops"
                    + " mattering.");
        }

        System.out.println("\nspearman = rank correlation of the source's top-150 with what"
                + "\nactually happened. The fog, measured.");
    }

    /** n, overall spearman, top-24 hits, then per-position spearman. */
    static double[] score(Source source, Map<String, Double> actual){
        List<String> top = new ArrayList<>();
        for(String sleeperID : source.valueBySleeperID().keySet()){
            Player player = Player.getPlayerFromSIDV2(sleeperID);
            if(player != null && StartingLineup.isSkillPosition(player.position)
                    && actual.containsKey(sleeperID)){
                top.add(sleeperID);
            }
        }
        Comparator<String> bySource = Comparator.comparingDouble(
                id -> source.valueBySleeperID().get(id));
        top.sort(source.lowerIsBetter() ? bySource : bySource.reversed());
        if(top.size() > 150){
            top = new ArrayList<>(top.subList(0, 150));
        }
        if(top.size() < 60){
            return null;
        }
        double overall = spearman(top, actual);

        List<String> byActual = new ArrayList<>(top);
        byActual.sort(Comparator.comparingDouble(
                (String id) -> actual.get(id)).reversed());
        int hits = 0;
        for(int i = 0; i < 24 && i < top.size(); i++){
            if(byActual.subList(0, Math.min(24, byActual.size())).contains(top.get(i))){
                hits++;
            }
        }

        double[] result = new double[]{top.size(), overall, hits, 0, 0, 0, 0};
        Position[] positions = {Position.QB, Position.RB, Position.WR, Position.TE};
        for(int p = 0; p < positions.length; p++){
            List<String> subset = new ArrayList<>();
            for(String sleeperID : top){
                if(Player.getPlayerFromSIDV2(sleeperID).position == positions[p]){
                    subset.add(sleeperID);
                }
            }
            result[3 + p] = subset.size() >= 8 ? spearman(subset, actual) : Double.NaN;
        }
        return result;
    }

    static double spearman(List<String> orderedBySource, Map<String, Double> actual){
        int n = orderedBySource.size();
        List<String> byActual = new ArrayList<>(orderedBySource);
        byActual.sort(Comparator.comparingDouble(
                (String id) -> actual.get(id)).reversed());
        double sum = 0;
        for(int i = 0; i < n; i++){
            int actualRank = byActual.indexOf(orderedBySource.get(i));
            sum += (double) (i - actualRank) * (i - actualRank);
        }
        return 1 - 6 * sum / ((double) n * (n * n - 1));
    }

    static Map<String, Double> csvValues(File file, String column) throws Exception {
        List<String> lines = Files.readAllLines(file.toPath());
        String[] header = lines.get(0).split(",");
        int nameCol = -1;
        int posCol = -1;
        int valueCol = -1;
        for(int c = 0; c < header.length; c++){
            if(header[c].equals("name")){
                nameCol = c;
            }
            if(header[c].equals("position")){
                posCol = c;
            }
            if(header[c].equals(column)){
                valueCol = c;
            }
        }
        Map<String, Double> values = new LinkedHashMap<>();
        if(valueCol < 0){
            return values;
        }
        for(String line : lines.subList(1, lines.size())){
            String[] cells = line.split(",");
            if(cells.length <= Math.max(valueCol, Math.max(nameCol, posCol))
                    || cells[valueCol].isEmpty()
                    || !cells[valueCol].matches("\\d+(\\.\\d+)?")){
                continue;
            }
            Position position;
            try {
                position = Position.valueOf(cells[posCol].trim());
            }
            catch(IllegalArgumentException notSkill){
                continue;
            }
            Player player = Player.getPlayerFromNameAndPos(cells[nameCol], position);
            if(player != null){
                values.putIfAbsent(player.sleeperIDString,
                        Double.parseDouble(cells[valueCol]));
            }
        }
        return values;
    }
}
