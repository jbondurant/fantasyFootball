import PlayerImportAndSetup.Position;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Is 8.7 points a week an honest price for a streamed defence?
 *
 * The number is load-bearing. PlanBacktest.streamedDefencePerWeek() asks
 * WeeklyStarterValue.wireRates() for it, LateRoundValue turns it into "a
 * drafted defence is worth -12.9 against streaming one", and that is the whole
 * argument for letting the defence fall to the last round. It has never been
 * stress-tested, and this repo has already been bitten once by a wire
 * calculation that took a MAX over undrafted players.
 *
 * What wireRates actually does, read line by line:
 *
 *   1. it picks the CANDIDATE POOL by preseason ADP rank - `season.rank()` is
 *      assigned in ADP order inside OutcomeDistributions.load, so the pool is
 *      "defences this league leaves undrafted", decided before the season.
 *      That half is clean, and it is the half the comment defends.
 *
 *   2. it then sorts those candidates by their REALISED season rate and averages
 *      the top quartile. That half is not clean. The rates are
 *      meanWhenPlaying x games / 18 - what the defence went on to do. Taking the
 *      best 25% of them is choosing with the season already run.
 *
 * So the comment on wireRates ("Chosen on expected rate, not on what the player
 * went on to score") describes step 1 and is silent about step 2, which is the
 * step that sets the number. This tool measures how much step 2 is worth.
 *
 * The honest comparison is a STREAMING POLICY that may only use what a manager
 * knew before the week:
 *
 *   PRESEASON      hold the best undrafted defence by ADP all season. No
 *                  in-season information at all - the floor for a manager who
 *                  drafts nothing and never reacts.
 *   FORM (lag L)   hold the preseason best for L weeks, then each week start
 *                  the undrafted defence with the best points-per-game over the
 *                  weeks ALREADY PLAYED. One defence held at a time.
 *   ORACLE         the best undrafted defence each week, chosen after the fact.
 *                  Not a policy - the ceiling, printed so the honest numbers
 *                  can be read against it.
 *
 * Everything is per week over an 18-week season, the same denominator wireRates
 * uses, so the columns are directly comparable.
 *
 *   ./gradlew run -Pmain=WireRateStress
 */
public class WireRateStress {

    static final int WEEKS = WeeklyActuals.WEEKS;

    /** One defence's season: preseason ADP rank, and what it scored each week. */
    record DefSeason(String season, String id, String name, int rank,
                     Double[] weekly) {

        double total(){
            double sum = 0;
            for(Double week : weekly){
                sum += week == null ? 0 : week;
            }
            return sum;
        }

        /** Points per game over weeks 0..before-1, or null with no evidence. */
        Double formThrough(int before){
            double sum = 0;
            int played = 0;
            for(int week = 0; week < before && week < weekly.length; week++){
                if(weekly[week] != null){
                    sum += weekly[week];
                    played++;
                }
            }
            return played == 0 ? null : sum / played;
        }
    }

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();

        System.out.printf("%n============ IS THE STREAMED-DEFENCE RATE HONEST? ============%n%n");

        double shipped = WeeklyStarterValue.wireRates(configuration,
                WeeklyStarterValue.pool()).getOrDefault(Position.DEF, 0.0);
        System.out.printf("SHIPPED  WeeklyStarterValue.wireRates(DEF) = %.2f a week"
                + " (%.0f a season)%n", shipped, shipped * 17);
        System.out.printf("         this is what PlanBacktest and LateRoundValue use.%n%n");

        int drafted = defencesDraftedPerSeason(configuration);
        System.out.printf("HOW MANY DEFENCES THIS LEAGUE ACTUALLY DRAFTS%n");
        System.out.printf("   %d a season on average over its real drafts. wireRates"
                + " assumes 12 -%n   it hardcodes a replacement rank of 13 because"
                + " InsuranceTest.replacementRanks%n   counts skill positions only and"
                + " never returns a DEF entry.%n%n", drafted);

        Map<String, List<DefSeason>> bySeason = load();
        if(bySeason.isEmpty()){
            System.out.println("no seasons joined - cannot stress the rate");
            return;
        }
        System.out.printf("%d seasons joined: %s%n%n", bySeason.size(), bySeason.keySet());

        // The pool wireRates uses: preseason DEF ranks 13..36, every season
        // thrown into one bag.
        int from = 13;
        List<Double> pooled = new ArrayList<>();
        for(List<DefSeason> seasons : bySeason.values()){
            for(DefSeason def : seasons){
                if(def.rank() >= from - 1 && def.rank() < from - 1 + 24){
                    pooled.add(def.total() / 18.0);
                }
            }
        }
        pooled.sort(Comparator.reverseOrder());
        double reimplemented = topQuartile(pooled);
        System.out.printf("REIMPLEMENTED here from the same band: %.2f a week"
                + " (shipped %.2f)%n", reimplemented, shipped);
        System.out.printf("   %s%n%n", Math.abs(reimplemented - shipped) < 0.5
                ? "matches - this tool is measuring the same thing"
                : "DOES NOT MATCH - the join differs, treat what follows with care");

        System.out.printf("%-34s %8s %8s   %s%n", "ESTIMATOR", "pts/wk", "season",
                "uses the future?");
        System.out.printf("%s%n", "-".repeat(78));

        double bandMean = pooled.stream().mapToDouble(Double::doubleValue)
                .average().orElse(0);
        row("band mean (never touch the wire)", bandMean, "no");
        row("SHIPPED pooled top quartile", reimplemented, "YES - ranks by realised");

        // Per-season rather than pooled: the shipped number takes the best 25%
        // of every season's defences thrown together, so one exceptional season
        // can supply most of them.
        double perSeason = 0;
        for(List<DefSeason> seasons : bySeason.values()){
            List<Double> rates = new ArrayList<>();
            for(DefSeason def : seasons){
                if(def.rank() >= from - 1 && def.rank() < from - 1 + 24){
                    rates.add(def.total() / 18.0);
                }
            }
            rates.sort(Comparator.reverseOrder());
            perSeason += topQuartile(rates);
        }
        perSeason /= bySeason.size();
        row("top quartile within each season", perSeason, "YES - ranks by realised");

        Map<String, Double> preseason = new LinkedHashMap<>();
        Map<String, Double> oracle = new LinkedHashMap<>();
        Map<Integer, Map<String, Double>> form = new LinkedHashMap<>();
        int[] lags = {1, 2, 3, 4, 6};
        for(int lag : lags){
            form.put(lag, new LinkedHashMap<>());
        }
        for(Map.Entry<String, List<DefSeason>> entry : bySeason.entrySet()){
            List<DefSeason> free = new ArrayList<>();
            for(DefSeason def : entry.getValue()){
                if(def.rank() >= from - 1 && def.rank() < from - 1 + 24){
                    free.add(def);
                }
            }
            free.sort(Comparator.comparingInt(DefSeason::rank));
            if(free.isEmpty()){
                continue;
            }
            preseason.put(entry.getKey(), free.get(0).total() / 18.0);
            oracle.put(entry.getKey(), oracle(free) / 18.0);
            for(int lag : lags){
                form.get(lag).put(entry.getKey(), form(free, lag) / 18.0);
            }
        }
        row("hold best undrafted by ADP, all season", mean(preseason), "no");
        for(int lag : lags){
            row(String.format("stream on form, react after week %d", lag),
                    mean(form.get(lag)), "no");
        }
        row("ORACLE best undrafted each week", mean(oracle), "YES - the ceiling");

        System.out.printf("%s%n%n", "-".repeat(78));

        // Season by season, because the season is the unit of independent
        // randomness and a single mean hides how wide this is.
        System.out.printf("SEASON BY SEASON, points a week%n%n");
        System.out.printf("%-10s %10s %10s %10s %10s%n", "SEASON", "preseason",
                "form L=2", "form L=4", "oracle");
        for(String season : bySeason.keySet()){
            if(!preseason.containsKey(season)){
                continue;
            }
            System.out.printf("%-10s %10.2f %10.2f %10.2f %10.2f%n", season,
                    preseason.get(season), form.get(2).get(season),
                    form.get(4).get(season), oracle.get(season));
        }
        Map<String, Double> honest = form.get(2);
        System.out.printf("%-10s %10.2f %10.2f %10.2f %10.2f%n", "mean",
                mean(preseason), mean(form.get(2)), mean(form.get(4)), mean(oracle));
        System.out.printf("%-10s %10.2f %10.2f %10.2f %10.2f%n", "std err",
                stdErr(preseason), stdErr(form.get(2)), stdErr(form.get(4)),
                stdErr(oracle));

        // The same question with no borrowed constant in it. LateRoundValue
        // compares a 135.8 season total against 17 x the wire rate, which is
        // two mismatches at once: the total is 18 weeks and the wire is charged
        // 17, and the 135.8 comes from a different tool on a different join.
        // Here both sides are the same seasons, the same feed and the same 18
        // weeks, so the difference between them is the answer and nothing else.
        System.out.printf("%n%s%n   DRAFTED AGAINST STREAMED, ONE DENOMINATOR%n%s%n",
                "=".repeat(66), "=".repeat(66));
        System.out.printf("   both columns: 18 real weeks, same seasons, same feed.%n"
                + "   a drafted defence is HELD all season, which is what this league%n"
                + "   does with one.%n%n");
        Map<String, Double> topThree = new LinkedHashMap<>();
        Map<String, Double> allTwelve = new LinkedHashMap<>();
        for(Map.Entry<String, List<DefSeason>> entry : bySeason.entrySet()){
            List<DefSeason> taken = new ArrayList<>();
            for(DefSeason def : entry.getValue()){
                if(def.rank() < 12){
                    taken.add(def);
                }
            }
            taken.sort(Comparator.comparingInt(DefSeason::rank));
            if(taken.size() < 12){
                continue;
            }
            topThree.put(entry.getKey(), taken.subList(0, 3).stream()
                    .mapToDouble(DefSeason::total).average().orElse(0));
            allTwelve.put(entry.getKey(), taken.stream()
                    .mapToDouble(DefSeason::total).average().orElse(0));
        }
        System.out.printf("%-10s %12s %12s %12s %12s%n", "SEASON", "DEF1-3 held",
                "DEF1-12 held", "form L=2", "L=2 - DEF1-3");
        for(String season : bySeason.keySet()){
            if(!topThree.containsKey(season) || !form.get(2).containsKey(season)){
                continue;
            }
            double streamed = form.get(2).get(season) * 18;
            System.out.printf("%-10s %12.1f %12.1f %12.1f %+12.1f%n", season,
                    topThree.get(season), allTwelve.get(season), streamed,
                    streamed - topThree.get(season));
        }
        double streamedMean = mean(form.get(2)) * 18;
        System.out.printf("%-10s %12.1f %12.1f %12.1f %+12.1f%n", "mean",
                mean(topThree), mean(allTwelve), streamedMean,
                streamedMean - mean(topThree));
        System.out.printf("%-10s %12.1f %12.1f %12.1f%n", "std err",
                stdErr(topThree), stdErr(allTwelve), stdErr(form.get(2)) * 18);
        System.out.printf("%n   a DRAFTED top-band defence is worth %+.0f a season against"
                + " an honest%n   stream, %+.0f against a stream you never touch. The"
                + " shipped rate says%n   %+.0f. Read the error bars before believing"
                + " any of the three.%n",
                mean(topThree) - streamedMean,
                mean(topThree) - mean(preseason) * 18,
                mean(topThree) - reimplemented * 18);

        // What it does to the decision that rests on it.
        System.out.printf("%n%s%n   WHAT THIS DOES TO THE DEFENCE ADVICE%n%s%n",
                "=".repeat(66), "=".repeat(66));
        System.out.printf("   LateRoundValue prices a drafted defence as the best%n"
                + "   preseason band's season, %.1f, minus 17 weeks of streaming.%n%n",
                LateRoundValue.DEF_BEST_BAND);
        System.out.printf("   %-36s %10s %10s%n", "STREAMING RATE USED", "a season",
                "drafted DEF");
        verdict("SHIPPED top quartile", reimplemented);
        verdict("hold best undrafted by ADP", mean(preseason));
        verdict("stream on form, L=2", mean(honest));
        verdict("stream on form, L=4", mean(form.get(4)));
        verdict("band mean", bandMean);
        System.out.printf("%n   The last column is LateRoundValue's headline number: how"
                + " much a%n   DRAFTED defence beats a streamed one by, over a season."
                + " Negative%n   means let it fall.%n");
    }

    static void row(String label, double rate, String hindsight){
        System.out.printf("%-34s %8.2f %8.0f   %s%n", label, rate, rate * 17, hindsight);
    }

    static void verdict(String label, double rate){
        System.out.printf("   %-36s %10.1f %+10.1f%n", label, rate * 17,
                LateRoundValue.DEF_BEST_BAND - rate * 17);
    }

    static double topQuartile(List<Double> sortedDescending){
        if(sortedDescending.isEmpty()){
            return 0;
        }
        int best = Math.max(1, sortedDescending.size() / 4);
        return sortedDescending.subList(0, best).stream()
                .mapToDouble(Double::doubleValue).average().orElse(0);
    }

    /**
     * The streaming policy a manager can actually run.
     *
     * Weeks 0..lag-1: hold the best undrafted defence by preseason ADP, because
     * that is the only ranking that exists yet. From week lag on: start whoever
     * has the best points per game over the weeks ALREADY PLAYED, preseason ADP
     * breaking ties and standing in for anyone with no games yet. Nothing in the
     * choice for week w can see week w or later.
     */
    static double form(List<DefSeason> free, int lag){
        double total = 0;
        for(int week = 0; week < WEEKS; week++){
            DefSeason pick = free.get(0);
            if(week >= lag){
                double best = -Double.MAX_VALUE;
                for(DefSeason def : free){
                    Double form = def.formThrough(week);
                    if(form != null && form > best){
                        best = form;
                        pick = def;
                    }
                }
            }
            Double scored = pick.weekly()[week];
            total += scored == null ? 0 : scored;
        }
        return total;
    }

    /** Not a policy: the best undrafted defence each week, known afterwards. */
    static double oracle(List<DefSeason> free){
        double total = 0;
        for(int week = 0; week < WEEKS; week++){
            double best = 0;
            for(DefSeason def : free){
                Double scored = def.weekly()[week];
                if(scored != null && scored > best){
                    best = scored;
                }
            }
            total += best;
        }
        return total;
    }

    static double mean(Map<String, Double> values){
        return values.values().stream().mapToDouble(Double::doubleValue)
                .average().orElse(0);
    }

    /** The season is the unit of independent randomness, so n is the seasons. */
    static double stdErr(Map<String, Double> values){
        int n = values.size();
        if(n < 2){
            return 0;
        }
        double m = mean(values);
        double sum = 0;
        for(double value : values.values()){
            sum += (value - m) * (value - m);
        }
        return Math.sqrt(sum / (n - 1) / n);
    }

    /** How many defences this league really takes, averaged over its drafts. */
    static int defencesDraftedPerSeason(AAAConfiguration configuration){
        int defences = 0;
        int drafts = 0;
        for(JsonArray picks : configuration.getPreviousDraftPicks()){
            drafts++;
            for(JsonElement element : picks){
                Player player = Player.getPlayerFromSIDV2(element.getAsJsonObject()
                        .get("player_id").getAsString());
                if(player != null && player.position == Position.DEF){
                    defences++;
                }
            }
        }
        return drafts == 0 ? 12 : Math.round((float) defences / drafts);
    }

    /**
     * Every defence season with its preseason ADP rank and its weekly series.
     *
     * The join is OutcomeDistributions.load's, kept deliberately identical -
     * same ADP files, same name normalisation, same LeagueActuals feed - so a
     * disagreement between this tool and wireRates can only come from the
     * ESTIMATOR and not from the data underneath it.
     */
    static Map<String, List<DefSeason>> load() throws Exception {
        Map<String, List<DefSeason>> bySeason = new LinkedHashMap<>();
        File[] files = new File("data").listFiles();
        if(files == null){
            return bySeason;
        }
        List<File> boards = new ArrayList<>();
        for(File file : files){
            if(file.getName().matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                boards.add(file);
            }
        }
        boards.sort(Comparator.comparing(File::getName));
        for(File file : boards){
            String season = file.getName().split("-")[3];
            List<DefSeason> loaded = loadOne(file, season);
            if(loaded.size() >= 12){
                bySeason.put(season, loaded);
            }
        }
        return bySeason;
    }

    static List<DefSeason> loadOne(File adpFile, String season) throws Exception {
        Map<String, Double> totals = new HashMap<>(LeagueActuals.seasonPoints(season));
        totals.putAll(LeagueActuals.seasonDefencePoints(season));
        Map<String, String> idByName = new HashMap<>();
        for(String id : totals.keySet()){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null){
                idByName.putIfAbsent(TightEndTiming.normalise(
                        player.firstName + " " + player.lastName), id);
            }
        }
        List<Map<String, Double>> weekly = new ArrayList<>();
        for(int week = 1; week <= WEEKS; week++){
            weekly.add(LeagueActuals.weeklyPoints(season, week));
        }

        List<String> lines = Files.readAllLines(adpFile.toPath());
        String[] header = lines.get(0).split(",");
        int nameCol = -1;
        int posCol = -1;
        int adpCol = -1;
        for(int c = 0; c < header.length; c++){
            if(header[c].equals("name")){ nameCol = c; }
            if(header[c].equals("position")){ posCol = c; }
            if(header[c].equals("AVG")){ adpCol = c; }
        }
        if(nameCol < 0 || posCol < 0 || adpCol < 0){
            return List.of();
        }
        List<String[]> rows = new ArrayList<>();
        for(String line : lines.subList(1, lines.size())){
            String[] cells = line.split(",");
            if(cells.length > Math.max(adpCol, Math.max(nameCol, posCol))
                    && cells[adpCol].matches("\\d+(\\.\\d+)?")){
                rows.add(cells);
            }
        }
        final int adpColumn = adpCol;
        rows.sort(Comparator.comparingDouble(r -> Double.parseDouble(r[adpColumn])));

        List<DefSeason> out = new ArrayList<>();
        int rank = 0;
        for(String[] cells : rows){
            String label = cells[posCol].trim();
            if(!label.equals("DST") && !label.equals("DEF")){
                continue;
            }
            String id = idByName.get(TightEndTiming.normalise(cells[nameCol]));
            if(id == null){
                continue;
            }
            Double[] series = new Double[WEEKS];
            for(int week = 0; week < WEEKS; week++){
                series[week] = weekly.get(week).get(id);
            }
            out.add(new DefSeason(season, id, cells[nameCol].trim(), rank++, series));
        }
        return out;
    }
}
