import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The daily projection archive: one feed failing must not stop the others
 * (TRAPS #147), a feed already recorded is not recorded twice, the in-season
 * feeds are named for what they serve, and CBS's week page is refused by name.
 */
public class ProjectionArchiveTest {

    @Test
    public void aFeedThatThrowsIsNamedAndTheOthersAreStillWritten(){
        List<String> written = new ArrayList<>();
        List<String> failed = AdpSnapshot.archive("2026-09-25", List.of("sleeper", "cbs-ros", "espn-ros"), Set.of(),
                feed -> {
                    if(feed.equals("cbs-ros")){
                        throw new IllegalStateException("CBS served \"Week 3 Proj\"");
                    }
                    return Map.of("1", 100.04, "2", 50.0);
                },
                id -> true, written::add);
        assertEquals(List.of("cbs-ros: CBS served \"Week 3 Proj\""), failed);
        assertEquals(2, written.size(), "sleeper and espn-ros landed although cbs-ros, between them, threw");
        assertEquals("2026-09-25,sleeper,1,100.0\n2026-09-25,sleeper,2,50.0\n", written.get(0));
        assertTrue(written.get(1).startsWith("2026-09-25,espn-ros,"));
    }

    @Test
    public void aFeedAlreadyRecordedTodayIsSkippedAndAnEmptyAnswerIsAFailure(){
        List<String> written = new ArrayList<>();
        List<String> failed = AdpSnapshot.archive("2026-09-25", List.of("sleeper", "borischen-week"), Set.of("sleeper"),
                feed -> feed.equals("sleeper") ? Map.of("1", 1.0) : Map.of("9", 3.0),
                id -> !id.equals("9"), written::add);
        assertEquals(1, failed.size(), "a feed whose every man is filtered out answered with nobody");
        assertTrue(failed.get(0).startsWith("borischen-week"));
        assertTrue(written.isEmpty(), "sleeper was already recorded, so a rerun writes only what is missing");
        List<String> lines = List.of("date,source,sleeper_id,league_points", "2026-09-25,sleeper,1,1.0",
                "2026-09-25,espn-ros,1,1.0", "2026-09-24,cbs-ros,1,1.0");
        assertEquals(Set.of("sleeper", "espn-ros"), AdpSnapshot.recordedOn(lines, "2026-09-25"));
    }

    @Test
    public void inSeasonTheBoardWidensToTheMenPlayingThisWeek(){
        assertTrue(AdpSnapshot.kept(false, 120, null));
        assertFalse(AdpSnapshot.kept(false, 400, 12.0), "preseason: the board is ADP 250");
        assertTrue(AdpSnapshot.kept(true, Double.MAX_VALUE, 6.5), "in season an undrafted man with a role is kept");
        assertFalse(AdpSnapshot.kept(true, Double.MAX_VALUE, 1.2));
        assertFalse(AdpSnapshot.kept(true, Double.MAX_VALUE, null));
    }

    @Test
    public void inSeasonNoArchivedFeedKeepsAPreseasonName(){
        assertEquals(ProjectionSources.automaticSources(), ProjectionSources.archiveFeeds(false));
        List<String> inSeason = ProjectionSources.archiveFeeds(true);
        assertTrue(inSeason.contains("sleeper"), "the season feed is still the season feed");
        for(String preseasonBoard : List.of("espn", "cbs", "borischen")){
            assertFalse(inSeason.contains(preseasonBoard),
                    preseasonBoard + " serves a different kind of number in season and must not share its column");
        }
        assertEquals(List.of("sleeper", "sleeper-ros", "espn-ros", "cbs-ros", "borischen-week"), inSeason);
    }

    @Test
    public void cbsIsReadOnlyFromThePageItWasAskedFor(){
        String season = "<html><head><title>2026 Projections Fantasy Football Stats - QB Points - CBS Sports</title>";
        String rest = "<html><head><title>Rest of Season Proj Fantasy Football Stats - QB Points - CBS Sports</title>";
        String week = "<html><head><title>Week 3 Proj Fantasy Football Stats - QB Points - CBS Sports</title>";
        CbsProjections.checkServed(season, "season", "QB");
        CbsProjections.checkServed(rest, "restofseason", "QB");
        IllegalStateException weekPage = assertThrows(IllegalStateException.class,
                () -> CbsProjections.checkServed(week, "season", "QB"));
        assertTrue(weekPage.getMessage().contains("Week 3 Proj"), "the refusal says what was served");
        assertThrows(IllegalStateException.class, () -> CbsProjections.checkServed(season, "restofseason", "QB"));
        assertTrue(CbsProjections.url("QB", "2026", "restofseason").endsWith("/QB/2026/restofseason/projections/ppr/"));
        assertEquals(CbsProjections.url("QB", "2026", "season"), CbsProjections.url("QB", "2026"));
    }

    @Test
    public void cbsRowsCarryTheirGamesForThePerGameCheck(){
        // J. Allen's rest-of-season row on 2026-09-25: gp 15, 3406 yards, 28 TD, 10 INT, 592 rush yards, 11 rush TD, 4 FL
        List<String> raw = List.of("J. Allen", "15", "433", "297", "3406", "227.1", "28", "10", "104.0", "130", "592",
                "4.6", "11", "4", "398.2", "26.5");
        com.google.gson.JsonObject stats = CbsProjections.parseRow("QB", raw);
        assertEquals(15, stats.get("gp").getAsDouble(), 1e-9);
        assertEquals(3406, stats.get("pass_yd").getAsDouble(), 1e-9);
        assertEquals(11, stats.get("rush_td").getAsDouble(), 1e-9);
    }
}
