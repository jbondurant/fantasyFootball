import PlayerImportAndSetup.Position;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DraftWeightCurveTest {

    /** Trades made exactly even at a planted weight: the fit must find that weight, not a neighbour. */
    @Test
    public void thePlantedWeightIsTheOneFound(){
        for(double planted : new double[]{0.15, 0.5, 0.8}){
            Random random = new Random(3);
            List<DraftWeightCurve.Trade> trades = new ArrayList<>();
            while(trades.size() < 40){
                double draftA = 10 + 90 * random.nextDouble(), draftB = 10 + 90 * random.nextDouble();
                double paceB = 120 * random.nextDouble();
                // pace A so the trade is even at the planted weight
                double paceA = (planted * (draftB - draftA) + (1 - planted) * paceB) / (1 - planted);
                if(paceA >= 0){
                    trades.add(new DraftWeightCurve.Trade("2024", "x", 0, 0.5, 3, draftA, draftB, paceA, paceB));
                }
            }
            assertEquals(planted, DraftWeightCurve.best(trades), 0.0101, "planted " + planted);
        }
    }

    /** Real trades even at a weight, random pairings of the same sides not: the check finds it too, and random is less even. */
    @Test
    public void realTradesBeatRandomPairingsAtTheirWeight(){
        Random random = new Random(5);
        List<DraftWeightCurve.Trade> trades = new ArrayList<>();
        while(trades.size() < 60){
            double draftA = 10 + 90 * random.nextDouble(), draftB = 10 + 90 * random.nextDouble();
            double paceB = 120 * random.nextDouble();
            double paceA = (0.4 * (draftB - draftA) + 0.6 * paceB) / 0.6;
            if(paceA >= 0){
                trades.add(new DraftWeightCurve.Trade("2024", "x", 0, 0.5, 3, draftA, draftB, paceA, paceB));
            }
        }
        List<List<DraftWeightCurve.Trade>> shuffles = new ArrayList<>();
        for(int r = 0; r < 20; r++){
            shuffles.add(DraftWeightCurve.shuffled(trades, random));
        }
        assertEquals(0.4, DraftWeightCurve.bestOverRandom(trades, shuffles), 0.0101);
        assertTrue(DraftWeightCurve.randomLoss(shuffles, 0.4) > 5 * DraftWeightCurve.loss(trades, 0.4) + 0.05);
    }

    /** Unevenness is bounded: a side that got nothing on the screen in use is 1, not infinite. */
    @Test
    public void nothingOnOneSideIsOneNotInfinity(){
        DraftWeightCurve.Trade breakout = new DraftWeightCurve.Trade("2024", "x", 0, 0.5, 4, 0, 60, 90, 0);
        assertEquals(1.0, breakout.uneven(1), 1e-9, "on draft value alone he got nothing");
        assertEquals(1.0, breakout.uneven(0), 1e-9, "on pace alone the other side got nothing");
        assertEquals(Math.abs(0.6 * 90 - 0.4 * 60) / (0.6 * 90 + 0.4 * 60), breakout.uneven(0.4), 1e-9);
    }

    /** The curve never rises with games played; a rise is pooled with the week before it. */
    @Test
    public void theCurveNeverRises(){
        assertArrayEquals(new double[]{0.8, 0.65, 0.65, 0.3},
                DraftWeightCurve.nonIncreasing(new double[]{0.8, 0.6, 0.7, 0.3}, new double[]{1, 1, 1, 1}), 1e-9);
        assertArrayEquals(new double[]{0.8, 0.68, 0.68, 0.3},
                DraftWeightCurve.nonIncreasing(new double[]{0.8, 0.6, 0.7, 0.3}, new double[]{1, 1, 4, 1}), 1e-9);
    }

    /** All draft value before a game, straight lines between measured weeks, flat after the last. */
    @Test
    public void theCurveIsReadBetweenAndBeyondItsWeeks(){
        TreeMap<Integer, Double> table = new TreeMap<>(Map.of(1, 0.8, 2, 0.6, 12, 0.2));
        assertEquals(1.0, DraftWeightCurve.at(table, 0), 1e-9);
        assertEquals(0.9, DraftWeightCurve.at(table, 0.5), 1e-9);
        assertEquals(0.8, DraftWeightCurve.at(table, 1), 1e-9);
        assertEquals(0.7, DraftWeightCurve.at(table, 1.5), 1e-9);
        assertEquals(0.4, DraftWeightCurve.at(table, 7), 1e-9);
        assertEquals(0.2, DraftWeightCurve.at(table, 17), 1e-9);
    }

    /**
     * Games played come from the clock, not Sleeper's leg: a trade proposed on
     * the Tuesday after week 3's Monday game was made with three weeks played
     * (its leg says 3, which read as two), and so was one on the Saturday of
     * week 4, after only Thursday's game.
     */
    @Test
    public void gamesPlayedComeFromTheClock(){
        TreeMap<Integer, LocalDateTime> ends = new TreeMap<>();
        ends.put(1, LocalDateTime.parse("2025-09-09T00:15"));
        ends.put(2, LocalDateTime.parse("2025-09-16T00:15"));
        ends.put(3, LocalDateTime.parse("2025-09-23T00:15"));
        ends.put(4, LocalDateTime.parse("2025-09-30T00:15"));
        ZoneId eastern = ZoneId.of("America/New_York");
        long tuesday = ZonedDateTime.of(2025, 9, 23, 10, 20, 0, 0, eastern).toInstant().toEpochMilli();
        long saturday = ZonedDateTime.of(2025, 9, 27, 3, 6, 0, 0, eastern).toInstant().toEpochMilli();
        long sundayOfWeekOne = ZonedDateTime.of(2025, 9, 7, 15, 0, 0, 0, eastern).toInstant().toEpochMilli();
        assertEquals(3, DraftWeightCurve.gamesPlayed(ends, tuesday));
        assertEquals(3, DraftWeightCurve.gamesPlayed(ends, saturday));
        assertEquals(0, DraftWeightCurve.gamesPlayed(ends, sundayOfWeekOne));
    }

    /** A side is what came from the other roster; a man cut for room is not traded; picks, defences and third rosters are out. */
    @Test
    public void aSideIsWhatTheOtherRosterSent(){
        Map<String, Position> positionOf = Map.of("a", Position.WR, "b", Position.RB, "c", Position.TE, "d", Position.DEF);
        JsonObject plain = JsonParser.parseString("{\"roster_ids\":[1,2],\"adds\":{\"a\":1,\"b\":2},"
                + "\"drops\":{\"a\":2,\"b\":1,\"c\":1},\"draft_picks\":[],\"waiver_budget\":[]}").getAsJsonObject();
        assertEquals(List.of(List.of("a"), List.of("b")), DraftWeightCurve.sides(plain, positionOf));

        JsonObject pick = JsonParser.parseString("{\"roster_ids\":[1,2],\"adds\":{\"a\":1,\"b\":2},\"drops\":{\"a\":2,\"b\":1},"
                + "\"draft_picks\":[{\"round\":3}],\"waiver_budget\":[]}").getAsJsonObject();
        assertNull(DraftWeightCurve.sides(pick, positionOf), "a pick is on neither screen");

        JsonObject defence = JsonParser.parseString("{\"roster_ids\":[1,2],\"adds\":{\"a\":1,\"d\":2},\"drops\":{\"a\":2,\"d\":1}}")
                .getAsJsonObject();
        assertNull(DraftWeightCurve.sides(defence, positionOf), "a defence has no draft-value chart");

        JsonObject three = JsonParser.parseString("{\"roster_ids\":[1,2,3],\"adds\":{\"a\":1,\"b\":2,\"c\":3},"
                + "\"drops\":{\"a\":2,\"b\":3,\"c\":1}}").getAsJsonObject();
        assertNull(DraftWeightCurve.sides(three, positionOf), "three rosters have no two sides");
    }
}
