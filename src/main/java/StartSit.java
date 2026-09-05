import PlayerImportAndSetup.Position;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The lineup to set this week, and which of its calls are real.
 *
 * The best legal ten out of league-scored week projections is the easy half.
 * The half that matters is saying which choices are actually choices: a
 * projection is not a fact, and two men three points apart are a coin flip
 * dressed as a decision. So the gap at which a call stops being a call is
 * MEASURED, not asserted - `-Pcalibrate` walks five finished seasons, takes
 * every same-position pair of men who both played in a week, bins them by the
 * gap the projections gave them, and reports how often the man projected LOWER
 * actually outscored the other. That curve is written to
 * data/start-sit-flip-<date>.txt and read back by StartSitTest, so the bar and
 * the measurement cannot drift apart.
 *
 *   ./gradlew run -Pmain=StartSit [-Pweek=n] [-Pme=<name>] [-Pcalibrate]
 *
 * A man ABSENT from the week's projection feed is not playing - bye, inactive,
 * or not yet published. That is a different statement from "projected low", and
 * the report keeps them apart rather than sorting a bye to the bottom and
 * calling it a bench decision.
 */
public class StartSit {

    /** One man, this week. `playing` false means the feed has no row for him at all. */
    public record Man(String id, String name, Position position, double projected, boolean playing) {}

    /** How often the lower-projected man of a pair actually won, by the gap between them. */
    public record Flip(double gapFrom, double gapTo, int pairs, double flipRate) {}

    static final double[] BINS = {0, 1, 2, 3, 4, 5, 7, 9, 12, 16, 25, Double.MAX_VALUE};

    /**
     * The flip curve over finished seasons: for every same-position pair in a
     * week where BOTH men played, how often the one the projections put lower
     * scored more. Pairs are drawn only from men projected at all, because a man
     * with no projection is not a lineup choice.
     */
    static List<Flip> flipCurve(List<Map<String, Double>> projectedWeeks,
                                List<Map<String, Double>> actualWeeks,
                                Map<String, Position> positionOf,
                                Map<Position, Integer> startableDepth,
                                int[] tiesOut){
        int[] pairs = new int[BINS.length - 1];
        int[] flips = new int[BINS.length - 1];
        int ties = 0;
        for(int w = 0; w < projectedWeeks.size(); w++){
            Map<String, Double> projected = projectedWeeks.get(w);
            Map<String, Double> actual = actualWeeks.get(w);
            Map<Position, List<String>> byPosition = new HashMap<>();
            for(String id : projected.keySet()){
                Position position = positionOf.get(id);
                if(position == null || !actual.containsKey(id)){
                    continue;   // he did not play; not a decision anybody regrets
                }
                byPosition.computeIfAbsent(position, u -> new ArrayList<>()).add(id);
            }
            for(Map.Entry<Position, List<String>> entry : byPosition.entrySet()){
                // THE POPULATION IS MEN THIS LEAGUE ACTUALLY ROSTERS. Pairing
                // every projected man at a position answers a question nobody
                // asks - the 90th receiver against the 91st - and those pairs
                // are almost all two men who scored nothing, which counts as
                // "the projection was right" and drags the tightest bin to a
                // flip rate BELOW the loose ones. The first run did exactly
                // that: 0-1 points read 0.166 against 0.364 for 1-2. The depth
                // is the league's own, from how many it drafts at each position.
                List<String> group = new ArrayList<>(entry.getValue());
                group.sort(Comparator.comparingDouble((String id) -> -projected.get(id)));
                int depth = Math.min(group.size(),
                        startableDepth.getOrDefault(entry.getKey(), 24));
                group = group.subList(0, depth);
                for(int i = 0; i < group.size(); i++){
                    for(int j = i + 1; j < group.size(); j++){
                        String a = group.get(i), b = group.get(j);
                        double pa = projected.get(a), pb = projected.get(b);
                        String higher = pa >= pb ? a : b, lower = pa >= pb ? b : a;
                        double scoredHigher = actual.get(higher), scoredLower = actual.get(lower);
                        if(scoredHigher == scoredLower){
                            ties++;   // not a win for either reading
                            continue;
                        }
                        int bin = binOf(Math.abs(pa - pb));
                        pairs[bin]++;
                        if(scoredLower > scoredHigher){
                            flips[bin]++;
                        }
                    }
                }
            }
        }
        tiesOut[0] = ties;
        List<Flip> curve = new ArrayList<>();
        for(int b = 0; b < pairs.length; b++){
            if(pairs[b] == 0){
                continue;
            }
            curve.add(new Flip(BINS[b], BINS[b + 1], pairs[b], (double) flips[b] / pairs[b]));
        }
        return curve;
    }

    static int binOf(double gap){
        for(int b = 0; b < BINS.length - 1; b++){
            if(gap >= BINS[b] && gap < BINS[b + 1]){
                return b;
            }
        }
        return BINS.length - 2;
    }

    /**
     * The gap at which the flip rate first drops below `bar` - below this many
     * points the two men are a coin flip and the model has no opinion worth
     * acting on.
     */
    /** The newest committed flip curve, parsed back out of its own report. */
    static List<Flip> readCurve(List<String> lines){
        List<Flip> curve = new ArrayList<>();
        for(String line : lines){
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^(\\d+)-(\\d+)\\s+(\\d+)\\s+([\\d.]+)\\s*$").matcher(line);
            if(m.find()){
                curve.add(new Flip(Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)),
                        Integer.parseInt(m.group(3)), Double.parseDouble(m.group(4))));
            }
        }
        return curve;
    }

    /** How often the man projected `gap` lower actually outscores the other. */
    static double flipRate(List<Flip> curve, double gap){
        for(Flip flip : curve){
            if(gap >= flip.gapFrom() && gap < flip.gapTo()){
                return flip.flipRate();
            }
        }
        return curve.isEmpty() ? 0.5 : curve.get(curve.size() - 1).flipRate();
    }

    static double closeBelow(List<Flip> curve, double bar){
        for(Flip flip : curve){
            if(flip.flipRate() < bar){
                return flip.gapFrom();
            }
        }
        return curve.isEmpty() ? 0 : curve.get(curve.size() - 1).gapTo();
    }

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        if(System.getProperty("calibrate") != null){
            calibrate(configuration);
            return;
        }
        String season = LeagueWeek.season();
        int week = LeagueWeek.week();
        String me = System.getProperty("me", configuration.getUserIDToDisplayName()
                .getOrDefault(configuration.getMyID(), configuration.getMyID()));
        List<Flip> curve = List.of();
        Path newest = null;
        try(var files = Files.list(Path.of("data"))){
            newest = files.filter(p -> p.getFileName().toString().matches("start-sit-flip-\\d{4}-\\d{2}-\\d{2}\\.txt"))
                    .max(Comparator.comparing(p -> p.getFileName().toString())).orElse(null);
        }
        if(newest != null){
            curve = readCurve(Files.readAllLines(newest));
        }

        Map<String, Double> projected = LeagueWeek.projected(season, week);
        Map<String, String> ownerOf = LeagueOwners.today(configuration);
        List<Man> mine = new ArrayList<>();
        for(Map.Entry<String, String> entry : ownerOf.entrySet()){
            if(!entry.getValue().equals(me)){
                continue;
            }
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player == null || player.position == null){
                continue;
            }
            Double points = projected.get(entry.getKey());
            mine.add(new Man(entry.getKey(), player.firstName + " " + player.lastName,
                    player.position, points == null ? 0 : points, points != null));
        }
        mine.sort(Comparator.comparingDouble(Man::projected).reversed());

        List<TeamRankings.Man> playable = new ArrayList<>();
        for(Man man : mine){
            if(man.playing()){
                playable.add(DraftExpectation.man(man.id(), man.projected(), false, 0));
            }
        }
        TeamRankings.Lineup lineup = TeamRankings.bestLineup(playable);
        List<String> starters = new ArrayList<>();
        for(TeamRankings.Man man : lineup.starting()){ starters.add(man.id()); }

        StringBuilder out = new StringBuilder();
        out.append(String.format("START/SIT  %s  season %s week %d  (%s)%n",
                LocalDate.now(), season, week, me));
        out.append(String.format("Projections are league-scored (6-pt passing TD) and read through the %s path.%n",
                LeagueWeek.finished(week) ? "FINISHED-week" : "live, day-cached"));
        out.append("A man with no row in the week's feed is NOT PLAYING - bye, inactive, or unpublished - which is\n");
        out.append("a different thing from projected low.\n");
        out.append(newest == null
                ? "NO FLIP CURVE FOUND - run -Pcalibrate first; the odds beside each bench call are missing.\n\n"
                : String.format("The odds beside a bench call are MEASURED, from %s: over five seasons, how often the%n"
                        + "man projected that much lower actually outscored the other. 50%% is a coin flip.%n%n", newest));
        out.append(String.format("%-24s %-4s %9s   %s%n", "MAN", "POS", "PROJ", "VERDICT"));
        for(Man man : mine){
            String verdict;
            if(!man.playing()){
                verdict = "NOT PLAYING - no row in the week's feed";
            }
            else if(starters.contains(man.id())){
                verdict = "START";
            }
            else {
                double best = closestStarter(man, mine, starters);
                verdict = best == Double.MAX_VALUE || curve.isEmpty()
                        ? "bench"
                        : String.format("bench, %.1f behind %s - he outscores that starter %.0f%% of the time",
                                best, man.position(), 100 * flipRate(curve, best));
            }
            out.append(String.format("%-24s %-4s %9s   %s%n", man.name(), man.position(),
                    man.playing() ? String.format("%.1f", man.projected()) : "-", verdict));
        }
        out.append(String.format("%nprojected starters: %.1f (%d of ten slots filled)%n",
                lineup.starters(), lineup.starting().size()));
        System.out.print(out);
        Path target = Path.of("data", "start-sit-" + season + "-w" + week + ".txt");
        Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
        System.out.println("written to " + target);
    }

    /** The smallest gap between this benched man and a starter he could replace. */
    private static double closestStarter(Man man, List<Man> roster, List<String> starters){
        double best = Double.MAX_VALUE;
        for(Man other : roster){
            if(starters.contains(other.id()) && other.position() == man.position()){
                best = Math.min(best, other.projected() - man.projected());
            }
        }
        return best == Double.MAX_VALUE ? Double.MAX_VALUE : Math.max(0, best);
    }

    private static void calibrate(AAAConfiguration configuration) throws Exception {
        int current = Integer.parseInt(configuration.getSeason());
        List<Map<String, Double>> projectedWeeks = new ArrayList<>();
        List<Map<String, Double>> actualWeeks = new ArrayList<>();
        Map<String, Position> positionOf = new HashMap<>();
        for(int year = current - 5; year < current; year++){
            String season = String.valueOf(year);
            for(int week = 1; week <= WeeklyActuals.WEEKS; week++){
                Map<String, Double> projected = WeeklyProjections.pointsBySleeperID(season, week);
                Map<String, Double> actual = LeagueActuals.weeklyPoints(season, week);
                if(projected.isEmpty() || actual.isEmpty()){
                    continue;
                }
                projectedWeeks.add(projected);
                actualWeeks.add(actual);
                for(String id : projected.keySet()){
                    positionOf.computeIfAbsent(id, u -> {
                        Player player = Player.getPlayerFromSIDV2(u);
                        return player == null ? null : player.position;
                    });
                }
            }
        }
        Map<Position, Integer> depth = InsuranceTest.replacementRanks(configuration);
        depth.putIfAbsent(Position.DEF, 13);
        int[] ties = new int[1];
        List<Flip> curve = flipCurve(projectedWeeks, actualWeeks, positionOf, depth, ties);
        StringBuilder out = new StringBuilder();
        out.append(String.format("WHEN IS A LINEUP CALL A CALL?  %s%n", LocalDate.now()));
        out.append(String.format("%d week-feeds over %d seasons. Same-position pairs of men who BOTH played and were%n",
                projectedWeeks.size(), 5));
        out.append(String.format("both inside the depth this league actually rosters (%s), binned by the gap the%n",
                depth));
        out.append("projections gave them: how often did the LOWER one win?\n");
        out.append(String.format("0.50 is a coin flip and the projection told you nothing. %d tied pairs are excluded -%n", ties[0]));
        out.append("a tie is not a win for either reading, and counting it as one is what made the first\n");
        out.append("run of this curve read 0.166 in its tightest bin against 0.364 in the next.\n\n");
        out.append(String.format("%-14s %10s %10s%n", "PROJ GAP", "pairs", "lower won"));
        for(Flip flip : curve){
            out.append(String.format("%-14s %10d %9.3f%n",
                    flip.gapTo() == Double.MAX_VALUE ? String.format("%.0f+", flip.gapFrom())
                            : String.format("%.0f-%.0f", flip.gapFrom(), flip.gapTo()),
                    flip.pairs(), flip.flipRate()));
        }
        for(double bar : new double[]{0.45, 0.40, 0.35}){
            out.append(String.format("%nbelow %.0f%% flips, a gap of %.0f points or more is a real call",
                    bar * 100, closeBelow(curve, bar)));
        }
        out.append("\n");
        System.out.print(out);
        Path target = Path.of("data", "start-sit-flip-" + LocalDate.now() + ".txt");
        Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
        System.out.println("written to " + target);
    }
}
