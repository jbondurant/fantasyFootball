import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Would a bust/boom channel ever reorder Justin's actual picks? A bound, not a model.
 *
 * The shipped objective is blind to bust and boom: WeeklyStarterValue.oneWeek()
 * drops a starter only when he is drawn !up(), and ranks the survivors by the
 * preseason projection, which never updates. BustBoomValue adds the missing
 * channel with FREE PARAMETERS - a bust rate, a boom rate, and a DETECTION LAG,
 * the number of weeks of evidence before the lineup is allowed to react. This
 * sweeps them across every plausible value and asks one thing at each of picks
 * 79, 90, 103, 114 and 127: does the ORDER of the positions change?
 *
 * Not "does the value change" - it always changes. Only the order decides a pick.
 *
 * WHAT WAS ALREADY KNOWN, and why this is a bound rather than a discovery:
 *
 *   StarterContribution (2026-08-29) already swept a bust dial across thirty
 *   worlds. Its lineup sorts by Player::perGame AFTER the bust scaling is
 *   applied, which is perfect detection with ZERO lag - the most generous corner
 *   this sweep contains. The tight end lost in all thirty worlds, and at the
 *   idealised corner - nobody hurt, nobody busting - a bench player read -3.2,
 *   a tie.
 *
 *   The boom channel was measured directly on real outcomes (2026-08-30): a
 *   round-10 back outscores a top-twelve back 10% of the time, by 63 points when
 *   he does, worth 6.1 points in expectation. That is the whole size of the
 *   prize with PERFECT hindsight. A detection lag can only make it smaller.
 *
 * So the expected answer is that nothing reorders, and this exists to say so
 * with the lag dimension attached and at Justin's real picks, which neither of
 * those two measured.
 *
 *   ./gradlew run -Pmain=BustBoomSweep [-Pscenarios=800]
 */
public class BustBoomSweep {

    static final int[] PICKS = {79, 90, 103, 114, 127};
    static final double[] RATES = {0.00, 0.05, 0.10, 0.20, 0.30};
    /** 17 means the lineup never learns, which is the shipped objective. */
    static final int[] LAGS = {1, 3, 6, 12, 17};
    static final Position[] CANDIDATES = {Position.RB, Position.WR, Position.TE,
            Position.QB, Position.DEF};

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int scenarios = Integer.getInteger("scenarios", 800);

        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, List.of(),
                model, earliness);
        Map<String, Double> points = planner.points();
        BustBoomValue value = BustBoomValue.forCurrentBoard(configuration, points,
                scenarios, 424_242L);

        System.out.printf("%n========== WOULD BUST AND BOOM REORDER A PICK? ==========%n%n");
        System.out.printf("%d scenarios, one sampled world shared by every cell, so two%n"
                + "cells never differ by sampling noise. bust rate = boom rate on the%n"
                + "main grid; the asymmetric check is at the bottom.%n", scenarios);
        System.out.printf("bust multiplies a man's realised rate by 0.60, boom by 1.60,%n"
                + "and detection after the lag is PERFECT - all three are generous.%n");

        // The reference check: with both rates at zero this must reproduce the
        // shipped objective, or the sweep is measuring a different model.
        value.set(BustBoomValue.Knobs.off());
        WeeklyStarterValue shipped = WeeklyStarterValue.forCurrentBoard(configuration,
                points, scenarios, 424_242L);
        List<String> nine = startingNine(planner, points);
        double mine = value.of(nine);
        double theirs = shipped.of(nine);
        System.out.printf("%nREFERENCE  rates at zero: this %.1f, WeeklyStarterValue %.1f,"
                + " gap %.3f%n", mine, theirs, Math.abs(mine - theirs));
        System.out.printf("           %s%n", Math.abs(mine - theirs) < 0.05
                ? "identical - the channel is the only difference between them"
                : "NOT IDENTICAL - do not trust the sweep, the base model differs");

        Map<Integer, String> baselineOrder = new LinkedHashMap<>();
        int reorders = 0;
        int argmaxChanges = 0;
        // The decomposition that actually answers the question. Two different
        // things move the ordering in this sweep and only one of them is the
        // channel under test:
        //   DISPERSION  the bust/boom multiplier changes what a man SCORES.
        //               Visible even at lag "never", where the lineup is never
        //               allowed to react, so it is not the missing channel at
        //               all - it is my magnitude knob, deliberately set too high.
        //   DETECTION   the lineup reacting to a level it has learned. This is
        //               the channel WeeklyStarterValue is missing, and it is
        //               isolated by comparing lag L against lag "never" at the
        //               SAME rate, which holds the scoring fixed.
        int dispersionTop = 0;
        int detectionTop = 0;
        for(int pick : PICKS){
            Set<String> gone = boardAt(points, nine, pick);
            System.out.printf("%n%s%n  PICK %d (round %d)%n%s%n", "=".repeat(64), pick,
                    (pick + 11) / 12, "=".repeat(64));

            value.set(BustBoomValue.Knobs.off());
            double base = value.of(nine);
            Map<Position, String> best = new LinkedHashMap<>();
            for(Position position : CANDIDATES){
                String id = BenchValueGap.bestFree(points, gone, position);
                if(id != null){
                    best.put(position, id);
                }
            }
            String reference = ordering(value, nine, base, best);
            baselineOrder.put(pick, reference);
            System.out.printf("  shipped objective ranks: %s%n%n", reference);
            System.out.printf("  %-6s", "LAG");
            for(double rate : RATES){
                System.out.printf(" %-22s", String.format("bust=boom=%.0f%%", rate * 100));
            }
            System.out.println();
            for(int lag : LAGS){
                System.out.printf("  %-6s", lag == 17 ? "never" : lag + "wk");
                for(double rate : RATES){
                    value.set(new BustBoomValue.Knobs(rate, rate, lag, 0.60, 1.60));
                    double cell = value.of(nine);
                    String order = ordering(value, nine, cell, best);
                    boolean same = order.equals(reference);
                    if(!same){
                        reorders++;
                        if(!first(order).equals(first(reference))){
                            argmaxChanges++;
                        }
                    }
                    System.out.printf(" %-22s", same ? "-" : order);
                }
                System.out.println();
            }
            System.out.printf("%n  '-' means the order is EXACTLY the shipped one."
                    + " Anything else is printed in full.%n");

            // Isolate the channel from the knob, rate by rate.
            System.out.printf("%n  %-8s %-18s %-18s %s%n", "RATE", "top, lag never",
                    "top, best lag", "what moved it");
            for(double rate : RATES){
                value.set(new BustBoomValue.Knobs(rate, rate, 17, 0.60, 1.60));
                String frozen = first(ordering(value, nine, value.of(nine), best));
                String moved = null;
                for(int lag : LAGS){
                    if(lag == 17){
                        continue;
                    }
                    value.set(new BustBoomValue.Knobs(rate, rate, lag, 0.60, 1.60));
                    String top = first(ordering(value, nine, value.of(nine), best));
                    if(!top.equals(frozen) && moved == null){
                        moved = top + " (lag " + lag + ")";
                    }
                }
                boolean dispersed = !frozen.equals(first(reference));
                if(dispersed){
                    dispersionTop++;
                }
                if(moved != null){
                    detectionTop++;
                }
                System.out.printf("  %-8s %-18s %-18s %s%n",
                        String.format("%.0f%%", rate * 100), frozen,
                        moved == null ? frozen : moved,
                        dispersed && moved != null ? "BOTH"
                                : dispersed ? "the magnitude knob, NOT detection"
                                : moved != null ? "DETECTION - the real channel"
                                : "nothing");
            }

            // The magnitudes behind the top cell, so a reader can see how far
            // apart the positions are and judge whether a flip was ever close.
            value.set(new BustBoomValue.Knobs(0.20, 0.20, 3, 0.60, 1.60));
            double hot = value.of(nine);
            System.out.printf("%n  %-6s %12s %12s %10s%n", "POS", "shipped", "20%/lag 3",
                    "change");
            value.set(BustBoomValue.Knobs.off());
            double cold = value.of(nine);
            for(Map.Entry<Position, String> entry : best.entrySet()){
                List<String> trial = new ArrayList<>(nine);
                trial.add(entry.getValue());
                value.set(BustBoomValue.Knobs.off());
                double before = value.of(trial) - cold;
                value.set(new BustBoomValue.Knobs(0.20, 0.20, 3, 0.60, 1.60));
                double after = value.of(trial) - hot;
                System.out.printf("  %-6s %12.1f %12.1f %+10.1f%n", entry.getKey(),
                        before, after, after - before);
            }
        }

        System.out.printf("%n%s%n  VERDICT%n%s%n", "=".repeat(64), "=".repeat(64));
        System.out.printf("  %d cells swept per pick, %d picks.%n",
                RATES.length * LAGS.length, PICKS.length);
        System.out.printf("  orderings that differ from the shipped one: %d%n", reorders);
        System.out.printf("  cells where the TOP position changes: %d%n", argmaxChanges);
        System.out.printf("%n  The top position is the only part that decides a pick."
                + " A reorder%n  further down the list changes nothing Justin does.%n");
        System.out.printf("%n  AND OF THOSE TOP CHANGES, WHAT CAUSED THEM:%n");
        System.out.printf("    the magnitude knob alone (visible at lag 'never',%n"
                + "    where the lineup is never allowed to react):        %d of %d%n",
                dispersionTop, RATES.length * PICKS.length);
        System.out.printf("    DETECTION, the channel actually under test:          %d of %d%n",
                detectionTop, RATES.length * PICKS.length);
        System.out.printf("%n  Only the second row is evidence about the missing model."
                + " The first%n  is evidence about how hard I turned a dial I invented.%n");
    }

    /** Positions ranked by marginal value, best first, as "RB>WR>TE>DEF>QB". */
    static String ordering(BustBoomValue value, List<String> nine, double base,
                           Map<Position, String> best){
        List<Map.Entry<Position, Double>> scored = new ArrayList<>();
        for(Map.Entry<Position, String> entry : best.entrySet()){
            List<String> trial = new ArrayList<>(nine);
            trial.add(entry.getValue());
            scored.add(Map.entry(entry.getKey(), value.of(trial) - base));
        }
        scored.sort(Map.Entry.<Position, Double>comparingByValue().reversed());
        StringBuilder out = new StringBuilder();
        for(Map.Entry<Position, Double> entry : scored){
            if(out.length() > 0){
                out.append('>');
            }
            out.append(entry.getKey());
        }
        return out.toString();
    }

    static String first(String ordering){
        int cut = ordering.indexOf('>');
        return cut < 0 ? ordering : ordering.substring(0, cut);
    }

    /**
     * The two keepers plus the seven picks Model A makes - a full starting nine.
     * From here every further pick is bench, which is the case in question.
     */
    static List<String> startingNine(DraftPlanner planner, Map<String, Double> points){
        List<String> roster = new ArrayList<>(planner.myKeeperIDs());
        Position[] shape = {Position.RB, Position.WR, Position.RB, Position.WR,
                Position.WR, Position.WR, Position.TE};
        Set<String> used = new HashSet<>(roster);
        for(Position position : shape){
            String best = BenchValueGap.bestFree(points, used, position);
            if(best != null){
                roster.add(best);
                used.add(best);
            }
        }
        return roster;
    }

    /**
     * Who is gone by that pick. Without this the "best free" man at every
     * position is a first-rounder, and adding the best back in football to a
     * bench proves nothing.
     */
    static Set<String> boardAt(Map<String, Double> points, List<String> mine, int pick){
        Set<String> gone = new HashSet<>(mine);
        List<String> byAdp = new ArrayList<>(points.keySet());
        byAdp.sort(Comparator.comparingDouble(SleeperProjections::adpOf));
        for(int i = 0; i < Math.min(pick - 1, byAdp.size()); i++){
            gone.add(byAdp.get(i));
        }
        return gone;
    }
}
