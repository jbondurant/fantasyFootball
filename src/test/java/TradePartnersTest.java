import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who trades is the one acceptance signal on the board that is measured rather
 * than modelled, so the counting has to be right.
 */
public class TradePartnersTest {

    /**
     * A trade names roster ids on BOTH sides, and both sides did the deal.
     *
     * The adds map is playerID to the roster RECEIVING him and the drops map is
     * playerID to the roster SENDING him, so a two-man trade touches the same two
     * rosters twice over. Counting only adds - or only drops - would attribute
     * every trade to one participant and halve the rate of everybody in the
     * league, which is the direction that would make a quiet manager look active.
     */
    @Test
    public void bothSidesOfATradeCount(){
        LeagueTransactions.Move trade = new LeagueTransactions.Move(
                "2025", 3, "trade", "complete",
                Map.of("henry", 4, "kelce", 7),      // 4 receives Henry, 7 receives Kelce
                Map.of("henry", 7, "kelce", 4),      // 7 sent Henry, 4 sent Kelce
                0);
        assertEquals(Set.of(4, 7), TradePartners.participants(trade),
                "a trade touches every roster on either side of it");
    }

    /** A three-way deal is legal and each participant did a deal. */
    @Test
    public void aThreeWayDealCountsForAllThree(){
        LeagueTransactions.Move trade = new LeagueTransactions.Move(
                "2025", 9, "trade", "complete",
                Map.of("a", 1, "b", 2, "c", 3), Map.of("a", 2, "b", 3, "c", 1), 0);
        assertEquals(Set.of(1, 2, 3), TradePartners.participants(trade));
    }

    /** A waiver claim has one roster in it and is not a trade at all. */
    @Test
    public void aWaiverClaimIsNotATrade(){
        LeagueTransactions.Move claim = new LeagueTransactions.Move(
                "2025", 5, "waiver", "complete", Map.of("schultz", 11), Map.of("harris", 11), 17);
        assertEquals(Set.of(11), TradePartners.participants(claim),
                "participants is about rosters touched; the type filter is what excludes it");
        assertNotEquals("trade", claim.type());
    }

    /** Null add/drop maps appear in the feed and must not throw. */
    @Test
    public void aMoveWithNoAddsOrDropsIsEmptyRatherThanFatal(){
        assertEquals(Set.of(), TradePartners.participants(new LeagueTransactions.Move(
                "2025", 1, "trade", "complete", null, null, 0)));
    }

    /** rate() is trades over seasons, and a manager with no seasons is not a divide by zero. */
    @Test
    public void theRateIsPerSeasonInTheLeague(){
        assertEquals(3.6, new TradePartners.Record("KevinDA", 5, 18, Map.of()).rate(), 1e-9);
        assertEquals(0.0, new TradePartners.Record("ghost", 0, 0, Map.of()).rate(), 1e-9,
                "a manager the log never saw must read zero, not infinity");
    }

    /**
     * And the committed report must agree with the code that wrote it - the same
     * read-it-back check the other measured constants get.
     */
    @Test
    public void theReportsRatesAreTradesOverSeasons() throws Exception {
        Path report;
        try(var files = Files.list(Path.of("data"))){
            report = files.filter(p -> p.getFileName().toString()
                            .matches("trade-partners-\\d{4}-\\d{2}-\\d{2}\\.txt"))
                    .max(Comparator.comparing(p -> p.getFileName().toString())).orElse(null);
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(report != null,
                "no partner report built yet - run -Pmain=TradePartners");
        Matcher row = Pattern.compile("^(\\S+)\\s+(\\d+)\\s+(\\d+)\\s+([\\d.]+)", Pattern.MULTILINE)
                .matcher(Files.readString(report));
        int seen = 0;
        while(row.find()){
            if(row.group(1).equals("MANAGER")){
                continue;
            }
            int seasons = Integer.parseInt(row.group(2));
            int trades = Integer.parseInt(row.group(3));
            assertEquals((double) trades / seasons, Double.parseDouble(row.group(4)), 0.005,
                    row.group(1) + "'s printed rate is not his trades over his seasons");
            assertTrue(seasons > 0, "a manager in the table was in at least one season");
            seen++;
        }
        assertTrue(seen > 0, "the report listed no managers");
    }
}
