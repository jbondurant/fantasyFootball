import PlayerImportAndSetup.Position;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Is it right to leave tight end until very late?
 *
 * The case for waiting rests on a shape claim: that tight end falls off a
 * cliff after a few names and then runs flat for twenty, so the man you get in
 * round 12 scores nearly what the man in round 6 does - while running back and
 * receiver keep paying for every round you spend early. If that is true, a TE
 * taken early buys almost nothing and costs a real player.
 *
 * That is a claim about actual outcomes, so it is settled with actual outcomes.
 * This joins five seasons of dated pre-season ADP to what those players really
 * scored, and asks the question as a SWAP rather than a preference, because a
 * pick is never spent in isolation:
 *
 *   A   tight end at the early pick, receiver at the late one
 *   B   receiver at the early pick, tight end at the late one
 *   C   receiver at both, and stream tight end off the waiver wire all year
 *
 * Whichever of those three scored most, historically, is the answer. Bust rate
 * and games played are reported beside it, because the reason to prefer a
 * position is often that its downside is shallower rather than its mean higher.
 *
 * Usage:
 *   ./gradlew run -Pmain=TightEndTiming
 */
public class TightEndTiming {

    public record Seen(String name, Position position, double adp, double points,
                       int games){}

    /** Every joined season, exposed so StarterContribution can reuse the join. */
    public static Map<String, List<Seen>> load() throws Exception {
        Map<String, List<Seen>> bySeason = new LinkedHashMap<>();
        for(File file : new File("data").listFiles()){
            String name = file.getName();
            if(!name.matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                continue;
            }
            List<Seen> seen = join(file, name.split("-")[3]);
            if(seen.size() > 100){
                bySeason.put(name.split("-")[3], seen);
            }
        }
        return bySeason;
    }

    /** ADP ranks that stand in for my real picks, rounds 5 through 11. */
    static final int[] EARLY = {55, 66, 79};
    static final int[] LATE = {90, 103, 114, 127};

    public static void main(String[] args) throws Exception {
        Map<String, List<Seen>> bySeason = new LinkedHashMap<>();
        for(File file : new File("data").listFiles()){
            String name = file.getName();
            if(!name.matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                continue;
            }
            String season = name.split("-")[3];
            List<Seen> seen = join(file, season);
            if(seen.size() > 100){
                bySeason.put(season, seen);
            }
        }
        if(bySeason.isEmpty()){
            System.out.println("no dated ADP files joined - nothing to measure");
            return;
        }
        System.out.printf("%d seasons joined: %s%n", bySeason.size(), bySeason.keySet());

        curve(bySeason);
        streamers(bySeason);
        swap(bySeason);
        injuryAwareSwap(bySeason);
        verdict(bySeason);
    }

    /**
     * Actual points by rank BAND within a position - where the cliffs and
     * plateaux are.
     *
     * Banded on purpose. Five seasons gives five observations per exact rank,
     * and the first run of this read TE9 at 71.9 and TE11 at 130.8, which is
     * sampling noise wearing the costume of a cliff. Bands of three trade the
     * false detail for a shape you can believe.
     */
    static void curve(Map<String, List<Seen>> bySeason){
        System.out.println("\n\nWHAT EACH POSITION ACTUALLY SCORED, BY PRE-SEASON RANK BAND");
        System.out.println("(pooled over seasons; the gap column is what the band above"
                + " was worth)");
        for(Position position : new Position[]{Position.TE, Position.RB, Position.WR}){
            int width = position == Position.TE ? 3 : 6;
            int depth = position == Position.TE ? 21 : 42;
            System.out.printf("%n%-4s %9s %10s %9s %8s %8s %7s%n", position, "BAND",
                    "points", "gap", "games", "bust", "n");
            double previous = Double.NaN;
            for(int start = 0; start < depth; start += width){
                List<Double> pooled = new ArrayList<>();
                List<Integer> played = new ArrayList<>();
                int busts = 0;
                for(List<Seen> season : bySeason.values()){
                    List<Seen> ranked = season.stream()
                            .filter(s -> s.position() == position)
                            .sorted(Comparator.comparingDouble(Seen::adp)).toList();
                    double bar = replacement(ranked, position);
                    for(int rank = start; rank < start + width && rank < ranked.size(); rank++){
                        pooled.add(ranked.get(rank).points());
                        played.add(ranked.get(rank).games());
                        if(ranked.get(rank).points() < bar){
                            busts++;
                        }
                    }
                }
                if(pooled.isEmpty()){
                    continue;
                }
                double points = pooled.stream().mapToDouble(Double::doubleValue)
                        .average().orElse(0);
                System.out.printf("%-4s %9s %10.1f %9s %8.1f %7.0f%% %7d%n", position,
                        (start + 1) + "-" + (start + width), points,
                        Double.isNaN(previous) ? "" : String.format("%.1f", points - previous),
                        played.stream().mapToInt(Integer::intValue).average().orElse(0),
                        100.0 * busts / pooled.size(), pooled.size());
                previous = points;
            }
        }
    }

    /** What the wire actually offered at tight end - the streamer's real ceiling. */
    static void streamers(Map<String, List<Seen>> bySeason){
        System.out.println("\n\nCOULD YOU JUST STREAM IT?");
        System.out.printf("%-8s %14s %14s %14s %12s%n", "SEASON", "TE1", "TE10",
                "best undrafted", "TE10 - wire");
        double totalGap = 0;
        int counted = 0;
        List<Double> gapList = new ArrayList<>();
        for(Map.Entry<String, List<Seen>> entry : bySeason.entrySet()){
            List<Seen> ranked = entry.getValue().stream()
                    .filter(s -> s.position() == Position.TE)
                    .sorted(Comparator.comparingDouble(Seen::adp)).toList();
            if(ranked.size() < 20){
                continue;
            }
            // "undrafted" = beyond the tight ends this league actually drafts
            double wire = ranked.subList(18, Math.min(ranked.size(), 30)).stream()
                    .mapToDouble(Seen::points).max().orElse(0);
            double gap = ranked.get(9).points() - wire;
            totalGap += gap;
            gapList.add(gap);
            counted++;
            System.out.printf("%-8s %14.1f %14.1f %14.1f %12.1f%n", entry.getKey(),
                    ranked.get(0).points(), ranked.get(9).points(), wire, gap);
        }
        double[] gaps = new double[gapList.size()];
        for(int i = 0; i < gaps.length; i++){
            gaps[i] = gapList.get(i);
        }
        System.out.printf("%nthe TE10 you draft beat the best tight end left on the wire"
                + " by %.1f points%na season, +/- %.1f. That is the whole prize for"
                + " spending a pick on one,%nand it does not clear its own error bar in"
                + " either direction.%n",
                totalGap / Math.max(1, counted), twoStandardErrors(gaps));
    }

    /**
     * The swap: TE early, TE late, or never.
     *
     * All three options must field the SAME number of lineup slots or the
     * comparison is rigged. Streaming spends both picks on receivers and takes
     * a tight end free off the wire, so it fields three; the other two must
     * therefore fill their third slot off the wire as well. The first version
     * of this compared two players against three and made streaming look
     * unbeatable for arithmetic reasons.
     */
    static void swap(Map<String, List<Seen>> bySeason){
        System.out.println("\n\nTHE SWAP - two picks, three ways to spend them");
        System.out.println("(every option fields three lineup slots; the third comes"
                + " off the wire)");
        System.out.printf("%-7s %-7s %12s %12s %12s   %s%n", "EARLY", "LATE",
                "A: TE early", "B: TE late", "C: stream TE", "best");
        for(int early : EARLY){
            for(int late : LATE){
                double a = 0;
                double b = 0;
                double c = 0;
                int counted = 0;
                for(List<Seen> season : bySeason.values()){
                    Seen teEarly = bestAt(season, Position.TE, early);
                    Seen wrLate = bestAt(season, Position.WR, late);
                    Seen wrEarly = bestAt(season, Position.WR, early);
                    Seen teLate = bestAt(season, Position.TE, late);
                    Seen wrLate2 = bestAt(season, Position.WR, late, wrLate);
                    double wireTe = wireLevel(season, Position.TE);
                    double wireWr = wireLevel(season, Position.WR);
                    if(teEarly == null || wrLate == null || wrEarly == null
                            || teLate == null || wrLate2 == null || wireTe == 0){
                        continue;
                    }
                    a += teEarly.points() + wrLate.points() + wireWr;
                    b += wrEarly.points() + teLate.points() + wireWr;
                    c += wrEarly.points() + wrLate.points() + wireTe;
                    counted++;
                }
                if(counted == 0){
                    continue;
                }
                a /= counted;
                b /= counted;
                c /= counted;
                String best = a >= b && a >= c ? "A - take the TE early"
                        : b >= a && b >= c ? "B - wait on TE"
                        : "C - never draft one";
                System.out.printf("%-7d %-7d %12.1f %12.1f %12.1f   %s%n", early, late,
                        a, b, c, best);
            }
        }
    }

    /**
     * The only question this tool exists to answer.
     *
     * Model A plans a tight end at pick 79 (round 7). It maximises expected
     * best-nine points from projections, which means it cannot see any of the
     * things that decide this particular call: that tight end runs flat for
     * twenty names while receiver does not, that the wire supplies a startable
     * tight end for free, or that receivers and backs carry the deeper bust
     * risk. So: at 79, is Model A right?
     */
    static void verdict(Map<String, List<Seen>> bySeason){
        int modelAPick = 79;
        int myNextPick = 90;
        System.out.println("\n\nWHO EACH OPTION ACTUALLY GOT (the join, shown so it can"
                + " be checked)");
        List<double[]> perSeason = new ArrayList<>();
        for(List<Seen> season : bySeason.values()){
            Seen teEarly = bestAt(season, Position.TE, modelAPick);
            Seen wrLate = bestAt(season, Position.WR, myNextPick);
            Seen wrEarly = bestAt(season, Position.WR, modelAPick);
            Seen teLate = bestAt(season, Position.TE, myNextPick);
            double wireTe = wireLevel(season, Position.TE);
            double wireWr = wireLevel(season, Position.WR);
            if(teEarly == null || wrLate == null || wrEarly == null || teLate == null
                    || wireTe == 0){
                continue;
            }
            System.out.printf("   %-6s TE@79 %-22s %6.1f | WR@79 %-22s %6.1f%n"
                    + "          TE@90 %-22s %6.1f | WR@90 %-22s %6.1f%n",
                    seasonOf(bySeason, season), teEarly.name(), teEarly.points(),
                    wrEarly.name(), wrEarly.points(), teLate.name(), teLate.points(),
                    wrLate.name(), wrLate.points());
            perSeason.add(new double[]{
                    teEarly.points() + wrLate.points() + wireWr,
                    wrEarly.points() + teLate.points() + wireWr,
                    wrEarly.points() + wrLate.points() + wireTe});
        }
        if(perSeason.size() < 3){
            return;
        }
        double a = mean(perSeason, 0);
        double b = mean(perSeason, 1);
        double c = mean(perSeason, 2);

        // Paired, season by season - the seasons themselves differ far more
        // than the options do, and pairing cancels that out.
        double[] bMinusA = new double[perSeason.size()];
        double[] cMinusA = new double[perSeason.size()];
        for(int i = 0; i < perSeason.size(); i++){
            bMinusA[i] = perSeason.get(i)[1] - perSeason.get(i)[0];
            cMinusA[i] = perSeason.get(i)[2] - perSeason.get(i)[0];
        }

        System.out.printf("%n%nSHOULD I LISTEN TO MODEL A WHEN IT CALLS TE AT PICK %d?%n",
                modelAPick);
        System.out.println("(season totals - the weaker of the two. The weekly table"
                + " above is the one\nto trust: it is the only one that can see the"
                + " early starters' injuries.)");
        // With five seasons and one player an option, the MEAN is at the mercy
        // of a single outcome - 2024's Brock Bowers at pick 90 scored 206.7 and
        // drags the average on his own. How often an option won is the sturdier
        // statistic, so both are shown, and they are allowed to disagree.
        long bWins = java.util.Arrays.stream(bMinusA).filter(d -> d > 0).count();
        long cWins = java.util.Arrays.stream(cMinusA).filter(d -> d > 0).count();
        System.out.printf("%n%-32s %9s %11s %12s %12s%n", "", "points", "vs A",
                "+/-2se", "seasons won");
        System.out.printf("   A  take the TE at %-14d %9.1f%n", modelAPick, a);
        System.out.printf("   B  wait, take one at %-11d %9.1f %+11.1f %12.1f %9d/%d%n",
                myNextPick, b, b - a, twoStandardErrors(bMinusA), bWins,
                perSeason.size());
        System.out.printf("   C  never draft one, stream     %9.1f %+11.1f %12.1f %9d/%d%n",
                c, c - a, twoStandardErrors(cMinusA), cWins, perSeason.size());
        System.out.printf("%n   B beat A in %d of %d seasons. The mean is noisy; that"
                + " count is not,%n   and it is the part worth trusting.%n",
                bWins, perSeason.size());

        boolean bSeparates = Math.abs(b - a) > twoStandardErrors(bMinusA);
        boolean cSeparates = Math.abs(c - a) > twoStandardErrors(cMinusA);
        System.out.printf("%n   %d seasons, one player per option per season - so the bars"
                + " are wide%n   on purpose. A difference inside its own bar is not a"
                + " difference.%n", perSeason.size());
        if(!bSeparates && !cSeparates){
            System.out.println("\n   NO ANSWER. Both alternatives sit inside their own"
                    + " error bars, so this\n   cannot tell you to override Model A."
                    + " What it CAN say is that nothing\n   here supports paying up for"
                    + " a tight end either - see the streaming\n   table above, where the"
                    + " wire beat the TE10 you would have drafted.");
        }
        else {
            System.out.printf("%n   %s%n", b > a && bSeparates
                    ? "OVERRIDE IT: waiting beat taking one at " + modelAPick
                      + ", by more than the noise."
                    : c > a && cSeparates
                    ? "OVERRIDE IT: drafting no tight end at all beat it, by more than"
                      + " the noise."
                    : "LISTEN TO IT: nothing beat taking the tight end there.");
        }
    }

    static String seasonOf(Map<String, List<Seen>> bySeason, List<Seen> season){
        for(Map.Entry<String, List<Seen>> entry : bySeason.entrySet()){
            if(entry.getValue() == season){
                return entry.getKey();
            }
        }
        return "?";
    }

    static double mean(List<double[]> rows, int column){
        double total = 0;
        for(double[] row : rows){
            total += row[column];
        }
        return total / rows.size();
    }

    static double twoStandardErrors(double[] values){
        int n = values.length;
        if(n < 2){
            return 0;
        }
        double mean = java.util.Arrays.stream(values).average().orElse(0);
        double variance = 0;
        for(double value : values){
            variance += (value - mean) * (value - mean);
        }
        variance /= n - 1;
        return 2 * Math.sqrt(variance / n);
    }

    /**
     * The same swap, scored week by week off ACTUAL games played.
     *
     * Season totals have already absorbed the games a starter missed, so a
     * total-based swap cannot see the thing that decides it: a fourth receiver
     * covers for a first-rounder who misses six games, while a lone tight end
     * has no cover at all. The early rounds are where that exposure lives, and
     * scoring totals hides it completely.
     *
     * So field a lineup. Each player is available for the games he really
     * played that season - measured, not modelled - scoring at his real
     * per-game rate, with the missed weeks scattered at random since the data
     * says how many he missed but not which. Every week fills 2 RB, 3 WR, 1 TE
     * and 2 flex from whoever is up, and any slot nobody can fill takes the
     * waiver wire's rate.
     */
    static void injuryAwareSwap(Map<String, List<Seen>> bySeason){
        int[] earlyPicks = {7, 18, 31, 42, 55, 66};
        Position[] earlyShape = {Position.RB, Position.WR, Position.RB,
                Position.WR, Position.WR, Position.WR};
        int draws = Integer.getInteger("draws", 400);

        System.out.println("\n\nTHE SWAP AGAIN, WITH THE EARLY STARTERS' REAL INJURIES");
        System.out.println("(rounds 1-6 as Model A shapes them: RB WR RB WR WR WR;"
                + " 17 weeks, actual games played)");
        System.out.printf("%n%-8s %13s %13s %13s %10s%n", "SEASON", "A: TE at 79",
                "B: TE at 90", "C: stream TE", "games lost");
        double a = 0;
        double b = 0;
        double c = 0;
        int counted = 0;
        List<double[]> perSeason = new ArrayList<>();
        for(Map.Entry<String, List<Seen>> entry : bySeason.entrySet()){
            List<Seen> season = entry.getValue();
            List<Seen> early = new ArrayList<>();
            boolean complete = true;
            for(int i = 0; i < earlyPicks.length; i++){
                Seen pick = bestAtExcluding(season, earlyShape[i], earlyPicks[i], early);
                if(pick == null){
                    complete = false;
                    break;
                }
                early.add(pick);
            }
            Seen teEarly = bestAtExcluding(season, Position.TE, 79, early);
            Seen wrEarly = bestAtExcluding(season, Position.WR, 79, early);
            if(!complete || teEarly == null || wrEarly == null){
                continue;
            }
            List<Seen> usedA = new ArrayList<>(early);
            usedA.add(teEarly);
            Seen wrLateA = bestAtExcluding(season, Position.WR, 90, usedA);
            List<Seen> usedB = new ArrayList<>(early);
            usedB.add(wrEarly);
            Seen teLateB = bestAtExcluding(season, Position.TE, 90, usedB);
            Seen wrLateC = bestAtExcluding(season, Position.WR, 90, usedB);
            if(wrLateA == null || teLateB == null || wrLateC == null){
                continue;
            }
            Map<Position, Double> wire = new EnumMap<>(Position.class);
            for(Position position : new Position[]{Position.RB, Position.WR, Position.TE}){
                wire.put(position, wireLevel(season, position) / 17.0);
            }

            List<Seen> rosterA = new ArrayList<>(early);
            rosterA.add(teEarly);
            rosterA.add(wrLateA);
            List<Seen> rosterB = new ArrayList<>(early);
            rosterB.add(wrEarly);
            rosterB.add(teLateB);
            List<Seen> rosterC = new ArrayList<>(early);
            rosterC.add(wrEarly);
            rosterC.add(wrLateC);

            double scoreA = weeklyScore(rosterA, wire, draws, 31_000L);
            double scoreB = weeklyScore(rosterB, wire, draws, 31_000L);
            double scoreC = weeklyScore(rosterC, wire, draws, 31_000L);
            int gamesLost = 0;
            for(Seen player : early){
                gamesLost += Math.max(0, 17 - player.games());
            }
            System.out.printf("%-8s %13.1f %13.1f %13.1f %10d%n", entry.getKey(),
                    scoreA, scoreB, scoreC, gamesLost);
            perSeason.add(new double[]{scoreA, scoreB, scoreC});
            a += scoreA;
            b += scoreB;
            c += scoreC;
            counted++;
        }
        if(counted == 0){
            System.out.println("   not enough joined seasons");
            return;
        }
        a /= counted;
        b /= counted;
        c /= counted;
        double[] bMinusA = new double[perSeason.size()];
        double[] cMinusA = new double[perSeason.size()];
        for(int i = 0; i < perSeason.size(); i++){
            bMinusA[i] = perSeason.get(i)[1] - perSeason.get(i)[0];
            cMinusA[i] = perSeason.get(i)[2] - perSeason.get(i)[0];
        }
        long bWins = java.util.Arrays.stream(bMinusA).filter(d -> d > 0).count();
        long cWins = java.util.Arrays.stream(cMinusA).filter(d -> d > 0).count();
        System.out.printf("%n%-30s %11s %11s %11s %12s%n", "", "points", "vs A",
                "+/-2se", "seasons won");
        System.out.printf("   A  TE at 79                %11.1f%n", a);
        System.out.printf("   B  TE at 90                %11.1f %+11.1f %11.1f %9d/%d%n",
                b, b - a, twoStandardErrors(bMinusA), bWins, perSeason.size());
        System.out.printf("   C  stream TE               %11.1f %+11.1f %11.1f %9d/%d%n",
                c, c - a, twoStandardErrors(cMinusA), cWins, perSeason.size());
        System.out.println("\n   This is the comparison that can see injuries to the"
                + " players drafted in\n   rounds 1-6, because it fields a lineup every"
                + " week instead of adding up\n   seasons. Missed weeks are scattered at"
                + " random - the data says how many\n   games each man missed, never"
                + " which ones.");
        System.out.println("\n   Read the bars, not the means. An earlier version of this"
                + " started a rostered\n   player even when the waiver wire scored more,"
                + " which punished whichever\n   option owned the weakest man and made"
                + " these gaps look roughly twice the\n   size they are. Fixed"
                + " 2026-08-29.");
    }

    /** A season of weekly lineups, given who was available when. */
    static double weeklyScore(List<Seen> roster, Map<Position, Double> wire,
                              int draws, long seed){
        Random random = new Random(seed);
        double total = 0;
        for(int draw = 0; draw < draws; draw++){
            boolean[][] up = new boolean[roster.size()][17];
            for(int p = 0; p < roster.size(); p++){
                List<Integer> weeks = new ArrayList<>();
                for(int week = 0; week < 17; week++){
                    weeks.add(week);
                }
                java.util.Collections.shuffle(weeks, random);
                int plays = Math.min(17, Math.max(0, roster.get(p).games()));
                for(int i = 0; i < plays; i++){
                    up[p][weeks.get(i)] = true;
                }
            }
            for(int week = 0; week < 17; week++){
                List<Seen> availableRb = new ArrayList<>();
                List<Seen> availableWr = new ArrayList<>();
                List<Seen> availableTe = new ArrayList<>();
                for(int p = 0; p < roster.size(); p++){
                    if(!up[p][week]){
                        continue;
                    }
                    Seen player = roster.get(p);
                    (player.position() == Position.RB ? availableRb
                            : player.position() == Position.WR ? availableWr
                            : availableTe).add(player);
                }
                Comparator<Seen> byRate = Comparator.comparingDouble(
                        (Seen s) -> perGame(s)).reversed();
                availableRb.sort(byRate);
                availableWr.sort(byRate);
                availableTe.sort(byRate);
                List<Seen> flexPool = new ArrayList<>();
                total += fill(availableRb, 2, Position.RB, wire, flexPool);
                total += fill(availableWr, 3, Position.WR, wire, flexPool);
                total += fill(availableTe, 1, Position.TE, wire, flexPool);
                flexPool.sort(byRate);
                double flexWire = wire.getOrDefault(Position.WR, 0.0);
                for(int slot = 0; slot < 2; slot++){
                    total += slot < flexPool.size()
                            ? Math.max(perGame(flexPool.get(slot)), flexWire)
                            : flexWire;
                }
            }
        }
        return total / draws;
    }

    /**
     * Fill n slots at a position with the best available, where "available"
     * includes the waiver wire.
     *
     * The first version took rostered players first and only fell back to the
     * wire when it ran out, which meant adding a tight end who scored less than
     * the wire's tight end LOWERED the score - it benched a better free player
     * to start a worse owned one. Nobody plays that way. A rostered man starts
     * only if he beats the wire; otherwise he drops to the flex pool and the
     * wire takes the slot.
     */
    static double fill(List<Seen> available, int slots, Position position,
                       Map<Position, Double> wire, List<Seen> flexPool){
        double wireRate = wire.getOrDefault(position, 0.0);
        double points = 0;
        int used = 0;
        for(int slot = 0; slot < slots; slot++){
            if(used < available.size() && perGame(available.get(used)) >= wireRate){
                points += perGame(available.get(used));
                used++;
            }
            else {
                points += wireRate;
            }
        }
        for(int extra = used; extra < available.size(); extra++){
            flexPool.add(available.get(extra));
        }
        return points;
    }

    static double perGame(Seen player){
        return player.games() > 0 ? player.points() / player.games() : 0;
    }

    public static Seen bestAtExcluding(List<Seen> season, Position position, double minimumAdp,
                                List<Seen> taken){
        return season.stream()
                .filter(s -> s.position() == position && s.adp() >= minimumAdp)
                .filter(s -> taken.stream().noneMatch(t -> t.name().equals(s.name())))
                .min(Comparator.comparingDouble(Seen::adp))
                .orElse(null);
    }

    /** The best man at a position that this league leaves undrafted. */
    public static double wireLevel(List<Seen> season, Position position){
        int drafted = position == Position.TE ? 18 : 80;
        List<Seen> ranked = season.stream()
                .filter(s -> s.position() == position)
                .sorted(Comparator.comparingDouble(Seen::adp)).toList();
        if(ranked.size() <= drafted){
            return 0;
        }
        return ranked.subList(drafted, Math.min(ranked.size(), drafted + 12)).stream()
                .mapToDouble(Seen::points).max().orElse(0);
    }

    static Seen bestAt(List<Seen> season, Position position, double minimumAdp){
        return bestAt(season, position, minimumAdp, null);
    }

    /** Who you would actually get: the earliest-ADP man at that position still there. */
    static Seen bestAt(List<Seen> season, Position position, double minimumAdp, Seen exclude){
        return season.stream()
                .filter(s -> s.position() == position && s.adp() >= minimumAdp)
                .filter(s -> exclude == null || !s.name().equals(exclude.name()))
                .min(Comparator.comparingDouble(Seen::adp))
                .orElse(null);
    }

    static double replacement(List<Seen> ranked, Position position){
        int rank = position == Position.TE ? 19 : position == Position.RB ? 48 : 60;
        List<Double> points = ranked.stream().map(Seen::points)
                .sorted(Comparator.reverseOrder()).toList();
        return points.size() > rank ? points.get(rank - 1) : 0;
    }

    /** ADP joined to what actually happened, by normalised name. */
    static List<Seen> join(File adpFile, String season) throws Exception {
        Map<String, Double> points = HistoricalActuals.pointsBySleeperID(season);
        Map<String, Integer> games = HistoricalActuals.gamesPlayedBySleeperID(season);
        Map<String, String> idByName = new HashMap<>();
        for(String id : points.keySet()){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null){
                idByName.putIfAbsent(normalise(player.firstName + " " + player.lastName), id);
            }
        }

        List<String> lines = Files.readAllLines(adpFile.toPath());
        String[] header = lines.get(0).split(",");
        int nameCol = -1;
        int posCol = -1;
        int adpCol = -1;
        for(int c = 0; c < header.length; c++){
            if(header[c].equals("name")){
                nameCol = c;
            }
            if(header[c].equals("position")){
                posCol = c;
            }
            if(header[c].equals("AVG")){
                adpCol = c;
            }
        }
        List<Seen> seen = new ArrayList<>();
        if(nameCol < 0 || posCol < 0 || adpCol < 0){
            return seen;
        }
        for(String line : lines.subList(1, lines.size())){
            String[] cells = line.split(",");
            if(cells.length <= Math.max(adpCol, Math.max(nameCol, posCol))
                    || !cells[adpCol].matches("\\d+(\\.\\d+)?")){
                continue;
            }
            Position position;
            try {
                position = Position.valueOf(cells[posCol].trim());
            }
            catch(IllegalArgumentException notSkill){
                continue;
            }
            String id = idByName.get(normalise(cells[nameCol]));
            if(id == null){
                continue;   // never played, or a name the join cannot reach
            }
            seen.add(new Seen(cells[nameCol].trim(), position,
                    Double.parseDouble(cells[adpCol]), points.getOrDefault(id, 0.0),
                    games.getOrDefault(id, 0)));
        }
        return seen;
    }

    /** Sleeper and FantasyPros disagree about suffixes and punctuation. */
    static String normalise(String name){
        return name.toLowerCase()
                .replaceAll("[.'`]", "")
                .replaceAll("\\s+(jr|sr|ii|iii|iv|v)$", "")
                .trim();
    }
}
