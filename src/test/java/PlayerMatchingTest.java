import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

/**
 * FantasyProsand Sleeper no longer share an id, so rows are joined on
 * name + team + position. This is the join every ranking and projection now
 * depends on, and it fails silently - a missed match just drops a player.
 */
class PlayerMatchingTest {

    private static final Player CHASE = TestPlayers.player("Ja'Marr", "Chase", "CIN", Position.WR, 7564);
    private static final Player HARRISON = TestPlayers.player("Marvin", "Harrison Jr.", "ARI", Position.WR, 11563);
    private static final Player ETIENNE = TestPlayers.player("Travis", "Etienne", "JAX", Position.RB, 7002);
    private static final Player BEARS = TestPlayers.defense("Chicago", "Bears", "CHI");
    private static final Player JAGS = TestPlayers.defense("Jacksonville", "Jaguars", "JAX");

    @BeforeEach
    void indexAHandfulOfPlayers(){
        Player.indexForTest(TestPlayers.listOf(CHASE, HARRISON, ETIENNE, BEARS, JAGS));
    }

    @AfterEach
    void reset(){
        Player.resetIndexForTest();
    }

    @Test
    void anExactRowMatches(){
        Assertions.assertEquals(CHASE, Player.getPlayerFromFantasyPros("Ja'Marr Chase", "CIN", Position.WR));
    }

    @Test
    void anApostropheOrPeriodDoesNotBreakTheMatch(){
        Assertions.assertEquals(CHASE, Player.getPlayerFromFantasyPros("JaMarr Chase", "CIN", Position.WR));
        Assertions.assertEquals(CHASE, Player.getPlayerFromFantasyPros("Ja.Marr Chase", "CIN", Position.WR));
    }

    @Test
    void aGenerationalSuffixIsIgnoredOnEitherSide(){
        // Sleeper carries "Harrison Jr.", FantasyPros often does not.
        Assertions.assertEquals(HARRISON, Player.getPlayerFromFantasyPros("Marvin Harrison", "ARI", Position.WR));
        Assertions.assertEquals(HARRISON, Player.getPlayerFromFantasyPros("Marvin Harrison Jr.", "ARI", Position.WR));
    }

    @Test
    void jacksonvilleIsTheSameTeamUnderEitherAbbreviation(){
        // FantasyPros writes JAC, Sleeper writes JAX.
        Assertions.assertEquals(ETIENNE, Player.getPlayerFromFantasyPros("Travis Etienne", "JAC", Position.RB));
        Assertions.assertEquals(JAGS, Player.getPlayerFromFantasyPros("Jacksonville Jaguars", "JAC", Position.DEF));
    }

    @Test
    void aTradedPlayerStillMatchesOnNameAndPosition(){
        // The two sites disagree about who plays where all offseason.
        Assertions.assertEquals(CHASE, Player.getPlayerFromFantasyPros("Ja'Marr Chase", "NYJ", Position.WR));
    }

    @Test
    void aDefenseMatchesOnItsTeam(){
        Assertions.assertEquals(BEARS, Player.getPlayerFromFantasyPros("Chicago Bears", "CHI", Position.DEF));
    }

    @Test
    void aFreeAgentTeamIsNotTreatedAsARealTeam(){
        Assertions.assertEquals(CHASE, Player.getPlayerFromFantasyPros("Ja'Marr Chase", "FA", Position.WR));
    }

    @Test
    void anUnknownPlayerReturnsNullRatherThanAWrongOne(){
        Assertions.assertNull(Player.getPlayerFromFantasyPros("Nobody Atall", "CIN", Position.WR));
    }

    @Test
    void twoPlayersSharingANameAreDisambiguatedByTeam(){
        Player oneMikeWilliams = TestPlayers.player("Mike", "Williams", "LAC", Position.WR, 4068);
        Player otherMikeWilliams = TestPlayers.player("Mike", "Williams", "NYJ", Position.WR, 4069);
        Player.indexForTest(TestPlayers.listOf(oneMikeWilliams, otherMikeWilliams));

        Assertions.assertEquals(oneMikeWilliams,
                Player.getPlayerFromFantasyPros("Mike Williams", "LAC", Position.WR));
        Assertions.assertEquals(otherMikeWilliams,
                Player.getPlayerFromFantasyPros("Mike Williams", "NYJ", Position.WR));
    }

    @Test
    void anAmbiguousNameWithNoTeamMatchIsDroppedRatherThanGuessed(){
        Player oneMikeWilliams = TestPlayers.player("Mike", "Williams", "LAC", Position.WR, 4068);
        Player otherMikeWilliams = TestPlayers.player("Mike", "Williams", "NYJ", Position.WR, 4069);
        Player.indexForTest(TestPlayers.listOf(oneMikeWilliams, otherMikeWilliams));

        Assertions.assertNull(Player.getPlayerFromFantasyPros("Mike Williams", "DEN", Position.WR),
                "guessing between two players would silently mis-score a roster");
    }

    @Test
    void aRosteredPlayerWinsOverARetiredNamesake(){
        Player active = TestPlayers.player("Josh", "Allen", "BUF", Position.QB, 4984);
        Player retired = TestPlayers.player("Josh", "Allen", "", Position.QB, 3000);
        Player.indexForTest(TestPlayers.listOf(retired, active));

        Assertions.assertEquals(active, Player.getPlayerFromFantasyPros("Josh Allen", "BUF", Position.QB));
    }

    @Test
    void nameNormalisationRules(){
        Assertions.assertEquals("jamarrchase", Player.normalizeName("Ja'Marr Chase"));
        Assertions.assertEquals("marvinharrison", Player.normalizeName("Marvin Harrison Jr."));
        Assertions.assertEquals("amonrastbrown", Player.normalizeName("Amon-Ra St. Brown"));
        Assertions.assertEquals("", Player.normalizeName(null));
    }

    @Test
    void aTwoWordNameKeepsItsSurnameEvenWhenItLooksLikeASuffix(){
        // "Jr" and "V" are suffixes in "Brian Robinson Jr", but a two token name
        // has no suffix to strip - the second token is the surname.
        Assertions.assertEquals("lamarjackson", Player.normalizeName("Lamar Jackson"));
        Assertions.assertEquals("jaredverse", Player.normalizeName("Jared Verse"));
    }

    @Test
    void teamNormalisationRules(){
        Assertions.assertEquals("JAX", Player.normalizeTeam("JAC"));
        Assertions.assertEquals("JAX", Player.normalizeTeam("JAX"));
        Assertions.assertEquals("LV", Player.normalizeTeam("OAK"));
        Assertions.assertEquals("", Player.normalizeTeam("FA"));
        Assertions.assertEquals("", Player.normalizeTeam(null));
        Assertions.assertEquals("BUF", Player.normalizeTeam(" buf "));
    }
}
