import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The harvest runs on PPR boards. This league is half-PPR. How much does that
 * cost?
 *
 * FFC publishes half-PPR only from 2018, so a half-PPR harvest would either
 * stop at 2018 or put a format change in the middle of the sample - right where
 * the old-versus-recent question is asked, making any era difference
 * inseparable from the boards having changed. PPR runs the whole way, so PPR is
 * what the harvest uses.
 *
 * That substitution is a PROXY, and a proxy is only as good as its measured
 * bias. This measures it on the eight seasons where both boards exist: same
 * outcomes, same game, same plans, two boards. If the two agree about which
 * plans are good, the pre-2018 seasons are not being distorted by their format.
 * If they disagree, the harvest inherits that disagreement and the report has
 * to say so.
 *
 *   ./gradlew run -Pmain=FormatProxyAudit
 */
public class FormatProxyAudit {

    public static void main(String[] args){
        int rounds = EraIngest.rounds();
        Map<String, EraBoards.Board> ppr = EraBoards.usable("ppr",
                EraIngest.MIN_RATE, EraIngest.minDepth());
        Map<String, EraBoards.Board> half = EraBoards.usable("half-ppr",
                EraIngest.MIN_RATE, EraIngest.minDepth());
        Map<String, EraBoards.Board> pprBoth = new TreeMap<>();
        Map<String, EraBoards.Board> halfBoth = new TreeMap<>();
        for(String season : ppr.keySet()){
            if(half.containsKey(season)){
                pprBoth.put(season, ppr.get(season));
                halfBoth.put(season, half.get(season));
            }
        }

        System.out.printf("%nFORMAT PROXY: PPR BOARDS STANDING IN FOR HALF-PPR%n%n");
        if(pprBoth.size() < 2){
            System.out.println("not enough seasons carry both boards to measure this");
            return;
        }
        System.out.printf("seasons with both boards: %s%n",
                String.join(" ", pprBoth.keySet()));

        int sample = Integer.getInteger("planSample", 20000);
        EraScores.Table pprTable = EraScores.compute(pprBoth, rounds, sample);
        EraScores.Table halfTable = EraScores.compute(halfBoth, rounds, sample);

        // Index p must mean the same plan in both tables or every comparison
        // below is nonsense. Same enumeration, same seed - checked, not assumed.
        if(!pprTable.plans().equals(halfTable.plans())){
            System.out.println("the two boards produced different plan spaces -"
                    + " nothing here would be comparable");
            return;
        }
        System.out.printf("plans %d, %d rounds, %s%n%n", pprTable.plans().size(),
                rounds, Boolean.getBoolean("noKeepers") ? "no keepers"
                        : EraKeepers.describe());

        System.out.printf("%-6s %10s %10s %9s %9s   %s%n", "SEASON", "ppr rows",
                "half rows", "agree", "mean |gap|", "best plan (ppr / half-ppr)");
        List<Integer> seasons = new ArrayList<>();
        for(int s = 0; s < pprTable.seasons().size(); s++){
            seasons.add(s);
            double[] a = pprTable.season(s);
            double[] b = halfTable.season(s);
            double gap = 0;
            for(int plan = 0; plan < a.length; plan++){
                gap += Math.abs(a[plan] - b[plan]);
            }
            System.out.printf("%-6s %10d %10d %9.2f %9.0f   %s / %s%n",
                    pprTable.seasons().get(s),
                    pprBoth.get(pprTable.seasons().get(s)).match().matched(),
                    halfBoth.get(pprTable.seasons().get(s)).match().matched(),
                    RegimeShift.pearson(RegimeShift.rank(a), RegimeShift.rank(b)),
                    gap / a.length,
                    EraPlans.shape(pprTable.plans().get(argmax(a))),
                    EraPlans.shape(halfTable.plans().get(argmax(b))));
        }

        double[] pprMean = new double[pprTable.plans().size()];
        double[] halfMean = new double[halfTable.plans().size()];
        for(int plan = 0; plan < pprMean.length; plan++){
            pprMean[plan] = pprTable.mean(plan, seasons);
            halfMean[plan] = halfTable.mean(plan, seasons);
        }
        System.out.printf("%npooled over these seasons: agreement %.3f, and the plan"
                + " each board picks:%n   ppr      %s%n   half-ppr %s%n",
                RegimeShift.pearson(RegimeShift.rank(pprMean), RegimeShift.rank(halfMean)),
                EraPlans.shape(pprTable.plans().get(argmax(pprMean))),
                EraPlans.shape(halfTable.plans().get(argmax(halfMean))));

        // What the format costs in the currency that matters: pick the plan on
        // one board, score it on the other.
        int pprPick = argmax(pprMean);
        int halfPick = argmax(halfMean);
        System.out.printf("%nCROSS-SCORING (the cost of choosing on the wrong board)%n");
        System.out.printf("   ppr's plan, scored on half-ppr boards   %+8.1f%n",
                halfTable.mean(pprPick, seasons));
        System.out.printf("   half-ppr's own plan                     %+8.1f%n",
                halfTable.mean(halfPick, seasons));
        System.out.printf("   difference                              %+8.1f%n",
                halfTable.mean(pprPick, seasons) - halfTable.mean(halfPick, seasons));
        System.out.printf("%nRead that difference against EraSample's error bar before"
                + " calling it a bias.%nThe two boards also disagree about WHO is on"
                + " them at all, which is part of%nwhat is being measured here, not a"
                + " confound to be removed.%n");

        positionalDrift(pprBoth, halfBoth);
    }

    /**
     * Where the formats actually differ: PPR pays a full point a catch, so it
     * lifts receivers and pass-catching backs up the board. Worth printing
     * because it says WHICH WAY any bias would run.
     */
    static void positionalDrift(Map<String, EraBoards.Board> ppr,
                                Map<String, EraBoards.Board> half){
        System.out.printf("%nWHERE THE BOARDS DIFFER, BY POSITION (mean ADP, top 120)%n");
        System.out.printf("%-6s", "SEASON");
        for(Position position : new Position[]{Position.QB, Position.RB, Position.WR,
                Position.TE}){
            System.out.printf(" %10s", position);
        }
        System.out.println();
        for(String season : ppr.keySet()){
            System.out.printf("%-6s", season);
            for(Position position : new Position[]{Position.QB, Position.RB, Position.WR,
                    Position.TE}){
                System.out.printf(" %+10.1f", meanAdp(half.get(season), position)
                        - meanAdp(ppr.get(season), position));
            }
            System.out.println();
        }
        System.out.printf("positive means half-PPR drafts that position LATER than PPR"
                + " does.%n");
    }

    static double meanAdp(EraBoards.Board board, Position position){
        double total = 0;
        int counted = 0;
        for(String id : board.ids()){
            if(board.positionOf().get(id) == position && board.adp().get(id) <= 120){
                total += board.adp().get(id);
                counted++;
            }
        }
        return counted == 0 ? 0 : total / counted;
    }

    static int argmax(double[] values){
        int best = 0;
        for(int i = 1; i < values.length; i++){
            if(values[i] > values[best]){
                best = i;
            }
        }
        return best;
    }
}
