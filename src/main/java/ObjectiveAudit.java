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

        System.out.printf("%nWHAT EACH CONSTANT IS WORTH%n%n");
        System.out.printf("%-15s %-30s %11s %10s   %s%n", "CONSTANT", "VARIANT",
                "backtest", "vs base", "16-round plan");

        String basePlan = null;
        double baseScore = 0;
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
            planner.scoreWith(build(variant, planner.points(), null));
            String plan = Draft16.shape(planner.plan(rollouts, 0, 0.10,
                    DraftSimulator.SEED).positions());
            if(basePlan == null){
                basePlan = plan;
                baseScore = mean;
            }
            System.out.printf("%-15s %-30s %11.0f %+10.0f   %s%s%n", variant.group(),
                    variant.name(), mean, mean - baseScore, plan,
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
        System.out.println("\nA move under about 90 points is inside the backtest's own"
                + " standard error and\nsays nothing. A changed plan says something"
                + " whatever the backtest does,\nbecause the plan is what gets drafted"
                + " on Tuesday.");

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
