import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SeasonTableTest {

    static TitleOdds.League league(boolean median, double teamSigma, boolean live, double spread){
        List<String> managers = new ArrayList<>();
        Map<Integer, String> managerOf = new HashMap<>();
        Map<String, Integer> wins = new HashMap<>();
        Map<String, Double> banked = new HashMap<>();
        Map<String, double[]> underWay = new HashMap<>();
        Random random = new Random(11);
        for(int i = 0; i < 12; i++){
            String m = "m" + i;
            managers.add(m);
            managerOf.put(i + 1, m);
            wins.put(m, 3 + random.nextInt(6));
            banked.put(m, 800 + 200 * random.nextDouble());
            if(live){
                underWay.put(m, new double[]{10 + random.nextDouble() * 40, 60 + random.nextDouble() * 60});
            }
        }
        Map<Integer, List<int[]>> schedule = new HashMap<>();
        for(int w = 10; w <= 14; w++){
            List<int[]> games = new ArrayList<>();
            int[] rotate = new int[12];
            for(int i = 0; i < 12; i++){
                rotate[i] = i == 0 ? 0 : 1 + (i - 1 + w) % 11;
            }
            for(int g = 0; g < 6; g++){
                games.add(new int[]{rotate[g] + 1, rotate[11 - g] + 1});
            }
            schedule.put(w, games);
        }
        return new TitleOdds.League("2026", 10, 14, 15, 6, median, spread, managers, managerOf, wins, banked, schedule, underWay, teamSigma);
    }

    static double[][] means(TitleOdds.League league, long seed){
        Random random = new Random(seed);
        double[][] mean = new double[12][league.weeks()];
        for(double[] team : mean){
            double level = 100 + 40 * random.nextDouble();
            for(int w = 0; w < team.length; w++){
                team[w] = level + 10 * random.nextGaussian();
            }
        }
        return mean;
    }

    static Map<String, double[]> byName(TitleOdds.League league, double[][] mean){
        Map<String, double[]> out = new HashMap<>();
        for(int i = 0; i < 12; i++){
            out.put(league.managers().get(i), mean[i]);
        }
        return out;
    }

    /** The whole point of the table: the champion of every season, exactly as play has it, in every state of the league. */
    @Test
    public void theSameSeasonsAsPlay(){
        for(boolean median : new boolean[]{true, false}){
            for(boolean live : new boolean[]{false, true}){
                for(double sigma : new double[]{0, 6.5}){
                    TitleOdds.League league = league(median, sigma, live, 24);
                    SeasonTable table = new SeasonTable(league, 3000, TitleOdds.SEED);
                    for(long seed = 1; seed <= 3; seed++){
                        double[][] mean = means(league, seed);
                        int[] played = TitleOdds.play(league, byName(league, mean), 3000, TitleOdds.SEED).champion();
                        assertArrayEquals(played, table.champions(mean), "median " + median + ", live " + live + ", sigma " + sigma);
                    }
                }
            }
        }
    }

    /** Six equal teams in the bracket: the byes win a quarter of the time each, the rest an eighth. */
    @Test
    public void theBracketOfEqualTeams(){
        TitleOdds.League league = league(true, 0, false, 24);
        double[][] mean = new double[12][league.weeks()];
        for(double[] team : mean){
            Arrays.fill(team, 120);
        }
        SeasonTable table = new SeasonTable(league, 50, TitleOdds.SEED);
        for(int s = 0; s < 50; s++){
            double[] title = table.titleChances(mean, s);
            double[] sorted = title.clone();
            Arrays.sort(sorted);
            assertEquals(0.25, sorted[11], 1e-6);
            assertEquals(0.25, sorted[10], 1e-6);
            for(int i = 6; i < 10; i++){
                assertEquals(0.125, sorted[i], 1e-6);
            }
            for(int i = 0; i < 6; i++){
                assertEquals(0, sorted[i], 0);
            }
        }
    }

    /** One team's title chance sums to one across the league in any season, and the formula's average matches the playing of the games. */
    @Test
    public void theFormulaAveragesToThePlayedGames(){
        for(double sigma : new double[]{0, 6.5}){
            TitleOdds.League league = league(true, sigma, true, 24);
            double[][] mean = means(league, 5);
            int sims = 300_000;
            SeasonTable table = new SeasonTable(league, sims, TitleOdds.SEED);
            assertEquals(1, Arrays.stream(table.titleChances(mean, 7)).sum(), 1e-6);
            int[] champions = table.champions(mean);
            int[] everyone = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
            double[][] chance = table.chances(mean, everyone, 0, sims);
            for(int m = 0; m < 12; m++){
                final int who = m;
                double played = Arrays.stream(champions).filter(c -> c == who).count() / (double) sims;
                double formula = Arrays.stream(chance[m]).average().orElse(0);
                double se = Math.sqrt(Math.max(played * (1 - played), 1e-6) / sims);
                assertTrue(Math.abs(played - formula) < 4 * se, "manager " + m + ": played " + played + ", formula " + formula);
            }
        }
    }

    /** A change by the formula agrees with the same change played, within their noise, and has less of it. */
    @Test
    public void aChangeByFormulaMatchesThePlayedChangeWithLessNoise(){
        TitleOdds.League league = league(true, 6.5, false, 24);
        double[][] before = means(league, 5);
        double[][] after = before.clone();
        after[3] = Arrays.stream(before[3]).map(p -> p + 6).toArray();
        after[8] = Arrays.stream(before[8]).map(p -> p - 6).toArray();
        int sims = 200_000;
        SeasonTable table = new SeasonTable(league, sims, TitleOdds.SEED);
        int[] was = table.champions(before), now = table.champions(after);
        double[] playedBefore = new double[sims], playedAfter = new double[sims];
        for(int s = 0; s < sims; s++){
            playedBefore[s] = was[s] == 3 ? 1 : 0;
            playedAfter[s] = now[s] == 3 ? 1 : 0;
        }
        TitleOdds.Delta played = SeasonTable.paired(playedBefore, playedAfter);
        TitleOdds.Delta formula = SeasonTable.paired(table.chances(before, new int[]{3}, 0, sims)[0], table.chances(after, new int[]{3}, 0, sims)[0]);
        assertTrue(played.change() > 0.01, "six points a week moves a title");
        assertTrue(Math.abs(played.change() - formula.change()) < 4 * played.se(), "played " + played + ", formula " + formula);
        assertTrue(formula.se() < played.se() / 2, "played " + played + ", formula " + formula);
    }

    /** Stakes itself: no change is exactly no change, and a real one agrees with the same change played. */
    @Test
    public void stakesUsesTheFormulaAgainstItsOwnBase(){
        TitleOdds.League league = league(true, 6.5, false, 24);
        double[][] mean = means(league, 5);
        Map<String, double[]> byName = byName(league, mean);
        int sims = 60_000;
        TitleOdds.Outcomes base = TitleOdds.play(league, byName, sims, TitleOdds.SEED);
        SeasonTable table = new SeasonTable(league, sims, TitleOdds.SEED);
        int[] everyone = java.util.stream.IntStream.range(0, 12).toArray();
        TitleOdds.Stakes stakes = new TitleOdds.Stakes(league, Map.of(), byName, base, Map.of(), Map.of(), Map.of(), table, table.chances(mean, everyone, 0, sims));
        TitleOdds.Delta[] nothing = stakes.swap(new TitleOdds.Swap("m3", mean[3], "m8", mean[8]));
        assertEquals(0, nothing[0].change(), 0);
        assertEquals(0, nothing[1].se(), 0);
        TitleOdds.Swap trade = new TitleOdds.Swap("m3", Arrays.stream(mean[3]).map(p -> p + 6).toArray(), "m8", Arrays.stream(mean[8]).map(p -> p - 6).toArray());
        TitleOdds.Delta[] formula = stakes.swap(trade), played = stakes.played(trade);
        assertTrue(formula[0].change() > 0.01);
        assertTrue(formula[1].change() < 0);
        for(int i = 0; i < 2; i++){
            assertTrue(Math.abs(formula[i].change() - played[i].change()) < 4 * played[i].se(), "formula " + formula[i] + ", played " + played[i]);
        }
        assertEquals(Arrays.stream(stakes.baseChance()[3]).average().orElse(0), stakes.title("m3"), 0);
    }

    @Test
    public void thePhiFunction(){
        assertEquals(0.5, SeasonTable.phi(0), 1e-7);
        assertEquals(0.841344746, SeasonTable.phi(1), 2e-7);
        assertEquals(0.158655254, SeasonTable.phi(-1), 2e-7);
        assertEquals(0.977249868, SeasonTable.phi(2), 2e-7);
        assertEquals(1, SeasonTable.phi(40), 0);
    }
}
