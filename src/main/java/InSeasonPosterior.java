import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SLEEPER'S SEASON NUMBER, MOVED BY THE WEEKS ALREADY PLAYED AT THE MEASURED RATE.
 *
 * Justin, after the first trade board of the season: "are these points strict
 * Sleeper projections, or adjusted for week 1, which can't be dismissed as
 * 100% noise?" They were strict Sleeper, and Sleeper's season feed does not
 * move on results (TRAPS #136), so every tool priced on it was blind to the
 * week. This is the projection source that is not: {@code -Pprojections=posterior}.
 *
 * For every man at the four fitted positions who has played, his season number
 * becomes the posterior of {@link InSeasonLearning}'s rule - the preseason rate
 * and the played weeks' rate weighted by kappa, the one free parameter the
 * study measured on thirteen seasons and refit here - times the games the
 * objective counts. After one game that keeps 8-15% of the surprise. A man who
 * has not played, a defence, or a man outside the four positions keeps
 * Sleeper's number, so a consumer reads this map exactly as it reads Sleeper's.
 *
 * What it is not: a claim that the adjustment wins. The study put the rule's
 * season-level gain at +5.6 with a bar of 16.7, and this source exists so that
 * a trade or a lineup can be priced BOTH ways and the difference read, not so
 * that one way replaces the other.
 */
public class InSeasonPosterior {

    /** His season number after `games` played weeks at `observedPpg`: the posterior rate over the games the objective counts. */
    static double season(double seasonPrior, double observedPpg, int games, double kappa, int gamesInSeason){
        double prior = seasonPrior / gamesInSeason;
        return WeekReaction.posterior(prior, observedPpg, games, kappa) * gamesInSeason;
    }

    /** Sleeper's season projections with every played man moved by his weeks so far. */
    public static Map<String, Double> season(AAAConfiguration configuration){
        Map<String, Double> sleeper = SleeperProjections.parseTodaysWebPage();
        String season = LeagueWeek.season();
        int current = LeagueWeek.week();
        List<Map<String, Double>> weeks = new ArrayList<>();
        for(int w = 1; w <= current; w++){
            Map<String, Double> lines = LeagueWeek.actualSoFar(season, w);
            if(!lines.isEmpty()){
                weeks.add(lines);
            }
        }
        Map<Position, InSeasonLearning.Kappa> kappa = InSeasonLearning.fitKappa(
                InSeasonLearning.harvest(EraBoards.usable("ppr", EraIngest.MIN_RATE, EraIngest.minDepth())), null);
        Map<String, Double> out = new LinkedHashMap<>(sleeper);
        int moved = 0;
        for(Map.Entry<String, Double> e : sleeper.entrySet()){
            Player player = Player.getPlayerFromSIDV2(e.getKey());
            Position position = player == null ? null : player.position;
            if(position == null || !kappa.containsKey(position)){
                continue;
            }
            int games = 0;
            double total = 0;
            for(Map<String, Double> week : weeks){
                Double points = week.get(e.getKey());
                if(points != null){
                    games++;
                    total += points;
                }
            }
            if(games == 0){
                continue;
            }
            out.put(e.getKey(), season(e.getValue(), total / games, games,
                    kappa.get(position).kappa(), WeeklyStarterValue.WEEKS));
            moved++;
        }
        System.out.printf("posterior: %d men moved by %d played week%s at kappa QB %.1f RB %.1f WR %.1f TE %.1f games%n",
                moved, weeks.size(), weeks.size() == 1 ? "" : "s",
                kappa.get(Position.QB).kappa(), kappa.get(Position.RB).kappa(),
                kappa.get(Position.WR).kappa(), kappa.get(Position.TE).kappa());
        return out;
    }
}
