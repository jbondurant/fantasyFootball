import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.Test;

/**
 * If a tool's numbers depend on the survival table, it must BUILD one.
 *
 * `LiveBoard.expectedRank` falls back to the retired ADP cutoff when no
 * survival table exists. It does so silently, and everything downstream of it -
 * `rolloutStats`, `rolloutSeasons`, `rolloutRoster`, `drain`, `answer` - inherits
 * that. So a tool that calls any of them without warming produces numbers from
 * an estimator nothing ships, and looks entirely normal doing it.
 *
 * Three separate tools were caught doing exactly this in one day -
 * LivePathStress, FragilityBinding and DefenceTiming - and two of them had
 * their numbers in DRAFT-READY before anybody noticed. The first fix was a list
 * of eight names in OneWarmUpTest, which promptly missed LiveBoard's own main
 * and DefenceTiming.
 *
 * A list I maintain by hand was never going to hold. This DERIVES the set from
 * what each file actually calls, so a tool written tomorrow is covered without
 * anybody remembering to add it.
 */
public class SurvivalDependentToolsTest {

    /**
     * Anything whose result changes when SURVIVAL is null - QUALIFIED.
     *
     * The names have to carry "LiveBoard." because BoardValue has its own
     * rolloutRoster taking a PlanBacktest.Board: a different method that
     * happens to share a name, and the unqualified version of this list
     * flagged the historical backtest, which cannot use a survival table and
     * does not need one.
     */
    private static final List<String> SURVIVAL_DEPENDENT = List.of(
            "LiveBoard.expectedRank(", "LiveBoard.rolloutStats(",
            "LiveBoard.rolloutSeasons(", "LiveBoard.rolloutRoster(",
            "LiveBoard.answer(", "LiveBoard.drain(");

    /**
     * Tools that vary the configuration ON PURPOSE and must not be forced onto
     * the single warm-up. Each needs a reason, not just an entry.
     */
    private static final Map<String, String> EXEMPT = Map.of(
            "LiveBoard", "defines the methods; its own main does use LiveSetup",
            "BoardSourceCheck", "builds one setup per projection feed - that is"
                    + " the measurement",
            "RealDraftSurvival", "builds a survival table per HISTORICAL season",
            "RealMidDraft", "same, per historical season",
            "MidDraftRank", "builds its own table to compare three rules against"
                    + " each other",
            "DrainPrediction", "same, and reconstructs the retired rule on purpose",
            "RankPrediction", "compares the cutoff against survival directly");

    @Test
    public void everyToolWhoseNumbersDependOnSurvivalWarmsIt() throws Exception {
        List<String> offenders = new ArrayList<>();
        int tools = 0;
        try(Stream<Path> files = Files.walk(Path.of("src/main/java"))){
            for(Path path : files.filter(p -> p.toString().endsWith(".java")).toList()){
                String source = Files.readString(path);
                String name = path.getFileName().toString().replace(".java", "");
                if(!source.contains("public static void main")){
                    continue;
                }
                boolean depends = SURVIVAL_DEPENDENT.stream().anyMatch(source::contains);
                if(!depends || EXEMPT.containsKey(name)){
                    continue;
                }
                tools++;
                if(!source.contains("LiveSetup.forTonight()")
                        && !source.contains("warmSurvival")){
                    offenders.add(name + " uses the rollout but never builds a"
                            + " survival table, so its numbers come from the"
                            + " retired ADP cutoff");
                }
            }
        }
        assertTrue(tools >= 4,
                "expected to find several survival-dependent tools, found " + tools);
        assertEquals(List.of(), offenders, String.join("; ", offenders));
    }

    @Test
    public void theExemptionsAreStillReal() throws Exception {
        for(Map.Entry<String, String> entry : EXEMPT.entrySet()){
            Path path = Path.of("src/main/java/" + entry.getKey() + ".java");
            assertTrue(Files.exists(path),
                    entry.getKey() + " is exempted but no longer exists - an"
                            + " exemption list that outlives its files is how"
                            + " the next one hides");
        }
    }
}
