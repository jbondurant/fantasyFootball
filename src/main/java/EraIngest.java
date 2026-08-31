import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetch, cache and JOIN every season Sleeper and FFC will still serve - and
 * print how much of each one the join actually reached.
 *
 * The backtest has run on five seasons, and five is not enough to tell a good
 * draft plan from a lucky one. This is the ingestion half of fixing that.
 * Sleeper's stats API serves scored seasons back to 2010; Fantasy Football
 * Calculator serves 12-team ADP from real drafts over the same range. Joining
 * them is easy to do and easy to do BADLY, so the report leads with the match
 * rate and the names it missed rather than with a season count.
 *
 * It also audits the scoring on the way past, because the new seasons must not
 * inherit the bug ScoringAudit found in the old ones: outcomes graded with
 * Sleeper's pts_half_ppr, which pays 4 for a passing touchdown in a league that
 * pays 6.
 *
 *   ./gradlew run -Pmain=EraIngest
 *   ./gradlew run -Pmain=EraIngest -Pformat=ppr    (the board-format control)
 *
 * The first run fetches roughly three hundred documents and takes a few
 * minutes. Everything is cached forever afterwards - a finished season does
 * not change.
 */
public class EraIngest {

    /**
     * The match-rate gate.
     *
     * 0.90 overall and 0.95 inside the top 100 by ADP. The reasoning: an
     * unmatched player is deleted from the board, so every miss hands his ADP
     * slot to the man behind him and shifts the whole board up. Below the top
     * 100 that is a bench-round distortion; inside it, it is a distortion of
     * the picks the plan is actually choosing between, which is why the early
     * board is held to the tighter number. The gate is a judgement call, so it
     * is printed with the rates rather than hidden - and no season this repo
     * ended up using is anywhere near it.
     */
    public static final double MIN_RATE = 0.90;
    public static final double MIN_TOP_RATE = 0.95;

    /** Rounds the replayed draft runs. -Prounds; see the depth gate below. */
    public static int rounds(){
        return Integer.getInteger("rounds", 11);
    }

    /**
     * A board must survive the draft it is replaying.
     *
     * Twelve teams over N rounds remove 12N players. A shorter board runs out
     * and the last picks start returning whoever is left over, which reads as a
     * strategy difference and is an artifact of the data. The margin is one
     * round, so the final pick still has real choices rather than one.
     */
    public static int minDepth(){
        return EraGame.consumed(rounds()) + EraBoards.TEAMS;
    }

    public static void main(String[] args){
        String format = System.getProperty("format");
        int rounds = rounds();

        System.out.printf("%nSEASONS AVAILABLE, AND HOW WELL THEY JOIN%n");
        System.out.printf("board: fantasyfootballcalculator 12-team ADP from real drafts%n");
        System.out.printf("outcomes: sleeper weekly stats, scored under THIS league's"
                + " settings (%.0f-point passing TDs)%n",
                LeagueActuals.leagueScoring().passTD);
        System.out.printf("gates: match rate >= %.0f%%, top-100 match >= %.0f%%,"
                + " board depth >= %d for a %d-round draft%n%n",
                MIN_RATE * 100, MIN_TOP_RATE * 100, minDepth(), rounds);

        System.out.printf("%-6s %-9s %7s %6s %6s %7s %8s %6s %5s %5s  %s%n",
                "SEASON", "FORMAT", "DRAFTS", "BOARD", "JOINED", "RATE", "TOP-100",
                "SKILL", "DEF", "WEEKS", "VERDICT");
        List<EraBoards.Board> boards = new ArrayList<>();
        Map<String, List<String>> misses = new LinkedHashMap<>();
        for(String season : EraBoards.candidateSeasons()){
            EraBoards.Board board = EraBoards.tryBuild(season, format);
            if(board == null){
                System.out.printf("%-6s %-9s %7s %6s %6s %7s %8s %6s %5s %5s  %s%n",
                        season, format == null ? EraBoards.defaultFormat(season) : format,
                        "-", "-", "-", "-", "-", "-", "-", "-", "NO DATA");
                continue;
            }
            EraBoards.Match match = board.match();
            String verdict = verdict(match, rounds);
            System.out.printf("%-6s %-9s %7d %6d %6d %6.1f%% %7.1f%% %6d %5d %5d  %s%n",
                    season, match.format(), match.drafts(), match.boardRows(),
                    match.matched(), match.rate() * 100, match.topRate() * 100,
                    match.skill(), match.defences(), match.weeks(), verdict);
            misses.put(season, match.missedByAdp());
            if(verdict.equals("USE")){
                boards.add(board);
            }
        }

        System.out.printf("%n%d of %d seasons usable.%n", boards.size(),
                EraBoards.candidateSeasons().size());

        System.out.printf("%nWHO THE JOIN MISSED, EARLIEST ADP FIRST%n");
        System.out.printf("(an unmatched man is deleted from the board, so these are"
                + " the players%nthe replayed draft could never take - the list is"
                + " the honest cost of the join)%n");
        for(Map.Entry<String, List<String>> entry : misses.entrySet()){
            List<String> missed = entry.getValue();
            List<String> early = new ArrayList<>();
            for(String miss : missed){
                if(Double.parseDouble(miss.split(" ")[0]) <= 120){
                    early.add(miss);
                }
            }
            System.out.printf("   %s  %d missed, %d inside pick 120%s%n", entry.getKey(),
                    missed.size(), early.size(),
                    early.isEmpty() ? "" : ":  " + String.join(" | ",
                            early.subList(0, Math.min(6, early.size()))));
        }

        scoringAudit(boards);
        defenceAudit(boards);

        System.out.printf("%nDEPTH CHECK: a %d-round draft removes %d players.%n",
                rounds, EraGame.consumed(rounds));
        for(EraBoards.Board board : boards){
            System.out.printf("   %s  %d skill + %d defences on the board, %d needed%n",
                    board.season(), board.match().skill(), board.match().defences(),
                    EraGame.consumed(rounds));
        }
    }

    static String verdict(EraBoards.Match match, int rounds){
        if(match.rate() < MIN_RATE){
            return "REJECT match rate";
        }
        if(match.topRate() < MIN_TOP_RATE){
            return "REJECT top-100";
        }
        if(match.skill() < minDepth()){
            return "REJECT too shallow";
        }
        return "USE";
    }

    /**
     * Does the new data get graded in this league's points or Sleeper's?
     *
     * The answer has to be visible per season, not asserted once, because the
     * bug being avoided is invisible: pts_half_ppr looks like points, sorts
     * like points, and is simply somebody else's league.
     */
    static void scoringAudit(List<EraBoards.Board> boards){
        System.out.printf("%nSCORING AUDIT: TOP-12 QUARTERBACK, BOTH WAYS%n");
        System.out.printf("%-6s %-22s %10s %10s %9s   %s%n", "SEASON", "TOP QB",
                "league", "feed", "gap", "mean gap, top 12");
        for(EraBoards.Board board : boards){
            Map<String, Double> league = board.seasonPoints();
            Map<String, Double> feed = feedSeason(board);
            List<String> qbs = new ArrayList<>();
            for(String id : board.ids()){
                if(board.positionOf().get(id) == Position.QB){
                    qbs.add(id);
                }
            }
            qbs.sort(Comparator.comparingDouble(
                    id -> -league.getOrDefault(id, 0.0)));
            double sum = 0;
            int counted = 0;
            for(String id : qbs.subList(0, Math.min(12, qbs.size()))){
                sum += league.getOrDefault(id, 0.0) - feed.getOrDefault(id, 0.0);
                counted++;
            }
            String best = qbs.isEmpty() ? "-" : name(board, qbs.get(0));
            System.out.printf("%-6s %-22s %10.1f %10.1f %+9.1f   %+.1f%n",
                    board.season(), best,
                    qbs.isEmpty() ? 0 : league.getOrDefault(qbs.get(0), 0.0),
                    qbs.isEmpty() ? 0 : feed.getOrDefault(qbs.get(0), 0.0),
                    qbs.isEmpty() ? 0 : league.getOrDefault(qbs.get(0), 0.0)
                            - feed.getOrDefault(qbs.get(0), 0.0),
                    counted == 0 ? 0 : sum / counted);
        }
    }

    /**
     * The same question for defences, whose scoring is a dozen team categories
     * rather than one touchdown rule - and where a mismatch would be far harder
     * to spot, because nobody has an intuition for what a defence should score.
     */
    static void defenceAudit(List<EraBoards.Board> boards){
        System.out.printf("%nSCORING AUDIT: TOP-6 DEFENCES, BOTH WAYS%n");
        System.out.printf("%-6s %10s %10s %9s%n", "SEASON", "league", "feed", "gap");
        double worst = 0;
        for(EraBoards.Board board : boards){
            Map<String, Double> league = board.seasonPoints();
            Map<String, Double> feed = feedSeason(board);
            List<String> defences = new ArrayList<>();
            for(String id : board.ids()){
                if(board.positionOf().get(id) == Position.DEF){
                    defences.add(id);
                }
            }
            defences.sort(Comparator.comparingDouble(
                    id -> -league.getOrDefault(id, 0.0)));
            double leagueSum = 0;
            double feedSum = 0;
            int counted = 0;
            for(String id : defences.subList(0, Math.min(6, defences.size()))){
                leagueSum += league.getOrDefault(id, 0.0);
                feedSum += feed.getOrDefault(id, 0.0);
                counted++;
            }
            if(counted == 0){
                continue;
            }
            double gap = (leagueSum - feedSum) / counted;
            worst = Math.max(worst, Math.abs(gap));
            System.out.printf("%-6s %10.1f %10.1f %+9.1f%n", board.season(),
                    leagueSum / counted, feedSum / counted, gap);
        }
        System.out.printf("%nA startable defence is graded %.1f points a season away"
                + " from the feed's number.%n", worst);
    }

    static Map<String, Double> feedSeason(EraBoards.Board board){
        Map<String, Double> total = new java.util.HashMap<>();
        for(int week = 1; week <= board.weeks(); week++){
            EraActuals.weeklyFeedPoints(board.season(), week)
                    .forEach((id, points) -> total.merge(id, points, Double::sum));
        }
        return total;
    }

    static String name(EraBoards.Board board, String id){
        for(JsonElement element : EraActuals.skillRows(board.season())){
            JsonObject row = element.getAsJsonObject();
            if(row.has("player_id") && row.get("player_id").getAsString().equals(id)){
                JsonObject player = row.getAsJsonObject("player");
                return EraBoards.text(player, "first_name") + " "
                        + EraBoards.text(player, "last_name");
            }
        }
        return id;
    }
}
