import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.List;

/** The team-ranking page fills the league's real lineup and counts what is missing. */
public class TeamRankingsTest {

    private static TeamRankings.Man man(String id, String pos, double pts){
        return new TeamRankings.Man(id, id, pos, "T", pts, false, 0, null);
    }

    @Test
    public void flexTakesTheBestLeftoverAcrossPositions(){
        List<TeamRankings.Man> roster = List.of(
                man("qb", "QB", 300), man("rb1", "RB", 200), man("rb2", "RB", 150), man("rb3", "RB", 120),
                man("wr1", "WR", 190), man("wr2", "WR", 170), man("wr3", "WR", 160), man("wr4", "WR", 90),
                man("te1", "TE", 110), man("te2", "TE", 125), man("def", "DEF", 100));
        TeamRankings.Lineup l = TeamRankings.bestLineup(roster);
        assertEquals(0, l.holes());
        // fixed slots: 300 + (200+150) + (190+170+160) + 125 + 100 = 1395; FLEX: rb3 120 + te1 110 = 230
        assertEquals(1395 + 230, l.starters(), 1e-9);
        assertEquals(230, l.byPosition().get("FLEX"), 1e-9, "the TE2 at 125 starts at TE; TE1 at 110 goes to FLEX over WR4 at 90");
        assertEquals(1, l.bench().size());
        assertEquals("wr4", l.bench().get(0).id());
    }

    @Test
    public void anEmptySlotIsAHoleWorthZero(){
        List<TeamRankings.Man> roster = List.of(man("rb1", "RB", 200), man("wr1", "WR", 190));
        TeamRankings.Lineup l = TeamRankings.bestLineup(roster);
        assertEquals(390, l.starters(), 1e-9);
        assertEquals(8, l.holes(), "QB, RB2, WR2, WR3, TE, DEF and both FLEX are empty");
    }

    @Test
    public void rankingIsByStartersWithBenchAsTieBreak(){
        TeamRankings.Team a = new TeamRankings.Team(1, "a", List.of(), TeamRankings.bestLineup(List.of(man("q", "QB", 300))));
        TeamRankings.Team b = new TeamRankings.Team(2, "b", List.of(), TeamRankings.bestLineup(List.of(man("q", "QB", 300), man("q2", "QB", 250))));
        TeamRankings.Team c = new TeamRankings.Team(3, "c", List.of(), TeamRankings.bestLineup(List.of(man("q", "QB", 310))));
        List<TeamRankings.Team> ranked = TeamRankings.rank(List.of(a, b, c));
        assertEquals("c", ranked.get(0).manager(), "more starter points wins");
        assertEquals("b", ranked.get(1).manager(), "equal starters: the deeper bench ranks higher");
    }
}
