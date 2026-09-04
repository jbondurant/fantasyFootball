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
        Map<String, Double> points = ProjectionSources.resolve(System.getProperty("projections", "sleeper"));

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
        out.append(String.format("OBJECTIVE STABILITY  %s  (%d scenarios, %d seeds, roster of %d projected men the rules let %s keep)%n",
                LocalDate.now(), scenarios, seeds, roster.size(), configuration.getUserIDToDisplayName().getOrDefault(user, user)));
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
        Path target = Path.of("data", "objective-stability-" + LocalDate.now() + ".txt");
        Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
        System.out.println("written to " + target);
    }
}
