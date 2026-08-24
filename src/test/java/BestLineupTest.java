import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

/**
 * The league starts QB, RB, RB, WR, WR, WR, TE, FLEX, FLEX, DEF. Roster
 * strength - and therefore every trade this thing proposes - is the best
 * lineup you can field, so the flex slots have to take the best two players
 * left over rather than a fixed position.
 */
class BestLineupTest {

    private static Score score(double points, String lastName, Position position, int id){
        return new Score(points, TestPlayers.player("Test", lastName, "BUF", position, id));
    }

    private static ArrayList<Score> scores(Score... values){
        ArrayList<Score> list = new ArrayList<>();
        for(Score value : values){
            list.add(value);
        }
        return list;
    }

    @Test
    void aFullRosterStartsItsBestNineAndADefense(){
        ArrayList<Score> roster = scores(
                score(400, "Qb1", Position.QB, 1),
                score(300, "Qb2", Position.QB, 2),      // benched
                score(280, "Rb1", Position.RB, 3),
                score(260, "Rb2", Position.RB, 4),
                score(240, "Rb3", Position.RB, 5),      // flex
                score(250, "Wr1", Position.WR, 6),
                score(230, "Wr2", Position.WR, 7),
                score(210, "Wr3", Position.WR, 8),
                score(190, "Wr4", Position.WR, 9),      // flex
                score(180, "Wr5", Position.WR, 10),     // benched
                score(200, "Te1", Position.TE, 11),
                score(100, "Def", Position.DEF, 12));

        double expected = 400            // QB
                + 280 + 260              // RB, RB
                + 250 + 230 + 210        // WR, WR, WR
                + 200                    // TE
                + 240 + 190              // FLEX, FLEX: best two left over
                + 100;                   // DEF

        Assertions.assertEquals(expected, new ScoredRoster("u1", roster).scoreBestROSStartingLineup(), 0.0001);
    }

    @Test
    void aTightEndCanTakeAFlexSlot(){
        ArrayList<Score> roster = scores(
                score(400, "Qb1", Position.QB, 1),
                score(280, "Rb1", Position.RB, 2),
                score(260, "Rb2", Position.RB, 3),
                score(250, "Wr1", Position.WR, 4),
                score(230, "Wr2", Position.WR, 5),
                score(210, "Wr3", Position.WR, 6),
                score(300, "Te1", Position.TE, 7),
                score(290, "Te2", Position.TE, 8),      // outscores every spare RB/WR
                score(270, "Te3", Position.TE, 9),
                score(50, "Rb3", Position.RB, 10));

        double expected = 400 + 280 + 260 + 250 + 230 + 210 + 300 + 290 + 270;

        Assertions.assertEquals(expected, new ScoredRoster("u1", roster).scoreBestROSStartingLineup(), 0.0001);
    }

    @Test
    void anIncompleteRosterScoresWhatItHas(){
        // Mid-draft, and in the trade previews, rosters are routinely short.
        ArrayList<Score> roster = scores(
                score(400, "Qb1", Position.QB, 1),
                score(280, "Rb1", Position.RB, 2));

        Assertions.assertEquals(680, new ScoredRoster("u1", roster).scoreBestROSStartingLineup(), 0.0001);
    }

    @Test
    void anEmptyRosterScoresZeroRatherThanThrowing(){
        Assertions.assertEquals(0.0, new ScoredRoster("u1", scores()).scoreBestROSStartingLineup(), 0.0001);
    }

    @Test
    void theFlexTakesTheBestTwoOfTheSixCandidates(){
        Assertions.assertEquals(90.0, ScoredRoster.getFlexScore(10, 20, 30, 40, 50, 0), 0.0001);
    }

    @Test
    void addingPointsNeverLowersTheBestLineup(){
        ArrayList<Score> before = scores(
                score(400, "Qb1", Position.QB, 1),
                score(280, "Rb1", Position.RB, 2),
                score(260, "Rb2", Position.RB, 3),
                score(250, "Wr1", Position.WR, 4),
                score(230, "Wr2", Position.WR, 5),
                score(210, "Wr3", Position.WR, 6),
                score(200, "Te1", Position.TE, 7));
        double baseline = new ScoredRoster("u1", before).scoreBestROSStartingLineup();

        ArrayList<Score> after = new ArrayList<>(before);
        after.add(score(500, "Wr9", Position.WR, 8));

        Assertions.assertTrue(new ScoredRoster("u1", after).scoreBestROSStartingLineup() > baseline,
                "a better player than anyone already starting has to raise the lineup");
    }
}
