import PlayerImportAndSetup.Position;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * After the week: what each man's box score is worth, and what it is not.
 *
 * Justin, the Monday of week 1: "who do I sell after an abnormally high game,
 * who do I keep after a higher-than-expected game, and the converse". Two
 * questions hide in that and only one of them is measurable here.
 *
 * WHAT THE EVIDENCE KEEPS is measured. {@link InSeasonLearning} fitted the one
 * free parameter of the honest update rule on thirteen seasons: kappa, in
 * games, is how much evidence it takes to move the preseason prior halfway.
 * After g games the estimate keeps g/(kappa+g) of the surprise and gives the
 * rest back - about one point in thirteen for a quarterback after one game, one
 * in seven for a back. The same study measured whether flipping the board on
 * that evidence is right, week by week, and this report reruns that measurement
 * for the week it is reading rather than quoting the number.
 *
 * WHAT A RIVAL WILL PAY is not measured anywhere in this repo and cannot be from
 * its data. The verdicts assume the obvious thing - that a man who just scored
 * thirty is easier to move and a man who just scored four is cheaper to ask
 * for - and the footer says so. The trade board prices on Sleeper's season
 * projections; this report counts how many of those moved since the season
 * started, because if the answer is none, the board has not seen the week and
 * this page is the only thing that has.
 *
 * One reading Sleeper's own data does support: when the SEASON projection was
 * raised in the same window, the projection's authors saw a role change and not
 * a box score, and that is the one case where a big week is worth keeping or
 * paying for. The report reads it off the daily snapshots the same way
 * {@link MarketMovers} does.
 *
 * The prior is the last daily projection snapshot before Sleeper's own
 * season_start_date, per man, over the {@link WeeklyStarterValue#WEEKS} games
 * the objective counts. Actuals are league-scored through {@link LeagueWeek}.
 * "Abnormal" is measured too: z is the surprise over the within-player weekly
 * scatter the study fitted, in the position's own units, so |z| at one is a
 * week outside the man's own normal range and two is rare.
 *
 *   ./gradlew run -Pmain=WeekReaction [-Pweek=n] [-Pme=<name>] [-PminMove=3] [-Ptop=25]
 *
 * Defences are not read: kappa was fitted for the four skill positions only.
 */
public class WeekReaction {

    /** A man's season so far, set against his preseason prior. */
    public record Read(String id, String name, Position position, String team, String owner,
                       double priorPpg, Double weekProjected, Double weekActual,
                       double observedPpg, int games, double z, double keptShare,
                       double newPpg, double rosChange, double sleeperMove, String flags,
                       String verdict, double bandLow, double bandHigh) {}

    /** A week this far outside a man's own scatter is worth a row. */
    static final double ABNORMAL = 1.0;

    /* ---------------- the arithmetic, tested ---------------- */

    /** The posterior mean after `games` games: the study's rule, one line. */
    static double posterior(double prior, double observed, int games, double kappa){
        return games == 0 ? prior : (kappa * prior + games * observed) / (kappa + games);
    }

    /** The share of a surprise the estimate keeps after `games` games. */
    static double keptShare(int games, double kappa){
        return games / (kappa + games);
    }

    /** The surprise in units of the man's own weekly scatter, over `games` games. */
    static double z(double prior, double observed, int games, double weeklySd){
        return games == 0 || weeklySd <= 0 ? 0 : (observed - prior) / (weeklySd / Math.sqrt(games));
    }

    /** The latest snapshot day strictly before the season started, or null. */
    static String priorDay(Collection<String> days, String seasonStart){
        String best = null;
        for(String day : days){
            if(day.compareTo(seasonStart) < 0 && (best == null || day.compareTo(best) > 0)){
                best = day;
            }
        }
        return best;
    }

    /** The position's level this season: mean prior ppg of the `cap` men a roster could reach. */
    static double level(Map<String, Double> priorPpg, Map<String, Position> positionOf,
                        Position position, int cap){
        List<Double> at = new ArrayList<>();
        for(Map.Entry<String, Double> entry : priorPpg.entrySet()){
            if(positionOf.get(entry.getKey()) == position){
                at.add(entry.getValue());
            }
        }
        at.sort(Comparator.reverseOrder());
        double sum = 0;
        int n = 0;
        for(int i = 0; i < Math.min(cap, at.size()); i++){
            sum += at.get(i);
            n++;
        }
        return n == 0 ? 1 : sum / n;
    }

    /**
     * What to do, on the stated assumption that rivals price the box score.
     * `sleeperMove` is the change in his SEASON projection since the prior
     * snapshot; a rise past `minMove` is the one thing that turns a big week
     * into a keep. A man carrying an injury tag beyond Questionable is HURT
     * whatever he scored: the negative surprise is the injury, not the player.
     */
    static String verdict(boolean mine, boolean free, double z, double sleeperMove,
                          double minMove, boolean hurt){
        if(hurt){
            return "HURT";
        }
        if(Math.abs(z) < ABNORMAL){
            return "";
        }
        boolean raised = sleeperMove >= minMove;
        boolean cut = sleeperMove <= -minMove;
        if(z > 0){
            if(free){
                return raised ? "WORTH A CLAIM: Sleeper raised him" : "DON'T CHASE: a box score";
            }
            if(mine){
                return raised ? "KEEP: Sleeper raised him too" : "SELL HIGH: the box score, not the man";
            }
            return raised ? "PAY UP OR PASS: Sleeper raised him" : "DON'T CHASE: a box score";
        }
        if(free){
            return "";
        }
        if(mine){
            return cut ? "SELL OR DROP: Sleeper cut him" : "HOLD: one bad week";
        }
        return cut ? "LEAVE HIM: Sleeper cut him" : "BUY LOW: one bad week";
    }

    /** The feed's position text as one of the four the study fitted, else null. */
    static Position skill(String text){
        if(text == null){
            return null;
        }
        switch(text){
            case "QB": return Position.QB;
            case "RB": return Position.RB;
            case "WR": return Position.WR;
            case "TE": return Position.TE;
            default: return null;
        }
    }

    /* ---------------- the report ---------------- */

    public static void main(String[] args) throws IOException {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        String season = LeagueWeek.season();
        double minMove = Double.parseDouble(System.getProperty("minMove", "3"));
        int top = Integer.getInteger("top", 25);
        String me = System.getProperty("me", configuration.getUserIDToDisplayName()
                .getOrDefault(configuration.getMyID(), configuration.getMyID()));
        int gamesInSeason = WeeklyStarterValue.WEEKS;

        // The week to read: Sleeper's current one, unless it has no lines yet
        // (Tuesday morning, when Sleeper has moved on and the games are last week's).
        int week = LeagueWeek.week();
        if(Integer.getInteger("week") == null && week > 1
                && LeagueWeek.actualSoFar(season, week).isEmpty()){
            week--;
        }
        boolean finished = LeagueWeek.finished(week);

        // The prior: the last daily snapshot before Sleeper's own season start.
        String seasonStart = LeagueWeek.state().get("season_start_date").getAsString();
        SleeperProjections.parseTodaysWebPage();              // today's snapshot exists after this
        LeagueScoringSettings scoring = SleeperLeague.getSeriousLeague().league.leagueScoringSettings;
        TreeMap<String, Path> days = MarketMovers.cachedDays(season);
        String priorDay = priorDay(days.keySet(), seasonStart);
        if(priorDay == null){
            throw new IllegalStateException("no sleeperProjections" + season
                    + "<date>.txt dated before the season start " + seasonStart
                    + "; cached days: " + days.keySet());
        }
        String today = days.lastKey();
        Map<String, MarketMovers.Row> then = MarketMovers.read(days.get(priorDay), scoring);
        Map<String, MarketMovers.Row> now = MarketMovers.read(days.get(today), scoring);

        // The week: its projections and every played week's league-scored actuals.
        Map<String, Double> weekProjected = LeagueWeek.projected(season, week);
        List<Map<String, Double>> weeks = new ArrayList<>();
        for(int w = 1; w <= week; w++){
            weeks.add(LeagueWeek.actualSoFar(season, w));
        }
        Map<String, Double> thisWeek = weeks.get(week - 1);

        // The one free parameter, measured now, and the study's own check of it
        // at exactly this many games seen.
        Map<String, EraBoards.Board> boards = EraBoards.usable("ppr", EraIngest.MIN_RATE, EraIngest.minDepth());
        Map<String, List<InSeasonLearning.Man>> harvest = InSeasonLearning.harvest(boards);
        Map<Position, InSeasonLearning.Kappa> kappa = InSeasonLearning.fitKappa(harvest, null);
        // The 80% band on his rest-of-season rate, under whichever recipe RosBands
        // finds calibrated at this many games seen (its report says how honest).
        RosBands.Recipe recipe = RosBands.fit(harvest, Math.min(week, RosBands.SEEN[RosBands.SEEN.length - 1]));
        List<String> seasons = new ArrayList<>(harvest.keySet());
        Map<Position, String> flipRow = new EnumMap<>(Position.class);
        if(week <= 10){
            Map<String, Map<Position, double[]>> priors = new TreeMap<>();
            Map<String, Map<Position, InSeasonLearning.Kappa>> kappas = new TreeMap<>();
            for(String s : seasons){
                priors.put(s, InSeasonLearning.priorTable(harvest, s, null));
                kappas.put(s, InSeasonLearning.fitKappa(harvest, s));
            }
            for(Position position : InSeasonLearning.POSITIONS){
                List<InSeasonLearning.Flip> found = InSeasonLearning.flips(
                        harvest, seasons, position, week, 1.0, priors, kappas);
                PowerBacktest.Paired paired = InSeasonLearning.accuracy("", found, seasons.size(), false);
                flipRow.put(position, paired == null ? "n/a"
                        : String.format("%.1f%% (bar %.1f, %s, %d flips)", 100 * (0.5 + paired.diff()),
                                100 * paired.bar(), paired.real() ? "REAL" : "noise", found.size()));
            }
        }

        // Every man's prior, and each position's level this season.
        Map<String, Double> priorPpg = new HashMap<>();
        Map<String, Position> positionOf = new HashMap<>();
        for(MarketMovers.Row row : then.values()){
            Position position = skill(row.position());
            if(position != null && row.team() != null){
                priorPpg.put(row.id(), row.points() / gamesInSeason);
                positionOf.put(row.id(), position);
            }
        }
        Map<Position, Double> levelOf = new EnumMap<>(Position.class);
        for(Position position : InSeasonLearning.POSITIONS){
            levelOf.put(position, level(priorPpg, positionOf, position, InSeasonLearning.CAP.get(position)));
        }

        Map<String, String> ownerOf = LeagueOwners.today(configuration);
        List<Read> reads = new ArrayList<>();
        int unpriced = 0;
        List<String> ids = new ArrayList<>(priorPpg.keySet());
        for(String id : ownerOf.keySet()){
            if(!priorPpg.containsKey(id)){
                if(skill(now.containsKey(id) ? now.get(id).position() : null) != null){
                    unpriced++;
                }
            }
        }
        for(String id : ids){
            MarketMovers.Row before = then.get(id);
            MarketMovers.Row after = now.getOrDefault(id, before);
            String owner = ownerOf.get(id);
            boolean free = owner == null;
            if(free && before.adp() > MarketMovers.DRAFTABLE){
                continue;                       // an undrafted free agent is the wire tool's problem
            }
            Position position = positionOf.get(id);
            int games = 0;
            double total = 0;
            for(Map<String, Double> w : weeks){
                Double points = w.get(id);
                if(points != null){
                    games++;
                    total += points;
                }
            }
            double prior = priorPpg.get(id);
            double observed = games == 0 ? prior : total / games;
            InSeasonLearning.Kappa k = kappa.get(position);
            double weeklySd = Math.sqrt(k.within()) * levelOf.get(position);
            double zz = z(prior, observed, games, weeklySd);
            double newPpg = posterior(prior, observed, games, k.kappa());
            double move = after.points() - before.points();
            boolean hurt = after.injuryStatus() != null && !after.injuryStatus().equals("Questionable");
            RosBands.Band band = RosBands.band(recipe, position, prior, observed, games,
                    levelOf.get(position), gamesInSeason - week);
            reads.add(new Read(id, after.name(), position, after.team(),
                    free ? "free agent" : owner, prior, weekProjected.get(id), thisWeek.get(id),
                    observed, games, zz, keptShare(games, k.kappa()), newPpg,
                    (gamesInSeason - week) * (newPpg - prior), move,
                    MarketMovers.flags(before, after, LocalDate.parse(priorDay)),
                    verdict(me.equals(owner), free, zz, move, minMove, hurt),
                    band.low(), band.high()));
        }

        StringBuilder out = new StringBuilder();
        out.append(String.format("WEEK %d REACTION  season %s  %s%n", week, season,
                finished ? "(the week is finished)"
                        : "PARTIAL: Sleeper still says week " + week + "; " + men(thisWeek)
                                + " men carry a scored line so far (a game may be in progress) and the rest have not played"));
        out.append(String.format("data: %s; prior %s (the last daily snapshot before Sleeper's season start %s)%n",
                DataStamp.stamp(), priorDay, seasonStart));

        out.append("\n== THE RULE: what one week's evidence is worth, measured on the harvest ==\n");
        out.append(String.format("%-4s %8s %14s %12s   %s%n", "POS", "kappa", "keeps after " + week,
                "weekly sd", "a flip of the board at week " + week + " is right"));
        for(Position position : InSeasonLearning.POSITIONS){
            InSeasonLearning.Kappa k = kappa.get(position);
            out.append(String.format("%-4s %6.1f g %13.0f%% %9.1f pts   %s%n", position, k.kappa(),
                    100 * keptShare(week, k.kappa()),
                    Math.sqrt(k.within()) * levelOf.get(position),
                    flipRow.getOrDefault(position, "not measured past week 10")));
        }
        out.append(String.format("kappa is in games; 'keeps' is the share of a surprise the estimate keeps after %d game%s;%n",
                week, week == 1 ? "" : "s"));
        out.append("weekly sd is one man's own week-to-week scatter at that position's level this season (z is the surprise over it).\n");
        out.append(String.format("seasons %d (%s-%s), boards FFC ADP, outcomes Sleeper weekly under this league's scoring; InSeasonLearning's fit, rerun.%n",
                seasons.size(), seasons.get(0), seasons.get(seasons.size() - 1)));
        out.append(String.format("band = 80%% on his rest-of-season rate: %s scale, %s quantiles, chosen by RosBands' interval score at %d game%s seen;%n",
                recipe.rateScaled() ? "the man's own" : "the position's", recipe.empirical() ? "empirical" : "normal",
                Math.min(week, RosBands.SEEN[RosBands.SEEN.length - 1]), week == 1 ? "" : "s"));
        out.append("its coverage on thirteen seasons is in data/ros-bands-<date>.txt (tight ends under-cover early).\n");

        // Did Sleeper move on the week?
        List<Read> rostered = new ArrayList<>();
        for(Read r : reads){
            if(!r.owner().equals("free agent")){
                rostered.add(r);
            }
        }
        int moved = 0;
        for(Read r : rostered){
            if(Math.abs(r.sleeperMove()) >= minMove){
                moved++;
            }
        }
        out.append(String.format("%n== DID SLEEPER MOVE: season projections %s -> %s, rostered skill men ==%n", priorDay, today));
        out.append(String.format("%d of %d moved by %.0f+ season points (%d rostered skill men have no row in the prior snapshot and are not read).%n",
                moved, rostered.size(), minMove, unpriced));
        List<Read> movers = new ArrayList<>(rostered);
        movers.sort(Comparator.comparingDouble((Read r) -> -Math.abs(r.sleeperMove())));
        for(Read r : movers.subList(0, Math.min(8, movers.size()))){
            if(Math.abs(r.sleeperMove()) < minMove){
                break;
            }
            out.append(String.format("   %-22s %-3s %-4s %-12s %+6.1f   %s%n", cut(r.name(), 22), r.position(),
                    r.team(), cut(r.owner(), 12), r.sleeperMove(), r.flags()));
        }
        out.append("The trade board and the wire price on these season numbers, so what did not move here the board has not seen.\n");

        String header = String.format("%-22s %-3s %-4s %-12s %7s %6s %6s %8s %5s %6s %7s %11s %7s %7s  %-38s %s%n",
                "player", "pos", "team", "owner", "prior/g", "wk-pr", "wk-act", "ppg(g)", "z", "keeps",
                "new/g", "80% band/g", "ROS", "SlprD", "verdict", "Sleeper's own data says");
        out.append("\n== MINE ==\n").append(header);
        List<Read> mine = new ArrayList<>();
        List<Read> theirs = new ArrayList<>();
        List<Read> wire = new ArrayList<>();
        for(Read r : reads){
            if(r.owner().equals(me)){
                mine.add(r);
            }
            else if(r.owner().equals("free agent")){
                if(r.z() >= ABNORMAL){
                    wire.add(r);
                }
            }
            else if(Math.abs(r.z()) >= ABNORMAL || r.verdict().equals("HURT")){
                theirs.add(r);
            }
        }
        Comparator<Read> byZ = Comparator.comparingDouble((Read r) -> -Math.abs(r.z()));
        mine.sort(Comparator.comparingDouble((Read r) -> -r.z()));
        theirs.sort(byZ);
        wire.sort(byZ);
        for(Read r : mine){
            out.append(row(r));
        }
        List<String> noLine = new ArrayList<>();
        for(Read r : mine){
            if(r.weekActual() == null){
                noLine.add(r.name());
            }
        }
        if(!noLine.isEmpty()){
            out.append(finished ? "did not play this week: " : "no line yet (game not played): ")
                    .append(String.join(", ", noLine)).append('\n');
        }

        out.append(String.format("%n== THEIRS, outside a normal week (|z| >= %.0f) ==%n", ABNORMAL)).append(header);
        for(Read r : theirs.subList(0, Math.min(top, theirs.size()))){
            out.append(row(r));
        }
        out.append(String.format("%n== THE WIRE: the box scores everybody will bid on (free, drafted at the prior, z >= %.0f) ==%n", ABNORMAL)).append(header);
        for(Read r : wire.subList(0, Math.min(15, wire.size()))){
            out.append(row(r));
        }

        out.append("\nprior/g = season projection at the prior snapshot over " + gamesInSeason + " games; wk-pr/wk-act = this week, league-scored;\n");
        out.append("ppg(g) = mean of his played weeks so far; z = (ppg - prior) over his weekly sd / sqrt(g); keeps = the share the rule keeps;\n");
        out.append("new/g = the posterior; 80% band/g = RosBands' interval on his rate over the weeks left; ROS = (new - prior) x weeks left (byes not subtracted); SlprD = his season projection today minus the prior snapshot.\n");
        out.append("ASSUMED, NOT MEASURED: that rivals price the box score. Nothing in this repo measures what a manager will pay this week.\n");
        out.append("MEASURED: what the evidence keeps, and whether Sleeper's season number moved. Defences are not read.\n");

        System.out.print(out);
        Path report = Path.of("data", "week-reaction-" + season + "-w" + week + ".txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("\nwritten to " + report);
    }

    /** Skill players with a line - not the defences and club aggregates the feed also carries. */
    static int men(Map<String, Double> lines){
        int count = 0;
        for(String id : lines.keySet()){
            if(LeagueActuals.isMan(id)){
                count++;
            }
        }
        return count;
    }

    private static String row(Read r){
        return String.format("%-22s %-3s %-4s %-12s %7.1f %6s %6s %5.1f(%d) %+5.1f %5.0f%% %7.1f %5.1f-%-5.1f %+7.1f %+7.1f  %-38s %s%n",
                cut(r.name(), 22), r.position(), r.team(), cut(r.owner(), 12), r.priorPpg(),
                r.weekProjected() == null ? "-" : String.format("%.1f", r.weekProjected()),
                r.weekActual() == null ? "-" : String.format("%.1f", r.weekActual()),
                r.observedPpg(), r.games(), r.z(), 100 * r.keptShare(), r.newPpg(), r.bandLow(), r.bandHigh(),
                r.rosChange(), r.sleeperMove(), cut(r.verdict(), 38), r.flags());
    }

    private static String cut(String s, int n){
        return s == null ? "?" : s.length() <= n ? s : s.substring(0, n);
    }
}
