import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import java.util.List;

/** The season ladder's reading: lines from labels, the quote filter, and chances and medians between lines. */
public class SeasonPropsTest {

    /** Jeanty's rushing-yards ladder as Polymarket quoted it on 2026-11-07, midpoints. */
    private static final List<SeasonProps.Line> JEANTY = List.of(new SeasonProps.Line(574.5, 0.7345), new SeasonProps.Line(774.5, 0.395),
            new SeasonProps.Line(974.5, 0.135), new SeasonProps.Line(1174.5, 0.0295));

    @Test
    public void aLabelNamesItsLine(){
        assertEquals(774.5, SeasonProps.strike("774.5+ rushing yards"));
        assertEquals(1174.5, SeasonProps.strike("1,174.5+ rushing yards"));
        assertEquals(3.5, SeasonProps.strike("3.5+ rushing TDs"));
        assertNull(SeasonProps.strike("Ashton Jeanty"));
        assertNull(SeasonProps.strike(null));
    }

    @Test
    public void chancesAreReadBetweenLinesAndNotPastThem(){
        assertEquals(0.395, SeasonProps.above(JEANTY, 774.5), 1e-12);
        assertEquals((0.7345 + 0.395) / 2, SeasonProps.above(JEANTY, 674.5), 1e-12);
        assertTrue(Double.isNaN(SeasonProps.above(JEANTY, 500)), "below the lowest line the ladder says nothing");
        assertTrue(Double.isNaN(SeasonProps.above(JEANTY, 1300)));
    }

    @Test
    public void theMedianIsWhereTheLadderCrossesEvenMoney(){
        assertEquals(574.5 + 200 * (0.7345 - 0.5) / (0.7345 - 0.395), SeasonProps.median(JEANTY), 1e-9);
        assertTrue(Double.isNaN(SeasonProps.median(List.of(new SeasonProps.Line(1, 0.9), new SeasonProps.Line(2, 0.7)))), "no line straddles even money");
    }

    @Test
    public void onlyThisMansSeasonStatEventsCount(){
        assertEquals("Rushing Yards", SeasonProps.statOf("Pro Football: Ashton Jeanty 2026-27 Regular Season Rushing Yards", "Ashton Jeanty", "2026"));
        assertEquals("Rushing Yards", SeasonProps.statOf("Pro Football: Mike Washington Jr. 2026-27 Regular Season Rushing Yards", "Mike Washington", "2026"));
        assertNull(SeasonProps.statOf("Pro Football: Ashton Jeanty 2025-26 Regular Season Rushing Yards", "Ashton Jeanty", "2026"), "another season");
        assertNull(SeasonProps.statOf("Pro Football: 2026-27 1,000+ Rushing Yards", "Ashton Jeanty", "2026"));
        assertNull(SeasonProps.statOf("Pro Football: Ashton Jeanty 2026-27 Regular Season Longest Rush", "Ashton Jeanty", "2026"), "a stat with no Sleeper key");
        assertEquals("2026-27", SeasonProps.seasonLabel("2026"));
        assertEquals("2099-00", SeasonProps.seasonLabel("2099"));
    }

    @Test
    public void wideOrEndPricedLinesAreLeftOutAndCounted(){
        JsonArray markets = JsonParser.parseString("["
                + "{\"groupItemTitle\":\"774.5+ rushing yards\",\"bestBid\":0.39,\"bestAsk\":0.40},"
                + "{\"groupItemTitle\":\"574.5+ rushing yards\",\"bestBid\":0.729,\"bestAsk\":0.74},"
                + "{\"groupItemTitle\":\"424.5+ rushing yards\",\"bestBid\":0.73,\"bestAsk\":0.99},"
                + "{\"groupItemTitle\":\"1,374.5+ rushing yards\",\"bestBid\":0.002,\"bestAsk\":0.025},"
                + "{\"groupItemTitle\":\"974.5+ rushing yards\",\"bestBid\":0.13,\"bestAsk\":0.14,\"closed\":true}]").getAsJsonArray();
        int[] leftOut = {0};
        List<SeasonProps.Line> ladder = SeasonProps.ladder(markets, leftOut);
        assertEquals(List.of(574.5, 774.5), ladder.stream().map(SeasonProps.Line::strike).toList(), "sorted by line; a closed line is skipped");
        assertEquals(0.395, ladder.get(1).p(), 1e-12);
        assertEquals(2, leftOut[0], "a 26-cent spread and a 1-cent midpoint");
    }

    /** 0.91 - 0.71 is 0.20000000000000007 in doubles; the rule is twenty cents or less. */
    @Test
    public void aSpreadOfExactlyTwentyCentsIsKept(){
        JsonArray markets = JsonParser.parseString("[{\"groupItemTitle\":\"774.5+ rushing yards\",\"bestBid\":0.71,\"bestAsk\":0.91}]").getAsJsonArray();
        int[] leftOut = {0};
        assertEquals(1, SeasonProps.ladder(markets, leftOut).size());
        assertEquals(0, leftOut[0]);
    }
}
