import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * THE PLAYER-WEEKS {@link FeatureScreen} IS RUN ON, AND THE HISTORY EACH
 * CANDIDATE FEATURE IS READ FROM.
 *
 * Every rule here was set before any result was seen, and most of them were
 * set by a pre-registration review (three independent critiques of the draft
 * protocol, 2026-09-25) that found the draft would have measured the wrong
 * thing in a dozen places. The ones that change what a row is:
 *
 *  - PLAYED means Sleeper's stats.gp >= 1, in every season. A stat row is not a
 *    game played: inactive, IR and NFI men carry rows (from 2021 with
 *    gms_active = 1 and no gp), and a 2018-2020 non-player may have a row of
 *    team snap counts or none. Membership in LeagueWeek.actual is not played
 *    either - that map drops a man who played and scored exactly zero.
 *  - y is his own stat row scored under the league's rules
 *    (LeagueActuals.scoreSkill), zero when the row carries no scoring stat.
 *  - Every row must join a regular-season game in nflverse's schedule on
 *    season, week, team, OPPONENT and DATE, or it is excluded and counted. The
 *    suspended 2022 week-17 Buffalo-Cincinnati game is not in the schedule and
 *    falls out here, by name in the audit.
 *  - The population weeks are 1-17, except 1-16 in 2018-2020: week 17 was
 *    those seasons' last, when starters rest, and the confirmation seasons
 *    have no such week.
 *  - Teams are the stat row's, that week. Positions are the player record's,
 *    which is TODAY's (the /stats rows carry the current record) - except that
 *    a man whose PRE-GAME projected line has 10+ pass attempts is a QB that
 *    week (Taysom Hill started at quarterback in 2020 and 2021 as a "TE").
 *    Counted in the audit. A starting passer is looked for among every skill
 *    line with 10+ attempts, not only the QB-listed ones.
 *  - A week whose stat rows cannot be read is an error in any population
 *    season and the season before it, never an empty week: an empty week
 *    would read as every man missing a game.
 *
 * Sleeper's stored weekly projection is a late number: membership is filtered
 * on playing (an inactive man carries none; a promoted backup's roughly
 * doubles), point values are not moved toward the result. So a model fitted
 * here is fitted on a projection read after the inactives, and must be fed
 * one.
 */
public class ScreenData {

    static final int HISTORY_FROM = 2010;

    static int weeks(String season){
        return Integer.parseInt(season) >= 2021 ? 18 : 17;
    }

    /** The last population week: 17, but 16 when 17 was the season's final week (2018-2020). */
    static int lastPopulationWeek(String season){
        return Integer.parseInt(season) >= 2021 ? 17 : 16;
    }

    /** One franchise across its moves, for anything that follows a team across seasons. */
    static String franchise(String team){
        if(team == null){
            return null;
        }
        return switch(team){
            case "OAK" -> "LV";
            case "SD" -> "LAC";
            case "STL", "LA" -> "LAR";
            default -> team;
        };
    }

    // ------------------------------------------------------------------ one man's week

    /** One man's week from the /stats array rows ({@link LeagueWeek#teamStatsBody}). */
    record Line(String id, String team, String opponent, String date, Position position, String lastName,
                boolean played, boolean started, double passAtt, double rushAtt, double targets,
                double receptions, double passTd, double rushTd, double recTd, double sacks,
                double airYards, double points) {

        /** Plays run through him: carries and targets, or a passer's attempts and carries. */
        double touches(){
            return !played ? 0 : position == Position.QB ? passAtt + rushAtt : rushAtt + targets;
        }

        /** The usage Sleeper projects (it projects catches, not targets, before 2021). */
        double opportunities(){
            return position == Position.QB ? passAtt + rushAtt : rushAtt + receptions;
        }

        double tds(){
            return position == Position.QB ? passTd + rushTd : rushTd + recTd;
        }

        Line asQuarterback(){
            return new Line(id, team, opponent, date, Position.QB, lastName, played, started, passAtt, rushAtt, targets,
                    receptions, passTd, rushTd, recTd, sacks, airYards, points);
        }
    }

    static Position skill(String position){
        if(position == null){
            return null;
        }
        return switch(position){
            case "QB" -> Position.QB;
            case "RB" -> Position.RB;
            case "WR" -> Position.WR;
            case "TE" -> Position.TE;
            default -> null;
        };
    }

    static String text(JsonObject o, String key){
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    static JsonObject object(JsonObject o, String key){
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /** Every skill man's line of a week, from the /stats array body. */
    static Map<String, Line> lines(String body, LeagueScoringSettings scoring){
        Map<String, Line> out = new HashMap<>();
        for(JsonElement e : JsonParser.parseString(body).getAsJsonArray()){
            if(!e.isJsonObject()){
                continue;
            }
            JsonObject row = e.getAsJsonObject();
            String id = text(row, "player_id");
            String team = text(row, "team");
            JsonObject player = object(row, "player");
            Position position = skill(text(player, "position"));
            if(id == null || team == null || position == null){
                continue;
            }
            JsonObject stats = object(row, "stats");
            if(stats == null){
                stats = new JsonObject();
            }
            boolean played = FaabDemand.stat(stats, "gp") >= 1;
            double air = position == Position.QB ? FaabDemand.stat(stats, "pass_air_yd") : FaabDemand.stat(stats, "rec_air_yd");
            out.put(id, new Line(id, team, text(row, "opponent"), text(row, "date"), position, text(player, "last_name"),
                    played, FaabDemand.stat(stats, "gs") >= 1,
                    FaabDemand.stat(stats, "pass_att"), FaabDemand.stat(stats, "rush_att"), FaabDemand.stat(stats, "rec_tgt"),
                    FaabDemand.stat(stats, "rec"), FaabDemand.stat(stats, "pass_td"), FaabDemand.stat(stats, "rush_td"),
                    FaabDemand.stat(stats, "rec_td"), FaabDemand.stat(stats, "pass_sack"), air,
                    played ? LeagueActuals.scoreSkill(stats, scoring) : 0));
        }
        return out;
    }

    /** id -> projected stat line, for the rows that carry a points projection (LeagueWeek.projectedFrom's membership). */
    static Map<String, JsonObject> projectedLines(String body){
        Map<String, JsonObject> out = new HashMap<>();
        for(Map.Entry<String, JsonElement> e : JsonParser.parseString(body).getAsJsonObject().entrySet()){
            if(e.getValue().isJsonObject()){
                JsonObject stats = e.getValue().getAsJsonObject();
                if(stats.has("pts_half_ppr") && !stats.get("pts_half_ppr").isJsonNull()){
                    out.put(e.getKey(), stats);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ a season

    static final class Season {
        final String season;
        final Map<Integer, Map<String, Line>> lines = new TreeMap<>();
        final Map<Integer, Map<String, Double>> projectedPoints = new TreeMap<>();
        final Map<Integer, Map<String, JsonObject>> projected = new TreeMap<>();
        int unreadWeeks;

        Season(String season){
            this.season = season;
        }

        Line line(int week, String id){
            return lines.getOrDefault(week, Map.of()).get(id);
        }
    }

    /** A season's stat rows, and its weekly projections when it is a population season. */
    static Season load(String season, boolean withProjections, LeagueScoringSettings scoring){
        return load(season, withProjections, withProjections, scoring);
    }

    /** As above; {@code strict}: a week that cannot be read is an error, not an empty week. */
    static Season load(String season, boolean withProjections, boolean strict, LeagueScoringSettings scoring){
        Season s = new Season(season);
        for(int w = 1; w <= weeks(season); w++){
            try {
                s.lines.put(w, lines(LeagueWeek.teamStatsBody(season, w), scoring));
            }
            catch(RuntimeException unread){
                if(strict){
                    throw new IllegalStateException("week " + w + " of " + season + " stat rows unreadable; an empty week would read as"
                            + " every man missing a game", unread);
                }
                s.unreadWeeks++;
                s.lines.put(w, Map.of());
            }
            if(withProjections && w <= 17){
                String body = LeagueWeek.projectionsBody(season, w);
                s.projectedPoints.put(w, LeagueWeek.projectedFrom(body));
                s.projected.put(w, projectedLines(body));
            }
        }
        return s;
    }

    // ------------------------------------------------------------------ a row

    /** One population player-week. {@code x} is filled by the screen in registry order, NaN where missing. */
    static final class Row {
        final String season;
        final int week;
        final Line line;
        final double p;
        final double y;
        final NflverseGames.Side side;
        double cal = Double.NaN;
        /** y - cal minus the mean of the same over his season, week and position: what a history feature reads. */
        double resid = Double.NaN;
        double[] x;

        Row(String season, int week, Line line, double p, NflverseGames.Side side){
            this.season = season;
            this.week = week;
            this.line = line;
            this.p = p;
            this.y = line.points();
            this.side = side;
        }

        String id(){
            return line.id();
        }

        Position position(){
            return line.position();
        }

        String seasonWeek(){
            return season + "|" + week;
        }

        String teamSeason(){
            return season + "|" + franchise(line.team());
        }

        String teamWeek(){
            return season + "|" + week + "|" + line.team();
        }
    }

    /** What the harvest kept and dropped, by season - printed at the top of every report. */
    static final class Audit {
        final Map<String, int[]> bySeason = new TreeMap<>();      // {played skill lines, projected >= 5, joined, unjoined, projected >= 5 not played, reassigned to QB}
        final List<String> unjoined = new ArrayList<>();

        int[] of(String season){
            return bySeason.computeIfAbsent(season, k -> new int[6]);
        }
    }

    /**
     * The population of the given seasons: every skill man with gp that week
     * and a Sleeper weekly projection of at least {@code minProjection}, joined
     * to his game.
     */
    static List<Row> population(Map<String, Season> seasons, List<String> which, Map<String, NflverseGames.Side> sides,
                                double minProjection, Audit audit){
        List<Row> rows = new ArrayList<>();
        for(String season : which){
            Season s = seasons.get(season);
            int[] count = audit.of(season);
            for(int w = 1; w <= lastPopulationWeek(season); w++){
                Map<String, Double> projected = s.projectedPoints.getOrDefault(w, Map.of());
                Map<String, Line> week = s.lines.getOrDefault(w, Map.of());
                for(Map.Entry<String, Double> e : projected.entrySet()){
                    if(e.getValue() < minProjection){
                        continue;
                    }
                    Line line = week.get(e.getKey());
                    Player player = line != null ? null : Player.getPlayerFromSIDV2(e.getKey());
                    boolean skillMan = line != null || player != null && skill(String.valueOf(player.position)) != null;
                    if(skillMan && (line == null || !line.played())){
                        count[4]++;     // projected, and did not play: outside the population, counted
                    }
                }
                for(Line line : week.values()){
                    if(!line.played()){
                        continue;
                    }
                    count[0]++;
                    Double p = projected.get(line.id());
                    if(p == null || p < minProjection){
                        continue;
                    }
                    count[1]++;
                    NflverseGames.Side side = sides.get(NflverseGames.key(season, w, line.team()));
                    if(side == null || !side.opponent().equals(line.opponent()) || !side.gameday().equals(line.date())){
                        count[3]++;
                        if(audit.unjoined.size() < 40){
                            audit.unjoined.add(String.format("%s w%d %s %s vs %s on %s: %s", season, w, line.id(), line.team(),
                                    line.opponent(), line.date(), side == null ? "no scheduled game for the team that week"
                                            : "schedule says " + side.opponent() + " on " + side.gameday()));
                        }
                        continue;
                    }
                    count[2]++;
                    JsonObject projectedLine = s.projected.getOrDefault(w, Map.of()).get(line.id());
                    if(line.position() != Position.QB && projectedLine != null && FaabDemand.stat(projectedLine, "pass_att") >= 10){
                        line = line.asQuarterback();        // projected to throw 10+: a quarterback this week
                        count[5]++;
                    }
                    rows.add(new Row(season, w, line, p, side));
                }
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------ the history features read

    /**
     * Everything the features read that is not the row itself: the seasons'
     * stat rows (2010 on), the schedule, the population rows (for residual
     * histories, through their calibrated baseline {@code cal}) and birth
     * dates. Built once; every method answers from strictly earlier games
     * unless its name says otherwise.
     */
    static final class Context {
        final Map<String, Season> seasons;
        final Map<String, NflverseGames.Side> sides;
        final Map<String, LocalDate> born;
        final Map<String, Integer> firstSeason = new HashMap<>();
        final Map<String, Map<String, Double>> seasonTouches = new HashMap<>();
        final Map<String, Map<String, Map<String, Integer>>> seasonFranchiseGames = new HashMap<>();
        final Map<String, String> startingQb = new HashMap<>();              // season|week|team -> qb id
        final Map<String, List<Integer>> teamWeeks = new HashMap<>();        // season|team -> weeks played, ascending
        final Map<Row, Double> gamesTogether = new IdentityHashMap<>();
        final Map<String, double[]> plays = new HashMap<>();                  // season|week|team -> {plays, passes}
        final Map<String, List<Row>> rowsBySeasonId = new HashMap<>();
        final Map<String, List<Row>> rowsByCoach = new HashMap<>();          // coach|position
        final Map<String, List<Row>> rowsAgainst = new HashMap<>();          // season|opponent franchise|position
        final Map<String, Integer> startersSeen = new TreeMap<>();            // "0 starters" / "2+ starters" counts

        Context(Map<String, Season> seasons, Map<String, NflverseGames.Side> sides, List<Row> rows, Map<String, LocalDate> born){
            this.seasons = seasons;
            this.sides = sides;
            this.born = born;
            List<String> order = new ArrayList<>(seasons.keySet());
            order.sort(Comparator.naturalOrder());
            for(String season : order){
                Season s = seasons.get(season);
                Map<String, Double> touches = seasonTouches.computeIfAbsent(season, k -> new HashMap<>());
                Map<String, Map<String, Integer>> franchises = seasonFranchiseGames.computeIfAbsent(season, k -> new HashMap<>());
                for(Map.Entry<Integer, Map<String, Line>> week : s.lines.entrySet()){
                    Map<String, List<Line>> byTeam = new HashMap<>();
                    for(Line line : week.getValue().values()){
                        if(!line.played()){
                            continue;
                        }
                        firstSeason.merge(line.id(), Integer.parseInt(season), Math::min);
                        touches.merge(line.id(), line.touches(), Double::sum);
                        franchises.computeIfAbsent(line.id(), k -> new HashMap<>()).merge(franchise(line.team()), 1, Integer::sum);
                        byTeam.computeIfAbsent(line.team(), k -> new ArrayList<>()).add(line);
                    }
                    for(Map.Entry<String, List<Line>> team : byTeam.entrySet()){
                        String key = season + "|" + week.getKey() + "|" + team.getKey();
                        double p = 0;
                        double passes = 0;
                        Line starter = null;
                        int starters = 0;
                        Line busiest = null;
                        for(Line line : team.getValue()){
                            p += line.passAtt() + line.rushAtt() + line.sacks();
                            passes += line.passAtt() + line.sacks();
                            if(line.position() == Position.QB || line.passAtt() >= 10){
                                if(line.started()){
                                    starters++;
                                    if(starter == null || line.passAtt() > starter.passAtt()){
                                        starter = line;
                                    }
                                }
                                if(busiest == null || line.passAtt() > busiest.passAtt()){
                                    busiest = line;
                                }
                            }
                        }
                        plays.put(key, new double[]{p, passes});
                        startersSeen.merge(starters == 1 ? "one starting QB" : starters == 0 ? "no QB marked started (busiest passer used)"
                                : "two or more QBs marked started (busiest used)", 1, Integer::sum);
                        Line qb = starters == 1 ? starter : busiest;
                        if(qb != null){
                            startingQb.put(key, qb.id());
                        }
                        teamWeeks.computeIfAbsent(season + "|" + team.getKey(), k -> new ArrayList<>()).add(week.getKey());
                    }
                }
            }
            for(List<Integer> weeks : teamWeeks.values()){
                weeks.sort(Comparator.naturalOrder());
            }
            for(Row r : rows){
                rowsBySeasonId.computeIfAbsent(r.season + "|" + r.id(), k -> new ArrayList<>()).add(r);
                if(r.side.coach() != null){
                    rowsByCoach.computeIfAbsent(r.side.coach() + "|" + r.position(), k -> new ArrayList<>()).add(r);
                }
                rowsAgainst.computeIfAbsent(r.season + "|" + franchise(r.line.opponent()) + "|" + r.position(), k -> new ArrayList<>()).add(r);
            }
            for(List<Row> list : rowsBySeasonId.values()){
                list.sort(Comparator.comparingInt(r -> r.week));
            }
            countGamesTogether(order, rows);
        }

        /**
         * P7's count, chronologically: for each population row, the games before
         * it in which he (gp, a target) and this game's starting QB (gp, 10+
         * attempts) were on the same team - every earlier game since 2010, this
         * season's included.
         */
        private void countGamesTogether(List<String> order, List<Row> rows){
            Map<String, List<Row>> byWeek = new HashMap<>();
            for(Row r : rows){
                byWeek.computeIfAbsent(r.seasonWeek(), k -> new ArrayList<>()).add(r);
            }
            Map<String, Integer> pairs = new HashMap<>();
            for(String season : order){
                for(Map.Entry<Integer, Map<String, Line>> week : seasons.get(season).lines.entrySet()){
                    for(Row r : byWeek.getOrDefault(season + "|" + week.getKey(), List.of())){
                        String qb = startingQb.get(r.teamWeek());
                        if(qb != null && r.position() != Position.QB){
                            gamesTogether.put(r, (double) Math.min(34, pairs.getOrDefault(qb + "|" + r.id(), 0)));
                        }
                    }
                    Map<String, List<Line>> byTeam = new HashMap<>();
                    for(Line line : week.getValue().values()){
                        if(line.played()){
                            byTeam.computeIfAbsent(line.team(), k -> new ArrayList<>()).add(line);
                        }
                    }
                    for(List<Line> team : byTeam.values()){
                        for(Line qb : team){
                            if(qb.position() != Position.QB || qb.passAtt() < 10){
                                continue;
                            }
                            for(Line receiver : team){
                                if(receiver.position() != Position.QB && receiver.targets() >= 1){
                                    pairs.merge(qb.id() + "|" + receiver.id(), 1, Integer::sum);
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------- small readers

        Line line(String season, int week, String id){
            Season s = seasons.get(season);
            return s == null ? null : s.line(week, id);
        }

        /** The team's game weeks this season strictly before {@code week}, most recent last. */
        List<Integer> teamGamesBefore(String season, String team, int week){
            List<Integer> out = new ArrayList<>();
            for(int w : teamWeeks.getOrDefault(season + "|" + team, List.of())){
                if(w < week){
                    out.add(w);
                }
            }
            return out;
        }

        static List<Integer> last(List<Integer> weeks, int k){
            return weeks.subList(Math.max(0, weeks.size() - k), weeks.size());
        }

        /** His teammates' lines at a position in a week (him excluded), played or not. */
        List<Line> mates(String season, int week, String team, Position position, String except){
            List<Line> out = new ArrayList<>();
            Season s = seasons.get(season);
            if(s == null){
                return out;
            }
            for(Line line : s.lines.getOrDefault(week, Map.of()).values()){
                if(line.team().equals(team) && line.position() == position && !line.id().equals(except)){
                    out.add(line);
                }
            }
            return out;
        }

        /** Touches by man over some weeks of a season, at one team. */
        Map<String, Double> touchesOver(String season, List<Integer> weeks, String team, Position position, String except){
            Map<String, Double> out = new HashMap<>();
            for(int w : weeks){
                for(Line line : mates(season, w, team, position, except)){
                    if(line.played()){
                        out.merge(line.id(), line.touches(), Double::sum);
                    }
                }
            }
            return out;
        }

        static String top(Map<String, Double> touches){
            String best = null;
            for(Map.Entry<String, Double> e : new TreeMap<>(touches).entrySet()){
                if(best == null || e.getValue() > touches.get(best)){
                    best = e.getKey();
                }
            }
            return best;
        }

        boolean played(String season, int week, String id){
            Line line = line(season, week, id);
            return line != null && line.played();
        }

        int rookie(Row r){
            Integer first = firstSeason.get(r.id());
            return first != null && first == Integer.parseInt(r.season) ? 1 : 0;
        }

        /** His earlier population rows this season. */
        List<Row> earlier(Row r){
            List<Row> out = new ArrayList<>();
            for(Row e : rowsBySeasonId.getOrDefault(r.season + "|" + r.id(), List.of())){
                if(e.week < r.week){
                    out.add(e);
                }
            }
            return out;
        }

        /** His earlier played lines this season, most recent last, with their weeks. */
        List<Integer> hisPlayedWeeksBefore(Row r){
            List<Integer> out = new ArrayList<>();
            Season s = seasons.get(r.season);
            for(int w = 1; w < r.week; w++){
                Line line = s.line(w, r.id());
                if(line != null && line.played()){
                    out.add(w);
                }
            }
            return out;
        }

        private final Map<String, Boolean> homeRoofed = new HashMap<>();

        /** The team's home games this season are mostly under a roof. */
        boolean homeRoofed(String season, String team){
            return homeRoofed.computeIfAbsent(season + "|" + team, k -> countRoofed(season, team));
        }

        private boolean countRoofed(String season, String team){
            int roofed = 0;
            int all = 0;
            for(NflverseGames.Side side : sides.values()){
                if(side.season().equals(season) && side.home() && !side.neutral() && side.team().equals(team) && side.roof() != null){
                    all++;
                    roofed += side.roofed() ? 1 : 0;
                }
            }
            return all > 0 && 2 * roofed > all;
        }

        /** Wins and losses before this week, from the schedule's final margins. */
        int[] record(String season, String team, int week){
            int[] wl = new int[2];
            for(int w = 1; w < week; w++){
                NflverseGames.Side side = sides.get(NflverseGames.key(season, w, team));
                if(side != null && side.margin() != null){
                    if(side.margin() > 0){
                        wl[0]++;
                    }
                    else if(side.margin() < 0){
                        wl[1]++;
                    }
                }
            }
            return wl;
        }

        private final Map<String, Boolean> coached = new HashMap<>();

        /** Did this coach coach this franchise in the given season (any game)? */
        boolean coached(String coach, String franchise, String season){
            return coached.computeIfAbsent(coach + "|" + franchise + "|" + season, k -> findCoached(coach, franchise, season));
        }

        private boolean findCoached(String coach, String franchise, String season){
            for(int w = 1; w <= 18; w++){
                for(NflverseGames.Side side : sidesOf(season, w)){
                    if(franchise(side.team()).equals(franchise) && coach.equals(side.coach())){
                        return true;
                    }
                }
            }
            return false;
        }

        private final Map<String, List<NflverseGames.Side>> sidesByWeek = new HashMap<>();

        List<NflverseGames.Side> sidesOf(String season, int week){
            if(sidesByWeek.isEmpty()){
                for(NflverseGames.Side side : sides.values()){
                    sidesByWeek.computeIfAbsent(side.season() + "|" + side.week(), k -> new ArrayList<>()).add(side);
                }
            }
            return sidesByWeek.getOrDefault(season + "|" + week, List.of());
        }

        private final Map<String, String> stadiumSeason = new HashMap<>();
        private final Map<String, String> surface = new HashMap<>();

        /**
         * The playing surface: the majority of the stadium's own surface over the
         * seven seasons centred on this one (each season voting with its most
         * common named surface, trimmed - "grass " is grass), ties to this
         * season's. nflverse codes Gillette, Paul Brown and NRG as grass and
         * Arrowhead as astroturf in 2019-2020 against every season either side;
         * a seven-season majority corrects an error one or two seasons long and
         * still follows a real change once it has lasted four. Null when no
         * season in the window names one.
         */
        String surface(NflverseGames.Side side){
            if(side.stadium() == null){
                return null;
            }
            return surface.computeIfAbsent(side.stadium() + "|" + side.season(), k -> {
                int season = Integer.parseInt(side.season());
                Map<String, Integer> votes = new TreeMap<>();
                for(int s = season - 3; s <= season + 3; s++){
                    String named = stadiumSurface(side.stadium(), String.valueOf(s));
                    if(named != null){
                        votes.merge(named, 1, Integer::sum);
                    }
                }
                String own = stadiumSurface(side.stadium(), side.season());
                String best = null;
                for(Map.Entry<String, Integer> e : votes.entrySet()){
                    if(best == null || e.getValue() > votes.get(best) || e.getValue().equals(votes.get(best)) && e.getKey().equals(own)){
                        best = e.getKey();
                    }
                }
                return best;
            });
        }

        /** The stadium's most common named surface in a season, trimmed and lower-cased; null when none is named. */
        String stadiumSurface(String stadium, String season){
            return stadiumSeason.computeIfAbsent(stadium + "|" + season, k -> {
                Map<String, Integer> count = new TreeMap<>();
                for(NflverseGames.Side side : sides.values()){
                    if(side.home() && stadium.equals(side.stadium()) && side.season().equals(season)
                            && side.surface() != null && !side.surface().isBlank()){
                        count.merge(side.surface().trim().toLowerCase(), 1, Integer::sum);
                    }
                }
                String best = null;
                for(Map.Entry<String, Integer> e : count.entrySet()){
                    if(best == null || e.getValue() > count.get(best)){
                        best = e.getKey();
                    }
                }
                return best;
            });
        }

        /** The franchise's coach in its first game of the season. */
        String openingCoach(String season, String franchise){
            for(int w = 1; w <= 18; w++){
                for(NflverseGames.Side side : sidesOf(season, w)){
                    if(franchise(side.team()).equals(franchise)){
                        return side.coach();
                    }
                }
            }
            return null;
        }

        /** Plays per game with a prior of four games at last season's rate (or the league's). */
        double[] playsPerGame(String season, String team, int week){
            double plays = 0;
            double passes = 0;
            int n = 0;
            for(int w : teamGamesBefore(season, team, week)){
                double[] pp = this.plays.get(season + "|" + w + "|" + team);
                if(pp != null){
                    plays += pp[0];
                    passes += pp[1];
                    n++;
                }
            }
            double[] prior = lastSeasonRate(season, franchise(team));
            double k = 4;
            // four pseudo-games at last season's plays, passing at last season's share
            return new double[]{(plays + k * prior[0]) / (n + k), (passes + k * prior[0] * prior[1]) / (plays + k * prior[0])};
        }

        private final Map<String, double[]> lastSeasonRate = new HashMap<>();

        /** {plays per game, pass share} of the franchise last season, or the league's when it has none. */
        double[] lastSeasonRate(String season, String franchise){
            return lastSeasonRate.computeIfAbsent(season + "|" + franchise, k -> rateOf(season, franchise));
        }

        private double[] rateOf(String season, String franchise){
            String last = String.valueOf(Integer.parseInt(season) - 1);
            double plays = 0;
            double passes = 0;
            int games = 0;
            double leaguePlays = 0;
            double leaguePasses = 0;
            int leagueGames = 0;
            for(Map.Entry<String, double[]> e : this.plays.entrySet()){
                if(!e.getKey().startsWith(last + "|")){
                    continue;
                }
                leaguePlays += e.getValue()[0];
                leaguePasses += e.getValue()[1];
                leagueGames++;
                if(franchise(e.getKey().substring(e.getKey().lastIndexOf('|') + 1)).equals(franchise)){
                    plays += e.getValue()[0];
                    passes += e.getValue()[1];
                    games++;
                }
            }
            if(games > 0){
                return new double[]{plays / games, passes / plays};
            }
            if(leagueGames > 0){
                return new double[]{leaguePlays / leagueGames, leaguePasses / leaguePlays};
            }
            return new double[]{62, 0.58};
        }

        /** Plays run against a defence per game this season before the week, with the same prior. */
        double playsFacedPerGame(String season, String defence, int week){
            double plays = 0;
            int n = 0;
            for(int w : teamGamesBefore(season, defence, week)){
                NflverseGames.Side side = sides.get(NflverseGames.key(season, w, defence));
                if(side == null){
                    continue;
                }
                double[] pp = this.plays.get(season + "|" + w + "|" + side.opponent());
                if(pp != null){
                    plays += pp[0];
                    n++;
                }
            }
            double prior = lastSeasonFaced(season, franchise(defence));
            return (plays + 4 * prior) / (n + 4);
        }

        private final Map<String, Double> lastSeasonFaced = new HashMap<>();

        /** Plays run against the franchise per game last season, or the league's per team-game when it has none. */
        double lastSeasonFaced(String season, String franchise){
            return lastSeasonFaced.computeIfAbsent(season + "|" + franchise, k -> {
                String last = String.valueOf(Integer.parseInt(season) - 1);
                double plays = 0;
                int games = 0;
                for(NflverseGames.Side side : sides.values()){
                    if(side.season().equals(last) && franchise(side.team()).equals(franchise)){
                        double[] pp = this.plays.get(last + "|" + side.week() + "|" + side.opponent());
                        if(pp != null){
                            plays += pp[0];
                            games++;
                        }
                    }
                }
                return games > 0 ? plays / games : lastSeasonRate(season, "no such franchise")[0];
            });
        }

        private final Map<String, Double> meanTouches = new HashMap<>();

        /** The position's mean touches per played game (games with a touch) in a season, for P3b's prior. */
        double meanTouches(String season, Position position){
            return meanTouches.computeIfAbsent(season + "|" + position, k -> {
                Season s = seasons.get(season);
                double sum = 0;
                int n = 0;
                if(s != null){
                    for(Map<String, Line> week : s.lines.values()){
                        for(Line line : week.values()){
                            if(line.played() && line.position() == position && line.touches() > 0){
                                sum += line.touches();
                                n++;
                            }
                        }
                    }
                }
                return n > 0 ? sum / n : 10;
            });
        }

        private final Map<String, Double> rushPerGame = new HashMap<>();

        /** His carries per played game in a season; the position's (passers with 10+ attempts) when he has none. */
        double rushPerGame(String season, String id){
            Season s = seasons.get(season);
            if(s == null){
                return 3;
            }
            double sum = 0;
            int n = 0;
            for(Map<String, Line> week : s.lines.values()){
                Line line = week.get(id);
                if(line != null && line.played()){
                    sum += line.rushAtt();
                    n++;
                }
            }
            if(n > 0){
                return sum / n;
            }
            return rushPerGame.computeIfAbsent(season, k -> {
                double all = 0;
                int games = 0;
                for(Map<String, Line> week : s.lines.values()){
                    for(Line line : week.values()){
                        if(line.played() && line.position() == Position.QB && line.passAtt() >= 10){
                            all += line.rushAtt();
                            games++;
                        }
                    }
                }
                return games > 0 ? all / games : 3;
            });
        }

        private final Map<String, Double> meanDepth = new HashMap<>();

        /** The position mean of air yards per target (or per attempt) in the season before, for G1b's prior. */
        double meanDepth(String season, Position position){
            return meanDepth.computeIfAbsent(season + "|" + position, k -> depthOf(season, position));
        }

        private double depthOf(String season, Position position){
            Season s = seasons.get(String.valueOf(Integer.parseInt(season) - 1));
            if(s == null){
                return position == Position.QB ? 8 : 9;
            }
            double air = 0;
            double n = 0;
            for(Map<String, Line> week : s.lines.values()){
                for(Line line : week.values()){
                    if(line.played() && line.position() == position){
                        air += line.airYards();
                        n += position == Position.QB ? line.passAtt() : line.targets();
                    }
                }
            }
            return n > 0 ? air / n : 8;
        }
    }

    static final Map<String, Integer> HOURS_BEHIND_ET = hoursBehind();

    private static Map<String, Integer> hoursBehind(){
        Map<String, Integer> out = new HashMap<>();
        for(String t : List.of("ATL", "BAL", "BUF", "CAR", "CIN", "CLE", "DET", "IND", "JAX", "MIA", "NE", "NYG", "NYJ",
                "PHI", "PIT", "TB", "WAS")){
            out.put(t, 0);
        }
        for(String t : List.of("CHI", "DAL", "GB", "HOU", "KC", "MIN", "NO", "TEN")){
            out.put(t, 1);
        }
        out.put("DEN", 2);
        for(String t : List.of("LAR", "LAC", "LV", "OAK", "SEA", "SF")){
            out.put(t, 3);
        }
        return out;
    }

    static final Set<String> TURF = Set.of("fieldturf", "matrixturf", "sportturf", "astroturf", "a_turf");
    static final Set<String> PARTY_CITIES = Set.of("LV", "MIA", "NO");

    /**
     * Hours a team's home clock is behind Eastern on a game day. Arizona keeps
     * no daylight time: three hours behind while the East is on it (until the
     * first Sunday of November), two after.
     */
    static double hoursBehindEt(String team, String gameday){
        if("ARI".equals(team)){
            LocalDate day = LocalDate.parse(gameday);
            LocalDate dstEnds = LocalDate.of(day.getYear(), 11, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.SUNDAY));
            return day.getMonthValue() >= 3 && day.isBefore(dstEnds) ? 3 : 2;
        }
        return HOURS_BEHIND_ET.getOrDefault(team, 0);
    }

    /**
     * The share of a season's population weeks that are week 9 or later - a
     * calendar constant (8 of 16 through 2020, 9 of 17 from 2021), so a
     * second-half contrast never depends on which men happened to be
     * projected later in the season.
     */
    static double lateShare(String season){
        return Integer.parseInt(season) >= 2021 ? 9.0 / 17 : 8.0 / 16;
    }

    /** Age in years at the game. */
    static double age(LocalDate born, String gameday){
        return ChronoUnit.DAYS.between(born, LocalDate.parse(gameday)) / 365.25;
    }
}
