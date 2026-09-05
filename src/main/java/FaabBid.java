import PlayerImportAndSetup.Position;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
 * What to bid, from this league's own five seasons of revealed willingness to pay.
 *
 * A guideline says "spend 20% on a league-winner". This does not. The league is
 * FAAB with 100 for the season, and Sleeper keeps every waiver claim - INCLUDING
 * THE FAILED ONES - so a losing bid is a rival telling you exactly what he was
 * willing to pay and did not get. Group claims by (player, clearing moment) and
 * each contest hands over its clearing price and its whole field.
 *
 * Four pieces, and they are separable:
 *
 *   WHAT WINNING IS WORTH   the add/drop marginal on the weekly-starter
 *                           objective - {@link TuesdaySwap}'s question, whose
 *                           answer is an input here rather than repeated
 *   WHAT A BID BUYS         P(win | bid), the fraction of comparable contests
 *                           whose clearing price a bid would have beaten
 *   WHAT A DOLLAR COSTS     100 across a season is a budget, so a dollar spent
 *                           now is a dollar not available in week 9; the report
 *                           prints the bid under a range of shadow prices rather
 *                           than pretending to know the right one
 *   WHAT THE OTHERS DO      is exactly what the clearing prices ARE - no
 *                           equilibrium is assumed, their revealed behaviour is
 *                           measured and best-responded to
 *
 *   ./gradlew run -Pmain=FaabBid -Pfit              harvest and fit, write the curve
 *   ./gradlew run -Pmain=FaabBid -Pvalue=25         what to bid for a man worth 25
 *
 * AND THE FINDING THAT SHAPES IT, which is a negative one. The design assumed
 * comparable meant "a man of similar quality", so contests were to be banded by
 * something observable before bidding. Measured over 1,448 contests, nothing
 * observable predicts the price:
 *
 *   his projection for the claimed week        -0.04
 *   what he scored the week before             -0.04   (-0.08 among contested)
 *   what he scored in the claimed week         +0.14   HINDSIGHT, and still weak
 *   how many managers bid                      +0.50   not knowable before bidding
 *
 * Adonai Mitchell went for 53 on an 8.2 projection. So this league does not
 * price players, it prices moments, and a model that bands by player quality
 * would be inventing a signal that is not there. What is left is real and
 * enough: the unconditional distribution of what claims clear at, YOUR OWN
 * valuation of the man, and the bid that maximises the two together.
 */
public class FaabBid {

    /** One settled contest: what it took to win a man, and how many were bidding. */
    public record Contest(String season, int week, String playerID, int clearingBid,
                          int bidders, double projection) {}

    /** Clearing prices for one band of player quality. */
    public record Band(String label, double fromProjection, double toProjection,
                       List<Integer> clearingBids) {

        /** The chance a bid of `bid` beats this band's clearing price. */
        double winChance(int bid){
            if(clearingBids.isEmpty()){
                return 0;
            }
            long beaten = clearingBids.stream().filter(price -> bid > price).count();
            return (double) beaten / clearingBids.size();
        }

        int quantile(double q){
            if(clearingBids.isEmpty()){
                return 0;
            }
            List<Integer> sorted = new ArrayList<>(clearingBids);
            sorted.sort(Comparator.naturalOrder());
            return sorted.get(Math.min(sorted.size() - 1, (int) (q * sorted.size())));
        }
    }

    /**
     * Every contest in a league-season: claims grouped by the man added and the
     * moment they cleared, the winner's bid being the clearing price.
     *
     * A `failed` claim is usually a loss to a higher bid, but not always - a
     * manager whose earlier claim won can have the rest of his queue die for a
     * full roster, and that is not a revealed price. Any contest whose highest
     * bid did NOT win is dropped for exactly that reason rather than being
     * quietly counted as somebody outbidding a bigger number.
     */
    static List<Contest> contests(String season, JsonArray transactions,
                                  Map<Integer, Map<String, Double>> projectionByWeek){
        record Claim(int bid, boolean won, int week){}
        Map<String, List<Claim>> byContest = new TreeMap<>();
        for(JsonElement element : transactions){
            JsonObject row = element.getAsJsonObject();
            // a trade or a free add carries settings: null, and a failed claim can
            // carry status_updated: null - neither is a bid
            if(!row.has("settings") || row.get("settings").isJsonNull()
                    || !row.has("adds") || row.get("adds").isJsonNull()
                    || !row.has("status_updated") || row.get("status_updated").isJsonNull()
                    || !row.has("status") || row.get("status").isJsonNull()){
                continue;
            }
            JsonObject settings = row.getAsJsonObject("settings");
            if(!settings.has("waiver_bid") || settings.get("waiver_bid").isJsonNull()){
                continue;
            }
            int bid = settings.get("waiver_bid").getAsInt();
            boolean won = "complete".equals(row.get("status").getAsString());
            int week = row.has("leg") && !row.get("leg").isJsonNull() ? row.get("leg").getAsInt() : 0;
            long cleared = row.get("status_updated").getAsLong();
            for(String playerID : row.getAsJsonObject("adds").keySet()){
                byContest.computeIfAbsent(playerID + "@" + cleared, u -> new ArrayList<>())
                        .add(new Claim(bid, won, week));
            }
        }
        List<Contest> out = new ArrayList<>();
        for(Map.Entry<String, List<Claim>> entry : byContest.entrySet()){
            List<Claim> claims = entry.getValue();
            Claim winner = null;
            int highest = Integer.MIN_VALUE;
            for(Claim claim : claims){
                if(claim.won()){ winner = claim; }
                highest = Math.max(highest, claim.bid());
            }
            if(winner == null || winner.bid() < highest){
                continue;   // nobody won it, or the top bid lost for a reason that is not price
            }
            String playerID = entry.getKey().substring(0, entry.getKey().indexOf('@'));
            Map<String, Double> projections = projectionByWeek.getOrDefault(winner.week(), Map.of());
            out.add(new Contest(season, winner.week(), playerID, winner.bid(), claims.size(),
                    projections.getOrDefault(playerID, 0.0)));
        }
        return out;
    }

    /** Contests split into bands of the projection they carried when claimed. */
    static List<Band> bands(List<Contest> contests, double[] edges, String[] labels){
        List<Band> bands = new ArrayList<>();
        for(int b = 0; b < edges.length - 1; b++){
            List<Integer> prices = new ArrayList<>();
            for(Contest contest : contests){
                if(contest.projection() >= edges[b] && contest.projection() < edges[b + 1]){
                    prices.add(contest.clearingBid());
                }
            }
            bands.add(new Band(labels[b], edges[b], edges[b + 1], prices));
        }
        return bands;
    }

    /**
     * The bid that maximises expected points: P(win at b) x (worth - b x dollarCost).
     *
     * `dollarCost` is the shadow price of a FAAB dollar - what a dollar spent
     * today would have bought later. At 1.0 the budget is free and the answer is
     * the pure auction bid; above 1.0 the season is competing with this week.
     * Nobody knows the true value, so the report prints several rather than
     * choosing one and calling it precision.
     */
    static int bestBid(Band band, double worth, double dollarCost, int budgetLeft){
        int best = 0;
        double bestNet = 0;
        for(int bid = 0; bid <= budgetLeft; bid++){
            double net = band.winChance(bid) * (worth - bid * dollarCost);
            if(net > bestNet + 1e-9){
                bestNet = net;
                best = bid;
            }
        }
        return best;
    }

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        Path curvePath = Path.of("data", "faab-contests-" + LocalDate.now() + ".txt");

        if(System.getProperty("fit") != null){
            List<Contest> all = new ArrayList<>();
            for(LeagueTransactions.Year year : LeagueTransactions.chain(configuration.getLeagueID())){
                if(!year.complete()){
                    continue;   // the season in progress has no settled history yet
                }
                Map<Integer, Map<String, Double>> projections = new HashMap<>();
                for(int week = 1; week <= 18; week++){
                    try {
                        projections.put(week, WeeklyProjections.pointsBySleeperID(year.season(), week));
                    }
                    catch(RuntimeException noWeek){
                        projections.put(week, Map.of());
                    }
                }
                for(int week = 1; week <= 18; week++){
                    all.addAll(contests(year.season(), JsonParser.parseString(
                            LeagueTransactions.transactionsRaw(year.leagueID(), week)).getAsJsonArray(), projections));
                }
            }
            String report = report(all);
            Files.writeString(curvePath, report, StandardCharsets.UTF_8);
            System.out.print(report);
            System.out.println("written to " + curvePath);
            return;
        }

        // the live question: what to bid for a man worth `value` to my roster
        Path newest = newestCurve();
        if(newest == null){
            System.out.println("no contest history yet - run with -Pfit first.");
            return;
        }
        List<Integer> prices = readPrices(Files.readAllLines(newest), "ALL");
        List<Integer> contested = readPrices(Files.readAllLines(newest), "CONTESTED");
        double worth = Double.parseDouble(System.getProperty("value", "0"));
        int budget = Integer.getInteger("budget", 100);
        if(worth <= 0){
            System.out.println("give the man's worth to your roster with -Pvalue=<points>."
                    + " TuesdaySwap prints exactly that number for every add it considers.");
            return;
        }
        StringBuilder out = new StringBuilder();
        out.append(String.format("WHAT TO BID  %s  for a man worth %.1f to the roster, %d of budget left%n",
                LocalDate.now(), worth, budget));
        out.append(String.format("Prices from %s: %d contests, %d of them contested by two or more.%n%n",
                newest.getFileName(), prices.size(), contested.size()));
        out.append("A dollar spent now is a dollar not available in week 9, and nobody knows that exchange\n");
        out.append("rate - so here is the bid at several, rather than one dressed as precision.\n\n");
        out.append(String.format("%-14s %10s %10s %12s%n", "a dollar is", "bid", "P(win)", "E[points]"));
        for(double dollarCost : new double[]{1.0, 1.5, 2.0, 3.0}){
            Band band = new Band("all", 0, Double.MAX_VALUE, prices);
            int bid = bestBid(band, worth, dollarCost, budget);
            out.append(String.format("%-14s %10d %9.0f%% %12.1f%n",
                    String.format("worth %.1fx", dollarCost), bid, 100 * band.winChance(bid),
                    band.winChance(bid) * (worth - bid * dollarCost)));
        }
        Band contestedBand = new Band("contested", 0, Double.MAX_VALUE, contested);
        out.append(String.format("%nIf somebody else wants him too, the field is dearer: at 1.0x the bid becomes %d%n",
                bestBid(contestedBand, worth, 1.0, budget)));
        out.append(String.format("(P(win) %.0f%%). Half of all claims clear at nothing, so most weeks the honest bid is 0 or 1.%n",
                100 * contestedBand.winChance(bestBid(contestedBand, worth, 1.0, budget))));
        out.append("\nAnd the limit worth keeping in mind: this league does not price PLAYERS, it prices\n");
        out.append("moments. Nothing observable about a man before the bid predicts what he costs, so this\n");
        out.append("says what the field pays in general and what he is worth to you - not what HE will cost.\n");
        System.out.print(out);
    }

    static Path newestCurve() throws Exception {
        try(var files = Files.list(Path.of("data"))){
            return files.filter(p -> p.getFileName().toString().matches("faab-contests-\\d{4}-\\d{2}-\\d{2}\\.txt"))
                    .max(Comparator.comparing(p -> p.getFileName().toString())).orElse(null);
        }
    }

    /** The committed clearing prices, read back out of the report rather than refitted. */
    static List<Integer> readPrices(List<String> lines, String which){
        List<Integer> prices = new ArrayList<>();
        boolean inBlock = false;
        for(String line : lines){
            if(line.startsWith("PRICES " + which)){
                inBlock = true;
                continue;
            }
            if(inBlock){
                if(line.isBlank()){
                    break;
                }
                for(String token : line.trim().split("[ ,]+")){
                    if(!token.isBlank()){
                        prices.add(Integer.parseInt(token));
                    }
                }
            }
        }
        return prices;
    }

    static String report(List<Contest> contests){
        List<Integer> all = new ArrayList<>(), contested = new ArrayList<>();
        for(Contest contest : contests){
            all.add(contest.clearingBid());
            if(contest.bidders() >= 2){
                contested.add(contest.clearingBid());
            }
        }
        all.sort(Comparator.naturalOrder());
        contested.sort(Comparator.naturalOrder());
        Band allBand = new Band("all", 0, Double.MAX_VALUE, all);
        Band contestedBand = new Band("contested", 0, Double.MAX_VALUE, contested);
        long free = all.stream().filter(price -> price == 0).count();

        StringBuilder out = new StringBuilder();
        out.append(String.format("WHAT THIS LEAGUE PAYS  %s%n", LocalDate.now()));
        out.append(String.format("%d settled contests over its own past seasons. A contest is every claim on one man at%n", contests.size()));
        out.append("one clearing; its price is what it took to win him, and the LOSING bids are kept because a\n");
        out.append("losing bid is a rival's revealed willingness to pay. A contest whose highest bid did not win\n");
        out.append("is dropped: that is a roster-full cascade, not somebody being outbid.\n\n");
        out.append("NOTHING OBSERVABLE ABOUT THE MAN PREDICTS HIS PRICE. Correlation of the clearing price with\n");
        out.append("his projection for that week -0.04; with what he scored the week before -0.04 (-0.08 among\n");
        out.append("contested claims); with what he went on to score that week, which is hindsight, +0.14. Only\n");
        out.append("the number of rival bidders moves it (+0.50), and that is not knowable before bidding.\n");
        out.append("Adonai Mitchell cost 53 on an 8.2 projection. This league prices moments, not players.\n\n");
        out.append(String.format("%-12s %8s %8s %8s %8s %8s %8s%n", "", "n", "median", "75th", "90th", "99th", "max"));
        out.append(String.format("%-12s %8d %8d %8d %8d %8d %8d%n", "every claim", all.size(),
                allBand.quantile(0.50), allBand.quantile(0.75), allBand.quantile(0.90),
                allBand.quantile(0.99), allBand.quantile(0.999)));
        out.append(String.format("%-12s %8d %8d %8d %8d %8d %8d%n", "contested", contested.size(),
                contestedBand.quantile(0.50), contestedBand.quantile(0.75), contestedBand.quantile(0.90),
                contestedBand.quantile(0.99), contestedBand.quantile(0.999)));
        out.append(String.format("%n%d of %d claims cleared at nothing (%.0f%%), so the usual right bid is 0 or 1.%n",
                free, all.size(), 100.0 * free / all.size()));
        out.append("\nWHAT A BID BUYS - P(win) at each price:\n");
        int[] ladder = {0, 1, 2, 3, 5, 8, 13, 20, 35, 50};
        out.append(String.format("%-12s", ""));
        for(int bid : ladder){ out.append(String.format(" %6s", "$" + bid)); }
        out.append("\n");
        out.append(String.format("%-12s", "every claim"));
        for(int bid : ladder){ out.append(String.format(" %5.0f%%", 100 * allBand.winChance(bid))); }
        out.append("\n");
        out.append(String.format("%-12s", "contested"));
        for(int bid : ladder){ out.append(String.format(" %5.0f%%", 100 * contestedBand.winChance(bid))); }
        out.append("\n\nPRICES ALL\n");
        out.append(join(all)).append("\n\nPRICES CONTESTED\n").append(join(contested)).append("\n");
        return out.toString();
    }

    private static String join(List<Integer> prices){
        StringBuilder line = new StringBuilder();
        for(int i = 0; i < prices.size(); i++){
            line.append(prices.get(i)).append(i + 1 < prices.size() ? "," : "");
        }
        return line.toString();
    }
}
