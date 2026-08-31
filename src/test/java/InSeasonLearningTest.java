import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The learning rule's arithmetic, on data whose answer is known in advance.
 *
 * `InSeasonLearning` reports numbers Justin will act on, and its one free
 * parameter - kappa, the prior's weight in games - is exactly the sort of
 * constant this repo has shipped wrong before. The trust coefficient was a
 * Spearman rank correlation standing in for a regression slope for weeks, and
 * nothing caught it because nothing tested it against a case with a known
 * answer. These do.
 *
 * The load-bearing claim is the SUBTRACTION inside sigma^2_between: the spread
 * of observed season rates already contains the sampling noise of a
 * seventeen-week season, and a decomposition that forgets to remove it reports
 * players as more different from each other than they are, which makes kappa
 * too small and the rule too twitchy. Two synthetic worlds pin it - one where
 * every player is truly identical, one where they truly differ.
 */
class InSeasonLearningTest {

    /** Weeks of a season in the synthetic worlds. */
    static final int WEEKS = 17;

    static InSeasonLearning.Man man(String season, int rank, double[] week){
        return new InSeasonLearning.Man(season, season + "-" + rank, Position.RB, rank,
                rank - 1, week, week.length);
    }

    /**
     * Three seasons of running backs whose true rate is set by `spread`.
     *
     * spread 0 means every man at every rank has the same true level, so all of
     * the observed difference between them is sampling noise and kappa should
     * come out enormous. A larger spread makes them genuinely different and
     * kappa should fall.
     */
    static Map<String, List<InSeasonLearning.Man>> world(double spread, long seed){
        Random random = new Random(seed);
        Map<String, List<InSeasonLearning.Man>> harvest = new TreeMap<>();
        for(String season : new String[]{"2001", "2002", "2003"}){
            List<InSeasonLearning.Man> men = new ArrayList<>();
            for(int rank = 1; rank <= 40; rank++){
                double truth = 10 + spread * random.nextGaussian();
                double[] week = new double[WEEKS];
                for(int w = 0; w < WEEKS; w++){
                    week[w] = Math.max(0, truth + 5 * random.nextGaussian());
                }
                men.add(man(season, rank, week));
            }
            harvest.put(season, men);
        }
        return harvest;
    }

    @Test
    void identicalPlayersGiveAHugeKappa(){
        Map<Position, InSeasonLearning.Kappa> fitted =
                InSeasonLearning.fitKappa(world(0, 11), null);
        InSeasonLearning.Kappa backs = fitted.get(Position.RB);
        // every man is truly the same, so a season of evidence about one of them
        // says nothing about how he differs; the rule must almost never update
        assertTrue(backs.kappa() > 40,
                "identical players should give a large kappa, got " + backs.kappa());
    }

    @Test
    void genuinelyDifferentPlayersGiveASmallKappa(){
        Map<Position, InSeasonLearning.Kappa> fitted =
                InSeasonLearning.fitKappa(world(5, 11), null);
        InSeasonLearning.Kappa backs = fitted.get(Position.RB);
        assertTrue(backs.kappa() < 5,
                "players who really differ should give a small kappa, got "
                        + backs.kappa());
    }

    /**
     * The subtraction itself. Without it, identical players look different by
     * their sampling noise alone and kappa collapses - which is the failure the
     * whole decomposition exists to prevent.
     */
    @Test
    void samplingNoiseIsStrippedFromTheBetweenVariance(){
        InSeasonLearning.Kappa backs =
                InSeasonLearning.fitKappa(world(0, 7), null).get(Position.RB);
        double naive = backs.within() / backs.kappa();      // = between, as fitted
        assertTrue(naive < backs.within() / WEEKS * 4,
                "between-player variance should collapse toward zero when the"
                        + " players are identical, got " + naive);
    }

    /** The posterior mean is a weighted average and has to behave like one. */
    @Test
    void theEstimateIsAWeightedAverage(){
        double[] week = new double[WEEKS];
        for(int w = 0; w < WEEKS; w++){
            week[w] = 20;                       // twenty a week, every week
        }
        InSeasonLearning.Man player = man("2001", 1, week);
        Map<Position, double[]> prior = new EnumMap<>(Position.class);
        double[] curve = new double[InSeasonLearning.CAP.get(Position.RB) + 1];
        java.util.Arrays.fill(curve, 1.0);
        prior.put(Position.RB, curve);

        // nothing seen: the estimate IS the prior, at the season's level
        assertEquals(10.0, InSeasonLearning.estimate(player, 0, 6, prior, 10.0), 1e-9);

        // kappa games of prior against kappa games of evidence: dead centre
        assertEquals(15.0, InSeasonLearning.estimate(player, 6, 6, prior, 10.0), 1e-9);

        // a prior worth nothing: the estimate is the evidence
        assertEquals(20.0, InSeasonLearning.estimate(player, 6, 0, prior, 10.0), 1e-9);

        // more evidence moves it further, never past the evidence
        double early = InSeasonLearning.estimate(player, 3, 6, prior, 10.0);
        double late = InSeasonLearning.estimate(player, 12, 6, prior, 10.0);
        assertTrue(early < late && late < 20, "the estimate must climb toward the"
                + " evidence without overshooting it: " + early + " then " + late);
    }

    /**
     * NaN means "did not play" and must never be counted as a zero-point week.
     * Collapsing the two would hand the availability channel's evidence to the
     * bust channel, and the whole point of the measurement is to keep them apart.
     */
    @Test
    void absenceIsNotAZero(){
        double[] week = new double[WEEKS];
        java.util.Arrays.fill(week, Double.NaN);
        week[0] = 30;
        week[1] = 10;
        week[16] = 20;
        InSeasonLearning.Man player = man("2001", 1, week);

        assertEquals(2, player.gamesThrough(5));
        assertEquals(40.0, player.pointsThrough(5), 1e-9);
        assertEquals(20.0, player.ppg(), 1e-9);          // 60 over three games, not 17
        assertEquals(3, player.games());
        assertEquals(20.0, player.restPoints(5), 1e-9);
        assertEquals(1, player.restGames(5));
        assertEquals(100.0, player.weekVariance(), 1e-9); // 30,10,20 -> var 100
    }
}
