import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Contests, the win curve, and the bid that follows from them. */
public class FaabBidTest {

    private static JsonArray rows(String json){
        return JsonParser.parseString(json).getAsJsonArray();
    }

    @Test
    public void aContestIsOneManAtOneClearingAndItsPriceIsWhatWonHim(){
        // three bids on 7593 at one clearing: 18 wins, 15 and 2 lose
        List<FaabBid.Contest> contests = FaabBid.contests("2024", rows("""
                [{"settings":{"waiver_bid":18},"status":"complete","status_updated":1,"leg":4,"adds":{"7593":2}},
                 {"settings":{"waiver_bid":15},"status":"failed","status_updated":1,"leg":4,"adds":{"7593":5}},
                 {"settings":{"waiver_bid":2},"status":"failed","status_updated":1,"leg":4,"adds":{"7593":9}}]"""),
                Map.of(4, Map.of("7593", 11.0)));
        assertEquals(1, contests.size());
        assertEquals(18, contests.get(0).clearingBid(), "the price is what it took, not what was offered");
        assertEquals(3, contests.get(0).bidders());
        assertEquals(11.0, contests.get(0).projection(), 1e-9);
    }

    @Test
    public void aTopBidThatLostIsACascadeAndNotAPrice(){
        // 20 failed while 5 won: the 20 died for a full roster, so this reveals nothing
        List<FaabBid.Contest> contests = FaabBid.contests("2024", rows("""
                [{"settings":{"waiver_bid":20},"status":"failed","status_updated":1,"leg":4,"adds":{"99":2}},
                 {"settings":{"waiver_bid":5},"status":"complete","status_updated":1,"leg":4,"adds":{"99":5}}]"""),
                Map.of());
        assertTrue(contests.isEmpty(), "counting this would read as 5 outbidding 20");
    }

    @Test
    public void twoClearingsOfTheSameManAreTwoContests(){
        List<FaabBid.Contest> contests = FaabBid.contests("2024", rows("""
                [{"settings":{"waiver_bid":3},"status":"complete","status_updated":1,"leg":4,"adds":{"99":2}},
                 {"settings":{"waiver_bid":9},"status":"complete","status_updated":2,"leg":6,"adds":{"99":5}}]"""),
                Map.of());
        assertEquals(2, contests.size(), "he was dropped and claimed again; that is a second price");
    }

    @Test
    public void theWinCurveAndTheBidThatFollowsFromIt(){
        FaabBid.Band band = new FaabBid.Band("t", 0, Double.MAX_VALUE, List.of(0, 0, 2, 4, 10));
        assertEquals(0.0, band.winChance(0), 1e-9, "a zero bid never beats a zero clearing price");
        assertEquals(0.4, band.winChance(1), 1e-9, "it beats the two zeroes");
        assertEquals(1.0, band.winChance(11), 1e-9);
        // worth 20: bidding 5 wins 60% for a net of 9; bidding 11 wins all of it for 9 too
        assertTrue(FaabBid.bestBid(band, 20, 1.0, 100) > 0);
        assertEquals(0, FaabBid.bestBid(band, 1, 1.0, 100),
                "a man worth 1 point is not worth a dollar that wins 40% of the time");
        assertTrue(FaabBid.bestBid(band, 20, 3.0, 100) <= FaabBid.bestBid(band, 20, 1.0, 100),
                "a dearer dollar can only lower the bid");
        assertTrue(FaabBid.bestBid(band, 20, 1.0, 3) <= 3, "and the budget is a hard ceiling");
    }

    @Test
    public void theCommittedPricesAreReadBackOutOfTheirOwnReport() throws Exception {
        Path newest = FaabBid.newestCurve();
        assertNotNull(newest, "the harvest must be committed for the live path to work");
        List<String> lines = Files.readAllLines(newest);
        List<Integer> all = FaabBid.readPrices(lines, "ALL");
        List<Integer> contested = FaabBid.readPrices(lines, "CONTESTED");
        assertTrue(all.size() > 1000, "five seasons of this league: " + all.size());
        assertTrue(contested.size() < all.size() && contested.size() > 100);
        assertTrue(new FaabBid.Band("x", 0, 1, contested).quantile(0.5)
                >= new FaabBid.Band("x", 0, 1, all).quantile(0.5),
                "a contested claim is never cheaper at the median than an average one");
    }
}
