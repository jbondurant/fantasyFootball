import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * EVERY scoring rule, not just the passing touchdown.
 *
 * ScoringAudit found that quarterbacks are graded at 4 points a passing
 * touchdown while the league pays 6. pts_half_ppr is a single precomputed
 * number, so that cannot be the only place it disagrees with Justin's league -
 * and a defence, whose scoring is the most league-specific thing in fantasy
 * football, was never checked at all.
 *
 * This walks the league's real scoring_settings category by category, against
 * what pts_half_ppr measurably applies, and prices each gap in points per
 * season for a STARTER at the affected position.
 *
 * What "standard" means here is not taken on trust. Sleeper's advertised
 * default for a new league and the arithmetic actually baked into the
 * pts_half_ppr field are two different things, and they differ - the
 * points-allowed 14-20 band is one of them. So the first section rebuilds
 * pts_half_ppr from raw components using LeagueScoringSettings.halfPprFeed()
 * and reports how many real stat lines come back exact. A rule table resting on
 * a guess about the feed would be worth nothing.
 *
 *   ./gradlew run -Pmain=ScoringRuleAudit -q
 */
public class ScoringRuleAudit {

    /**
     * One category, the two values, and the stat it multiplies.
     *
     * The gap in points is always (league - feed) x the player's count of that
     * stat, so a rule that agrees prices out at exactly zero and stays in the
     * table as evidence it was checked rather than skipped.
     */
    record Rule(String key, double league, double feed, String scope){}

    /** How many of each position this 12-team league actually starts. */
    static final Map<String, Integer> STARTERS = new LinkedHashMap<>();
    static {
        STARTERS.put("QB", 12);
        STARTERS.put("RB", 24);
        STARTERS.put("WR", 36);
        STARTERS.put("TE", 12);
        STARTERS.put("DEF", 12);
    }

    public static void main(String[] args){
        LeagueScoringSettings league = LeagueActuals.leagueScoring();
        LeagueScoringSettings feed = LeagueScoringSettings.halfPprFeed();
        List<String> seasons = seasons();

        System.out.printf("%nSEASONS AUDITED: %s%n", String.join(", ", seasons));

        reconstruction(seasons, feed);
        List<Rule> rules = rules(league, feed);
        ruleTable(rules, seasons);
        positionTotals(seasons, league, feed);
        defenceDetail(seasons, league, feed);
    }

    // ------------------------------------------------------------------
    // 1. Is halfPprFeed() really what the feed does?
    // ------------------------------------------------------------------

    static void reconstruction(List<String> seasons, LeagueScoringSettings feed){
        // The feed did not hold still. Scoring each season BOTH ways - fumbles
        // free, and fumbles at -1 - shows which convention was in force, rather
        // than asserting it in a comment. Whichever column wins, the league
        // charges -1 in all five, so fum is a mismatch in every season either
        // way; what this settles is how big it is.
        LeagueScoringSettings charged = LeagueScoringSettings.halfPprFeed();
        charged.fumble = -1.0;
        System.out.printf("%nDOES pts_half_ppr REBUILD FROM ITS COMPONENTS?%n");
        System.out.printf("if it does, the 'standard' column below is measured, not assumed%n");
        System.out.printf("%-8s %22s %22s %20s%n", "SEASON",
                "skill, fum free", "skill, fum at -1", "defence lines");
        for(String season : seasons){
            int[] free = exact(HistoricalActuals.raw(season), false, feed);
            int[] paid = exact(HistoricalActuals.raw(season), false, charged);
            int[] defence = exact(LeagueActuals.defenceRaw(season), true, feed);
            System.out.printf("%-8s %12d / %-7d %12d / %-7d %10d / %-7d%n",
                    season, free[0], free[1], paid[0], paid[1], defence[0], defence[1]);
        }
        System.out.printf("%nA season where the right-hand skill column wins is one where"
                + " Sleeper's own%nfield charged for fumbles. The convention changed under the"
                + " repo's feet, which%nis a reason to score outcomes from components rather"
                + " than trust a total.%n");
    }

    /** {exact, total} stat lines reproduced by scoring components at feed values. */
    static int[] exact(String data, boolean defence, LeagueScoringSettings feed){
        int ok = 0;
        int total = 0;
        for(JsonElement element : JsonParser.parseString(data).getAsJsonArray()){
            JsonObject stats = element.getAsJsonObject().getAsJsonObject("stats");
            if(stats == null || stats.get("pts_half_ppr") == null
                    || stats.get("pts_half_ppr").isJsonNull()){
                continue;
            }
            total++;
            double published = stats.get("pts_half_ppr").getAsDouble();
            if(Math.abs(published - LeagueActuals.score(stats, defence, feed)) < 0.011){
                ok++;
            }
        }
        return new int[]{ok, total};
    }

    // ------------------------------------------------------------------
    // 2. The rule table.
    // ------------------------------------------------------------------

    static List<Rule> rules(LeagueScoringSettings l, LeagueScoringSettings f){
        List<Rule> rules = new ArrayList<>();
        String skill = "QB RB WR TE";
        rules.add(new Rule("pass_yd", l.passYard, f.passYard, "QB"));
        rules.add(new Rule("pass_td", l.passTD, f.passTD, "QB"));
        rules.add(new Rule("pass_int", l.interception, f.interception, "QB"));
        rules.add(new Rule("pass_2pt", l.passTwoPoint, f.passTwoPoint, "QB"));
        rules.add(new Rule("rush_yd", l.rushYard, f.rushYard, skill));
        rules.add(new Rule("rush_td", l.rushTD, f.rushTD, skill));
        rules.add(new Rule("rush_2pt", l.rushTwoPoint, f.rushTwoPoint, skill));
        rules.add(new Rule("rec", l.reception, f.reception, "RB WR TE"));
        rules.add(new Rule("rec_yd", l.receivingYard, f.receivingYard, "RB WR TE"));
        rules.add(new Rule("rec_td", l.receivingTD, f.receivingTD, "RB WR TE"));
        rules.add(new Rule("rec_2pt", l.receivingTwoPoint, f.receivingTwoPoint, "RB WR TE"));
        rules.add(new Rule("fum_lost", l.fumbleLost, f.fumbleLost, skill));
        rules.add(new Rule("fum", l.fumble, f.fumble, skill));
        rules.add(new Rule("st_td", l.specialTeamsTD, f.specialTeamsTD, skill));
        rules.add(new Rule("st_ff", l.specialTeamsForcedFumble, f.specialTeamsForcedFumble, skill));
        rules.add(new Rule("st_fum_rec", l.specialTeamsFumbleRecovery,
                f.specialTeamsFumbleRecovery, skill));
        rules.add(new Rule("fum_rec_td", l.fumbleRecoveryTD, f.fumbleRecoveryTD, skill));

        rules.add(new Rule("sack", l.sack, f.sack, "DEF"));
        rules.add(new Rule("int", l.defenceInterception, f.defenceInterception, "DEF"));
        rules.add(new Rule("fum_rec", l.fumbleRecovery, f.fumbleRecovery, "DEF"));
        rules.add(new Rule("ff", l.forcedFumble, f.forcedFumble, "DEF"));
        rules.add(new Rule("safe", l.safety, f.safety, "DEF"));
        rules.add(new Rule("blk_kick", l.blockedKick, f.blockedKick, "DEF"));
        rules.add(new Rule("def_td", l.defenceTD, f.defenceTD, "DEF"));
        rules.add(new Rule("def_st_td", l.defenceSpecialTeamsTD, f.defenceSpecialTeamsTD, "DEF"));
        rules.add(new Rule("def_st_ff", l.defenceSpecialTeamsForcedFumble,
                f.defenceSpecialTeamsForcedFumble, "DEF"));
        rules.add(new Rule("def_st_fum_rec", l.defenceSpecialTeamsFumbleRecovery,
                f.defenceSpecialTeamsFumbleRecovery, "DEF"));
        rules.add(new Rule("pts_allow_0", l.pointsAllowed0, f.pointsAllowed0, "DEF"));
        rules.add(new Rule("pts_allow_1_6", l.pointsAllowed1to6, f.pointsAllowed1to6, "DEF"));
        rules.add(new Rule("pts_allow_7_13", l.pointsAllowed7to13, f.pointsAllowed7to13, "DEF"));
        rules.add(new Rule("pts_allow_14_20", l.pointsAllowed14to20, f.pointsAllowed14to20, "DEF"));
        rules.add(new Rule("pts_allow_21_27", l.pointsAllowed21to27, f.pointsAllowed21to27, "DEF"));
        rules.add(new Rule("pts_allow_28_34", l.pointsAllowed28to34, f.pointsAllowed28to34, "DEF"));
        rules.add(new Rule("pts_allow_35p", l.pointsAllowed35plus, f.pointsAllowed35plus, "DEF"));
        return rules;
    }

    /** A player-season the audit can price: position, feed points, one stat line. */
    record Line(String position, double feedPoints, JsonObject stats){}

    /**
     * How far apart two values have to be to count as a different rule.
     *
     * Sleeper serves scoring_settings as 32-bit floats, so this league's
     * pass_yd arrives as 0.03999999910593033 and its rush_yd as
     * 0.10000000149011612. Comparing those to an exact 0.04 and 0.1 at machine
     * precision flags two categories that are in fact identical. No real
     * fantasy rule differs by less than a hundredth of a point.
     */
    static final double SAME_RULE = 1e-6;

    static void ruleTable(List<Rule> rules, List<String> seasons){
        Map<String, List<Line>> starters = starters(seasons);
        System.out.printf("%nEVERY SCORING RULE, LEAGUE AGAINST pts_half_ppr%n");
        System.out.printf("'moves' is the mean points per season the rule shifts a STARTER at the%n"
                + "affected position - top %s by that season's feed points%n",
                STARTERS.toString());
        System.out.printf("%-16s %9s %9s %10s   %s%n",
                "RULE", "LEAGUE", "STANDARD", "MOVES/SEASON", "AFFECTS");
        List<Rule> mismatches = new ArrayList<>();
        for(Rule rule : rules){
            boolean differs = Math.abs(rule.league() - rule.feed()) > SAME_RULE;
            if(differs){
                mismatches.add(rule);
            }
            System.out.printf("%-16s %9.2f %9.2f %+10.1f   %s%s%n",
                    rule.key(), rule.league(), rule.feed(),
                    moves(rule, starters, rule.scope().split(" ")), rule.scope(),
                    differs ? "   <-- MISMATCH" : "");
        }
        if(mismatches.isEmpty()){
            System.out.printf("%nno mismatches - the feed already grades this league.%n");
            return;
        }
        System.out.printf("%nTHE MISMATCHES, POSITION BY POSITION%n");
        System.out.printf("the row above pools every affected position, which hides who pays%n");
        System.out.printf("%-16s", "RULE");
        for(String position : STARTERS.keySet()){
            System.out.printf(" %9s", position);
        }
        System.out.println();
        for(Rule rule : mismatches){
            System.out.printf("%-16s", rule.key());
            for(String position : STARTERS.keySet()){
                if(!rule.scope().contains(position)){
                    System.out.printf(" %9s", "-");
                    continue;
                }
                System.out.printf(" %+9.1f", moves(rule, starters, new String[]{position}));
            }
            System.out.println();
        }
    }

    /** Mean points a season this rule moves for a starter at these positions. */
    static double moves(Rule rule, Map<String, List<Line>> starters, String[] positions){
        double gap = rule.league() - rule.feed();
        if(Math.abs(gap) <= SAME_RULE){
            return 0.0;
        }
        double moved = 0;
        int counted = 0;
        for(String position : positions){
            for(Line line : starters.getOrDefault(position, List.of())){
                moved += gap * LeagueActuals.stat(line.stats(), rule.key());
                counted++;
            }
        }
        return counted == 0 ? 0 : moved / counted;
    }

    // ------------------------------------------------------------------
    // 3. What it does to a whole starter, position by position.
    // ------------------------------------------------------------------

    static void positionTotals(List<String> seasons, LeagueScoringSettings league,
                               LeagueScoringSettings feed){
        Map<String, List<Line>> starters = starters(seasons);
        System.out.printf("%nTHE WHOLE GAP, PER STARTING PLAYER PER SEASON%n");
        System.out.printf("'as graded' is the pts_half_ppr the backtest uses today. 'rules' is"
                + " what the%nmismatching rules above account for; 'drift' is the rest - seasons"
                + " where the%nfeed's own published total does not rebuild from its own"
                + " components.%n");
        System.out.printf("%-6s %10s %12s %12s %10s %10s %8s%n",
                "POS", "STARTERS", "as graded", "league", "gap", "rules", "drift");
        List<Rule> rules = rules(league, feed);
        for(String position : STARTERS.keySet()){
            List<Line> lines = starters.getOrDefault(position, List.of());
            if(lines.isEmpty()){
                continue;
            }
            boolean defence = position.equals("DEF");
            double graded = 0;
            double correct = 0;
            for(Line line : lines){
                graded += line.feedPoints();
                correct += LeagueActuals.score(line.stats(), defence, league);
            }
            double fromRules = 0;
            for(Rule rule : rules){
                if(rule.scope().contains(position)){
                    fromRules += moves(rule, starters, new String[]{position});
                }
            }
            double gap = (correct - graded) / lines.size();
            System.out.printf("%-6s %10d %12.1f %12.1f %+10.1f %+10.1f %+8.1f%n",
                    position, lines.size(), graded / lines.size(), correct / lines.size(),
                    gap, fromRules, gap - fromRules);
        }
    }

    // ------------------------------------------------------------------
    // 4. Defence, named team by named team.
    // ------------------------------------------------------------------

    static void defenceDetail(List<String> seasons, LeagueScoringSettings league,
                              LeagueScoringSettings feed){
        System.out.printf("%nDEFENCE: IS THE MIS-SCORING FLAT OR DOES IT RE-ORDER?%n");
        System.out.printf("a flat shift moves what a defence is worth; a re-order would move"
                + " WHICH defence%n");
        System.out.printf("'residual' is gap MINUS the one mismatching rule - it is Sleeper's own%n"
                + "arithmetic failing to reconstruct, and it is the only reason a gap can be"
                + " negative%n");
        System.out.printf("%-8s %9s %9s %9s %10s %10s %10s%n", "SEASON", "mean gap",
                "min gap", "max gap", "residual", "rank moves", "top1 same");
        double band = league.pointsAllowed14to20 - feed.pointsAllowed14to20;
        for(String season : seasons){
            Map<String, Double> graded = HistoricalActuals.defencePointsBySleeperID(season);
            Map<String, Double> correct = LeagueActuals.leagueSeasonDefencePoints(season);
            Map<String, Double> bandOnly = new java.util.HashMap<>();
            for(JsonElement element : JsonParser.parseString(LeagueActuals.defenceRaw(season))
                    .getAsJsonArray()){
                JsonObject row = element.getAsJsonObject();
                JsonObject stats = row.getAsJsonObject("stats");
                if(stats != null && row.has("player_id")){
                    bandOnly.put(row.get("player_id").getAsString(),
                            band * LeagueActuals.stat(stats, "pts_allow_14_20"));
                }
            }
            List<String> byFeed = new ArrayList<>(graded.keySet());
            byFeed.sort(Comparator.comparingDouble((String id) -> -graded.get(id)));
            List<String> byLeague = new ArrayList<>(byFeed);
            byLeague.sort(Comparator.comparingDouble((String id) -> -correct.getOrDefault(id, 0.0)));
            double sum = 0;
            double residual = 0;
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for(String id : byFeed){
                double gap = correct.getOrDefault(id, 0.0) - graded.get(id);
                sum += gap;
                residual += gap - bandOnly.getOrDefault(id, 0.0);
                min = Math.min(min, gap);
                max = Math.max(max, gap);
            }
            int moves = 0;
            for(int i = 0; i < Math.min(12, byFeed.size()); i++){
                if(!byFeed.get(i).equals(byLeague.get(i))){
                    moves++;
                }
            }
            System.out.printf("%-8s %9.1f %9.1f %9.1f %10.2f %10d %10s%n", season,
                    sum / byFeed.size(), min, max, residual / byFeed.size(), moves,
                    byFeed.get(0).equals(byLeague.get(0)) ? "yes" : "NO");
        }
        System.out.printf("%nA defence gains one point for every game it holds a team to 14-20,%n"
                + "which is most of a season's games for most defences. That is a LEVEL shift,%n"
                + "not a re-ranking - so it changes what a defence is worth against a receiver,%n"
                + "and barely changes which defence is the best one.%n");
    }

    // ------------------------------------------------------------------
    // Shared: the starters of every audited season, pooled.
    // ------------------------------------------------------------------

    private static Map<String, List<Line>> cachedStarters;

    static synchronized Map<String, List<Line>> starters(List<String> seasons){
        if(cachedStarters != null){
            return cachedStarters;
        }
        Map<String, List<Line>> out = new LinkedHashMap<>();
        for(String season : seasons){
            Map<String, List<Line>> season_ = new LinkedHashMap<>();
            collect(JsonParser.parseString(HistoricalActuals.raw(season)).getAsJsonArray(),
                    season_);
            collect(JsonParser.parseString(LeagueActuals.defenceRaw(season)).getAsJsonArray(),
                    season_);
            for(Map.Entry<String, Integer> entry : STARTERS.entrySet()){
                List<Line> lines = season_.getOrDefault(entry.getKey(), new ArrayList<>());
                lines.sort(Comparator.comparingDouble((Line l) -> -l.feedPoints()));
                out.computeIfAbsent(entry.getKey(), u -> new ArrayList<>())
                        .addAll(lines.subList(0, Math.min(entry.getValue(), lines.size())));
            }
        }
        cachedStarters = out;
        return out;
    }

    static void collect(JsonArray rows, Map<String, List<Line>> into){
        for(JsonElement element : rows){
            JsonObject row = element.getAsJsonObject();
            JsonObject stats = row.getAsJsonObject("stats");
            JsonElement playerElement = row.get("player");
            if(stats == null || playerElement == null || !playerElement.isJsonObject()){
                continue;
            }
            JsonElement half = stats.get("pts_half_ppr");
            JsonElement position = playerElement.getAsJsonObject().get("position");
            if(half == null || half.isJsonNull() || position == null || position.isJsonNull()){
                continue;
            }
            into.computeIfAbsent(position.getAsString(), u -> new ArrayList<>())
                    .add(new Line(position.getAsString(), half.getAsDouble(), stats));
        }
    }

    /**
     * The seasons the backtest actually has boards for.
     *
     * Read off data/ rather than hardcoded, so the agent adding seasons gets
     * them audited without touching this file.
     */
    static List<String> seasons(){
        TreeSet<String> found = new TreeSet<>();
        File data = new File("data");
        File[] files = data.listFiles();
        if(files != null){
            for(File file : files){
                if(file.getName().matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                    found.add(file.getName().split("-")[3]);
                }
            }
        }
        return new ArrayList<>(found);
    }
}
