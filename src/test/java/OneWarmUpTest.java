import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Every tool on the live path must warm the way Draft2026 warms.
 *
 * A harness configured differently from the tool it certifies certifies
 * nothing, and this project produced FIVE instances of that:
 *
 *   LivePathStress    no survival table - found by an adversarial pass
 *   PreFlight         no scheduleRounds - complained about itself
 *   TailLegality      no scheduleRounds - measured the nine-round game
 *   DryRun            no survival table
 *   FragilityBinding  no survival table, and its number reached DRAFT-READY
 *
 * Two of those I wrote AFTER the fault had already been named once. Care is
 * not the fix; there being one place to assemble it is. This test is what
 * keeps that true.
 *
 * IT IS THE WEAKER OF THE TWO CHECKS. The list below is maintained by hand and
 * therefore missed LiveBoard's own main and DefenceTiming - two more copies,
 * found only because a published number moved. SurvivalDependentToolsTest
 * DERIVES its set from what each file actually calls, so a tool written
 * tomorrow is covered without anybody remembering. Keep both: this one also
 * catches a tool that assembles pools or sets the schedule itself, which the
 * derived one does not look at.
 */
public class OneWarmUpTest {

    /** Draft2026 and everything that claims to certify it. */
    private static final List<String> LIVE_PATH = List.of(
            "Draft2026", "LivePathStress", "CycleTiming", "SeatPlan",
            "TailLegality", "FragilityBinding", "DryRun", "PreFlight");

    private static String source(String name) throws Exception {
        return new String(Files.readAllBytes(
                Path.of("src/main/java/" + name + ".java")));
    }

    @Test
    public void everyLivePathToolGoesThroughTheOneWarmUp() throws Exception {
        for(String name : LIVE_PATH){
            assertTrue(source(name).contains("LiveSetup.forTonight()"),
                    name + " does not use LiveSetup.forTonight(), so it can warm"
                            + " differently from the tool it is about");
        }
    }

    @Test
    public void noneOfThemAssemblesItsOwnPools() throws Exception {
        for(String name : LIVE_PATH){
            String source = source(name);
            assertFalse(source.contains("BoardValue.pools("),
                    name + " builds its own pools; LiveSetup must be the only"
                            + " place that happens on the live path");
            assertFalse(source.contains("LiveBoard.defenceScatter()"),
                    name + " attaches its own defence scatter - the omission of"
                            + " exactly this is what left defences priced at 0.0");
        }
    }

    @Test
    public void noneOfThemSetsTheScheduleItself() throws Exception {
        for(String name : LIVE_PATH){
            assertFalse(source(name).contains("setProperty(\"scheduleRounds\""),
                    name + " sets scheduleRounds itself, which is how"
                            + " TailLegality came to measure the nine-round game");
        }
    }

    @Test
    public void theWarmUpItselfSetsTheSchedule() throws Exception {
        String setup = source("LiveSetup");
        assertTrue(setup.contains("setProperty(\"scheduleRounds\","),
                "LiveSetup must set the sixteen-round schedule - it is the only"
                        + " place left that can");
        assertTrue(setup.contains("warmSurvival"),
                "LiveSetup must build the survival table, or every tool falls"
                        + " back to the retired ADP cutoff");
        assertTrue(setup.contains("defenceScatter"),
                "LiveSetup must attach the defence scatter");
    }
}
