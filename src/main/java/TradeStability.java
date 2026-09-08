import PlayerImportAndSetup.Position;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * IS THE TRADE I AM RECOMMENDING BIGGER THAN THE YARDSTICK'S OWN WOBBLE?
 *
 * {@link ObjectiveStability} answered that for a ROSTER MARGINAL - one man in
 * or out - and measured the worst seed-to-seed spread at 6.8 points over 480
 * drawn seasons. That number then went to work as a floor in two other places,
 * and neither of them is measuring the same quantity:
 *
 *   - the trades board applies NO floor at all. It keeps anything above +0.05,
 *     so a trade worth a fifth of the noise is printed beside one worth twice
 *     it, in the same column, with nothing to tell them apart.
 *   - the board runs at 240 drawn seasons, not the 480 the 6.8 came from, so
 *     even where the floor IS applied it is one population's number against
 *     another population's yardstick - the mistake this repo has now made
 *     three times (#79, #81, #101).
 *
 * A trade is not a marginal. It moves two men at once, in opposite directions,
 * and the two changes can reinforce or cancel; there is no reason its noise
 * should match a single man's, and no measurement said it did. So: take the
 * board's best trades, re-value those SAME trades under several seeds, and
 * report the spread. Re-searching under each seed would confound valuation
 * noise with search noise, which is a different question.
 *
 * The recommendation this was built to check is worth +7.4 against a floor of
 * 6.8. If the spread here is larger than 0.6 the margin is not real, and it is
 * better to know that than to send the message.
 *
 *   ./gradlew run -Pmain=TradeStability [-Pscenarios=240] [-Pseeds=3] [-Ptop=8]
 */
public class TradeStability {

    /** One trade, valued once per seed. */
    public record Line(String give, String get, String with, double[] mine, double[] theirs) {

        static double spread(double[] values){
            double low = Double.MAX_VALUE, high = -Double.MAX_VALUE;
            for(double v : values){
                low = Math.min(low, v);
                high = Math.max(high, v);
            }
            return high - low;
        }

        public double mySpread(){
            return spread(mine);
        }

        public double theirSpread(){
            return spread(theirs);
        }

        /** Does this trade survive its own measurement error, against `floor`? */
        public boolean survives(double floor){
            double lowest = Double.MAX_VALUE;
            for(double v : mine){
                lowest = Math.min(lowest, v);
            }
            return lowest > floor;
        }
    }

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int scenarios = Integer.getInteger("scenarios", 240);
        int seeds = Integer.getInteger("seeds", 3);
        int top = Integer.getInteger("top", 8);
        int pool = Integer.getInteger("pool", 6);
        double floor = Double.parseDouble(System.getProperty("tradeFloor", "6.8"));
        long[] seedValues = {424_242L, 7L, 99L, 2026L, 31_337L};

        Map<String, Double> points = ProjectionSources.resolve("sleeper");
        Map<String, String> ownerOf = LeagueOwners.today(configuration);
        String me = System.getProperty("me", configuration.getUserIDToDisplayName()
                .getOrDefault(configuration.getMyID(), configuration.getMyID()));

        Map<String, List<String>> rosters = new TreeMap<>();
        Map<String, String> nameOf = new HashMap<>();
        Map<String, Position> positionOf = new HashMap<>();
        for(Map.Entry<String, String> entry : ownerOf.entrySet()){
            rosters.computeIfAbsent(entry.getValue(), u -> new ArrayList<>()).add(entry.getKey());
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            nameOf.put(entry.getKey(), player == null ? entry.getKey()
                    : player.firstName + " " + player.lastName);
            positionOf.put(entry.getKey(), player == null ? null : player.position);
        }
        for(List<String> roster : rosters.values()){
            roster.sort(Comparator.comparingDouble((String id) -> -points.getOrDefault(id, 0.0)));
        }

        // WHO IS WORTH KEEPING, so a row that sends one can say so. This tool
        // does not price keepers into its numbers - deliberately, since they add
        // no noise - which is exactly why the table needs to name them: a reader
        // scanning for the biggest gain would otherwise be handed a trade that
        // costs next season.
        Map<String, Double> keeperSurplus = new java.util.TreeMap<>();
        try {
            Map<String, Integer> keeperRound = new HashMap<>();
            for(String manager : rosters.keySet()){
                String user = configuration.getUserIDToDisplayName().entrySet().stream()
                        .filter(e -> e.getValue().equals(manager)).map(Map.Entry::getKey)
                        .findFirst().orElse(manager);
                try {
                    for(Keeper keeper : KeeperChooser.eligibleCandidates(configuration, user)){
                        keeperRound.put(keeper.player.sleeperIDString, keeper.roundCanBeKept);
                    }
                }
                catch(RuntimeException notPriceable){
                    // no keeper column for that manager; stated, not fatal
                }
            }
            Map<Position, java.util.TreeMap<Double, Double>> bestByAdp =
                    TradeMarket.bestStillAvailable(points, positionOf);
            Map<String, Integer> slotOfPlayer = TradeMarket.draftSlotOfPlayer(
                    LeagueOwners.today(configuration), configuration);
            for(String id : keeperRound.keySet()){
                double surplus = TradeMarket.keeperPoints(keeperRound, points, bestByAdp,
                        positionOf, configuration, slotOfPlayer, id);
                if(surplus > 0){
                    keeperSurplus.put(nameOf.getOrDefault(id, id), surplus);
                }
            }
        }
        catch(RuntimeException noKeepers){
            System.out.println("keeper surplus unavailable; rows will not be marked");
        }

        // the board under the first seed, which is the board the page shows
        WeeklyStarterValue first = WeeklyStarterValue.forCurrentBoard(
                configuration, points, scenarios, seedValues[0]);
        TradeMarket.Side mySideOne = TradeMarket.scoring(ids -> first.of(ids));
        List<TradeMarket.Trade> board = new ArrayList<>();
        for(Map.Entry<String, List<String>> entry : rosters.entrySet()){
            if(entry.getKey().equals(me)){
                continue;
            }
            board.addAll(TradeMarket.between(me, entry.getKey(), rosters.get(me), entry.getValue(),
                    mySideOne, mySideOne, pool));
            board.addAll(TradeMarket.unbalanced(me, entry.getKey(), rosters.get(me),
                    entry.getValue(), mySideOne, mySideOne, pool, points));
        }
        // MUTUALLY GOOD ONLY, which is the population the recommendations come
        // from. The first version of this measured the top of the raw board -
        // trades worth +241 to Justin because the other manager was handing him
        // Gibbs and Nacua for Tre' Harris. Nobody accepts those, so their noise
        // says nothing about the noise in an offer worth +7.4, and spread very
        // plausibly scales with magnitude. Measuring the stability of a
        // recommendation means measuring it on the trades that could BE one.
        List<TradeMarket.Trade> good = TradeMarket.mutual(board);
        good.sort(Comparator.comparingDouble(TradeMarket.Trade::myGain).reversed());
        List<TradeMarket.Trade> chosen = good.subList(0, Math.min(top, good.size()));

        // THE SAME TRADES, re-valued. Not re-searched: re-searching would let the
        // best trade change between seeds and measure something else entirely.
        Map<TradeMarket.Trade, double[]> mine = new java.util.IdentityHashMap<>();
        Map<TradeMarket.Trade, double[]> theirs = new java.util.IdentityHashMap<>();
        for(TradeMarket.Trade trade : chosen){
            mine.put(trade, new double[seeds]);
            theirs.put(trade, new double[seeds]);
        }
        for(int s = 0; s < seeds; s++){
            WeeklyStarterValue value = WeeklyStarterValue.forCurrentBoard(
                    configuration, points, scenarios, seedValues[s % seedValues.length]);
            TradeMarket.Side side = TradeMarket.scoring(ids -> value.of(ids));
            for(TradeMarket.Trade trade : chosen){
                mine.get(trade)[s] = side.gain(rosters.get(me), trade.give(), trade.get());
                theirs.get(trade)[s] = side.gain(rosters.get(trade.withManager()),
                        trade.get(), trade.give());
            }
        }

        List<Line> lines = new ArrayList<>();
        for(TradeMarket.Trade trade : chosen){
            lines.add(new Line(TradeMarket.label(trade.give(), nameOf),
                    TradeMarket.label(trade.get(), nameOf), trade.withManager(),
                    mine.get(trade), theirs.get(trade)));
        }

        double worst = 0;
        for(Line line : lines){
            worst = Math.max(worst, line.mySpread());
        }

        StringBuilder out = new StringBuilder();
        out.append(String.format("TRADE STABILITY  %s  (%d scenarios, %d seeds, top %d trades)%n%n",
                LocalDate.now(), scenarios, seeds, chosen.size()));
        out.append("THIS MEASURES NOISE, NOT DESIRABILITY. It values both sides season-only and symmetric,\n");
        out.append("because spread is what it is after, and keeper value is deterministic - it shifts a mean\n");
        out.append("and adds no wobble. So the trades below are NOT recommendations: a row can look strong\n");
        out.append("here and give away a man worth fifty points as a keeper next March. Rows that send one\n");
        out.append("are marked. The board that recommends is the console, which prices keepers on both sides.\n\n");
        out.append("The trades board applies no noise floor - it keeps anything above +0.05. This asks\n");
        out.append("whether the numbers it prints survive re-drawing the seasons underneath them. Each\n");
        out.append("column is one seed; the SAME trades are re-valued, never re-searched, and only\n");
        out.append("MUTUALLY GOOD trades are measured - the ones that could actually be recommended.\n\n");
        out.append(String.format("%-46s %s   %7s  %s%n", "TRADE", "YOUR GAIN BY SEED", "SPREAD", "VERDICT"));
        for(Line line : lines){
            String keeper = keeperWarning(line.give(), keeperSurplus, nameOf);
            out.append(String.format("%-46s", trim(line.give() + " -> " + line.get()
                    + (keeper.isEmpty() ? "" : keeper), 46)));
            for(double v : line.mine()){
                out.append(String.format(" %+7.1f", v));
            }
            out.append(String.format("   %6.1f  %s%n", line.mySpread(),
                    line.survives(floor) ? "clears " + floor + " on every seed"
                            : line.mySpread() > floor ? "SPREAD EXCEEDS THE FLOOR - not a number"
                            : "inside the floor on at least one seed"));
        }
        out.append(String.format("%nworst seed-to-seed spread of a TRADE gain: %.1f points%n", worst));
        out.append(String.format("ObjectiveStability measured 6.8 for a roster MARGINAL at 480 scenarios. This is a%n"
                + "different quantity at a different scenario count, and the two should not be assumed%n"
                + "equal - a trade moves two men in opposite directions and the errors need not cancel.%n"));

        Path target = Path.of("data", "trade-stability-" + LocalDate.now() + ".txt");
        Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
        System.out.print(out);
        System.out.println("written to " + target);
    }

    /** Names a man in the outgoing side who is worth keeping, or empty. */
    static String keeperWarning(String give, Map<String, Double> surplusByName,
                                Map<String, String> nameOf){
        for(Map.Entry<String, Double> entry : surplusByName.entrySet()){
            if(entry.getValue() > 25 && give.contains(entry.getKey())){
                return "  [KEEPER]";
            }
        }
        return "";
    }

    private static String trim(String text, int width){
        return text.length() <= width ? text : text.substring(0, width - 1) + "…";
    }
}
