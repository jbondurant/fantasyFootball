import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * THE SEASONS TitleOdds.play DRAWS, KEPT, AND A FORMULA FOR THE PLAYOFFS.
 *
 * Justin, 2026-11-07: "any way to improve title simulation, or find math that relies on odds
 * calculations instead of brute force simulations to arrive at same or better result". Two ways,
 * tested in TradeMarket's -PexplorerTitleCheck.
 *
 * THE SAME SEASONS, FASTER. A trade changes a team's means and nothing about its luck, yet play
 * draws every season's numbers again for every trade. Here they are drawn once, in play's order.
 * champions returns what play returns, season for season.
 *
 * THE PLAYOFFS BY FORMULA. Once a season's seeds are known, and each team's error for the season,
 * the playoff games are independent normals, so "a beats b" is exactly
 * Phi((mean a - mean b) / sqrt(spread a^2 + spread b^2)) and the bracket adds those up. Each
 * season then gives each team its exact chance of the title given that regular season, not a win
 * or a loss from one playing of three rounds. The average over seasons is the same number, so
 * nothing is assumed that the simulation doesn't already assume, and the noise is less. The regular
 * season stays simulated: thirty games, a median game and a points tiebreak have no such formula.
 */
final class SeasonTable {

    private final int n, weeks, sims, regular, round1, firstFuture;
    private final boolean medianGame, reseed;
    private final double[] luck, effect, first;
    private final double spread;
    private final int[][] games;
    private final int[] baseWins;
    private final double[] basePoints;

    SeasonTable(TitleOdds.League league, int sims, long seed){
        List<String> managers = league.managers();
        n = managers.size();
        weeks = league.weeks();
        this.sims = sims;
        regular = league.lastRegular() - league.thisWeek() + 1;
        round1 = league.playoffStart() - league.thisWeek();
        firstFuture = league.firstFuture();
        medianGame = league.medianGame();
        reseed = league.reseed();
        spread = league.spread();
        Map<String, Integer> indexOf = new HashMap<>();
        baseWins = new int[n];
        basePoints = new double[n];
        first = new double[n];
        for(int i = 0; i < n; i++){
            indexOf.put(managers.get(i), i);
            baseWins[i] = league.wins().getOrDefault(managers.get(i), 0);
            basePoints[i] = league.banked().getOrDefault(managers.get(i), 0.0);
            first[i] = league.liveSpread(managers.get(i));
        }
        games = new int[Math.max(0, regular)][];
        for(int k = 0; k < regular; k++){
            List<Integer> flat = new ArrayList<>();
            for(int[] g : league.schedule().getOrDefault(league.thisWeek() + k, List.of())){
                Integer a = indexOf.get(league.managerOf().get(g[0]));
                Integer b = indexOf.get(league.managerOf().get(g[1]));
                if(a != null && b != null){
                    flat.add(a);
                    flat.add(b);
                }
            }
            games[k] = flat.stream().mapToInt(Integer::intValue).toArray();
        }
        luck = new double[sims * weeks * n];
        effect = new double[sims * n];
        Random random = new Random(seed);
        for(int s = 0; s < sims; s++){
            if(league.teamSigma() > 0){
                for(int i = 0; i < n; i++){
                    effect[s * n + i] = league.teamSigma() * random.nextGaussian();
                }
            }
            for(int w = 0; w < weeks; w++){
                for(int i = 0; i < n; i++){
                    luck[(s * weeks + w) * n + i] = (w == 0 ? first[i] : spread) * random.nextGaussian();
                }
            }
        }
    }

    /** One thread's working space. */
    private final class Work {
        final double[] score = new double[n], sorted = new double[n], points = new double[n], title = new double[n];
        final int[] wins = new int[n], order = new int[n];
    }

    /** A manager's mean this week plus his team error, which only weeks not yet started carry. */
    private double strength(double[][] mean, int s, int i, int k){
        return mean[i][k] + (k >= firstFuture ? effect[s * n + i] : 0);
    }

    /** Season s's regular season, leaving the seeds in order. */
    private void seeds(double[][] mean, int s, Work w){
        System.arraycopy(baseWins, 0, w.wins, 0, n);
        System.arraycopy(basePoints, 0, w.points, 0, n);
        double[] score = w.score, sorted = w.sorted, points = w.points;
        int[] wins = w.wins, order = w.order;
        for(int k = 0; k < regular; k++){
            int row = (s * weeks + k) * n;
            for(int i = 0; i < n; i++){
                score[i] = mean[i][k] + luck[row + i] + (k >= firstFuture ? effect[s * n + i] : 0);
            }
            int[] week = games[k];
            for(int g = 0; g < week.length; g += 2){
                double a = score[week[g]], b = score[week[g + 1]];
                if(a > b){
                    wins[week[g]]++;
                }
                else if(b > a){
                    wins[week[g + 1]]++;
                }
            }
            for(int i = 0; i < n; i++){
                points[i] += score[i];
            }
            if(medianGame){
                for(int i = 0; i < n; i++){
                    double x = score[i];
                    int j = i;
                    while(j > 0 && sorted[j - 1] > x){
                        sorted[j] = sorted[j - 1];
                        j--;
                    }
                    sorted[j] = x;
                }
                double median = n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
                for(int i = 0; i < n; i++){
                    wins[i] += score[i] > median ? 1 : 0;
                }
            }
        }
        for(int i = 0; i < n; i++){
            int j = i;
            while(j > 0 && ahead(i, order[j - 1], wins, points)){
                order[j] = order[j - 1];
                j--;
            }
            order[j] = i;
        }
    }

    private static boolean ahead(int x, int y, int[] wins, double[] points){
        return wins[x] > wins[y] || wins[x] == wins[y] && Double.compare(-points[x], -points[y]) < 0;
    }

    private double played(double[][] mean, int s, int i, int k){
        return mean[i][k] + luck[(s * weeks + k) * n + i] + (k >= firstFuture ? effect[s * n + i] : 0);
    }

    private int winner(double[][] mean, int s, int higher, int lower, int k){
        return played(mean, s, lower, k) > played(mean, s, higher, k) ? lower : higher;
    }

    /** Each season's champion, as TitleOdds.play would have it from these means; mean[manager][week], managers in the league's order. */
    int[] champions(double[][] mean){
        return champions(mean, 0, sims);
    }

    int[] champions(double[][] mean, int from, int to){
        int[] out = new int[to - from];
        Work w = new Work();
        for(int s = from; s < to; s++){
            seeds(mean, s, w);
            int[] o = w.order;
            int threeSix = winner(mean, s, o[2], o[5], round1), fourFive = winner(mean, s, o[3], o[4], round1);
            boolean sixOn = reseed && threeSix == o[5];        // as TitleOdds.play: the 1 seed meets the lowest seed left
            int semiA = winner(mean, s, o[0], sixOn ? threeSix : fourFive, round1 + 1), semiB = winner(mean, s, o[1], sixOn ? fourFive : threeSix, round1 + 1);
            out[s - from] = winner(mean, s, semiA, semiB, round1 + 2);
        }
        return out;
    }

    /** The chance x beats y in week k, given both teams' errors. */
    private double beats(double[][] mean, int s, int x, int y, int k){
        double gap = strength(mean, s, x, k) - strength(mean, s, y, k);
        double sx = k == 0 ? first[x] : spread, sy = k == 0 ? first[y] : spread;
        double variance = sx * sx + sy * sy;
        return variance <= 0 ? (gap >= 0 ? 1 : 0) : phi(gap / Math.sqrt(variance));
    }

    /**
     * From the seeds in w.order, each manager's exact chance of the title, in w.title: the four ways
     * the first round can go, each with its two semi-finals and the four finals they can make. In a
     * league that re-seeds, the 6 seed through meets the 1 seed and the 4-5 winner meets the 2.
     */
    private void bracket(double[][] mean, int s, Work w){
        int[] o = w.order;
        double[] title = w.title;
        Arrays.fill(title, 0);
        double p36 = beats(mean, s, o[2], o[5], round1), p45 = beats(mean, s, o[3], o[4], round1);
        for(int a = 0; a < 2; a++){
            for(int b = 0; b < 2; b++){
                double reached = (a == 0 ? p36 : 1 - p36) * (b == 0 ? p45 : 1 - p45);
                if(reached == 0){
                    continue;
                }
                int threeSix = a == 0 ? o[2] : o[5], fourFive = b == 0 ? o[3] : o[4];
                boolean sixOn = reseed && a == 1;
                int x = sixOn ? threeSix : fourFive, y = sixOn ? fourFive : threeSix;      // 1 v x, 2 v y
                double one = beats(mean, s, o[0], x, round1 + 1), two = beats(mean, s, o[1], y, round1 + 1);
                last(mean, s, title, reached * one * two, o[0], o[1]);
                last(mean, s, title, reached * one * (1 - two), o[0], y);
                last(mean, s, title, reached * (1 - one) * two, x, o[1]);
                last(mean, s, title, reached * (1 - one) * (1 - two), x, y);
            }
        }
    }

    /** A final between x and y that is reached with this chance: each one's share of it, added to title. */
    private void last(double[][] mean, int s, double[] title, double reached, int x, int y){
        if(reached == 0){
            return;
        }
        double win = beats(mean, s, x, y, round1 + 2);
        title[x] += reached * win;
        title[y] += reached * (1 - win);
    }

    /** For a test: each manager's chance of the title in one season, from these means. */
    double[] titleChances(double[][] mean, int s){
        Work w = new Work();
        seeds(mean, s, w);
        bracket(mean, s, w);
        return w.title.clone();
    }

    /** Each season's chance of the title for these managers, over seasons from to to, by the formula. */
    double[][] chances(double[][] mean, int[] who, int from, int to){
        double[][] out = new double[who.length][to - from];
        Work w = new Work();
        for(int s = from; s < to; s++){
            seeds(mean, s, w);
            boolean any = false;
            for(int i = 0; i < 6 && !any; i++){
                for(int m : who){
                    any |= w.order[i] == m;
                }
            }
            if(any){
                bracket(mean, s, w);
                for(int m = 0; m < who.length; m++){
                    out[m][s - from] = w.title[who[m]];
                }
            }
        }
        return out;
    }

    /** The paired change, with its standard error, between two runs of per-season chances. */
    static TitleOdds.Delta paired(double[] before, double[] after){
        double sum = 0, sq = 0;
        for(int s = 0; s < before.length; s++){
            double d = after[s] - before[s];
            sum += d;
            sq += d * d;
        }
        double mean = sum / before.length;
        return new TitleOdds.Delta(mean, Math.sqrt(Math.max(0, sq / before.length - mean * mean) / before.length));
    }

    /** The normal distribution function; Abramowitz and Stegun 26.2.17, good to about 1e-7. */
    static double phi(double x){
        double z = Math.abs(x);
        if(z > 8.3){
            return x > 0 ? 1 : 0;
        }
        double t = 1 / (1 + 0.2316419 * z);
        double tail = Math.exp(-0.5 * z * z) * 0.3989422804014327
                * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        return x > 0 ? 1 - tail : tail;
    }
}
