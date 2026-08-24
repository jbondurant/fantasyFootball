import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/** The "only show me trades involving X" knobs at the top of TradeFinder.main. */
class TradeFilterTest {

    private static final Player ST_BROWN =
            TestPlayers.player("Amon-Ra", "St. Brown", "DET", Position.WR, 7547);
    private static final Player HARRISON =
            TestPlayers.player("Marvin", "Harrison Jr.", "ARI", Position.WR, 11563);
    private static final Player SWIFT =
            TestPlayers.player("D'Andre", "Swift", "CHI", Position.RB, 6790);

    private static final List<Player> ROSTER = List.of(ST_BROWN, HARRISON, SWIFT);

    @Test
    void aThreeWordNameMatches(){
        // Regression: the filter split the configured name on spaces and took
        // [1] as the surname, so this looked for "St." against a surname of
        // "St. Brown" and silently matched nobody.
        Assertions.assertTrue(TradeFilter.includes(ROSTER, "Amon-Ra St. Brown"));
    }

    @Test
    void aSuffixIsOptionalInEitherDirection(){
        Assertions.assertTrue(TradeFilter.includes(ROSTER, "Marvin Harrison"));
        Assertions.assertTrue(TradeFilter.includes(ROSTER, "Marvin Harrison Jr."));
    }

    @Test
    void punctuationAndCaseDoNotMatter(){
        Assertions.assertTrue(TradeFilter.includes(ROSTER, "dandre swift"));
        Assertions.assertTrue(TradeFilter.includes(ROSTER, "D'Andre Swift"));
    }

    @Test
    void aOneWordEntryIsIgnoredRatherThanThrowing(){
        // Used to be an ArrayIndexOutOfBoundsException out of split(" ")[1].
        Assertions.assertFalse(TradeFilter.includes(ROSTER, "Swift"));
        Assertions.assertFalse(TradeFilter.includes(ROSTER, ""));
    }

    @Test
    void somebodyNotInTheTradeDoesNotMatch(){
        Assertions.assertFalse(TradeFilter.includes(ROSTER, "Josh Allen"));
    }

    @Test
    void requiringEveryNamedPlayerMeansAllOfThem(){
        Assertions.assertTrue(TradeFilter.includesAll(ROSTER, List.of("Marvin Harrison", "D'Andre Swift")));
        Assertions.assertFalse(TradeFilter.includesAll(ROSTER, List.of("Marvin Harrison", "Josh Allen")));
        Assertions.assertTrue(TradeFilter.includesAll(ROSTER, List.of()), "no requirement excludes nothing");
    }

    @Test
    void excludingMeansAnyOfThem(){
        Assertions.assertTrue(TradeFilter.includesAny(ROSTER, List.of("Josh Allen", "D'Andre Swift")));
        Assertions.assertFalse(TradeFilter.includesAny(ROSTER, List.of("Josh Allen")));
        Assertions.assertFalse(TradeFilter.includesAny(ROSTER, List.of()), "no exclusion excludes nothing");
    }
}
