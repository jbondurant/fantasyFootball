import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The page's correctness claim, checked rather than asserted.
 *
 * {@link LeagueConsole} ships an answer SPACE - every bid it can be asked for,
 * every swap it can show - and the page only looks things up. That is what makes
 * this test possible: every number on the page came from Java, so every number
 * can be re-derived here from the same code and compared. A page that computed
 * its own answers in JavaScript could not be checked this way at all, which is
 * the argument for building it this way rather than the other.
 *
 * If this fails, the page is lying about something and should not be trusted
 * until it passes.
 */
public class LeagueConsoleTest {

    /**
     * The newest generated console, or empty if none has been built.
     *
     * BY SEASON AND WEEK AS NUMBERS, not as filename text. The week is not
     * zero-padded, so string order puts console-2026-w9 above console-2026-w10
     * through w18: from week 10 on, every test in this file would have
     * re-derived a nine-week-old page and passed green while the page actually
     * shipped went unchecked. The pages are committed and accumulate, so the
     * stale one is always there to be picked.
     */
    static Path newestConsole() throws Exception {
        Pattern named = Pattern.compile("console-(\\d{4})-w(\\d+)\\.html");
        try(var files = Files.list(Path.of("data"))){
            return files.filter(p -> named.matcher(p.getFileName().toString()).matches())
                    .max(Comparator.comparingLong(p -> {
                        Matcher m = named.matcher(p.getFileName().toString());
                        if(!m.matches()){
                            return Long.MIN_VALUE;
                        }
                        return Long.parseLong(m.group(1)) * 100 + Long.parseLong(m.group(2));
                    })).orElse(null);
        }
    }

    /** The comparator above, exercised on the order that would have broken it. */
    @Test
    public void theNewestConsoleIsTheNewestAndNotTheAlphabeticallyLast(){
        Pattern named = Pattern.compile("console-(\\d{4})-w(\\d+)\\.html");
        List<String> names = List.of("console-2026-w1.html", "console-2026-w9.html",
                "console-2026-w10.html", "console-2026-w18.html", "console-2027-w2.html");
        String picked = names.stream().max(Comparator.comparingLong(name -> {
            Matcher m = named.matcher(name);
            return m.matches() ? Long.parseLong(m.group(1)) * 100 + Long.parseLong(m.group(2))
                    : Long.MIN_VALUE;
        })).orElseThrow();
        assertEquals("console-2027-w2.html", picked, "a later season must win");
        assertEquals("console-2026-w18.html", names.stream().filter(n -> n.contains("2026"))
                .max(Comparator.comparingLong(name -> {
                    Matcher m = named.matcher(name);
                    return m.matches() ? Long.parseLong(m.group(2)) : Long.MIN_VALUE;
                })).orElseThrow(), "w18 beats w9, which string order does not");
    }

    /** Every {"v":..,"b":..,"c":..,"bid":..,"win":..} the page shipped. */
    static List<double[]> shippedBids(String page){
        List<double[]> rows = new ArrayList<>();
        Matcher m = Pattern.compile(
                "\\{\"v\":(\\d+),\"b\":(\\d+),\"c\":(\\d+),\"bid\":(\\d+),\"win\":([\\d.]+)\\}").matcher(page);
        while(m.find()){
            // group 5 IS captured and IS kept. It used to be parsed and dropped,
            // which left 5,124 win chances - the "chance of winning him" the page
            // actually shows - shipped with nothing re-deriving them. Rewriting
            // every one of them to 0.99 left all six tests green.
            rows.add(new double[]{ Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)),
                    Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4)),
                    Double.parseDouble(m.group(5)) });
        }
        return rows;
    }

    @Test
    public void everyBidOnThePageIsTheBidTheModelComputes() throws Exception {
        Path console = newestConsole();
        assumeBuilt(console);
        String page = Files.readString(console);
        List<double[]> shipped = shippedBids(page);
        assertEquals(61 * 21 * 4, shipped.size(),
                "the page must ship the whole answer space, not a sample of it");

        Path prices = FaabBid.newestCurve();
        assertNotNull(prices, "the contest history the page priced from must be committed");
        FaabBid.Band band = new FaabBid.Band("all", 0, Double.MAX_VALUE,
                FaabBid.readPrices(Files.readAllLines(prices), "ALL"));
        double[] costs = {1.0, 1.5, 2.0, 3.0};

        int checked = 0;
        for(double[] row : shipped){
            int budget = (int) row[1];
            int expected = FaabBid.bestBid(band, row[0], costs[(int) row[2]], budget);
            assertEquals(expected, (int) row[3],
                    "the page offers $" + (int) row[3] + " for a man worth " + row[0]
                            + " with $" + budget + " left at " + costs[(int) row[2]]
                            + "x, but the model says $" + expected);
            assertEquals(band.winChance(expected), row[4], 0.005,
                    "and the chance it prints beside that $" + expected + " bid must be the band's own");
            checked++;
        }
        assertEquals(shipped.size(), checked);
    }

    /**
     * The win ladder is the whole of the page's second wire table, and until now
     * no test read it. Both columns are re-derived from the same bands the
     * emitter used.
     */
    @Test
    public void bothWinLaddersAreTheBandsOwnNumbers() throws Exception {
        Path console = newestConsole();
        assumeBuilt(console);
        String page = Files.readString(console);
        Path prices = FaabBid.newestCurve();
        assertNotNull(prices, "the contest history the page priced from must be committed");
        List<String> lines = Files.readAllLines(prices);
        FaabBid.Band all = new FaabBid.Band("all", 0, Double.MAX_VALUE,
                FaabBid.readPrices(lines, "ALL"));
        FaabBid.Band contested = new FaabBid.Band("contested", 0, Double.MAX_VALUE,
                FaabBid.readPrices(lines, "CONTESTED"));

        for(String which : List.of("winAll", "winContested")){
            Matcher array = Pattern.compile("\"" + which + "\":\\[(.*?)\\]").matcher(page);
            assertTrue(array.find(), "the page must ship its " + which + " ladder");
            Matcher entry = Pattern.compile("\\{\"bid\":(\\d+),\"win\":([\\d.]+)\\}").matcher(array.group(1));
            FaabBid.Band band = which.equals("winAll") ? all : contested;
            int seen = 0;
            while(entry.find()){
                int bid = Integer.parseInt(entry.group(1));
                assertEquals(band.winChance(bid), Double.parseDouble(entry.group(2)), 0.005,
                        which + " says a $" + bid + " bid wins " + entry.group(2)
                                + ", but the band it came from disagrees");
                seen++;
            }
            assertTrue(seen > 0, which + " shipped an empty ladder");
        }
    }

    /**
     * The shadow prices the page offers in its dropdown must be the ones the
     * grid was actually built over, or the slider indexes a column that was
     * priced at a different cost.
     */
    @Test
    public void theShippedCostsAreTheCostsTheGridWasBuiltFrom() throws Exception {
        Path console = newestConsole();
        assumeBuilt(console);
        String page = Files.readString(console);
        Matcher costs = Pattern.compile("\"costs\":\\[([^\\]]*)\\]").matcher(page);
        assertTrue(costs.find(), "the page must say what shadow prices it priced");
        assertEquals(List.of("1", "1.5", "2", "3"), List.of(costs.group(1).split(",")),
                "the dropdown's costs and the grid's costs are the same four numbers");
        assertEquals(4, shippedBids(page).stream().mapToInt(r -> (int) r[2]).max().orElse(-1) + 1,
                "the grid must carry one column per shipped cost");
    }

    /**
     * The "his keeper" tag is a threshold, and a threshold in the page is a
     * second copy of a model. It used to be `r.hisKeeper>25` in JavaScript
     * beside `hisKeeper > 25` in TradeMarket; tuning the Java one would have
     * silently desynced them.
     */
    @Test
    public void theKeeperTagIsJavasDecisionAndNotThePages() throws Exception {
        Path console = newestConsole();
        assumeBuilt(console);
        String page = Files.readString(console);
        String script = page.substring(page.indexOf("const D ="));
        assertFalse(script.contains("hisKeeper>") || script.contains("hisKeeper >"),
                "the page must not compare hisKeeper to anything; ship the flag instead");
        Matcher row = Pattern.compile("\"hisKeeper\":(-?[\\d.]+),\"hisKeeperTag\":(true|false)").matcher(page);
        int seen = 0;
        while(row.find()){
            assertEquals(TradeMarket.asksForAKeeper(Double.parseDouble(row.group(1))),
                    Boolean.parseBoolean(row.group(2)),
                    "the tag beside a surplus of " + row.group(1) + " is not what TradeMarket says");
            seen++;
        }
        assertTrue(seen > 0, "the page shipped no trades to check the tag on");
    }

    @Test
    public void thePageDoesNoArithmeticOfItsOwn() throws Exception {
        Path console = newestConsole();
        assumeBuilt(console);
        String script = Files.readString(console);
        script = script.substring(script.indexOf("const D ="));
        // the page may format numbers and count rows; it must not price anything.
        // These are the names of the models - if one appears in the script the
        // page has started recomputing instead of looking up.
        for(String forbidden : List.of("winChance", "bestBid", "keeperValue", "bestLineup",
                "flipRate(", "0.5 *", "Math.pow", "surplus *",
                // thresholds the page used to apply itself, each caught by a
                // review rather than by this list - which is the argument for
                // adding to it every time one is found
                "hisKeeper>", "hisKeeper >", "sellers>=", "sellers >=", "sellers<=", "sellers <=")){
            assertFalse(script.contains(forbidden),
                    "the page appears to compute '" + forbidden + "' itself; every number must be"
                            + " precomputed in Java and looked up, or it cannot be checked against the model");
        }
    }

    @Test
    public void theLineupOnThePageAddsUpToTheTotalItPrints() throws Exception {
        Path console = newestConsole();
        assumeBuilt(console);
        String page = Files.readString(console);
        Matcher total = Pattern.compile("\"lineupTotal\":([\\d.]+),\"slots\":(\\d+)").matcher(page);
        assertTrue(total.find(), "the page must state its own lineup total");
        double stated = Double.parseDouble(total.group(1));
        int slots = Integer.parseInt(total.group(2));

        double summed = 0;
        int starters = 0;
        Matcher man = Pattern.compile(
                "\\{\"name\":\"[^\"]*\",\"pos\":\"[^\"]*\",\"proj\":([\\d.]+),\"playing\":(true|false),"
                        + "\"start\":(true|false),\"gap\":[\\d.]+,\"odds\":[\\d.]+\\}").matcher(page);
        while(man.find()){
            if(man.group(3).equals("true")){
                summed += Double.parseDouble(man.group(1));
                starters++;
            }
        }
        assertEquals(slots, starters, "the page's slot count must match the men it marks as starting");
        assertEquals(stated, summed, 0.05,
                "the printed total must be the sum of the men the page says are starting");
    }

    @Test
    public void everyTradeShownHelpsBothSides() throws Exception {
        Path console = newestConsole();
        assumeBuilt(console);
        String page = Files.readString(console);
        Matcher trade = Pattern.compile("\"you\":(-?[\\d.]+),\"him\":(-?[\\d.]+),\"season\":(-?[\\d.]+)").matcher(page);
        int seen = 0;
        while(trade.find()){
            assertTrue(Double.parseDouble(trade.group(1)) > 0,
                    "every trade shown must gain for Justin, and show that it does: " + trade.group(1));
            assertTrue(Double.parseDouble(trade.group(2)) > 0,
                    "and for the other manager, or it is not an offer he takes: " + trade.group(2));
            seen++;
        }
        assertTrue(seen > 0, "the page shipped no trades at all");
    }

    /**
     * The wire table is the part Justin asked for by name: he should not be
     * setting a slider to the number the model exists to compute. So every row
     * ships a worth, and every worth must re-derive to the bid printed beside
     * it - at the budget the page read off the rosters feed, not one he typed.
     */
    @Test
    public void everyWireBidIsTheBidTheModelComputes() throws Exception {
        Path console = newestConsole();
        assumeBuilt(console);
        String page = Files.readString(console);
        Matcher budget = Pattern.compile("\"budget\":(\\d+),").matcher(page);
        assertTrue(budget.find(), "the page must state the FAAB it read, not ask for it");
        int left = Integer.parseInt(budget.group(1));
        assertTrue(left >= 0 && left <= 100, "FAAB left off the rosters feed: " + left);

        Path prices = FaabBid.newestCurve();
        assertNotNull(prices, "the contest history the page priced from must be committed");
        FaabBid.Band band = new FaabBid.Band("all", 0, Double.MAX_VALUE,
                FaabBid.readPrices(Files.readAllLines(prices), "ALL"));

        Matcher row = Pattern.compile("\"worth\":(-?[\\d.]+),\"drop\":\"[^\"]*\","
                + "\"bid\":(\\d+),\"win\":([\\d.]+),\"noise\":(true|false)").matcher(page);
        int seen = 0;
        while(row.find()){
            double worth = Double.parseDouble(row.group(1));
            int bid = Integer.parseInt(row.group(2));
            assertEquals(FaabBid.bestBid(band, worth, 1.5, left), bid,
                    "the page bids $" + bid + " for a man it values at " + worth);
            assertEquals(band.winChance(bid), Double.parseDouble(row.group(3)), 0.005,
                    "the chance printed beside a $" + bid + " bid must be the band's own");
            seen++;
        }
        assertTrue(seen > 0, "the page shipped no wire rows and no way to see there were none");
    }

    /**
     * A gain is compared to the floor ObjectiveStability measured, and that
     * floor was measured over a particular number of drawn seasons. The page
     * must say it used that many, or the comparison is one population's number
     * against another population's yardstick.
     */
    @Test
    public void theWireIsJudgedAgainstItsOwnNoiseFloor() throws Exception {
        Path console = newestConsole();
        assumeBuilt(console);
        String page = Files.readString(console);
        Matcher stated = Pattern.compile("\"swapFloor\":([\\d.]+),\"wireScenarios\":(\\d+)").matcher(page);
        assertTrue(stated.find(), "the page must state the floor it applied and what it computed against");
        double floor = Double.parseDouble(stated.group(1));
        assertEquals(480, Integer.parseInt(stated.group(2)),
                "the wire search must run at the scenario count ObjectiveStability measured the floor over");

        Matcher row = Pattern.compile("\"worth\":(-?[\\d.]+),\"drop\":\"[^\"]*\",\"bid\":\\d+,"
                + "\"win\":[\\d.]+,\"noise\":(true|false),\"hole\":(true|false),"
                + "\"altDrop\":(null|\"[^\"]*\"),\"altWorth\":(null|-?[\\d.]+),"
                + "\"thenAdd\":(null|\"[^\"]*\"),\"thenDrop\":(null|\"[^\"]*\")").matcher(page);
        double previous = Double.MAX_VALUE;
        int seen = 0;
        while(row.find()){
            double worth = Double.parseDouble(row.group(1));
            assertEquals(worth < floor, Boolean.parseBoolean(row.group(2)),
                    "a gain of " + worth + " against a floor of " + floor + " is flagged wrong");
            assertTrue(worth <= previous + 1e-9, "the wire must ship best-first: " + worth + " after " + previous);
            previous = worth;
            boolean hasAlt = !row.group(4).equals("null");
            assertEquals(hasAlt, !row.group(5).equals("null"), "an alternative drop needs its number");
            if(hasAlt){
                assertTrue(Double.parseDouble(row.group(5)) > worth,
                        "an alternative is only worth showing if it gains more than the safe drop");
            }
            // A row that empties a slot must carry the second half of the plan.
            // The old assertion here only ran inside `if(hasAlt)`, where the
            // emitter's own booleans made it true by construction - it restated
            // `hole = free == swap` and `better = free != swap` and could not
            // fail however TradeMarket.slotsFilled behaved.
            if(Boolean.parseBoolean(row.group(3))){
                assertNotEquals("null", row.group(6),
                        "a drop that empties a slot must name the man who refills it");
                assertNotEquals("null", row.group(7),
                        "and the man that second claim displaces, because the roster is full");
            }
            seen++;
        }
        assertTrue(seen > 0, "the page shipped no wire rows");
    }

    /**
     * The ladder is the answer to "is Schultz nearly Fannin?" - he is +4.8
     * against the sixteenth man and -25.1 against Fannin, and both are the same
     * claim. The page and the terminal report must print the identical ladder,
     * because they are the identical search; if they ever differ, one of them is
     * telling Justin something the model did not say.
     */
    @Test
    public void thePagesLadderIsTheTerminalReportsLadder() throws Exception {
        Path console = newestConsole();
        assumeBuilt(console);
        Path report;
        try(var files = Files.list(Path.of("data"))){
            report = files.filter(p -> p.getFileName().toString().matches("tuesday-swap-\\d{4}-w\\d+\\.txt"))
                    .max(Comparator.comparing(p -> p.getFileName().toString())).orElse(null);
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(report != null,
                "no TuesdaySwap report built yet - run -Pmain=TuesdaySwap");

        // the report's ladder: "   drop <name>   +/-N.N[  <- empties a slot; ...]"
        List<String> printed = new ArrayList<>();
        List<Double> printedGains = new ArrayList<>();
        boolean inLadder = false;
        for(String line : Files.readAllLines(report)){
            if(line.startsWith("EVERY DROP FOR ")){
                inLadder = true;
                continue;
            }
            if(inLadder){
                Matcher rung = Pattern.compile("^   drop (.+?)\\s+([+-][\\d.]+)").matcher(line);
                if(!rung.find()){
                    break;
                }
                printed.add(rung.group(1).trim());
                printedGains.add(Double.parseDouble(rung.group(2)));
            }
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(!printed.isEmpty(),
                "the report predates the ladder section");

        String page = Files.readString(console);
        // BOTH ARTIFACTS ARE SNAPSHOTS OF LIVE FEEDS. Sleeper's projections and
        // the FFC ADP file refresh during the day - one refreshed at 13:23 in the
        // middle of this very session - so a page and a report generated hours
        // apart can legitimately be searching different pools. Comparing them
        // then is comparing two different questions, and the failure would read
        // as a model disagreement when it is a clock difference. So: compare
        // only when both name the same best add, and say plainly which it is
        // when they do not.
        Matcher named = Pattern.compile("EVERY DROP FOR ([^\\n]*?) - the same claim").matcher(
                Files.readString(report));
        assertTrue(named.find(), "the report must name the add its ladder is for");
        // ANCHORED ON THE WIRE ROW'S OWN SHAPE. Lineup entries also carry
        // name/pos/proj, and they are emitted first, so an unanchored match
        // reads the top of the LINEUP and compares a starter's name against the
        // ladder's add - which never matches, so the assumption below always
        // skipped and this test never ran once. "worth" appears only on a wire
        // row; that is what makes the match the right array.
        Matcher pageAdd = Pattern.compile(
                "\\{\"name\":\"([^\"]*)\",\"pos\":\"[^\"]*\",\"proj\":-?[\\d.]+,\"worth\"").matcher(page);
        assertTrue(pageAdd.find(), "the page must name its first wire row");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                named.group(1).trim().equalsIgnoreCase(pageAdd.group(1).trim()),
                "report's best add is " + named.group(1) + " but the page's is " + pageAdd.group(1)
                        + " - the feeds moved between the two runs; regenerate both to compare");
        Matcher first = Pattern.compile("\"ladder\":\\[(.*?)\\]").matcher(page);
        assertTrue(first.find(), "the page must ship a ladder with its wire rows");
        List<String> shipped = new ArrayList<>();
        List<Double> shippedGains = new ArrayList<>();
        Matcher rung = Pattern.compile(
                "\\{\"drop\":\"([^\"]*)\",\"gain\":(-?[\\d.]+),").matcher(first.group(1));
        while(rung.find()){
            shipped.add(rung.group(1));
            shippedGains.add(Double.parseDouble(rung.group(2)));
        }
        assertEquals(printed, shipped,
                "the page's ladder and TuesdaySwap's name different men, or name them in a"
                        + " different order. They are the same search over the same roster, so"
                        + " either the page is not calling TuesdaySwap.search any more, or one"
                        + " artifact is stale - regenerate both (-Pmain=TuesdaySwap then"
                        + " -Pmain=LeagueConsole) and look again");
        // TO THE REPORT'S OWN PRECISION, not beyond it. The report prints %+.1f
        // and the page ships %.2f, so 6.8 and 6.76 are the SAME NUMBER written
        // twice. Comparing them as strings called that a model disagreement and
        // sent me looking for a bug in a search that was working perfectly.
        for(int step = 0; step < printed.size(); step++){
            assertEquals(printedGains.get(step), shippedGains.get(step), 0.05,
                    "the two artifacts disagree about " + printed.get(step)
                            + " by more than the report's own rounding");
        }

        double previous = Double.MAX_VALUE;
        for(double gain : shippedGains){
            assertTrue(gain <= previous + 1e-9, "the ladder must be ordered best-first");
            previous = gain;
        }
    }

    /**
     * The "opens up" number is the last running total of the chain shipped
     * beside it, and every step of that chain must clear the floor. This is the
     * column that made a stolen flag look like a working feature: with
     * `depth` reading back 0 every chain was one step deep, so "opens up"
     * printed the immediate gain again and nothing looked wrong.
     */
    @Test
    public void whatATradeOpensUpIsTheChainItShips() throws Exception {
        Path console = newestConsole();
        assumeBuilt(console);
        String page = Files.readString(console);
        Matcher floor = Pattern.compile("\"swapFloor\":([\\d.]+)").matcher(page);
        assertTrue(floor.find(), "the page must state the floor its chains cleared");
        double bar = Double.parseDouble(floor.group(1));

        Matcher row = Pattern.compile(
                "\"you\":(-?[\\d.]+),\"him\":-?[\\d.]+.*?\"reach\":(null|-?[\\d.]+),"
                        + "\"chain\":(null|\\[.*?\\])").matcher(page);
        int priced = 0, deep = 0;
        while(row.find()){
            if(row.group(2).equals("null")){
                assertEquals("null", row.group(3), "a trade with no reach ships no chain");
                continue;
            }
            double reach = Double.parseDouble(row.group(2));
            Matcher step = Pattern.compile("\"gain\":(-?[\\d.]+),\"running\":(-?[\\d.]+)")
                    .matcher(row.group(3));
            double running = 0;
            int steps = 0;
            while(step.find()){
                double gain = Double.parseDouble(step.group(1));
                running += gain;
                assertEquals(running, Double.parseDouble(step.group(2)), 0.02,
                        "step " + (steps + 1) + " running total is not the sum of the gains before it");
                if(steps > 0){
                    assertTrue(gain >= bar - 0.005,
                            "step " + (steps + 1) + " gains " + gain + ", under the " + bar
                                    + " floor the page says every step clears");
                }
                steps++;
            }
            assertTrue(steps >= 1, "a shipped chain must have at least the forced first trade");
            assertEquals(Double.parseDouble(row.group(1)), Double.parseDouble(
                            row.group(3).replaceAll(".*?\"gain\":(-?[\\d.]+).*", "$1")), 0.02,
                    "the chain's first step must be the trade the row is about");
            assertEquals(reach, running, 0.02,
                    "'opens up' must be the chain's last running total");
            priced++;
            if(steps > 1){
                deep++;
            }
        }
        assertTrue(priced > 0, "no trade was priced for what it opens up");
        assertTrue(deep > 0,
                "every shipped chain is one step deep, which is what a stolen -Pdepth looks"
                        + " like: the column would just reprint the immediate gain");
    }

    /**
     * Six numbers, three a side, and they must be consistent with each other.
     *
     * The strong one is HIS full against HIS 2026. His side is priced
     * loss-averse on keepers: {@link TradeMarket#lossAverseOnKeepers} returns his
     * season gain MINUS what leaving costs his own best two keepers, and that
     * cost cannot be negative - removing men from a roster can only lower the
     * best two surpluses on it. So his full number can never exceed his 2026
     * number. If it ever does, the asymmetry has been applied backwards, which
     * is the exact error this repo already shipped once: the first version made
     * rivals CHEAP SELLERS of keepers when the truth is the opposite.
     */
    @Test
    public void bothManagersGetTheSameThreeNumbersAndHisAreConsistent() throws Exception {
        Path console = newestConsole();
        assumeBuilt(console);
        String page = Files.readString(console);
        boolean keepersOn = Pattern.compile("\"keepers\":true").matcher(page).find();

        Matcher row = Pattern.compile(
                "\"you\":(-?[\\d.]+),\"him\":(-?[\\d.]+),\"season\":(-?[\\d.]+),"
                        + "\"hisKeeper\":-?[\\d.]+,\"hisKeeperTag\":(?:true|false),"
                        + "\"simple\":(-?[\\d.]+),\"hole\":(true|false).*?"
                        + "\"himSimple\":(-?[\\d.]+),\"himSeason\":(-?[\\d.]+),"
                        + "\"himHole\":(true|false)").matcher(page);
        int seen = 0;
        while(row.find()){
            double him = Double.parseDouble(row.group(2));
            double himSeason = Double.parseDouble(row.group(7));
            if(keepersOn){
                assertTrue(him <= himSeason + 0.02,
                        "his full gain " + him + " exceeds his 2026 gain " + himSeason
                                + ", but loss aversion on keepers can only subtract - the"
                                + " asymmetry is applied backwards");
            }
            else {
                assertEquals(himSeason, him, 0.02,
                        "with keepers off his two numbers are the same computation");
            }
            seen++;
        }
        assertTrue(seen > 0,
                "no trade shipped all six numbers; the regex found none, which would make"
                        + " this test vacuous rather than passing");
        Matcher any = Pattern.compile("\"give\":").matcher(page);
        int trades = 0;
        while(any.find()){
            trades++;
        }
        assertEquals(trades, seen + countChainSteps(page) + countMirage(page),
                "every trade must ship all six numbers, not just the ones that happened to parse");
    }

    /**
     * His edge must be his gain minus his best alternative, and the headline
     * count must be the rows that are actually negative - not a number typed
     * into prose beside a table that says something else.
     */
    @Test
    public void hisEdgeIsHisGainMinusHisBestElsewhere() throws Exception {
        Path console = newestConsole();
        assumeBuilt(console);
        String page = Files.readString(console);
        Matcher stated = Pattern.compile("\"losesToElsewhere\":(\\d+)").matcher(page);
        assertTrue(stated.find(), "the page must say how many offers lose to his alternatives");

        Matcher row = Pattern.compile("\"him\":(-?[\\d.]+),.*?"
                + "\"hisBest\":(-?[\\d.]+),\"hisBestNaive\":-?[\\d.]+,"
                + "\"hisEdge\":(-?[\\d.]+)").matcher(page);
        int seen = 0, losing = 0;
        while(row.find()){
            double him = Double.parseDouble(row.group(1));
            double best = Double.parseDouble(row.group(2));
            double edge = Double.parseDouble(row.group(3));
            assertEquals(him - best, edge, 0.02,
                    "his edge must be his gain (" + him + ") minus his best elsewhere (" + best + ")");
            assertTrue(best >= 0,
                    "a best alternative is a max over mutually-good trades and cannot be negative:"
                            + " a manager can always decline");
            if(edge < 0){
                losing++;
            }
            seen++;
        }
        assertTrue(seen > 0, "no trade shipped an edge, which would make this test vacuous");
        assertEquals(losing, Integer.parseInt(stated.group(1)),
                "the headline count and the rows disagree about how many offers he does not need");
    }

    /**
     * Two keepers, not three, and the two with the most surplus.
     *
     * The league allows two, so a third-best surplus is worth nothing next
     * March. Marking three would overvalue a man Justin cannot actually keep,
     * and marking the wrong two would tell him to protect the wrong players -
     * a mistake that costs a whole season and is invisible until then.
     */
    @Test
    public void exactlyTheBestTwoKeepersAreMarked() throws Exception {
        Path console = newestConsole();
        assumeBuilt(console);
        String page = Files.readString(console);
        Matcher board = Pattern.compile("\"keepers2027\":\\[(.*?)\\],\"fairTrades\"").matcher(page);
        assertTrue(board.find(), "the page must ship the keeper view");

        Matcher row = Pattern.compile("\\{\"name\":\"([^\"]*)\",\"pos\":\"[^\"]*\","
                + "\"round\":(null|\\d+),\"surplus\":(null|-?[\\d.]+),\"keep\":(true|false)\\}")
                .matcher(board.group(1));
        List<String> kept = new ArrayList<>();
        double previous = Double.MAX_VALUE;
        int rows = 0;
        while(row.find()){
            double surplus = row.group(3).equals("null") ? 0 : Double.parseDouble(row.group(3));
            assertTrue(surplus <= previous + 1e-9,
                    "the keeper table must be ordered by surplus, best first: " + row.group(1));
            previous = surplus;
            if(Boolean.parseBoolean(row.group(4))){
                kept.add(row.group(1));
                assertTrue(surplus > 0,
                        row.group(1) + " is marked KEEP with a surplus of " + surplus
                                + "; a man worth nothing beyond his pick is not a keeper");
                assertNotEquals("null", row.group(2),
                        row.group(1) + " is marked KEEP without a round to keep him at");
            }
            rows++;
        }
        assertTrue(rows > 0, "the keeper view shipped no men, which would make this vacuous");
        assertTrue(kept.size() <= 2,
                "the league allows two keepers; the page marks " + kept.size() + ": " + kept);
    }

    /** The perception-gap board carries "give" keys too, and is not the trades table. */
    private static int countMirage(String page){
        Matcher board = Pattern.compile("\"mirage\":\\[(.*?)\\],\"opticsBar\"").matcher(page);
        if(!board.find()){
            return 0;
        }
        int rows = 0;
        Matcher row = Pattern.compile("\"gap\":").matcher(board.group(1));
        while(row.find()){
            rows++;
        }
        return rows;
    }

    /** Chain steps also carry a "give" key; they are not trades in the table. */
    private static int countChainSteps(String page){
        int steps = 0;
        Matcher chain = Pattern.compile("\"chain\":\\[(.*?)\\]").matcher(page);
        while(chain.find()){
            Matcher step = Pattern.compile("\"running\":").matcher(chain.group(1));
            while(step.find()){
                steps++;
            }
        }
        return steps;
    }

    private static void assumeBuilt(Path console){
        org.junit.jupiter.api.Assumptions.assumeTrue(console != null,
                "no console page built yet - run -Pmain=LeagueConsole");
    }
}
