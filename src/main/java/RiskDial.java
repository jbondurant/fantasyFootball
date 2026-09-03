import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Should a team below the playoff cutoff draft for variance?
 *
 * PlayoffOdds measured that six of twelve qualify and that this seat projects
 * below the line. Below a threshold, variance is free: a bust costs little
 * because the season was already likely lost, while a boom is what crosses the
 * bar. Above the line the sign flips. DraftPlanner already exposes the dial -
 * branches are ranked by mean - lambda * (mean - p_q) - so a negative lambda
 * asks it to prefer the wider outcome.
 *
 * The catch, and the reason this tool reports rather than recommends: that
 * dial moves AVAILABILITY risk, not PERFORMANCE risk. Its p_q is the spread
 * over which players happen to be on the board when my pick arrives, not the
 * spread between a player's boom and bust seasons. A team that needs variance
 * needs boom-or-bust PLAYERS, which lives in FogFit, not in whether the board
 * fell kindly. So this measures whether the dial changes anything, and says
 * plainly that a change would be the wrong lever moving.
 *
 *   ./gradlew run -Pmain=RiskDial -Poutlook=data/league-outlook-500-clean.txt
 *   ./gradlew run -Pmain=RiskDial [-Ptrials=300] [-Pdraws=200000] [-Pkeepers=Tuten,Purdy]
 */
public class RiskDial {

    /** z for the 10th percentile - the quantile DraftPlanner reports by default. */
    static final double Z10 = 1.2816;

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int rollouts = Integer.getInteger("trials", 300);
        int draws = Integer.getInteger("draws", 200_000);
        double q = Double.parseDouble(System.getProperty("quantile", "0.10"));
        String outlookPath = System.getProperty("outlook",
                "data/league-outlook-500-clean.txt");
        String meName = System.getProperty("me", "justinb314");

        List<Keeper> myKeepers = DraftPlanner.keepersFromProperty(configuration);
        int lastCompleted = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, lastCompleted);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, lastCompleted,
                earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, myKeepers,
                model, earliness);

        double[] opponents = opponents(outlookPath, meName);
        if(opponents.length == 0){
            System.out.println("could not read seats from " + outlookPath
                    + " - run LeagueOutlook first");
            return;
        }
        List<PlayoffOdds.TeamSeason> pooled = PlayoffOdds.history(configuration).pooled();

        // The comparison that decides this: whatever spread the dial can buy at
        // the draft, against the spread the season adds on its own.
        double[] field = new double[opponents.length + 1];
        System.arraycopy(opponents, 0, field, 1, opponents.length);
        field[0] = java.util.Arrays.stream(opponents).average().orElse(1);
        double leagueMean = java.util.Arrays.stream(field).average().orElse(1);
        double seasonNoise = PlayoffOdds.inSeasonNoise(pooled, field) * leagueMean;

        System.out.printf("%nsweeping the risk dial at %d rollouts, quantile p%.0f%n",
                rollouts, q * 100);
        System.out.printf("%d opponents read from %s%n%n", opponents.length, outlookPath);
        System.out.printf("%8s  %-30s %9s %9s %8s %10s%n", "LAMBDA", "PLAN",
                "mean", "p10", "spread", "P(top 6)");

        double[] lambdas = {1.0, 0.5, 0.0, -0.5, -1.0, -1.5};
        List<String> plans = new ArrayList<>();
        double widestSpread = 0;
        double narrowestSpread = Double.MAX_VALUE;
        for(double lambda : lambdas){
            DraftPlanner.Plan plan = planner.plan(rollouts, lambda, q, DraftSimulator.SEED);
            double spread = Math.max(0, (plan.mean() - plan.p10()) / Z10);
            double odds = topSix(plan.mean(), spread, opponents, pooled, draws,
                    DraftSimulator.SEED);
            widestSpread = Math.max(widestSpread, spread);
            narrowestSpread = Math.min(narrowestSpread, spread);
            String shape = shorthand(plan.positions());
            plans.add(shape);
            System.out.printf("%+8.2f  %-30s %9.1f %9.1f %8.1f %9.0f%%%n", lambda, shape,
                    plan.mean(), plan.p10(), spread, 100 * odds);
        }

        long distinct = plans.stream().distinct().count();
        if(distinct == 1){
            System.out.printf("%nThe plan is IDENTICAL at every setting: %s.%nThe dial"
                    + " changes nothing, so there is no risk decision to make in"
                    + " rounds 1-7.%n", plans.get(0));
        }
        else {
            System.out.printf("%nThe plan changes across settings (%d distinct"
                    + " sequences).%nTreat that with suspicion: lambda moves"
                    + " availability risk - how kindly the board%nfalls - not whether"
                    + " a player booms or busts. A below-cutoff team wants the"
                    + "%nsecond kind, and this dial cannot buy it.%n", distinct);
        }
        System.out.printf("%nspread = (mean - p10) / 1.2816, the plan's own outcome spread"
                + " in points.%n%nAnd here is why none of it moves the odds:%n"
                + "   the whole dial is worth %.0f points of spread%n"
                + "   the season adds %.0f points of its own%n"
                + "The draft's variance is swamped %.0f to 1. There is no risk lever"
                + " here to pull.%n", widestSpread - narrowestSpread, seasonNoise,
                seasonNoise / Math.max(1, widestSpread - narrowestSpread));
    }

    /** Every seat except mine, as best-nine projections. */
    static double[] opponents(String path, String meName){
        List<Double> values = new ArrayList<>();
        java.util.regex.Pattern row = java.util.regex.Pattern.compile(
                "^\\s*\\d+\\s+(\\S+)\\s+\\d+\\s+(\\d+\\.\\d+)\\s+(\\d+\\.\\d+)");
        try {
            for(String line : java.nio.file.Files.readAllLines(
                    java.nio.file.Path.of(path))){
                java.util.regex.Matcher matcher = row.matcher(line);
                if(matcher.find() && !matcher.group(1).equals(meName)){
                    values.add(Double.parseDouble(matcher.group(2)));
                }
            }
        }
        catch(Exception unreadable){
            return new double[0];
        }
        double[] out = new double[values.size()];
        for(int i = 0; i < out.length; i++){
            out[i] = values.get(i);
        }
        return out;
    }

    /**
     * My plan against the field. My season is the plan's mean plus its own
     * spread plus in-season noise; each opponent is his projection plus the
     * same in-season noise. Common random numbers across lambdas, so the
     * difference between two rows is the dial and not the sampler.
     */
    static double topSix(double myMean, double mySpread, double[] opponents,
                         List<PlayoffOdds.TeamSeason> pooled, int draws, long seed){
        double[] all = new double[opponents.length + 1];
        all[0] = myMean;
        System.arraycopy(opponents, 0, all, 1, opponents.length);
        double leagueMean = java.util.Arrays.stream(all).average().orElse(1);
        double noise = PlayoffOdds.inSeasonNoise(pooled, all) * leagueMean;

        Random random = new Random(seed);
        int made = 0;
        for(int draw = 0; draw < draws; draw++){
            double mine = myMean + random.nextGaussian() * mySpread
                    + random.nextGaussian() * noise;
            int better = 0;
            for(double opponent : opponents){
                if(opponent + random.nextGaussian() * noise > mine){
                    better++;
                }
            }
            if(better < 6){
                made++;
            }
        }
        return (double) made / draws;
    }

    static String shorthand(List<Position> positions){
        StringBuilder text = new StringBuilder();
        for(Position position : positions){
            text.append(position).append(' ');
        }
        return text.toString().trim();
    }
}
