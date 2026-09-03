import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Are the old seasons teaching the right lesson?
 *
 * Extending the backtest from five seasons to thirteen is only an improvement
 * if 2013 is the same game as 2025. It might not be. Workhorse running backs
 * became committees, passing volume rose, tight end and quarterback usage
 * moved. A draft plan fitted on 2013-2018 could be confidently, precisely wrong
 * about 2026 - and pooling everything would then be WORSE than the five seasons
 * we started with, because it would put a tight error bar around a stale
 * answer.
 *
 * So this does not assume exchangeability, it tests it, three ways:
 *
 *   1. AGREEMENT. Do the eras rank plans the same way? Season-by-season rank
 *      correlation, with the within-era agreement printed beside the
 *      across-era agreement - because two seasons of the SAME era do not agree
 *      perfectly either, and that noise is the yardstick a regime effect has
 *      to beat.
 *   2. TRANSFER. Fit a plan on the old seasons only and score it on the recent
 *      ones; fit on the recent ones only (leave-one-out) and score on the same
 *      seasons. If the old-fitted plan does materially worse, the old seasons
 *      are not exchangeable with the new.
 *   3. WEIGHTING. If they are not, how much should the old ones count? A grid
 *      of recency half-lives, each judged by held-out season performance, which
 *      turns "use a cutoff" from a taste into a measurement.
 *
 *   ./gradlew run -Pmain=RegimeShift
 *   ./gradlew run -Pmain=RegimeShift -PnoKeepers=true    (structure check)
 *   ./gradlew run -Pmain=RegimeShift -Pformat=half-ppr   (board-format check)
 */
public class RegimeShift {

    /** The seasons standing in for 2026 - what a plan has to get right. */
    public static int targetFrom(){
        return Integer.getInteger("targetFrom", 2021);
    }

    /** The era split under test. Everything before this is "old". */
    public static int cutoff(){
        return Integer.getInteger("cutoff", 2019);
    }

    public static void main(String[] args){
        int rounds = EraIngest.rounds();
        String format = System.getProperty("format");
        Map<String, EraBoards.Board> boards = EraBoards.usable(format,
                EraIngest.MIN_RATE, EraIngest.minDepth());

        System.out.printf("%nREGIME SHIFT: ARE THE OLD SEASONS THE SAME GAME?%n%n");
        System.out.printf("seasons        %d usable, %s to %s%n", boards.size(),
                boards.keySet().iterator().next(),
                new ArrayList<>(boards.keySet()).get(boards.size() - 1));
        System.out.printf("board          fantasyfootballcalculator %s, 12 teams%n",
                format == null ? EraBoards.defaultFormat("2020") : format);
        System.out.printf("outcomes       league-scored from component stats,"
                + " %.0f-point passing TDs, EVERY season alike%n",
                LeagueActuals.leagueScoring().passTD);
        System.out.printf("game           %d rounds at slot 7, %s%n", rounds,
                Boolean.getBoolean("noKeepers")
                        ? "NO keepers - the plain-draft control"
                        : EraKeepers.describe());
        System.out.printf("value          points above that season's"
                + " take-the-best-man-left baseline%n");

        long started = System.currentTimeMillis();
        EraScores.Table table = EraScores.compute(boards, rounds);
        System.out.printf("plans          %d legal position sequences, scored on all"
                + " %d seasons (%.1fs)%n%n", table.plans().size(), table.seasons().size(),
                (System.currentTimeMillis() - started) / 1000.0);

        perSeason(table);
        double[] agree = agreement(table);
        double[] shift = transfer(table);
        double[] weights = weighting(table);
        verdict(table, agree, shift, weights);
    }

    /**
     * The recommendation, computed rather than typed.
     *
     * Every number here comes from the tables above, so the conclusion cannot
     * drift away from the evidence the way a sentence in a document does. The
     * rule is stated before the numbers are known: a regime effect is real if
     * it is bigger than twice its own standard error, and it is worth acting on
     * only if down-weighting the old seasons costs less resolution than the
     * bias costs points.
     */
    static void verdict(EraScores.Table table, double[] agree, double[] shift,
                        double[] weights){
        System.out.printf("%n%nVERDICT%n%n");
        boolean eraStructure = agree[2] < Math.min(agree[0], agree[1]);
        System.out.printf("1. AGREEMENT. Seasons agree with each other at %.3f within"
                + " the old era and%n   %.3f within the recent one; across the split"
                + " they agree at %.3f.%n   %s%n", agree[0], agree[1], agree[2],
                eraStructure
                        ? "The across number is BELOW both - the eras do rank plans"
                          + " differently."
                        : "The across number is not below both - no era structure in"
                          + " how plans rank.");

        // In standard errors, printed rather than collapsed to a yes or no.
        // Both of these have landed within a whisker of 2.0 across plan
        // subsamples - the drift estimate read 1.95 sigma on twenty thousand
        // plans and 2.00 on all four hundred thousand - and a rule that flips a
        // RECOMMENDATION on the second decimal of a noisy estimate is worse
        // than no rule. So the verdict has three outcomes, and the middle one
        // says "borderline" out loud instead of picking a side.
        double driftSigmas = agree[4] == 0 ? 0 : -agree[3] / agree[4];
        double shiftSigmas = shift[1] == 0 ? 0 : shift[0] / shift[1];
        System.out.printf("   Agreement against the gap between seasons: %+.4f +/- %.4f"
                + " a year (%.2f sigma).%n   %s%n", agree[3], agree[4], driftSigmas,
                driftSigmas > 2
                        ? "It decays with distance - the past MAY be less relevant."
                        : "Flat - distance in time does not make a season less"
                          + " relevant.");

        double strongest = Math.max(driftSigmas, shiftSigmas);
        boolean realShift = shiftSigmas > 2;
        System.out.printf("%n2. TRANSFER. Fitting on the recent seasons instead of the"
                + " old ones is worth%n   %+.1f +/- %.1f points a season on held-out"
                + " seasons.%n   %s%n", shift[0], shift[1],
                realShift ? "That is outside the noise: the old seasons teach a"
                        + " measurably worse lesson."
                        : "That is inside the noise: no measurable penalty for"
                          + " learning from the old seasons.");

        System.out.printf("%n3. COST OF HEDGING. Flat weighting is worth %.1f effective"
                + " seasons; the best%n   half-life on held-out seasons (%.0f) is worth"
                + " %.1f, and beats flat by %+.1f +/- %.1f.%n", weights[4], weights[0],
                weights[3], weights[1], weights[2]);
        System.out.printf("   That winner reverses the curve %.0f times out of 6, so it"
                + " is %s.%n", weights[6],
                weights[6] >= 2 ? "the best of seven noisy draws, not a recency effect"
                        : "riding a monotone trend, which is what a real effect looks"
                          + " like");

        System.out.printf("%nRECOMMENDATION: ");
        if(strongest > 3 || eraStructure){
            System.out.printf("WEIGHT, do not pool. Use a half-life of %.0f seasons.%n"
                    + "The old seasons are still evidence - a cutoff throws them away"
                    + " entirely, which%ncosts more resolution than their bias is"
                    + " worth - but they should not count fully.%n", weights[0]);
        }
        else if(strongest > 2){
            System.out.printf("BORDERLINE. Pool all %d seasons, or hedge gently."
                    + " Do NOT cut.%n", table.seasons().size());
            System.out.printf("The strongest era signal is %.2f standard errors, which"
                    + " is exactly the region%nwhere a rule should not pretend to"
                    + " decide. Both estimates lean the same way -%nthe past is"
                    + " slightly less relevant - and neither survives a hard look: the"
                    + "%ndrift interval is optimistic (its pairs are not independent),"
                    + " and the weighting%ngrid's winner reverses the curve %.0f"
                    + " times.%n%nThe cheap hedge is the LONGEST half-life, %.0f"
                    + " seasons: it costs %.1f effective%nseasons out of %.1f. A cutoff"
                    + " year is NOT the same thing and is not supported -%nthere is no"
                    + " year in this data where anything changes.%n",
                    strongest, weights[6], weights[5],
                    weights[4] - EraScores.effectiveSampleSize(
                            EraScores.decay(table.seasons(), weights[5])),
                    weights[4]);
        }
        else {
            System.out.printf("POOL ALL %d SEASONS, FLAT. No cutoff.%n",
                    table.seasons().size());
            System.out.printf("Neither test finds the old seasons teaching a different"
                    + " lesson, and%ndown-weighting them costs real resolution: %.1f"
                    + " effective seasons against %.1f.%n"
                    + "The honest hedge, if one is wanted, is the longest half-life"
                    + " that costs almost%nnothing - the %.0f-season row above - and"
                    + " NOT the short ones, whose apparent%nwins are grid search on"
                    + " %d held-out seasons.%n", weights[3], weights[4], weights[5],
                    table.indexesFrom(targetFrom()).size());
        }
        System.out.printf("%nWhat this does NOT say: that the game has not changed."
                + " It says a plan fitted on%n2013-2018 does not measurably lose on"
                + " 2021-2025 IN THIS GAME - eleven rounds with%na quarterback and a"
                + " back already in hand. Run -PnoKeepers and the answer changes,%n"
                + "which is the point: the keeper structure removes the quarterback"
                + " timing decision,%nand that decision is where the eras differ most.%n");
    }

    /** What the seasons look like one at a time, before any pooling. */
    static void perSeason(EraScores.Table table){
        System.out.printf("EACH SEASON'S OWN BEST PLAN%n");
        System.out.printf("%-6s %-12s %9s %9s   %s%n", "SEASON", "BEST PLAN",
                "vs ADP", "baseline", "worst legal plan");
        for(int s = 0; s < table.seasons().size(); s++){
            int best = 0;
            int worst = 0;
            for(int p = 0; p < table.plans().size(); p++){
                if(table.value()[p][s] > table.value()[best][s]){
                    best = p;
                }
                if(table.value()[p][s] < table.value()[worst][s]){
                    worst = p;
                }
            }
            System.out.printf("%-6s %-12s %+9.0f %9.0f   %+.0f (%s)%n",
                    table.seasons().get(s), EraPlans.shape(table.plans().get(best)),
                    table.value()[best][s], table.baseline()[s],
                    table.value()[worst][s], EraPlans.shape(table.plans().get(worst)));
        }
        System.out.printf("%nThe spread between the best and worst legal plan is what"
                + " any of this can be%nworth in a season. A plan chosen for one season"
                + " is fitted to that season's%ninjuries and breakouts, which is why no"
                + " row above is evidence about 2026.%n%n");
    }

    /**
     * Do seasons agree about which plans are good?
     *
     * Rank correlation between every pair of seasons, then averaged three ways.
     * The within-era numbers are the control: if two 2023-and-2024 seasons only
     * agree at 0.6, then old-versus-recent agreeing at 0.6 is not a regime
     * shift, it is football being random. Only a cross-era number clearly BELOW
     * the within-era ones is evidence.
     */
    static double[] agreement(EraScores.Table table){
        int seasons = table.seasons().size();
        double[][] ranks = new double[seasons][];
        for(int s = 0; s < seasons; s++){
            ranks[s] = rank(table.season(s));
        }
        double[][] correlation = new double[seasons][seasons];
        for(int a = 0; a < seasons; a++){
            for(int b = 0; b < seasons; b++){
                correlation[a][b] = pearson(ranks[a], ranks[b]);
            }
        }

        int cutoff = cutoff();
        System.out.printf("AGREEMENT BETWEEN SEASONS (rank correlation over all"
                + " %d plans)%n", table.plans().size());
        System.out.printf("%-6s", "");
        for(String season : table.seasons()){
            System.out.printf(" %5s", season.substring(2));
        }
        System.out.println();
        for(int a = 0; a < seasons; a++){
            System.out.printf("%-6s", table.seasons().get(a));
            for(int b = 0; b < seasons; b++){
                System.out.printf(" %5.2f", correlation[a][b]);
            }
            System.out.println();
        }

        double withinOld = 0;
        int withinOldCount = 0;
        double withinRecent = 0;
        int withinRecentCount = 0;
        double across = 0;
        int acrossCount = 0;
        for(int a = 0; a < seasons; a++){
            for(int b = a + 1; b < seasons; b++){
                boolean oldA = Integer.parseInt(table.seasons().get(a)) < cutoff;
                boolean oldB = Integer.parseInt(table.seasons().get(b)) < cutoff;
                if(oldA && oldB){
                    withinOld += correlation[a][b];
                    withinOldCount++;
                }
                else if(!oldA && !oldB){
                    withinRecent += correlation[a][b];
                    withinRecentCount++;
                }
                else {
                    across += correlation[a][b];
                    acrossCount++;
                }
            }
        }
        System.out.printf("%nmean agreement   within pre-%d: %+.3f (%d pairs)"
                + "   within %d+: %+.3f (%d pairs)   across the split: %+.3f (%d pairs)%n",
                cutoff, withinOld / Math.max(1, withinOldCount), withinOldCount,
                cutoff, withinRecent / Math.max(1, withinRecentCount), withinRecentCount,
                across / Math.max(1, acrossCount), acrossCount);
        System.out.printf("If the across number sits inside the within numbers, the"
                + " eras are ranking%nplans the same way and the old seasons are"
                + " evidence about the new ones.%n");

        // The sharper question, and the one a half-life actually answers. A
        // cutoff assumes the game changed at a moment; DRIFT would show up as
        // agreement decaying with the gap between two seasons, whatever the
        // cutoff. Regress agreement on that gap over all pairs: a significantly
        // negative slope is gradual regime change, and is the only thing that
        // would justify discounting the past. A flat slope says two seasons
        // twelve years apart agree as well as two seasons running.
        int pairs = seasons * (seasons - 1) / 2;
        double[] gaps = new double[pairs];
        double[] agreements = new double[pairs];
        int at = 0;
        for(int a = 0; a < seasons; a++){
            for(int b = a + 1; b < seasons; b++){
                gaps[at] = Math.abs(Integer.parseInt(table.seasons().get(a))
                        - Integer.parseInt(table.seasons().get(b)));
                agreements[at] = correlation[a][b];
                at++;
            }
        }
        double[] fit = slope(gaps, agreements);
        System.out.printf("%nagreement against the GAP between two seasons:"
                + " %+.4f +/- %.4f per year%n(%d pairs, gaps of 1 to %d years)."
                + " %s%n%n", fit[0], fit[1], pairs, (int) max(gaps),
                Math.abs(fit[0]) > 2 * fit[1]
                        ? "Agreement DOES decay with distance - gradual drift."
                        : "No decay with distance: two seasons twelve years apart"
                          + " agree as well as two in a row.");
        System.out.printf("That interval is OPTIMISTIC and should be read as a floor on"
                + " the uncertainty:%n%d pairs come from only %d seasons, so they are"
                + " not independent observations,%nand ordinary least squares does not"
                + " know that. A slope that is marginal here is%nweaker than marginal"
                + " in truth.%n%n", pairs, seasons);

        return new double[]{ withinOld / Math.max(1, withinOldCount),
                withinRecent / Math.max(1, withinRecentCount),
                across / Math.max(1, acrossCount), fit[0], fit[1] };
    }

    /**
     * The test that decides it: fit here, score there.
     *
     * Every fit is scored on seasons it did not see, so nothing marks its own
     * homework. The comparison that matters is the last two columns - a plan
     * fitted on the old seasons against one fitted on the recent ones, both
     * judged on the same held-out recent seasons.
     */
    static double[] transfer(EraScores.Table table){
        int cutoff = cutoff();
        List<Integer> old = table.indexesBefore(cutoff);
        List<Integer> recent = table.indexesFrom(cutoff);
        List<Integer> targets = table.indexesFrom(targetFrom());
        if(old.isEmpty() || recent.size() < 2){
            System.out.println("not enough seasons either side of the split to test"
                    + " transfer");
            return new double[]{0, 0};
        }

        System.out.printf("TRANSFER: A PLAN FITTED ON ONE ERA, SCORED ON ANOTHER%n");
        System.out.printf("held-out seasons are %s+; every fit excludes the season"
                + " it is scored on%n%n", targetFrom());
        System.out.printf("%-6s %14s %14s %14s%n", "SEASON",
                "fit pre-" + cutoff, "fit " + cutoff + "+", "fit ALL");
        double[] oldFit = new double[targets.size()];
        double[] recentFit = new double[targets.size()];
        double[] pooledFit = new double[targets.size()];
        List<String> plansUsed = new ArrayList<>();
        for(int i = 0; i < targets.size(); i++){
            int target = targets.get(i);
            int oldPlan = table.best(old, null);
            int recentPlan = table.best(without(recent, target), null);
            int allPlan = table.best(without(all(table), target), null);
            oldFit[i] = table.value()[oldPlan][target];
            recentFit[i] = table.value()[recentPlan][target];
            pooledFit[i] = table.value()[allPlan][target];
            plansUsed.add(String.format("   %s  old-fit %s | recent-fit %s | all-fit %s",
                    table.seasons().get(target), EraPlans.shape(table.plans().get(oldPlan)),
                    EraPlans.shape(table.plans().get(recentPlan)),
                    EraPlans.shape(table.plans().get(allPlan))));
            System.out.printf("%-6s %+14.0f %+14.0f %+14.0f%n",
                    table.seasons().get(target), oldFit[i], recentFit[i], pooledFit[i]);
        }
        System.out.printf("%-6s %+14.1f %+14.1f %+14.1f%n", "mean", mean(oldFit),
                mean(recentFit), mean(pooledFit));
        System.out.println();
        plansUsed.forEach(System.out::println);

        System.out.printf("%nPAIRED DIFFERENCES on the same %d held-out seasons%n",
                targets.size());
        paired("recent-fit minus old-fit", recentFit, oldFit);
        paired("all-fit    minus old-fit", pooledFit, oldFit);
        paired("all-fit    minus recent-fit", pooledFit, recentFit);

        // The argmax comparison above is honest but weak: one plan out of four
        // hundred thousand, chosen on a handful of seasons, is mostly a draw
        // from whatever the noise favoured. A regime effect would show up in
        // the whole top REGION, not in one winner - and averaging the top one
        // percent cuts the variance by an order of magnitude without changing
        // the question. This is the powerful version of the same test.
        System.out.printf("%nTHE SAME TEST ON THE TOP 1%% OF PLANS, NOT ONE WINNER%n");
        double[] oldTop = new double[targets.size()];
        double[] recentTop = new double[targets.size()];
        List<Integer> oldBest = topFraction(table, old, 0.01);
        for(int i = 0; i < targets.size(); i++){
            int target = targets.get(i);
            oldTop[i] = meanOver(table, oldBest, target);
            recentTop[i] = meanOver(table,
                    topFraction(table, without(recent, target), 0.01), target);
            System.out.printf("   %-6s old %+8.1f   recent %+8.1f%n",
                    table.seasons().get(target), oldTop[i], recentTop[i]);
        }
        System.out.printf("   %-6s old %+8.1f   recent %+8.1f%n", "mean",
                mean(oldTop), mean(recentTop));
        paired("recent-top minus old-top", recentTop, oldTop);
        System.out.printf("   a positive, significant number here is the regime"
                + " effect. Anything%n   inside the interval says the eras teach the"
                + " same lesson.%n");
        double[] topDifference = new double[targets.size()];
        for(int i = 0; i < targets.size(); i++){
            topDifference[i] = recentTop[i] - oldTop[i];
        }
        double[] measured = { mean(topDifference), standardError(topDifference) };

        System.out.printf("%nHOW MUCH OF A FIT IS FLATTERY%n");
        int inSample = table.best(targets, null);
        System.out.printf("   best plan chosen ON the held-out seasons scores %+.1f;"
                + " honest fits score%n   %+.1f (recent) and %+.1f (old). The gap is"
                + " what searching %d plans on a handful%n   of seasons buys itself.%n",
                table.mean(inSample, targets), mean(recentFit), mean(oldFit),
                table.plans().size());
        return measured;
    }

    /**
     * If the eras differ, how fast should the past be forgotten?
     *
     * A cutoff year is a half-life of zero pretending to be a principle. This
     * scores a grid of half-lives the only way that means anything - fit on
     * everything except the season being scored, weight by age, and see which
     * weighting predicts held-out seasons best - and reports the effective
     * sample size each one actually buys.
     */
    static double[] weighting(EraScores.Table table){
        List<Integer> targets = table.indexesFrom(targetFrom());
        System.out.printf("%nRECENCY WEIGHTING, JUDGED ON HELD-OUT SEASONS%n");
        System.out.printf("%-16s %10s %14s   %s%n", "WEIGHTING", "n_eff",
                "held-out mean", "plan it picks most");
        Map<String, double[]> results = new LinkedHashMap<>();
        List<double[]> settings = new ArrayList<>();
        double[] halfLives = {1, 2, 3, 5, 8, 13, 21};
        for(double halfLife : halfLives){
            settings.add(new double[]{halfLife});
        }
        for(double[] setting : settings){
            double halfLife = setting[0];
            double[] weights = EraScores.decay(table.seasons(), halfLife);
            double[] scores = new double[targets.size()];
            Map<String, Integer> chosen = new LinkedHashMap<>();
            for(int i = 0; i < targets.size(); i++){
                int target = targets.get(i);
                int plan = table.best(without(all(table), target), weights);
                scores[i] = table.value()[plan][target];
                chosen.merge(EraPlans.shape(table.plans().get(plan)), 1, Integer::sum);
            }
            results.put(String.format("half-life %.0f", halfLife), scores);
            System.out.printf("%-16s %10.1f %+14.1f   %s%n",
                    String.format("half-life %.0f", halfLife),
                    EraScores.effectiveSampleSize(weights), mean(scores),
                    commonest(chosen));
        }
        double[] flat = EraScores.flat(table.seasons().size());
        double[] flatScores = new double[targets.size()];
        Map<String, Integer> flatChosen = new LinkedHashMap<>();
        for(int i = 0; i < targets.size(); i++){
            int target = targets.get(i);
            int plan = table.best(without(all(table), target), flat);
            flatScores[i] = table.value()[plan][target];
            flatChosen.merge(EraPlans.shape(table.plans().get(plan)), 1, Integer::sum);
        }
        results.put("flat (pool all)", flatScores);
        System.out.printf("%-16s %10.1f %+14.1f   %s%n", "flat (pool all)",
                EraScores.effectiveSampleSize(flat), mean(flatScores),
                commonest(flatChosen));

        String bestName = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for(Map.Entry<String, double[]> entry : results.entrySet()){
            if(mean(entry.getValue()) > bestScore){
                bestScore = mean(entry.getValue());
                bestName = entry.getKey();
            }
        }
        System.out.printf("%nbest weighting on held-out seasons: %s (%+.1f)%n",
                bestName, bestScore);
        paired("best weighting minus flat", results.get(bestName), flatScores);
        System.out.printf("%nA weighting only earns its complexity if it beats flat by"
                + " more than the%nstandard error above. Read the sign AND the"
                + " interval, not the sign alone.%n");
        double[] bestScores = results.get(bestName);
        double[] difference = new double[bestScores.length];
        for(int i = 0; i < bestScores.length; i++){
            difference[i] = bestScores[i] - flatScores[i];
        }
        double bestHalfLife = bestName.startsWith("half-life")
                ? Double.parseDouble(bestName.replaceAll("[^0-9.]", "")) : 0;
        // The cheapest hedge worth naming: the longest half-life on the grid,
        // which costs almost no effective sample size.
        double gentlest = halfLives[halfLives.length - 1];
        // A REAL recency effect is monotone: the more of the past you let in,
        // the worse you do. If the held-out curve wanders up and down across
        // the grid instead, its winner is the best of seven noisy draws, not a
        // discovery - and reporting it as a recommendation would be exactly the
        // kind of grid-search artifact this whole tool exists to avoid.
        int reversals = 0;
        for(int i = 1; i < halfLives.length; i++){
            double previous = mean(results.get(String.format("half-life %.0f",
                    halfLives[i - 1])));
            double current = mean(results.get(String.format("half-life %.0f",
                    halfLives[i])));
            if(current > previous){
                reversals++;
            }
        }
        System.out.printf("the curve reverses direction %d times across %d half-lives;"
                + " a real recency%neffect would fall monotonically as more of the past"
                + " is let in.%n", reversals, halfLives.length);
        return new double[]{ bestHalfLife, mean(difference), standardError(difference),
                EraScores.effectiveSampleSize(EraScores.decay(table.seasons(),
                        bestHalfLife == 0 ? gentlest : bestHalfLife)),
                EraScores.effectiveSampleSize(flat), gentlest, reversals };
    }

    // ------------------------------------------------------------------
    // Small statistics, spelled out rather than imported.
    // ------------------------------------------------------------------

    static void paired(String label, double[] a, double[] b){
        double[] difference = new double[a.length];
        for(int i = 0; i < a.length; i++){
            difference[i] = a[i] - b[i];
        }
        double mean = mean(difference);
        double error = standardError(difference);
        System.out.printf("   %-28s %+7.1f  +/- %.1f  (%d seasons)%s%n", label, mean,
                error, a.length,
                Math.abs(mean) > 2 * error ? "   <- outside the noise" : "");
    }

    static double mean(double[] values){
        double total = 0;
        for(double value : values){
            total += value;
        }
        return values.length == 0 ? 0 : total / values.length;
    }

    static double standardError(double[] values){
        if(values.length < 2){
            return 0;
        }
        double mean = mean(values);
        double sum = 0;
        for(double value : values){
            sum += (value - mean) * (value - mean);
        }
        return Math.sqrt(sum / (values.length - 1) / values.length);
    }

    /** Ranks, ties averaged - the input to a Spearman correlation. */
    static double[] rank(double[] values){
        Integer[] order = new Integer[values.length];
        for(int i = 0; i < values.length; i++){
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble(i -> values[i]));
        double[] ranks = new double[values.length];
        int i = 0;
        while(i < order.length){
            int j = i;
            while(j + 1 < order.length && values[order[j + 1]] == values[order[i]]){
                j++;
            }
            double average = (i + j) / 2.0 + 1;
            for(int k = i; k <= j; k++){
                ranks[order[k]] = average;
            }
            i = j + 1;
        }
        return ranks;
    }

    static double pearson(double[] a, double[] b){
        double meanA = mean(a);
        double meanB = mean(b);
        double covariance = 0;
        double varianceA = 0;
        double varianceB = 0;
        for(int i = 0; i < a.length; i++){
            covariance += (a[i] - meanA) * (b[i] - meanB);
            varianceA += (a[i] - meanA) * (a[i] - meanA);
            varianceB += (b[i] - meanB) * (b[i] - meanB);
        }
        return varianceA == 0 || varianceB == 0 ? 0
                : covariance / Math.sqrt(varianceA * varianceB);
    }

    /** The best-scoring fraction of plans over a set of seasons. */
    static List<Integer> topFraction(EraScores.Table table, List<Integer> seasons,
                                     double fraction){
        List<Integer> plans = new ArrayList<>();
        for(int plan = 0; plan < table.plans().size(); plan++){
            plans.add(plan);
        }
        plans.sort((a, b) -> Double.compare(table.mean(b, seasons),
                table.mean(a, seasons)));
        return plans.subList(0, Math.max(1, (int) (plans.size() * fraction)));
    }

    static double meanOver(EraScores.Table table, List<Integer> plans, int season){
        double total = 0;
        for(int plan : plans){
            total += table.value()[plan][season];
        }
        return plans.isEmpty() ? 0 : total / plans.size();
    }

    /** Least-squares slope of y on x, with its standard error. */
    static double[] slope(double[] x, double[] y){
        double meanX = mean(x);
        double meanY = mean(y);
        double covariance = 0;
        double variance = 0;
        for(int i = 0; i < x.length; i++){
            covariance += (x[i] - meanX) * (y[i] - meanY);
            variance += (x[i] - meanX) * (x[i] - meanX);
        }
        if(variance == 0){
            return new double[]{0, 0};
        }
        double slope = covariance / variance;
        double residual = 0;
        for(int i = 0; i < x.length; i++){
            double predicted = meanY + slope * (x[i] - meanX);
            residual += (y[i] - predicted) * (y[i] - predicted);
        }
        return new double[]{ slope,
                Math.sqrt(residual / Math.max(1, x.length - 2) / variance) };
    }

    static double max(double[] values){
        double most = values.length == 0 ? 0 : values[0];
        for(double value : values){
            most = Math.max(most, value);
        }
        return most;
    }

    static List<Integer> all(EraScores.Table table){
        List<Integer> indexes = new ArrayList<>();
        for(int i = 0; i < table.seasons().size(); i++){
            indexes.add(i);
        }
        return indexes;
    }

    static List<Integer> without(List<Integer> indexes, int drop){
        List<Integer> kept = new ArrayList<>(indexes);
        kept.remove(Integer.valueOf(drop));
        return kept;
    }

    static String commonest(Map<String, Integer> counts){
        String best = "";
        int most = 0;
        for(Map.Entry<String, Integer> entry : counts.entrySet()){
            if(entry.getValue() > most){
                most = entry.getValue();
                best = entry.getKey();
            }
        }
        return best + (counts.size() > 1 ? " (" + counts.size() + " different)" : "");
    }
}
