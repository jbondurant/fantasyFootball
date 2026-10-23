import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * STATIC ARBITRAGE ACROSS KALSHI'S NFL PROP CONTRACTS.
 *
 * Justin, 2026-09-26: "what about finding arbitrage inconsistencies across
 * correlated props, within and across apps". Kalshi prices one player's stat
 * as thresholds (a probability), a ladder (linear in the stat: its mean) and
 * an escalator (convex), and prices every player on a team at once. Like
 * option strikes, those prices have to fit together, and where they do not,
 * a portfolio of them pays more in EVERY outcome than it costs. Three checks,
 * each stated as positions, the least the portfolio can pay, what it costs at
 * the posted prices plus fees, and the size the book shows:
 *
 *  1. THRESHOLDS AGAINST EACH OTHER. P(stat &gt;= a) &gt;= P(stat &gt;= b) for a &lt; b.
 *     Buy YES at a and NO at b: pays at least $1 whatever happens.
 *  2. LADDERS AND ESCALATORS AGAINST THE THRESHOLD STRIP. Either payoff is a
 *     sum of step functions, and each step is bracketed by the thresholds
 *     either side of it - so the strip's prices bound the ladder's and the
 *     escalator's with no model at all. Priced outside the bounds, sell the
 *     rich side and buy the cheap side.
 *  3. A QUARTERBACK AGAINST HIS RECEIVERS. Ladders price means, and means
 *     add: every passing yard is some receiver's receiving yard, so the
 *     quarterback's passing yards bound his listed receivers' ladders from
 *     above. NEAR-riskless only: a second passer (injury, blowout, trick
 *     play) credits receivers without crediting him, and a receiver's
 *     negative yards count against the quarterback but floor at zero in his
 *     ladder.
 *
 * Fees are Kalshi's published taker formula, per contract 0.07 x P x (1 - P)
 * (rounded up to the cent per order - small orders pay more). Nothing here
 * trades; a hit is a quote to check by hand, at the size shown, before it moves.
 *
 *     ./gradlew run -Pmain=KalshiArb              (reads live, archives the read)
 *     ./gradlew run -Pmain=KalshiArb -Poffline=true   (the latest archived read)
 */
public class KalshiArb {

    static final double FEE_RATE = 0.07;

    static double fee(double price){
        return FEE_RATE * price * (1 - price);
    }

    /** A payoff as a constant plus steps: pays base + sum of delta where the stat >= at. */
    record Payoff(double base, double[] at, double[] delta) {
        double value(double y){
            double v = base;
            for(int i = 0; i < at.length; i++){
                if(y >= at[i]){
                    v += delta[i];
                }
            }
            return v;
        }
    }

    /** A ladder: perUnit a unit up to the cap, floored at zero. */
    static Payoff ladder(double perUnit, double cap){
        int n = (int) cap;
        double[] at = new double[n];
        double[] delta = new double[n];
        for(int i = 0; i < n; i++){
            at[i] = i + 1;
            delta[i] = perUnit;
        }
        return new Payoff(0, at, delta);
    }

    /** An escalator: schedule[k] for a stat in [k*step, (k+1)*step), the last element from the cap up. */
    static Payoff escalator(double[] schedule, int step){
        int n = schedule.length - 1;
        double[] at = new double[n];
        double[] delta = new double[n];
        for(int k = 1; k <= n; k++){
            at[k - 1] = k * step;
            delta[k - 1] = schedule[k] - schedule[k - 1];
        }
        return new Payoff(schedule[0], at, delta);
    }

    /**
     * The strip that bounds a payoff from above (upper) or below: weights on
     * the threshold contracts at strikes s (ascending) and a constant. A step
     * at t is at most the threshold at the largest strike &lt;= t (or $1 when
     * none is that low), and at least the threshold at the smallest strike
     * >= t (or nothing when none is that high).
     */
    record Strip(double constant, double[] weights) {}

    static Strip bound(Payoff p, int[] strikes, boolean upper){
        double constant = p.base();
        double[] w = new double[strikes.length];
        for(int j = 0; j < p.at().length; j++){
            double t = p.at()[j];
            double d = p.delta()[j];
            if(d == 0){
                continue;
            }
            int chosen = -1;
            if(upper){
                for(int i = 0; i < strikes.length; i++){
                    if(strikes[i] <= t){
                        chosen = i;
                    }
                }
                if(chosen < 0){
                    constant += d;
                }
                else{
                    w[chosen] += d;
                }
            }
            else{
                for(int i = strikes.length - 1; i >= 0; i--){
                    if(strikes[i] >= t){
                        chosen = i;
                    }
                }
                if(chosen >= 0){
                    w[chosen] += d;
                }
            }
        }
        return new Strip(constant, w);
    }

    /** One opportunity: what to hold, the least it pays, what it costs with fees, and the size the book shows. */
    record Hit(String check, String event, String what, double minPayoff, double cost, double fees, double size) {
        double edge(){
            return minPayoff - cost - fees;
        }
    }

    static boolean quoted(Double price){
        return price != null && price > 0 && price < 1;
    }

    /** Check 1 over one player's thresholds on one stat. */
    static List<Hit> thresholds(String event, String who, List<KalshiMarkets.Contract> th){
        List<Hit> out = new ArrayList<>();
        for(KalshiMarkets.Contract a : th){
            for(KalshiMarkets.Contract b : th){
                if(a.strike() >= b.strike() || !quoted(a.yesAsk()) || !quoted(b.noAsk())){
                    continue;
                }
                double cost = a.yesAsk() + b.noAsk();
                double fees = fee(a.yesAsk()) + fee(b.noAsk());
                if(1 - cost - fees > 0){
                    out.add(new Hit("thresholds", event, String.format("%s: buy YES %d+ at %.2f, NO %d+ at %.2f", who, a.strike(), a.yesAsk(),
                            b.strike(), b.noAsk()), 1, cost, fees, Math.min(a.yesAskSize(), b.yesBidSize())));
                }
            }
        }
        return out;
    }

    /** Check 2: one ladder or escalator against its player's threshold strip on the same stat. */
    static List<Hit> strip(String event, String who, KalshiMarkets.Contract c, Payoff p, List<KalshiMarkets.Contract> th){
        List<Hit> out = new ArrayList<>();
        List<KalshiMarkets.Contract> sorted = new ArrayList<>(th);
        sorted.sort(Comparator.comparingInt(KalshiMarkets.Contract::strike));
        int[] strikes = sorted.stream().mapToInt(KalshiMarkets.Contract::strike).toArray();
        // too rich: sell the payoff (buy its NO), buy the upper strip
        Strip up = bound(p, strikes, true);
        if(quoted(c.noAsk()) && allQuoted(sorted, up.weights(), true)){
            double cost = c.noAsk();
            double fees = fee(c.noAsk());
            for(int i = 0; i < strikes.length; i++){
                cost += up.weights()[i] * orZero(sorted.get(i).yesAsk());
                fees += up.weights()[i] * fee(orZero(sorted.get(i).yesAsk()));
            }
            double min = 1 - up.constant();
            if(min - cost - fees > 0){
                out.add(new Hit(c.kind().name().toLowerCase() + " rich", event, who + ": sell " + c.ticker() + ", buy the threshold strip",
                        min, cost, fees, c.yesBidSize()));
            }
        }
        // too cheap: buy the payoff, sell the lower strip (buy its NOs)
        Strip low = bound(p, strikes, false);
        if(quoted(c.yesAsk()) && allQuoted(sorted, low.weights(), false)){
            double cost = c.yesAsk();
            double fees = fee(c.yesAsk());
            double sumW = 0;
            for(int i = 0; i < strikes.length; i++){
                cost += low.weights()[i] * orZero(sorted.get(i).noAsk());
                fees += low.weights()[i] * fee(orZero(sorted.get(i).noAsk()));
                sumW += low.weights()[i];
            }
            if(sumW - cost - fees > 0){
                out.add(new Hit(c.kind().name().toLowerCase() + " cheap", event, who + ": buy " + c.ticker() + ", sell the threshold strip",
                        sumW, cost, fees, c.yesAskSize()));
            }
        }
        return out;
    }

    static double orZero(Double d){
        return d == null ? 0 : d;
    }

    static boolean allQuoted(List<KalshiMarkets.Contract> th, double[] w, boolean yes){
        for(int i = 0; i < w.length; i++){
            if(w[i] > 0 && !quoted(yes ? th.get(i).yesAsk() : th.get(i).noAsk())){
                return false;
            }
        }
        return true;
    }

    /**
     * Check 3: a quarterback's passing-yards thresholds bound (0.0025 x his
     * passing yards) from above; his team's receivers' ladders are each at
     * most 0.0025 x their receiving yards. Sell the receivers' ladders, buy
     * the quarterback's upper strip.
     */
    static List<Hit> quarterback(String event, String qb, List<KalshiMarkets.Contract> qbThresholds,
                                 List<KalshiMarkets.Contract> receiverLadders){
        List<Hit> out = new ArrayList<>();
        if(qbThresholds.size() < 2 || receiverLadders.isEmpty()){
            return out;
        }
        double perUnit = receiverLadders.get(0).perUnit();
        List<KalshiMarkets.Contract> sorted = new ArrayList<>(qbThresholds);
        sorted.sort(Comparator.comparingInt(KalshiMarkets.Contract::strike));
        int[] strikes = sorted.stream().mapToInt(KalshiMarkets.Contract::strike).toArray();
        Strip up = bound(ladder(perUnit, 600), strikes, true);        // 600: above any passing game on record (554)
        if(!allQuoted(sorted, up.weights(), true)){
            return out;
        }
        double cost = 0;
        double fees = 0;
        double size = Double.MAX_VALUE;
        for(KalshiMarkets.Contract r : receiverLadders){
            if(!quoted(r.noAsk()) || !r.perUnit().equals(perUnit)){
                return out;
            }
            cost += r.noAsk();
            fees += fee(r.noAsk());
            size = Math.min(size, r.yesBidSize());
        }
        for(int i = 0; i < strikes.length; i++){
            cost += up.weights()[i] * orZero(sorted.get(i).yesAsk());
            fees += up.weights()[i] * fee(orZero(sorted.get(i).yesAsk()));
        }
        double min = receiverLadders.size() - up.constant();
        if(min - cost - fees > 0){
            out.add(new Hit("QB vs receivers (near-riskless)", event, qb + ": sell " + receiverLadders.size()
                    + " receiver ladders, buy his passing-yards strip", min, cost, fees, size));
        }
        return out;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        boolean offline = Boolean.parseBoolean(System.getProperty("offline", "false"));
        Map<String, List<KalshiMarkets.Contract>> bySeries = offline ? KalshiMarkets.latestArchived() : KalshiMarkets.snapshot();
        StringBuilder out = new StringBuilder();
        out.append(String.format("KALSHI NFL STATIC ARBITRAGE  %s  (%s read)%n", LocalDateTime.now().withNano(0), offline ? "archived" : "live"));
        int contracts = 0;
        int quotedBoth = 0;
        List<Double> spreads = new ArrayList<>();
        for(List<KalshiMarkets.Contract> list : bySeries.values()){
            for(KalshiMarkets.Contract c : list){
                contracts++;
                if(quoted(c.yesBid()) && quoted(c.yesAsk())){
                    quotedBoth++;
                    spreads.add(c.yesAsk() - c.yesBid());
                }
            }
        }
        spreads.sort(Double::compare);
        out.append(String.format("%d open contracts in %d series; %d with a two-sided quote; median bid-ask spread %.2f%n", contracts,
                bySeries.size(), quotedBoth, spreads.isEmpty() ? Double.NaN : spreads.get(spreads.size() / 2)));
        out.append("series with open markets: ");
        bySeries.forEach((s, l) -> {
            if(!l.isEmpty()){
                out.append(s).append(' ').append(l.size()).append("  ");
            }
        });
        out.append('\n');

        // group thresholds, ladders and escalators by event, player and stat
        Map<String, List<KalshiMarkets.Contract>> thresholds = new TreeMap<>();
        Map<String, List<KalshiMarkets.Contract>> others = new TreeMap<>();
        for(List<KalshiMarkets.Contract> list : bySeries.values()){
            for(KalshiMarkets.Contract c : list){
                if(c.player() == null){
                    continue;
                }
                String game = c.event().substring(c.event().indexOf('-') + 1);
                String key = game + "|" + c.player() + "|" + c.stat();
                if(c.kind() == KalshiMarkets.Kind.THRESHOLD){
                    thresholds.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
                }
                else if(c.kind() == KalshiMarkets.Kind.LADDER || c.kind() == KalshiMarkets.Kind.ESCALATOR){
                    others.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
                }
            }
        }
        List<Hit> hits = new ArrayList<>();
        for(Map.Entry<String, List<KalshiMarkets.Contract>> e : thresholds.entrySet()){
            String[] k = e.getKey().split("\\|");
            String who = e.getValue().get(0).playerName() + " " + k[2];
            hits.addAll(thresholds(k[0], who, e.getValue()));
            for(KalshiMarkets.Contract c : others.getOrDefault(e.getKey(), List.of())){
                Payoff p = c.kind() == KalshiMarkets.Kind.LADDER ? ladder(c.perUnit(), c.cap())
                        : c.schedule() == null ? null : escalator(c.schedule(), c.step());
                if(p != null){
                    hits.addAll(strip(k[0], who, c, p, e.getValue()));
                }
            }
        }
        // quarterbacks against their receivers, by game and team
        Map<String, List<KalshiMarkets.Contract>> qbByTeam = new LinkedHashMap<>();
        Map<String, List<KalshiMarkets.Contract>> laddersByTeam = new LinkedHashMap<>();
        for(Map.Entry<String, List<KalshiMarkets.Contract>> e : thresholds.entrySet()){
            if(e.getKey().endsWith("|passing yards")){
                KalshiMarkets.Contract c0 = e.getValue().get(0);
                qbByTeam.computeIfAbsent(e.getKey().split("\\|")[0] + "|" + c0.team(), k -> new ArrayList<>()).addAll(e.getValue());
            }
        }
        for(List<KalshiMarkets.Contract> list : others.values()){
            for(KalshiMarkets.Contract c : list){
                if(c.kind() == KalshiMarkets.Kind.LADDER && c.stat().equals("receiving yards")){
                    String game = c.event().substring(c.event().indexOf('-') + 1);
                    laddersByTeam.computeIfAbsent(game + "|" + c.team(), k -> new ArrayList<>()).add(c);
                }
            }
        }
        int qbChecks = 0;
        for(Map.Entry<String, List<KalshiMarkets.Contract>> e : qbByTeam.entrySet()){
            List<KalshiMarkets.Contract> qb = e.getValue();
            if(qb.stream().map(KalshiMarkets.Contract::player).distinct().count() != 1){
                continue;       // two quarterbacks listed for one team: the bound is not his alone
            }
            qbChecks++;
            hits.addAll(quarterback(e.getKey().split("\\|")[0], qb.get(0).playerName(), qb, laddersByTeam.getOrDefault(e.getKey(), List.of())));
        }
        out.append(String.format("checked: %d player-stat threshold sets, %d ladders/escalators against their strips, %d quarterbacks against receivers%n",
                thresholds.size(), others.values().stream().mapToInt(List::size).sum(), qbChecks));
        hits.sort(Comparator.comparingDouble(h -> -h.edge()));
        out.append(String.format("%n%d opportunities that pay more in every outcome than they cost after fees:%n", hits.size()));
        for(Hit h : hits){
            out.append(String.format("  %-34s %-14s edge $%.3f per unit (pays >= %.3f, costs %.3f + fees %.3f), size %.0f%n    %s%n",
                    h.check(), h.event(), h.edge(), h.minPayoff(), h.cost(), h.fees(), h.size(), h.what()));
        }
        if(hits.isEmpty()){
            out.append("  none at the posted prices.\n");
        }
        System.out.print(out);
        Path report = Path.of("data", "kalshi-arb-" + LocalDateTime.now().toLocalDate() + ".txt");
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("\nwritten to " + report);
    }
}
