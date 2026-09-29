import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How much of a man's value is the yardstick's own sampling.
 *
 * WeeklyStarterValue draws every man's outcome scenarios once, at construction,
 * from a fixed seed, and reuses them in every simulated draft. Whatever error
 * that sample carries is the same in every trial, so the trial-to-trial
 * standard error a ladder prints cannot see it (TRAPS #79). This tool builds
 * the objective under several seeds and prints, for one fixed roster, the total
 * and each man's marginal (roster with him minus roster without him) under each
 * seed, then the largest seed-to-seed spread. That spread is the floor under any
 * difference read off Keepers16: two men whose values differ by less than it
 * are not separated by the yardstick.
 *
 *   ./gradlew run -Pmain=ObjectiveStability [-Pscenarios=480] [-Pseeds=3] [-Pme=<user id>]
 *
 * The roster is the projected men the rules let the manager keep (his roster on
 * the pre-draft fixture), so the numbers are comparable with Keepers16's ALONE rows.
 */
public class ObjectiveStability {

    public record Line(String name, double[] marginals) {
        double spread(){
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for(double m : marginals){ lo = Math.min(lo, m); hi = Math.max(hi, m); }
            return hi - lo;
        }
    }

    /**
     * A floor as a report measured it: the worst spread, the day, the scenario
     * count and the projection source it was measured on.
     *
     * THE FLOOR BELONGS TO THE FEED IT WAS MEASURED ON (2026-09-29). The wire
     * moved to the games left, and the plan was to carry the 6.8 across by
     * the weeks those totals span - 6.8 x 15/18 = 5.7 in week 4 - on the
     * argument that the objective is proportional to the totals (true, and
     * WireUnitsTest proves it) and that games-left totals are that fraction of
     * season totals (false). Measured before it shipped, on the same roster,
     * seeds and scenario count: 11.3 on the games left, and the roster's value
     * 1717 against 1752 - Sleeper's weekly lines for the weeks to come add up
     * to about what its season number does. So a pricing reads the floor
     * measured on its own source, never a conversion of another's (TRAPS #151).
     */
    record Floor(double points, String date, int scenarios, String source) {}

    /** The report's name suffix for a source: none for the season feed the original floor was measured on. */
    static String suffix(String source){
        return source.equals("sleeper") ? "" : "-" + source.replace(':', '_').replace(',', '_');
    }

    /** A report's floor, or null if the text is not a stability report. */
    static Floor parse(String text, String date, String source){
        java.util.regex.Matcher worst = java.util.regex.Pattern.compile(
                "worst seed-to-seed spread of a marginal: ([\\d.]+) points").matcher(text);
        java.util.regex.Matcher count = java.util.regex.Pattern.compile("\\((\\d+) scenarios,").matcher(text);
        if(!worst.find() || !count.find()){
            return null;
        }
        return new Floor(Double.parseDouble(worst.group(1)), date, Integer.parseInt(count.group(1)), source);
    }

    /** The newest floor measured on {@code source} in {@code directory}, or null if it has never been measured there. */
    static Floor measured(Path directory, String source){
        java.util.regex.Pattern name = java.util.regex.Pattern.compile(
                "objective-stability-(\\d{4}-\\d{2}-\\d{2})" + java.util.regex.Pattern.quote(suffix(source)) + "\\.txt");
        try(var files = Files.list(directory)){
            Path newest = files.filter(p -> name.matcher(p.getFileName().toString()).matches())
                    .max(java.util.Comparator.comparing(p -> p.getFileName().toString())).orElse(null);
            if(newest == null){
                return null;
            }
            java.util.regex.Matcher m = name.matcher(newest.getFileName().toString());
            m.matches();
            return parse(Files.readString(newest), m.group(1), source);
        }
        catch(java.io.IOException unreadable){
            return null;
        }
    }

    /**
     * The floor a pricing on {@code source} is judged against: -PswapFloor if
     * given, else the one measured on that source. A source never measured
     * falls back to the season feed's, loudly, since that is a yardstick from
     * another population.
     */
    static Floor floorFor(String source){
        String given = System.getProperty("swapFloor");
        if(given != null){
            return new Floor(Double.parseDouble(given), "given", -1, "-PswapFloor");
        }
        Floor own = measured(Path.of("data"), source);
        if(own != null){
            return own;
        }
        Floor season = measured(Path.of("data"), "sleeper");
        System.out.println("NO NOISE FLOOR MEASURED ON " + source + ": judging it against the season feed's"
                + (season == null ? " 6.8" : " " + season.points()) + ". Measure it:"
                + " ./gradlew run -Pmain=ObjectiveStability -Pprojections=" + source);
        return season != null ? season : new Floor(6.8, "2026-09-04", 480, "sleeper");
    }

    /** Largest seed-to-seed spread over the lines. */
    static double worstSpread(List<Line> lines){
        double worst = 0;
        for(Line line : lines){ worst = Math.max(worst, line.spread()); }
        return worst;
    }

    public static void main(String[] args) throws Exception {
        if(System.getProperty("fixtureDir") == null){
            System.setProperty("fixtureDir", Path.of("data", "fixtures", "2026-pre-draft").toString());
        }
        System.setProperty("scheduleRounds", "16");
        System.setProperty("fullRounds", "true");
        LiveDraft.freezeWith(List.of());
        int scenarios = Integer.getInteger("scenarios", 480);
        int seeds = Integer.getInteger("seeds", 3);
        long[] seedValues = {424_242L, 7L, 99L, 2026L, 31_337L, 8_675_309L};
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        String user = System.getProperty("me", configuration.getMyID());
        // the floor the wire applies was measured on "sleeper"; another source is
        // written under its own name, so it can never become the newest "the floor"
        String source = System.getProperty("projections", "sleeper");
        Map<String, Double> points = ProjectionSources.resolve(source);

        List<String> roster = new ArrayList<>();
        Map<String, String> nameOf = new HashMap<>();
        for(Keeper k : KeeperChooser.eligibleCandidates(configuration, user)){
            String id = k.player.sleeperIDString;
            if(points.getOrDefault(id, 0.0) <= 0){ continue; }
            roster.add(id);
            nameOf.put(id, k.player.firstName + " " + k.player.lastName + " " + k.player.position);
        }
        double[] totals = new double[seeds];
        Map<String, double[]> marginals = new HashMap<>();
        for(String id : roster){ marginals.put(id, new double[seeds]); }
        for(int i = 0; i < seeds; i++){
            WeeklyStarterValue value = WeeklyStarterValue.forCurrentBoard(configuration, points, scenarios, seedValues[i % seedValues.length]);
            totals[i] = value.of(roster);
            for(String id : roster){
                List<String> without = new ArrayList<>(roster);
                without.remove(id);
                marginals.get(id)[i] = totals[i] - value.of(without);
            }
        }
        List<Line> lines = new ArrayList<>();
        for(String id : roster){ lines.add(new Line(nameOf.get(id), marginals.get(id))); }
        lines.sort((a, b) -> Double.compare(b.marginals()[0], a.marginals()[0]));

        StringBuilder out = new StringBuilder();
        out.append(String.format("OBJECTIVE STABILITY  %s  (%d scenarios, %d seeds, roster of %d projected men the rules let %s keep)%s%n",
                LocalDate.now(), scenarios, seeds, roster.size(), configuration.getUserIDToDisplayName().getOrDefault(user, user),
                source.equals("sleeper") ? "" : "  priced on " + source));
        out.append("Each column is one seed of WeeklyStarterValue; MARGINAL = roster with the man minus roster without him.\n\n");
        out.append(String.format("%-28s", "roster total"));
        for(double t : totals){ out.append(String.format(" %8.1f", t)); }
        out.append(String.format("   spread %5.1f%n", new Line("", totals).spread()));
        for(Line line : lines){
            out.append(String.format("%-28s", line.name()));
            for(double m : line.marginals()){ out.append(String.format(" %+8.1f", m)); }
            out.append(String.format("   spread %5.1f%n", line.spread()));
        }
        out.append(String.format("%nworst seed-to-seed spread of a marginal: %.1f points - two men closer than this are not separated by the yardstick%n", worstSpread(lines)));
        System.out.print(out);
        Path target = Path.of("data", "objective-stability-" + LocalDate.now() + suffix(source) + ".txt");
        Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
        System.out.println("written to " + target);
    }
}
