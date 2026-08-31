import PlayerImportAndSetup.Position;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The three measured numbers inside RiskDiscountedValue, and which of them the
 * answer actually depends on.
 *
 * The objective is a definition, and a definition cannot be wrong. But it takes
 * three inputs that are not definitions - a trust coefficient, a games-missed
 * model, and a replacement rank per position - and each has been carried on the
 * strength of the sentence that introduced it. This runs the model against
 * defensible alternatives to each and reports the two things that matter: does
 * the sixteen-round plan change, and does the backtest move.
 *
 * READ THE BACKTEST COLUMN WITH THE ERROR BAR IN MIND. Five seasons, a spread
 * near two hundred points a season, so the mean carries a standard error close
 * to ninety. A variant that gains thirty points has not been shown to be
 * better; it has been shown not to be obviously worse. The PLAN column is the
 * sharper instrument here, because it is deterministic given the constants.
 *
 * AND KNOW WHAT THE BACKTEST CANNOT SEE. It feeds the objective
 * WeeklyStarterValue.expectedFromRank - a value read off rank, already smooth
 * in rank by construction - so a projection there sits almost exactly on its
 * own neighbourhood mean and the trust coefficient has nothing left to shrink.
 * The backtest is a fair test of the replacement ranks and close to blind to
 * the trust coefficient. That is a property of the instrument, not a finding
 * about the constant, and the two must not be confused.
 *
 *   ./gradlew run -Pmain=ObjectiveAudit [-Ptrials=80] [-Pkeepers=Tuten,Purdy]
 */
public class ObjectiveAudit {

    static final Position[] ALL = {Position.QB, Position.RB, Position.WR, Position.TE,
            Position.DEF};

    /** One configuration of the three constants. */
    record Variant(String group, String name, String provenance,
                   Map<Position, Integer> ranks, Map<Position, Double> trust,
                   int window, Map<Position, Double> positionMissed,
                   Map<String, Double> playerMissed, boolean leaveOneOutTrust){}

    static AAAConfiguration configuration;
    static DraftPlanner planner;
    static Map<String, PlanBacktest.Board> boards = new LinkedHashMap<>();
    static Map<String, List<OutcomeDistributions.Season>> bySeason;

    public static void main(String[] args) throws Exception {
        System.setProperty("scheduleRounds", System.getProperty("scheduleRounds", "16"));
        configuration = AAAConfiguration.getInstance();
        int rollouts = Integer.getInteger("trials", 80);

        // The -Pdeviate footgun: a nightly run once reproduced the committed
        // plan exactly because this defaulted to "never deviate", and the
        // result looked like agreement when it was the prior replaying itself.
        // Print every knob that can silently decide the answer.
        System.out.printf("%nFLAGS IN FORCE: deviate=%.0f scarcity=%.2f frontShape='%s'"
                        + " qbFrom=%d defLast=%s riskObjective=%s%n",
                PolicyBacktest.DEVIATE, PolicyBacktest.SCARCITY,
                PolicyBacktest.FRONT_SHAPE, PolicyBacktest.QB_FROM,
                PolicyBacktest.DEF_LAST, Boolean.getBoolean("riskObjective"));
        if(PolicyBacktest.DEVIATE != 0 || !PolicyBacktest.FRONT_SHAPE.isBlank()){
            System.out.println("   ^ the plan is being pinned; these numbers are not"
                    + " the model's own choice");
        }

        List<Keeper> myKeepers = DraftPlanner.keepersFromProperty(configuration);
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        planner = DraftPlanner.forCurrentSeason(configuration, myKeepers, model, earliness);

        bySeason = OutcomeDistributions.all();
        for(File file : new File("data").listFiles()){
            if(file.getName().matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                String season = file.getName().split("-")[3];
                PlanBacktest.Board board = PlanBacktest.board(file, season);
                if(board != null && board.ids().size() > 150){
                    boards.put(season, board);
                }
            }
        }
        List<String> seasons = new ArrayList<>(boards.keySet());
        seasons.sort(Comparator.naturalOrder());

        // the shipped configuration, every constant as committed
        Map<Position, Integer> shippedRanks = InsuranceTest.replacementRanks(configuration);
        Map<Position, Double> shippedTrust = PositionPredictability.reliability();
        Map<Position, Double> shippedMissed = RiskDiscountedValue.positionGamesMissed();
        Map<String, Double> sharks = RiskDiscountedValue.draftSharks();

        List<Variant> variants = new ArrayList<>();
        variants.add(new Variant("BASELINE", "as committed",
                "InsuranceTest ranks, Spearman trust, DraftSharks + position averages",
                shippedRanks, shippedTrust, RiskDiscountedValue.NEIGHBOURHOOD,
                shippedMissed, sharks, false));

        // ---- 1. REPLACEMENT RANKS -------------------------------------------
        variants.add(new Variant("1 REPLACEMENT", "best left at my last pick",
                "ReplacementRanks.atMyLastPick - measured from 5 drafts",
                ReplacementRanks.atMyLastPick(configuration), shippedTrust,
                RiskDiscountedValue.NEIGHBOURHOOD, shippedMissed, sharks, false));
        variants.add(new Variant("1 REPLACEMENT", "best nobody rosters, DEF counted",
                "ReplacementRanks.undrafted - same question, DEF no longer hard-coded",
                ReplacementRanks.undrafted(configuration), shippedTrust,
                RiskDiscountedValue.NEIGHBOURHOOD, shippedMissed, sharks, false));
        variants.add(new Variant("1 REPLACEMENT", "starters only (textbook VORP)",
                "12 teams x the lineup: QB12 RB24 WR36 TE12 DEF12",
                ReplacementRanks.mandatory(configuration), shippedTrust,
                RiskDiscountedValue.NEIGHBOURHOOD, shippedMissed, sharks, false));
        variants.add(new Variant("1 REPLACEMENT", "DEF only, 13 -> 11",
                "the one position where the two questions disagree",
                withDef(shippedRanks, 11), shippedTrust,
                RiskDiscountedValue.NEIGHBOURHOOD, shippedMissed, sharks, false));

        // ---- 2. TRUST AND ITS WINDOW ----------------------------------------
        variants.add(new Variant("2 TRUST", "no shrinkage at all",
                "trust = 1: believe the projection, the null the rest is judged against",
                shippedRanks, ones(), RiskDiscountedValue.NEIGHBOURHOOD, shippedMissed,
                sharks, false));
        variants.add(new Variant("2 TRUST", "measured slope, w=6",
                "TrustCoefficient: regression slope in the objective's own form",
                shippedRanks, TrustCoefficient.measured(configuration, 6, null), 6,
                shippedMissed, sharks, true));
        variants.add(new Variant("2 TRUST", "DEF trust 0.277 -> 0",
                "the measured DEF slope at w=6 is -0.00; 0.277 is the Spearman",
                shippedRanks, withTrust(shippedTrust, Position.DEF, 0.0),
                RiskDiscountedValue.NEIGHBOURHOOD, shippedMissed, sharks, false));
        for(int window : new int[]{2, 12, 30}){
            variants.add(new Variant("2 WINDOW", "window +-" + window + ", shipped trust",
                    "the +-6 was hand-chosen on 2026-08-30 and never tested",
                    shippedRanks, shippedTrust, window, shippedMissed, sharks, false));
        }
        for(int window : new int[]{2, 12, 30}){
            variants.add(new Variant("2 WINDOW", "window +-" + window + ", matched trust",
                    "trust re-measured AT this window - the self-consistent pair",
                    shippedRanks, TrustCoefficient.measured(configuration, window, null),
                    window, shippedMissed, sharks, true));
        }

        // ---- 3. AVAILABILITY -------------------------------------------------
        variants.add(new Variant("3 AVAILABILITY", "no discount at all",
                "delete the term: value = believed projection",
                shippedRanks, shippedTrust, RiskDiscountedValue.NEIGHBOURHOOD,
                zeros(), Map.of(), false));
        variants.add(new Variant("3 AVAILABILITY", "position averages only",
                "drop the 329-row DraftSharks file, keep the measured position means",
                shippedRanks, shippedTrust, RiskDiscountedValue.NEIGHBOURHOOD,
                shippedMissed, Map.of(), false));
        variants.add(new Variant("3 AVAILABILITY", "DEF discounted like skill",
                "DEF is the only position with NO discount; give it the skill average",
                shippedRanks, shippedTrust, RiskDiscountedValue.NEIGHBOURHOOD,
                withDefMissed(shippedMissed), sharks, false));

        // Five variants came back at exactly 1916 on the first run. Identical
        // means are either five policies making identical picks - which is the
        // honest answer when the backtest is coarser than the plan - or a
        // configuration silently collapsing, which is the -Pdeviate fault
        // again. Printing the seasons separately is what tells them apart, so
        // it is printed always rather than when somebody remembers to look.
        boolean plans = !"false".equals(System.getProperty("plans"));
        System.out.printf("%nWHAT EACH CONSTANT IS WORTH%n%n");
        System.out.printf("%-15s %-30s", "CONSTANT", "VARIANT");
        for(String season : seasons){
            System.out.printf(" %6s", season);
        }
        System.out.printf(" %8s %8s %9s   %s%n", "mean", "vs base", "+-se",
                plans ? "16-round plan" : "(plans skipped)");

        String basePlan = null;
        double baseScore = 0;
        double[] baseScores = null;
        Map<String, double[]> influence = new LinkedHashMap<>();
        for(Variant variant : variants){
            double[] scores = new double[seasons.size()];
            for(int i = 0; i < seasons.size(); i++){
                final String season = seasons.get(i);
                final Variant v = variant;
                List<Position> chosen = new ArrayList<>();
                Function<Map<String, Double>, RosterValue> factory = expected ->
                        build(v, expected, season);
                scores[i] = PolicyBacktest.runPolicy(boards.get(season),
                        PolicyBacktest.poolWithout(bySeason, season), 300, chosen,
                        factory);
            }
            double mean = java.util.Arrays.stream(scores).average().orElse(0);
            String plan = "-";
            if(plans){
                planner.scoreWith(build(variant, planner.points(), null));
                plan = Draft16.shape(planner.plan(rollouts, 0, 0.10,
                        DraftSimulator.SEED).positions());
            }
            if(basePlan == null){
                basePlan = plan;
                baseScore = mean;
                baseScores = scores;
            }
            System.out.printf("%-15s %-30s", variant.group(), variant.name());
            for(double score : scores){
                System.out.printf(" %6.0f", score);
            }
            System.out.printf(" %8.0f %+8.0f %9s   %s%s%n", mean, mean - baseScore,
                    String.format("%.0f", pairedStandardError(scores, baseScores)), plan,
                    plan.equals(basePlan) ? "" : "   <- PLAN CHANGED");
            influence.computeIfAbsent(variant.group(), u -> new double[]{0, 0})[0] =
                    Math.max(influence.get(variant.group())[0], Math.abs(mean - baseScore));
            if(!plan.equals(basePlan)){
                influence.get(variant.group())[1]++;
            }
        }

        System.out.printf("%n%-15s %14s %18s%n", "CONSTANT", "biggest move", "plans changed");
        for(Map.Entry<String, double[]> entry : influence.entrySet()){
            if(entry.getKey().equals("BASELINE")){
                continue;
            }
            System.out.printf("%-15s %14.0f %18.0f%n", entry.getKey(),
                    entry.getValue()[0], entry.getValue()[1]);
        }
        System.out.println("\nRead the vs-base column against its own +-se, not against"
                + " a remembered rule of\nthumb. The se is PAIRED - the standard error"
                + " of the season-by-season difference\nfrom the baseline, which is the"
                + " right test because every variant is scored on\nthe same five"
                + " seasons and most of the season-to-season spread cancels. It is\nstill"
                + " an n of five: nothing here clears two of its own standard errors, so"
                + " no\nvariant in this table has been shown to be better. A changed"
                + " PLAN is the finding\nthat survives, because the plan is what gets"
                + " drafted on Tuesday.");

        System.out.printf("%nWHERE THE TRUST TERM ACTUALLY BITES (shipped"
                + " configuration)%n%n");
        RiskDiscountedValue base = build(variants.get(0), planner.points(), null);
        System.out.printf("%-5s %12s %12s %12s %12s%n", "POS", "rank 1-6", "rank 7-18",
                "rank 19-40", "rank 41+");
        Map<Position, List<Map.Entry<String, Double>>> ranked = new EnumMap<>(Position.class);
        for(Map.Entry<String, Double> entry : planner.points().entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player != null){
                ranked.computeIfAbsent(player.position, u -> new ArrayList<>()).add(entry);
            }
        }
        for(Position position : ALL){
            List<Map.Entry<String, Double>> group = ranked.get(position);
            if(group == null){
                continue;
            }
            group.sort(Map.Entry.<String, Double>comparingByValue().reversed());
            double[] shift = new double[4];
            int[] count = new int[4];
            for(int i = 0; i < group.size(); i++){
                int band = i < 6 ? 0 : i < 18 ? 1 : i < 40 ? 2 : 3;
                shift[band] += base.believedOf(group.get(i).getKey())
                        - group.get(i).getValue();
                count[band]++;
            }
            System.out.printf("%-5s", position);
            for(int band = 0; band < 4; band++){
                System.out.printf(" %12s", count[band] == 0 ? "-"
                        : String.format("%+.1f", shift[band] / count[band]));
            }
            System.out.println();
        }
        System.out.println("\nMean points the trust term adds or removes, by rank band."
                + " A plus-or-minus-six\nwindow is nearly flat away from the top of a"
                + " position, so the whole effect of\nthe trust coefficient lands on the"
                + " first few men at each position - and on\ndefences, where thirty-two"
                + " players make every window a large share of the\nposition. Everywhere"
                + " else the coefficient could be anything at all.");

        availability(shippedMissed, sharks, ranked);
        defenceTiming(seasons);
    }

    /**
     * The one output all three constants are arguing about, tested directly.
     *
     * Every variant above still takes a defence in round 8, six rounds before
     * the committed plan does, and no constant moves it except a window so wide
     * it re-creates the position-mean shrinkage that crushed elite tight ends.
     * So stop asking the model and ask the outcomes: score the model's own
     * sixteen-round shape, then the same shape with the defence moved to the
     * last pick and the freed round spent on the position the model wanted
     * next. Nothing here is a projection - PlanBacktest.score fills lineups by
     * expected points and grades them on what really happened.
     */
    static void defenceTiming(List<String> seasons){
        Map<String, String> shapes = new LinkedHashMap<>();
        shapes.put("the model's plan (DEF r8)",
                "RB RB RB WR WR WR TE DEF WR QB QB TE RB RB");
        shapes.put("...DEF moved to the last pick",
                "RB RB RB WR WR WR TE WR QB QB TE RB RB DEF");
        shapes.put("RUNBOOK committed (DEF last)",
                PlanBacktest.STRATEGIES.get("RUNBOOK committed"));

        System.out.printf("%nWHAT THE ROUND-8 DEFENCE COSTS, ON OUTCOMES%n%n");
        System.out.printf("%-32s", "SHAPE");
        for(String season : seasons){
            System.out.printf(" %6s", season);
        }
        System.out.printf(" %8s%n", "mean");
        double[] first = null;
        for(Map.Entry<String, String> entry : shapes.entrySet()){
            double[] scores = new double[seasons.size()];
            for(int i = 0; i < seasons.size(); i++){
                scores[i] = PlanBacktest.score(boards.get(seasons.get(i)),
                        entry.getValue());
            }
            double mean = java.util.Arrays.stream(scores).average().orElse(0);
            System.out.printf("%-32s", entry.getKey());
            for(double score : scores){
                System.out.printf(" %6.0f", score);
            }
            System.out.printf(" %8.0f", mean);
            if(first == null){
                first = scores;
            }
            else {
                System.out.printf("   %+.0f +- %.0f", mean
                        - java.util.Arrays.stream(first).average().orElse(0),
                        pairedStandardError(scores, first));
            }
            System.out.println();
        }
        System.out.println("\nThe objective prices the best available defence at pick 90"
                + " above every skill\nplayer on the board. PolicyBacktest's own"
                + " [not legal] row already said a DRAFTED\ndefence is worth about"
                + " nine points a season over a wire one - inside noise, and\nfar less"
                + " than a round-8 pick returns anywhere else. These rows put a"
                + " number\non the specific error rather than on the position.");
    }

    /**
     * The games-missed model's two halves, and the seam between them.
     *
     * A player in the DraftSharks export is discounted by his own projected
     * absence; a player outside it by his position's historical average. Those
     * are not the same scale - one is a forecast for 2026, the other is what
     * actually happened from 2021 to 2025, and the second is roughly twice the
     * first. So whether a man appears in a 329-row CSV is worth about a tenth
     * of his value, which is not a fact about football.
     */
    static void availability(Map<Position, Double> positionMissed,
                             Map<String, Double> sharks,
                             Map<Position, List<Map.Entry<String, Double>>> ranked){
        System.out.printf("%nTHE GAMES-MISSED MODEL, AND THE SEAM IN IT%n%n");
        System.out.printf("%-5s %10s %10s %12s %12s %10s   %s%n", "POS", "in file",
                "outside", "sharks mult", "fallback", "seam", "top-24 covered");
        for(Position position : ALL){
            List<Map.Entry<String, Double>> group = ranked.get(position);
            if(group == null){
                continue;
            }
            double sharksTotal = 0;
            int inFile = 0;
            int outside = 0;
            int coveredTop = 0;
            for(int i = 0; i < group.size(); i++){
                Player player = Player.getPlayerFromSIDV2(group.get(i).getKey());
                Double missed = sharks.get(player.firstName + " " + player.lastName);
                if(missed != null){
                    sharksTotal += missed;
                    inFile++;
                    if(i < 24){
                        coveredTop++;
                    }
                }
                else {
                    outside++;
                }
            }
            double sharksMean = inFile == 0 ? Double.NaN : sharksTotal / inFile;
            double fallback = positionMissed.getOrDefault(position, 0.0);
            double sharksMultiplier = (17.0 - sharksMean) / 17.0;
            double fallbackMultiplier = (17.0 - fallback) / 17.0;
            System.out.printf("%-5s %10d %10d %12s %12.3f %9.1f%%   %d of 24%n",
                    position, inFile, outside,
                    inFile == 0 ? "-" : String.format("%.3f", sharksMultiplier),
                    fallbackMultiplier,
                    inFile == 0 ? 0.0
                            : 100 * (sharksMultiplier - fallbackMultiplier)
                                    / fallbackMultiplier,
                    coveredTop);
        }
        System.out.println("\nThe seam column is what membership of the CSV is worth."
                + " It runs one way: the\ndrafted man is in the file and keeps ~92% of"
                + " his projection, the replacement\nman is not and keeps ~82%, so every"
                + " skill marginal is inflated against its own\nreplacement. Defences"
                + " are in neither table and keep 100%, which is literally\ntrue - a"
                + " defence does not tear an achilles - and is still the largest"
                + " single\ncross-position asymmetry in the objective.");
        System.out.println("\nOn the functional form: games played and points per game"
                + " correlate +0.35 at\nrunning back and +0.67 at quarterback"
                + " (OutcomeDistributions), so a man who\nmisses games also scores less"
                + " in the ones he plays, and a linear (17-g)/17\nunderstates him. That"
                + " is NOT a licence to bend the curve. The correlation is\nmeasured on"
                + " realised seasons, where playing badly CAUSES the benching that\ncuts"
                + " games played - the arrow runs backwards from what a projected"
                + " absence\nwould need. Leave it linear.");
    }

    /**
     * The standard error of the mean season-by-season DIFFERENCE from baseline.
     *
     * Not the standard error of the variant's own mean. Every variant drafts
     * the same five seasons off the same five boards, so the enormous
     * season-to-season spread - 1666 to 2055 in the baseline row alone - is
     * common to both sides and cancels. Comparing two unpaired means throws
     * that cancellation away and reports an error bar so wide nothing could
     * ever move; comparing the paired differences is the test the design
     * actually supports. It is still n=5, so it can only ever say "no".
     */
    static double pairedStandardError(double[] scores, double[] baseline){
        if(baseline == null || scores.length != baseline.length || scores.length < 2){
            return 0;
        }
        double mean = 0;
        for(int i = 0; i < scores.length; i++){
            mean += scores[i] - baseline[i];
        }
        mean /= scores.length;
        double sum = 0;
        for(int i = 0; i < scores.length; i++){
            double deviation = scores[i] - baseline[i] - mean;
            sum += deviation * deviation;
        }
        return Math.sqrt(sum / (scores.length - 1) / scores.length);
    }

    static RiskDiscountedValue build(Variant variant, Map<String, Double> projections,
                                     String season){
        Map<Position, Double> trust = variant.trust();
        if(variant.leaveOneOutTrust() && season != null){
            // No hindsight: judging 2023 must not use a coefficient fitted on
            // 2023. The SHIPPED trust has no such guard - reliability() pools
            // every season including the one under test - which flatters it
            // slightly in every backtest row this repo has printed.
            trust = TrustCoefficient.measured(configuration, variant.window(), season);
        }
        return new RiskDiscountedValue(projections, variant.positionMissed(),
                variant.ranks(), trust, variant.window(), variant.playerMissed());
    }

    static Map<Position, Double> ones(){
        Map<Position, Double> out = new EnumMap<>(Position.class);
        for(Position position : ALL){
            out.put(position, 1.0);
        }
        return out;
    }

    static Map<Position, Double> zeros(){
        return new EnumMap<>(Position.class);
    }

    static Map<Position, Double> withTrust(Map<Position, Double> base, Position position,
                                           double value){
        Map<Position, Double> out = new EnumMap<>(base);
        out.put(position, value);
        return out;
    }

    static Map<Position, Integer> withDef(Map<Position, Integer> base, int rank){
        Map<Position, Integer> out = new EnumMap<>(base);
        out.put(Position.DEF, rank);
        return out;
    }

    /** Give defences the mean skill-position games-missed instead of nothing. */
    static Map<Position, Double> withDefMissed(Map<Position, Double> base){
        double total = 0;
        int seen = 0;
        for(Position position : new Position[]{Position.QB, Position.RB, Position.WR,
                Position.TE}){
            Double value = base.get(position);
            if(value != null){
                total += value;
                seen++;
            }
        }
        Map<Position, Double> out = new EnumMap<>(base);
        out.put(Position.DEF, seen == 0 ? 0 : total / seen);
        return out;
    }
}
