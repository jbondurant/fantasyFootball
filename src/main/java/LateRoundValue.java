import PlayerImportAndSetup.Position;
import java.util.*;

/**
 * An outcome-measured check on what the late-round pricer says.
 *
 * This began as a claimed fix and turned into a cross-check, because the claim
 * was wrong. I asserted that LiveLateRounds prices depth at zero, quoting
 * BenchValue's remark that "every position reads the same, because nothing a
 * bench man does changes the starting-nine projection". That remark is about
 * MODEL A and season totals. LiveLateRounds runs on WeeklyStarterValue, whose
 * own header answers the point directly: "a bench player scores in the weeks he
 * beats the men ahead of him, so his value is an option payoff". It sees depth.
 * The blind spot was mine.
 *
 * So this does not add a term. It prices the same three choices from REALISED
 * outcomes rather than from projections, so the live tool can be checked
 * against something it does not itself compute:
 *
 *   backup skill   BenchValue, 434 real rounds 8-16 picks, points over the wire
 *   drafted defence DefenceVersusDepth, realised points against the streaming rate
 *
 * The scale is points above what the ROSTER SPOT yields for free, which is the
 * right denominator because the spot is the scarce thing - Justin's own
 * correction, that a streamed defence still occupies one of the sixteen.
 *
 * A starting tight end is deliberately absent. He fills an empty slot, so his
 * worth depends on WHO is there and on the tight end you would get at your last
 * pick; that is a live number, and LiveLateRounds already computes it. Only the
 * two constants below can be settled in advance.
 *
 *   ./gradlew run -Pmain=LateRoundValue
 */
public class LateRoundValue {

    /** BenchValue, measured on 434 real picks by this league across 2021-25. */
    record Band(String label, int from, int to, double overWire, double hitRate){}

    static final Band[] BANDS = {
            new Band("8-9",   8,  9, 44.0, 0.29),
            new Band("10-12", 10, 12, 32.8, 0.19),
            new Band("13-16", 13, 16, 31.2, 0.18),
    };

    /**
     * DefenceVersusDepth: realised season points by PRESEASON defence band, the
     * mean row of data/defence-versus-depth-2026-09-04.txt (DEF1-3 and DEF10-12).
     * BandRegressionTest reads them back out of that file rather than trusting
     * these lines. The worst band was 129.5 before defences were banded by the
     * source's ADP order (TRAPS #80); the best band did not move.
     */
    static final String DEF_BANDS = "defence-versus-depth-2026-09-04.txt";
    static final double DEF_BEST_BAND = 135.8;
    static final double DEF_WORST_BAND = 127.2;
    static final int DEF_BAND_WINS = 3;         // of five seasons
    static final int WEEKS = 17;

    public static void main(String[] args) throws Exception {
        double defenceWire = PlanBacktest.streamedDefencePerWeek();
        double streamed = defenceWire * WEEKS;

        System.out.printf("%nWHAT EACH KIND OF PICK IS WORTH, ROUNDS 8-16%n");
        System.out.printf("scale: points above what that ROSTER SPOT yields for free.%n"
                + "a defence is measured against STREAMING one, a bench man against%n"
                + "the WAIVER WIRE. the spot is the scarce thing, so the spot is the%n"
                + "denominator.%n%n");

        System.out.printf("   streaming a defence pays %.1f a week, %.0f a season.%n",
                defenceWire, streamed);
        System.out.printf("   the best preseason defences returned %.1f a season.%n",
                DEF_BEST_BAND);
        System.out.printf("   so drafting the best defence beats streaming by %+.0f.%n%n",
                DEF_BEST_BAND - streamed);

        System.out.printf("%-8s %14s %14s %14s%n",
                "ROUND", "BACKUP SKILL", "STARTING DEF", "VERDICT");
        for(Band band : BANDS){
            double defence = DEF_BEST_BAND - streamed;
            String verdict = band.overWire() > defence + 5 ? "take the skill man"
                    : Math.abs(band.overWire() - defence) <= 5 ? "either"
                    : "take the defence";
            System.out.printf("%-8s %+14.1f %+14.1f   %s%n",
                    band.label(), band.overWire(), defence, verdict);
        }

        System.out.printf("%n   and the defence column is generous twice over.%n");
        System.out.printf("   it credits you the BEST preseason band, but preseason%n");
        System.out.printf("   ordering picked the better half in only %d seasons of 5,%n",
                DEF_BAND_WINS);
        System.out.printf("   and the bands run %.1f / %.1f - not even monotonic. the%n",
                DEF_BEST_BAND, DEF_WORST_BAND);
        System.out.printf("   honest expectation is the pooled mean, %+.0f.%n",
                (DEF_BEST_BAND + DEF_WORST_BAND) / 2 - streamed);

        System.out.printf("%n%s%n   THE RULE THIS PRODUCES%n%s%n",
                "=".repeat(60), "=".repeat(60));
        System.out.printf("   a starting DEFENCE is worth roughly %+.0f whenever you take%n"
                + "   him, so he is a LAST-ROUND pick - not because he is cheap late,%n"
                + "   but because he is no better early.%n",
                DEF_BEST_BAND - streamed);
        System.out.printf("%n   a BACKUP skill man is worth %+.1f in rounds 8-9 and %+.1f%n"
                + "   in 13-16, so the early bench picks are the ones that pay.%n",
                BANDS[0].overWire(), BANDS[2].overWire());
        System.out.printf("%n   a starting TIGHT END cannot be settled here. he fills an empty%n"
                + "   slot, so he is worth his projection over the tight end you would%n"
                + "   get at your last pick - a live number, which LiveLateRounds already%n"
                + "   computes on the real board.%n");
        System.out.printf("%n   USE THIS TO CHECK THAT TOOL, not to replace it. on draft night%n"
                + "   its ADDS column should put the best defence near or below zero and%n"
                + "   a rounds 8-9 backup well above it. if it prices a defence ahead of%n"
                + "   depth in round 8, it disagrees with five seasons of outcomes and%n"
                + "   the outcomes should win.%n");
    }
}
