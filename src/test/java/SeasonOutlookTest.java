import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The outlook exists to fire Justin's own rule - "win 2026, but if not after
 * like halfway, sell a bit for keepers" - so the rule's edges are what matter.
 */
public class SeasonOutlookTest {

    /**
     * SIX SPOTS MEANS THE ODDS SUM TO SIX. Every simulated season puts exactly
     * six teams in, so the expected number across teams is exactly six - and a
     * simulation that has drifted, double-counted a matchup, or lost a team to a
     * name mismatch will not satisfy that by accident. It is the one check that
     * exercises the whole loop end to end.
     */
    @Test
    public void theOddsAcrossTheLeagueSumToTheNumberOfPlayoffSpots() throws Exception {
        Path report;
        try(var files = Files.list(Path.of("data"))){
            report = files.filter(p -> p.getFileName().toString()
                            .matches("season-outlook-\\d{4}-\\d{2}-\\d{2}\\.txt"))
                    .max(Comparator.comparing(p -> p.getFileName().toString())).orElse(null);
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(report != null,
                "no outlook built yet - run -Pmain=SeasonOutlook");
        String text = Files.readString(report);
        Matcher row = Pattern.compile("^\\S+\\s+[\\d.]+\\s+[\\d.]+\\s+([\\d.]+)%",
                Pattern.MULTILINE).matcher(text);
        double total = 0;
        int teams = 0;
        while(row.find()){
            double odds = Double.parseDouble(row.group(1));
            assertTrue(odds >= 0 && odds <= 100, "a probability outside 0-100: " + odds);
            total += odds;
            teams++;
        }
        assertEquals(12, teams, "the league has twelve teams and the table must list them all");
        assertEquals(600.0, total, 1.0,
                "playoff odds across the league sum to " + total + "%, but six spots means"
                        + " they must sum to 600% - the simulation has lost or double-counted"
                        + " somebody");
    }

    /**
     * IT MUST REFUSE TO CALL THE SEASON EARLY. Justin's rule is explicitly a
     * halfway rule; a sell-for-keepers verdict in week 3 off a 20% sample is not
     * that rule, it is panic wearing a number. The tool's job at week 1 is to be
     * watchable and say nothing.
     */
    @Test
    public void nothingDecisiveIsSaidBeforeHalfway(){
        for(int played : new int[]{0, 1, 3, 6}){
            String early = SeasonOutlook.call(0.04, played, 14);
            assertTrue(early.contains("Too early"),
                    "at " + played + " of 14 weeks and 4% odds it still said: " + early);
            assertFalse(early.toLowerCase().contains("sell a bit"),
                    "it must not fire the sell rule before halfway: " + early);
        }
    }

    /** And past halfway it must actually commit, in both directions. */
    @Test
    public void pastHalfwayItSaysTheThing(){
        assertTrue(SeasonOutlook.call(0.08, 9, 14).contains("sell a bit"),
                "8% past halfway is exactly the case the rule was written for");
        assertTrue(SeasonOutlook.call(0.80, 9, 14).contains("buy, do not sell"),
                "80% past halfway is not a selling position");
        String middle = SeasonOutlook.call(0.40, 9, 14);
        assertTrue(middle.contains("undecided"),
                "40% is genuinely undecided and should say so rather than pick: " + middle);
        assertFalse(middle.contains("sell a bit"), "an undecided season is not a sell");
    }

    /** The sell verdict must carry his constraint, not just the trigger. */
    @Test
    public void theSellVerdictKeepsTheConstraintHePutOnIt(){
        String sell = SeasonOutlook.call(0.05, 10, 14);
        assertTrue(sell.contains("not in a drastic way"),
                "Justin's rule has a limit on it - 'not that drastic where I essentially"
                        + " determine the winner' - and a verdict that drops the limit is"
                        + " not his rule: " + sell);
        assertTrue(sell.contains("round 1-3"),
                "the limit is specifically about the early-round men staying");
    }
}
