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
import java.util.Set;
import java.util.TreeMap;

/**
 * One page for the week: the lineup, the defence, the wire, the bid and the
 * trades, in a browser instead of five terminal commands.
 *
 * THE PAGE COMPUTES NOTHING, and that is the whole design. Justin asked for it
 * to be interactive AND verifiably correct, and those pull against each other
 * the moment a browser does arithmetic that decides something: a formula in
 * JavaScript is a second implementation of a model that already exists in Java,
 * and the two drift. Every model in this repo has been wrong at least once in a
 * way only measurement caught, so a silent second copy of one is the last thing
 * it needs.
 *
 * So the ANSWER SPACE IS ENUMERATED HERE and shipped as data. The FAAB bid is
 * not a formula in the page, it is 5,124 precomputed bids - every value from 0
 * to 120 against every budget from 0 to 100 against four shadow prices - and
 * the slider performs a lookup. The trades are the same 5,339 swaps
 * {@link TradeMarket} searched, shipped whole; the controls filter and sort them
 * and nothing else. Interactivity here means choosing which precomputed answer
 * to look at.
 *
 * That makes the correctness claim checkable rather than rhetorical, and
 * LeagueConsoleTest checks it: it parses the JSON out of the emitted page and
 * re-derives every number from the same code that wrote it. A page that
 * calculated its own answers could not be tested that way at all.
 *
 * AND A CONTROL IS NOT INTERACTIVITY IF IT ASKS FOR SOMETHING KNOWN. The first
 * version had Justin set his own FAAB and his own valuation of a player on two
 * sliders; both are things this repo can compute, and asking made him do the
 * modelling and then admire the lookup. His remaining budget comes off the
 * rosters feed, and every free agent's worth is the objective's own (add, drop)
 * marginal - the same {@link TuesdaySwap#search} the terminal tool runs, called
 * rather than reimplemented, over the same forty men per position and at the
 * 480 drawn seasons ObjectiveStability measured the noise floor over. The
 * sliders survive only as a "what if", behind a fold.
 *
 *   ./gradlew run -Pmain=LeagueConsole [-Pme=<name>] [-Pscenarios=240] [-Ppool=6]
 *                                      [-Pcandidates=40] [-PwireScenarios=480]
 */
public class LeagueConsole {

    /**
     * Whether a position is a chip, in words.
     *
     * IN JAVA, because it is a decision rule with thresholds in it and those
     * belong on this side of the wire like every other one. The page used to
     * pick this string itself from `r.sellers>=6` and `r.sellers<=2`, which is
     * the same defect the keeper tag had: two numbers living in a browser where
     * nothing can test them and tuning them here would change nothing there.
     */
    static final int FLOODED_SELLERS = 6;
    static final int SCARCE_SELLERS = 2;

    static String supplyVerdict(int held, int starts, int sellers){
        if(held <= starts){
            return "nothing to sell";
        }
        if(sellers >= FLOODED_SELLERS){
            return "inventory - the market is flooded";
        }
        return sellers <= SCARCE_SELLERS ? "scarce - your leverage is here" : "ordinary supply";
    }

    /** The sequence a first trade unlocks, step by step. */
    static String chainJson(List<TradeMarket.Step> steps, Map<String, String> nameOf){
        StringBuilder out = new StringBuilder("[");
        for(int i = 0; i < steps.size(); i++){
            TradeMarket.Step step = steps.get(i);
            out.append(i == 0 ? "" : ",").append(String.format(
                    "{\"give\":%s,\"get\":%s,\"with\":%s,\"gain\":%s,\"running\":%s}",
                    quote(label(step.trade().give(), nameOf)), quote(label(step.trade().get(), nameOf)),
                    quote(step.trade().withManager()), num(step.trade().myGain()), num(step.cumulative())));
        }
        return out.append("]").toString();
    }

    /**
     * The position a trade would thin, or null if it thins none.
     *
     * A position is THIN when the men on it carrying no injury tag cannot fill
     * the slots the lineup demands - six Questionable receivers and one healthy
     * one is a three-receiver lineup held together by hope. Sending a body away
     * from such a position costs more than its projection, because what is
     * scarce there is availability rather than points, and a doubtful man is
     * still a ticket that a traded man is not.
     *
     * Only the position's own required slots count, not flex. Flex makes the
     * arithmetic kinder and the conclusion softer, and a rule meant to stop a
     * bad trade should be the strict version.
     */
    static final Map<Position, Integer> SLOTS = Map.of(Position.QB, 1, Position.RB, 2,
            Position.WR, 3, Position.TE, 1, Position.DEF, 1);

    static String thinnedByTrade(List<String> leaving, List<String> arriving,
                                 Map<String, Position> positionOf, List<StartSit.Man> roster){
        for(Position position : SLOTS.keySet()){
            // NET, not gross. Sending one receiver and receiving two does not
            // thin the receivers, and the first version flagged it anyway -
            // it only ever looked at who left. A rule that fires on a trade
            // which IMPROVES the position it is meant to protect will be
            // ignored, and then it protects nothing.
            long out = leaving.stream().filter(id -> positionOf.get(id) == position).count();
            long in = arriving.stream().filter(id -> positionOf.get(id) == position).count();
            if(out <= in){
                continue;
            }
            long healthy = roster.stream()
                    .filter(man -> man.position() == position && man.playing()
                            && !doubtful(SleeperProjections.injuryStatusOf(man.id())))
                    .count();
            if(healthy < SLOTS.get(position)){
                return position.name() + " (" + healthy + " healthy for " + SLOTS.get(position)
                        + " slots)";
            }
        }
        return null;
    }

    /** Anything other than no tag at all is a doubt worth pricing. */
    static boolean doubtful(String status){
        return status != null && !status.isBlank() && !status.equalsIgnoreCase("ok");
    }

    /**
     * WHO ACTUALLY ENTERS THE LINEUP IF THIS MAN SITS, and what the ten are
     * worth without him.
     *
     * The first version named "the best untagged bench man who shares his
     * position or is flex-eligible", and RB, WR and TE are all flex-eligible -
     * so it offered Chris Rodriguez, a running back, as the replacement for Mike
     * Evans at receiver, and Dalton Schultz, a tight end, for Bhayshul Tuten at
     * running back. Neither can fill the slot in question. Both went on Justin's
     * screen with a break-even beside them.
     *
     * Naming a replacement was the wrong shape. Benching a man does not promote
     * one specific other man: the lineup REBUILDS, and the effect can cascade -
     * a back leaves, another back takes his slot, and somebody else inherits the
     * flex. So this rebuilds the best legal ten without him and reports the
     * total, which handles the cascade by construction and cannot name an
     * ineligible man because it never names one at all.
     *
     * The break-even that follows is unchanged in spirit and now correct in
     * fact: start him only while
     *
     *     P(active) x (ten with him)  +  (1-P) x (ten with him, minus his points)
     *          exceeds  (best ten without him)
     *
     * which rearranges to P > 1 - (withHim - withoutHim) / his projection. On
     * Nabers that still gives 85%, because Addison really can take a receiver
     * slot; it is the cross-position cases the old rule got wrong.
     */
    static double bestTenWithout(StartSit.Man out, List<StartSit.Man> roster){
        List<TeamRankings.Man> men = new ArrayList<>();
        for(StartSit.Man man : roster){
            if(man.id().equals(out.id()) || !man.playing()){
                continue;
            }
            men.add(new TeamRankings.Man(man.id(), man.name(),
                    man.position() == null ? "?" : man.position().name(), "",
                    man.projected(), false, 0, ""));
        }
        return TeamRankings.bestLineup(men).starters();
    }

    static final java.util.Set<Position> FLEX =
            java.util.Set.of(Position.RB, Position.WR, Position.TE);

    /** Enough of a JSON writer for numbers, strings and arrays of records. */
    static String quote(String s){
        StringBuilder out = new StringBuilder("\"");
        for(char c : s.toCharArray()){
            switch(c){
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                default -> {
                    if(c < 0x20){ out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c)); } else { out.append(c); }
                }
            }
        }
        return out.append('"').toString();
    }

    static String num(double v){
        // Locale.ROOT, not the default: a comma-decimal locale writes 4,79 and
        // splits the value into a bogus extra member of the object literal, so
        // `const D = {...}` becomes a SyntaxError and every tab renders empty
        // with nothing on the page saying why. The machine that built this one
        // is en_CA, which is exactly what would have kept it quiet.
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /**
     * Every bid the page can be asked for, computed HERE by the same
     * {@link FaabBid#bestBid} the command line uses. Values 0-120 in twos,
     * budgets 0-100 in fives, four shadow prices: 5,124 answers, so the slider
     * never needs a formula behind it.
     */
    static String faabGrid(FaabBid.Band band, double[] dollarCosts){
        StringBuilder out = new StringBuilder("[");
        boolean first = true;
        for(int value = 0; value <= 120; value += 2){
            for(int budget = 0; budget <= 100; budget += 5){
                for(int c = 0; c < dollarCosts.length; c++){
                    int bid = FaabBid.bestBid(band, value, dollarCosts[c], budget);
                    if(!first){ out.append(","); }
                    first = false;
                    out.append(String.format("{\"v\":%d,\"b\":%d,\"c\":%d,\"bid\":%d,\"win\":%s}",
                            value, budget, c, bid, num(band.winChance(bid))));
                }
            }
        }
        return out.append("]").toString();
    }

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        String season = LeagueWeek.season();
        int week = LeagueWeek.week();
        int scenarios = Integer.getInteger("scenarios", 240);
        int pool = Integer.getInteger("pool", 6);
        String me = System.getProperty("me", configuration.getUserIDToDisplayName()
                .getOrDefault(configuration.getMyID(), configuration.getMyID()));

        Map<String, Double> points = ProjectionSources.resolve("sleeper");
        Map<String, Double> weekPoints = LeagueWeek.projected(season, week);
        WeeklyStarterValue value = WeeklyStarterValue.forCurrentBoard(configuration, points, scenarios, 424_242L);
        Map<String, String> ownerOf = LeagueOwners.today(configuration);

        Map<String, List<String>> rosters = new TreeMap<>();
        Map<String, String> nameOf = new HashMap<>();
        Map<String, Position> positionOf = new HashMap<>();
        for(Map.Entry<String, String> entry : ownerOf.entrySet()){
            rosters.computeIfAbsent(entry.getValue(), u -> new ArrayList<>()).add(entry.getKey());
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            nameOf.put(entry.getKey(), player == null ? entry.getKey() : player.firstName + " " + player.lastName);
            positionOf.put(entry.getKey(), player == null ? null : player.position);
        }
        for(List<String> roster : rosters.values()){
            roster.sort(Comparator.comparingDouble((String id) -> -points.getOrDefault(id, 0.0)));
        }

        // ---- the week's lineup, with the measured odds beside each bench call
        List<StartSit.Flip> curve = List.of();
        try(var files = Files.list(Path.of("data"))){
            Path newest = files.filter(p -> p.getFileName().toString().matches("start-sit-flip-\\d{4}-\\d{2}-\\d{2}\\.txt"))
                    .max(Comparator.comparing(p -> p.getFileName().toString())).orElse(null);
            if(newest != null){
                curve = StartSit.readCurve(Files.readAllLines(newest));
            }
        }
        List<StartSit.Man> mine = new ArrayList<>();
        for(String id : rosters.getOrDefault(me, List.of())){
            Double projected = weekPoints.get(id);
            mine.add(new StartSit.Man(id, nameOf.getOrDefault(id, id), positionOf.get(id),
                    projected == null ? 0 : projected, projected != null));
        }
        mine.sort(Comparator.comparingDouble(StartSit.Man::projected).reversed());
        List<TeamRankings.Man> playable = new ArrayList<>();
        for(StartSit.Man man : mine){
            if(man.playing()){
                playable.add(new TeamRankings.Man(man.id(), man.name(),
                        man.position() == null ? "?" : man.position().name(), "", man.projected(), false, 0, ""));
            }
        }
        TeamRankings.Lineup lineup = TeamRankings.bestLineup(playable);
        List<String> starters = new ArrayList<>();
        for(TeamRankings.Man man : lineup.starting()){ starters.add(man.id()); }

        // WHO IS HURT, AND WHAT IT WOULD TAKE TO BENCH HIM.
        //
        // On the morning of week 1 this page said the lineup was ten for ten
        // optimal while two of those ten carried a Questionable tag, one of them
        // a knee with an ACL note. StartSit reads projections and never looked at
        // injury_status, so the tool was confidently silent about the only thing
        // that could cost points that day. `SleeperProjections.injuryStatusOf`
        // had existed the whole time; nothing asked it.
        //
        // The number that decides it is a break-even, not an opinion: starting a
        // doubtful man beats starting a healthy replacement only while
        //
        //     P(he is active) * his projection  >  the replacement's projection
        //
        // so the bar is replacement / starter, and it is HIGH exactly when the
        // bench is close. A healthy 9.5 behind a questionable 11.2 needs 85%
        // certainty before the questionable man is the right start. What this
        // cannot know is his actual chance of playing - that arrives in the
        // inactive report ninety minutes before kickoff - so it prints the bar
        // and leaves the judgement where the information is.
        // ONE BENCH MAN CANNOT REPLACE FOUR STARTERS. The first version offered
        // Jordan Addison to every doubtful starter independently, which reads as
        // four plans and is one - the same defect as the waiver board naming the
        // only defence as the drop in every row. Replacements are assigned
        // EXCLUSIVELY, best starter first, and a starter with nobody left gets
        // none and says so: if enough of them sit, one is being started hurt
        // whatever the arithmetic says, and that is worth knowing before kickoff
        // rather than at one o'clock.
        Map<String, Double> withoutHim = new HashMap<>();
        for(StartSit.Man man : mine){
            if(starters.contains(man.id()) && doubtful(SleeperProjections.injuryStatusOf(man.id()))
                    && man.projected() > 0){
                withoutHim.put(man.id(), bestTenWithout(man, mine));
            }
        }
        double bestTen = lineup.starters();

        StringBuilder lineupJson = new StringBuilder("[");
        for(int i = 0; i < mine.size(); i++){
            StartSit.Man man = mine.get(i);
            boolean starting = starters.contains(man.id());
            double gap = starting || !man.playing() ? 0 : StartSit.closestStarter(man, mine, starters);
            double odds = starting || !man.playing() || curve.isEmpty() ? 0 : StartSit.flipRate(curve, gap);
            String status = SleeperProjections.injuryStatusOf(man.id());
            // start him only while P(active) x (ten with him) + (1-P) x (ten with
            // him, minus his points) beats the best ten WITHOUT him, which
            // rearranges to P > 1 - (withHim - withoutHim) / his projection
            Double without = withoutHim.get(man.id());
            double breakEven = without == null || man.projected() <= 0 ? 0
                    : Math.max(0, Math.min(1, 1 - (bestTen - without) / man.projected()));
            String replacement = without == null ? null
                    : String.format(java.util.Locale.ROOT, "%.1f without him", without);
            lineupJson.append(i == 0 ? "" : ",").append(String.format(
                    "{\"name\":%s,\"pos\":%s,\"proj\":%s,\"playing\":%b,\"start\":%b,"
                            + "\"gap\":%s,\"odds\":%s,\"status\":%s,\"instead\":%s,\"breakEven\":%s}",
                    quote(man.name()), quote(man.position() == null ? "?" : man.position().name()),
                    num(man.projected()), man.playing(), starting, num(gap), num(odds),
                    status == null || status.isBlank() ? "null" : quote(status),
                    replacement == null ? "null" : quote(replacement), num(breakEven)));
        }
        lineupJson.append("]");

        // ---- HIS ACTUAL FAAB, not a slider. The rosters feed carries what each
        // manager has spent, so "how much is left" is a fact and not a question.
        int budgetLeft = 100;
        for(com.google.gson.JsonElement element : com.google.gson.JsonParser
                .parseString(configuration.getTodaysRosterWebPageSerious()).getAsJsonArray()){
            com.google.gson.JsonObject roster = element.getAsJsonObject();
            if(!roster.has("owner_id") || roster.get("owner_id").isJsonNull()){
                continue;
            }
            String owner = configuration.getUserIDToDisplayName()
                    .getOrDefault(roster.get("owner_id").getAsString(), "");
            if(owner.equals(me) && roster.has("settings")){
                com.google.gson.JsonObject settings = roster.getAsJsonObject("settings");
                if(settings.has("waiver_budget_used") && !settings.get("waiver_budget_used").isJsonNull()){
                    budgetLeft = 100 - settings.get("waiver_budget_used").getAsInt();
                }
            }
        }

        // ---- the wire: what a claim clears at, and every bid it implies
        Path faabReport = FaabBid.newestCurve();
        List<Integer> allPrices = faabReport == null ? List.of()
                : FaabBid.readPrices(Files.readAllLines(faabReport), "ALL");
        List<Integer> contestedPrices = faabReport == null ? List.of()
                : FaabBid.readPrices(Files.readAllLines(faabReport), "CONTESTED");
        FaabBid.Band allBand = new FaabBid.Band("all", 0, Double.MAX_VALUE, allPrices);
        FaabBid.Band contestedBand = new FaabBid.Band("contested", 0, Double.MAX_VALUE, contestedPrices);

        // ---- WHO TO ADD, with the model doing the valuing.
        // A slider asking "what is he worth to your roster" hands the user the
        // one calculation the model exists to perform. Every free agent worth
        // considering is priced here the same way a waiver claim is - the roster
        // WITH him and without the man he displaces, minus the roster as it
        // stands - and the bid follows from that value and the budget he
        // actually has.
        Set<String> owned = new java.util.HashSet<>(ownerOf.keySet());
        Map<Position, List<String>> freeByPosition = new java.util.EnumMap<>(Position.class);
        for(Map.Entry<String, Double> entry : points.entrySet()){
            if(owned.contains(entry.getKey()) || entry.getValue() == null || entry.getValue() <= 0){
                continue;
            }
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player == null || player.position == null || player.position == Position.OTHER){
                continue;
            }
            freeByPosition.computeIfAbsent(player.position, u -> new ArrayList<>()).add(entry.getKey());
        }
        int perPosition = Integer.getInteger("candidates", 40);   // TuesdaySwap's own depth
        List<String> candidates = new ArrayList<>();
        for(Map.Entry<Position, List<String>> entry : freeByPosition.entrySet()){
            List<String> men = entry.getValue();
            men.sort(Comparator.comparingDouble((String id) -> -points.get(id)));
            candidates.addAll(men.subList(0, Math.min(perPosition, men.size())));
        }
        for(String id : candidates){          // free agents are not in ownerOf, so
            Player player = Player.getPlayerFromSIDV2(id);   // they have no name yet
            nameOf.put(id, player.firstName + " " + player.lastName);
            positionOf.put(id, player.position);
        }
        // THE SAME SEARCH THE TERMINAL TOOL RUNS. Calling TuesdaySwap.search
        // rather than re-writing the (add, drop) loop here is the only way the
        // page and `./gradlew run -Pmain=TuesdaySwap` cannot drift apart.
        //
        // AT THE FLOOR'S OWN SCENARIO COUNT, not the page's. ObjectiveStability
        // measured the 6.8-point spread over 480 drawn seasons; a gain computed
        // over 240 is a noisier number, and comparing it to that floor would be
        // asking whether one population clears another population's yardstick.
        int wireScenarios = Integer.getInteger("wireScenarios", 480);
        WeeklyStarterValue wireValue = wireScenarios == scenarios ? value
                : WeeklyStarterValue.forCurrentBoard(configuration, points, wireScenarios, 424_242L);
        List<String> myRoster = rosters.getOrDefault(me, List.of());
        List<TuesdaySwap.Swap> swaps = TuesdaySwap.search(myRoster, candidates, nameOf, positionOf,
                ids -> wireValue.of(ids));
        double swapFloor = Double.parseDouble(System.getProperty("swapFloor", "6.8"));
        StringBuilder wireJson = new StringBuilder("[");
        int wireRows = 0;
        double[] costs = {1.0, 1.5, 2.0, 3.0};
        // ONE PRICING, SHARED WITH TuesdaySwap. The slot-safe drop, the fallback
        // that empties a slot, and the completion that pays for it used to be
        // written out here and nowhere else, so this page and the report that
        // names the CLAIM ranked the same board differently and named different
        // best adds from the same feeds.
        List<TuesdaySwap.Priced> priced = TuesdaySwap.price(swaps, myRoster, candidates,
                nameOf, positionOf, points, ids -> wireValue.of(ids));
        for(TuesdaySwap.Priced row : priced){
            TuesdaySwap.Swap swap = row.swap();
            TuesdaySwap.Swap free = row.free();
            boolean hole = row.hole();
            TuesdaySwap.Completion completion = row.completion();
            double worth = row.worth();
            double altGain = row.altGain();
            if(worth < 0.05){
                continue;                     // not a gain once the plan is paid for
            }
            boolean better = free != swap && altGain > worth + 0.05;
            int bid = FaabBid.bestBid(allBand, worth, 1.5, budgetLeft);
            // THE WHOLE LADDER, SHIPPED. A row that prints only the cheapest
            // drop invites reading its number as a property of the player -
            // Justin read +4.8 as "Schultz is nearly Fannin" when it is +4.8
            // against the sixteenth man and -25.1 against Fannin himself. Both
            // are the same claim. Shipping every pair makes that unquotable.
            StringBuilder ladder = new StringBuilder("[");
            int rungs = 0;
            for(TuesdaySwap.Swap rung : swaps){
                if(!rung.addId().equals(swap.addId())){
                    continue;
                }
                TuesdaySwap.Completion whole = TuesdaySwap.complete(myRoster, rung, candidates,
                        nameOf, positionOf, points, ids -> wireValue.of(ids));
                ladder.append(rungs++ == 0 ? "" : ",").append(String.format(
                        "{\"drop\":%s,\"gain\":%s,\"thenAdd\":%s,\"thenDrop\":%s,\"whole\":%s}",
                        quote(rung.dropName()), num(rung.gain()),
                        whole == null ? "null" : quote(whole.addName()),
                        whole == null ? "null" : quote(whole.dropName()),
                        whole == null ? "null" : num(whole.gain())));
            }
            ladder.append("]");
            wireJson.append(wireRows++ == 0 ? "" : ",").append(String.format(
                    "{\"name\":%s,\"pos\":%s,\"proj\":%s,\"worth\":%s,\"drop\":%s,"
                            + "\"bid\":%d,\"win\":%s,\"noise\":%b,\"hole\":%b,"
                            + "\"altDrop\":%s,\"altWorth\":%s,\"thenAdd\":%s,\"thenDrop\":%s,"
                            + "\"ladder\":%s}",
                    quote(swap.addName()),
                    quote(swap.addPosition() == null ? "?" : swap.addPosition().name()),
                    num(points.getOrDefault(swap.addId(), 0.0)), num(worth),
                    quote(swap.dropName()), bid, num(allBand.winChance(bid)),
                    worth < swapFloor, hole,
                    better ? quote(free.dropName()) : "null",
                    better ? num(altGain) : "null",
                    completion != null && (better || hole) ? quote(completion.addName()) : "null",
                    completion != null && (better || hole) ? quote(completion.dropName()) : "null",
                    ladder));
        }
        wireJson.append("]");

        // ---- the trades, both sides priced by their own lights
        boolean withKeepers = Boolean.parseBoolean(System.getProperty("keepers", "true"));
        Map<String, Position> everyPosition = new HashMap<>(positionOf);
        for(String id : points.keySet()){
            everyPosition.computeIfAbsent(id, u -> {
                Player player = Player.getPlayerFromSIDV2(u);
                return player == null ? null : player.position;
            });
        }
        Map<Position, java.util.TreeMap<Double, Double>> bestByAdp =
                TradeMarket.bestStillAvailable(points, everyPosition);
        // PRICED OFF THIS SEASON'S DRAFT, NOT LAST SEASON'S.
        //
        // KeeperChooser.eligibleCandidates reads getPreviousDraftPicks - "picks
        // from every EARLIER draft" - which in the 2026 season means the 2025
        // board. That is the right basis for the 2026 keeper decision, made in
        // August and already history, and the wrong one for 2027: a man drafted
        // in 2026 was not in the 2025 draft at all and silently took the
        // undrafted default of a tenth.
        //
        // Justin found it by asking how a rookie he drafted this year and who
        // has not played could have a keeper cost. Ten of sixteen rounds were
        // wrong in both directions - Skattebo shown at r9 against a true r3, Bo
        // Nix at r8 against a true r15 - so the two men the panel named as
        // keepers were picked on prices that did not exist.
        // ...with the three-consecutive-year cap, which needs the earlier drafts
        List<String> earlier = new ArrayList<>();
        for(com.google.gson.JsonArray board : configuration.getPreviousDraftPicks()){
            earlier.add(board.toString());
        }
        Map<String, NextYearKeepers.Cost> nextYear = NextYearKeepers.from(
                configuration.getTodaysDraftPicks(), NextYearKeepers.consecutiveYears(earlier));
        Map<String, Integer> keeperRound = new HashMap<>();
        Map<String, String> keeperRefusal = new HashMap<>();
        for(Map.Entry<String, NextYearKeepers.Cost> entry : nextYear.entrySet()){
            if(entry.getValue().keepable()){
                keeperRound.put(entry.getKey(), entry.getValue().round());
            }
            else {
                keeperRefusal.put(entry.getKey(), entry.getValue().refusal());
            }
        }
        // a man picked up off waivers was in no draft; the ruleset prices him at a tenth
        for(String id : ownerOf.keySet()){
            if(!nextYear.containsKey(id)){
                keeperRound.put(id, Keeper.UNDRAFTED_ROUND_COST);
            }
        }
        Map<String, Integer> slotOfPlayer = TradeMarket.draftSlotOfPlayer(ownerOf, configuration);
        Map<String, Double> surplus = new HashMap<>();
        for(String id : keeperRound.keySet()){
            surplus.put(id, TradeMarket.keeperPoints(keeperRound, points, bestByAdp,
                    everyPosition, configuration, slotOfPlayer, id));
        }
        java.util.function.ToDoubleFunction<List<String>> seasonOnly = ids -> value.of(ids);
        java.util.function.ToDoubleFunction<List<String>> withKeeper =
                ids -> value.of(ids) + TradeMarket.keeperValue(ids, surplus, everyPosition);
        java.util.function.ToDoubleFunction<List<String>> myScorer = withKeepers ? withKeeper : seasonOnly;
        // one pair of Sides, built once and used for both the table and the
        // lookahead, so a trade cannot be scored one way in the row and another
        // way in the chain that row advertises
        TradeMarket.Side mySide = TradeMarket.scoring(myScorer);
        TradeMarket.Side theirSide = withKeepers
                ? TradeMarket.lossAverseOnKeepers(seasonOnly, surplus, everyPosition)
                : TradeMarket.scoring(seasonOnly);

        List<TradeMarket.Trade> everySwap = new ArrayList<>();
        for(Map.Entry<String, List<String>> entry : rosters.entrySet()){
            if(entry.getKey().equals(me)){
                continue;
            }
            everySwap.addAll(TradeMarket.between(me, entry.getKey(), rosters.get(me), entry.getValue(),
                    mySide, theirSide, pool));
            // and the uneven shapes, which is where consolidation lives: he holds
            // seven receivers and five backs at positions every rival has spare
            everySwap.addAll(TradeMarket.unbalanced(me, entry.getKey(), rosters.get(me),
                    entry.getValue(), mySide, theirSide, pool, points));
        }
        List<TradeMarket.Trade> mutuallyGood = TradeMarket.mutual(everySwap);
        double myBase = seasonOnly.applyAsDouble(rosters.get(me));
        // THE SAME THREE NUMBERS FOR HIM AS FOR ME. The table priced his side by
        // his own objective alone, which meant the two managers were not being
        // shown comparable things: Justin could see what a trade did to his
        // starters and to his 2026 and could see only one number for the man
        // opposite. His bases are cached per manager - the roster he starts from
        // does not change between the trades offered to him.
        // HIS BEST ALTERNATIVE. `him` alone is not an acceptance signal: what
        // decides whether he takes your offer is what it beats, and eleven other
        // rosters are a lot to beat.
        // Justin, 2026-09-06: "their perfect trades also would need to be
        // recursively limited by the ability of the other other manager to make a
        // better trade." So this is a fixed point, and the page ships BOTH ends
        // of the descent - the naive maximum and where it settles - because how
        // much the recursion moved things is the interesting part and asserting
        // it would be worth nothing.
        int batnaPool = Integer.getInteger("batnaPool", 4);
        List<TradeMarket.Alternative> rivalBoard = TradeMarket.alternatives(me, rosters, theirSide, batnaPool, points);
        TradeMarket.Market market = TradeMarket.match(rivalBoard, rosters.keySet(), me);
        // what each of his men fetches on his own, so an ask for two of them can
        // be set against what selling those two separately would bring him
        Map<String, Double> sellPrice = TradeMarket.sellPrice(rivalBoard);
        Map<String, Double> naive = market.naive();
        Map<String, Double> elsewhere = market.fallback();

        // DOES HE TRADE AT ALL. Every other signal on this board models how a
        // rival VALUES a deal; none of them models whether he does deals. From
        // this league's own log that is the bigger term - the two best offers
        // here go to a manager who has completed one trade in three seasons.
        Map<String, Double> tradesPerYear = new HashMap<>();
        Map<String, int[]> tradeRecord = new HashMap<>();
        for(TradePartners.Record record : TradePartners.records(configuration.getLeagueID())){
            tradesPerYear.put(record.manager(), record.rate());
            tradeRecord.put(record.manager(), new int[]{record.trades(), record.seasons()});
        }

        Map<String, Double> hisSeasonBase = new HashMap<>();
        Map<String, Double> hisSimpleBase = new HashMap<>();
        Map<String, Integer> hisSlotsBase = new HashMap<>();
        for(Map.Entry<String, List<String>> entry : rosters.entrySet()){
            hisSeasonBase.put(entry.getKey(), seasonOnly.applyAsDouble(entry.getValue()));
            hisSimpleBase.put(entry.getKey(), TradeMarket.simpleStarters(entry.getValue(), points, positionOf));
            hisSlotsBase.put(entry.getKey(), TradeMarket.slotsFilled(entry.getValue(), points, positionOf));
        }

        // WHAT EACH TRADE OPENS UP. Justin: "I'd prefer to do a +5 trade that
        // opens up a total possibility of a chain of +80 trades, than a +20
        // trade that only opens up a chain of +45." The greedy chain cannot
        // answer that - it always takes the biggest step and so never learns
        // where the small one leads - so each candidate is FORCED as step one
        // and the chain re-searched on the board it leaves.
        //
        // Only the best few get this: a chain is a full re-search of every rival
        // at every step, so pricing all of them would cost hours to rank rows
        // nobody scrolls to. Which few, and that it is a few, is stated on the
        // page rather than left as a silent cap.
        int lookahead = Integer.getInteger("lookahead", 8);
        int chainPool = Integer.getInteger("chainPool", 4);
        // NOT "depth". BoardValue documents it and I walked into it anyway:
        // something in the forked JVM already owns that property and sets it to
        // 0, so -Pdepth arrives as zero and the default never applies. It cost
        // an entire run here - every chain came back one step deep and the
        // "opens up" column repeated the immediate gain, which looks like a
        // correct answer rather than a stolen flag.
        int chainDepth = Integer.getInteger("chainDepth", 4);
        double tradeFloor = Double.parseDouble(System.getProperty("tradeFloor", "6.8"));
        List<TradeMarket.Trade> byGain = new ArrayList<>(mutuallyGood);
        byGain.sort(Comparator.comparingDouble(TradeMarket.Trade::myGain).reversed());
        Map<TradeMarket.Trade, List<TradeMarket.Step>> reachOf = new java.util.IdentityHashMap<>();
        for(TradeMarket.Trade trade : byGain.subList(0, Math.min(lookahead, byGain.size()))){
            reachOf.put(trade, TradeMarket.chainAfter(me, rosters, trade, mySide, theirSide,
                    chainDepth, chainPool, tradeFloor));
        }

        // EACH TRADE'S OWN ERROR BAR, because one global floor was the wrong
        // instrument. TradeStability measured the seed-to-seed spread of a
        // mutually-good trade gain: median 5.1, max 18.0, min 0.4. That is not a
        // constant, it is a per-trade property - and the 6.8 everything was being
        // judged against came from a roster MARGINAL at 480 scenarios, a
        // different quantity at a different scenario count.
        //
        // The trade recommended all session reads +7.4 on the seed this page
        // ships and +5.4 on another. Printing the first without the second is
        // how a number inside the noise gets sent as a recommendation.
        int errorSeeds = Integer.getInteger("errorSeeds", 3);
        long[] otherSeeds = {7L, 99L, 2026L, 31_337L};
        List<WeeklyStarterValue> shakes = new ArrayList<>();
        for(int i = 0; i < errorSeeds - 1; i++){
            shakes.add(WeeklyStarterValue.forCurrentBoard(configuration, points, scenarios,
                    otherSeeds[i % otherSeeds.length]));
        }

        StringBuilder tradesJson = new StringBuilder("[");
        int written = 0;
        int losesToElsewhere = 0;
        int fairTrades = 0;
        int askTrades = 0;
        int insideItsOwnNoise = 0;
        for(TradeMarket.Trade trade : mutuallyGood){
            // A GAIN THAT ROUNDS TO NOTHING IS NOT INFORMATION. The filter keeps
            // anything strictly positive, which at two decimal places can print
            // as "+0.00" - a row that says a trade is worth having and shows
            // zero. The page's own verification test caught this on its first
            // run, which is the argument for having written it.
            if(trade.myGain() < 0.05 || trade.theirGain() < 0.05){
                continue;
            }
            double thisSeason = seasonOnly.applyAsDouble(
                    TradeMarket.swap(rosters.get(me), trade.give(), trade.get())) - myBase;
            double hisKeeper = 0;
            for(String id : trade.get()){
                hisKeeper = Math.max(hisKeeper, surplus.getOrDefault(id, 0.0));
            }
            TradeMarket.Optics optics = TradeMarket.optics(trade.give(), trade.get(), SleeperProjections::adpOf);
            List<String> after = TradeMarket.swap(rosters.get(me), trade.give(), trade.get());
            double simple = TradeMarket.simpleStarters(after, points, positionOf)
                    - TradeMarket.simpleStarters(rosters.get(me), points, positionOf);
            // A TRADE THAT EMPTIES A SLOT IS NOT A MINUS NINETY-FIVE. The simple
            // model has no waiver wire, so sending away the only defence loses
            // the whole slot and reads as catastrophic when it means "you would
            // pick one up". The full model fills it at the streamed level and
            // says +1.6. Neither number is wrong; the simple one is answering a
            // different question, and shown as a score it would mislead.
            boolean hole = TradeMarket.slotsFilled(after, points, positionOf)
                    < TradeMarket.slotsFilled(rosters.get(me), points, positionOf);
            // and the identical three for the manager opposite, on his roster
            List<String> hisRoster = rosters.get(trade.withManager());
            List<String> hisAfter = TradeMarket.swap(hisRoster, trade.get(), trade.give());
            double himSeason = seasonOnly.applyAsDouble(hisAfter)
                    - hisSeasonBase.get(trade.withManager());
            double himSimple = TradeMarket.simpleStarters(hisAfter, points, positionOf)
                    - hisSimpleBase.get(trade.withManager());
            boolean himHole = TradeMarket.slotsFilled(hisAfter, points, positionOf)
                    < hisSlotsBase.get(trade.withManager());
            double askPrice = 0;
            for(String id : trade.get()){
                askPrice += sellPrice.getOrDefault(id, 0.0);
            }
            // A TRADE HE THANKS YOU FOR. Justin's standing goal, 2026-09-06:
            // "win my fantasy league this year and next year, while having
            // people see me as a good trading partner." That last clause is not
            // decoration - this is a KEEPER league, the same eleven managers
            // every season, so a reputation is an asset that compounds into
            // exactly the "next year" he named. A trade qualifies when it helps
            // him on the number he can check himself, empties nobody's lineup,
            // and does not visibly grab the earlier pick.
            // what the trade costs next March; deterministic, so it shifts the
            // mean and adds no spread - which is why the error bar below has to
            // subtract it rather than ignore it
            double keeperCost = withKeepers
                    ? TradeMarket.keeperValue(rosters.get(me), surplus, everyPosition)
                            - TradeMarket.keeperValue(after, surplus, everyPosition)
                    : 0;
            // re-value THIS trade under the other seeds; the search is not
            // repeated, so this is the valuation's own wobble and not a
            // different board
            // THE BAR MUST BRACKET THE NUMBER IT SITS BESIDE. It re-valued
            // season-only while `you` includes keeper value, so the two were
            // different quantities and the point estimate could sit outside its
            // own range - Tuten for Josh Allen read +8.6 [+8.6, +30.7], pinned
            // at the very bottom of a bracket measuring something else. Keeper
            // value is deterministic and adds no wobble, so the honest fix is to
            // subtract the same keeper cost from every re-valuation.
            double low = trade.myGain(), high = trade.myGain();
            for(WeeklyStarterValue shake : shakes){
                double again = shake.of(after) - shake.of(rosters.get(me)) - keeperCost;
                low = Math.min(low, again);
                high = Math.max(high, again);
            }
            boolean noise = low <= 0;
            if(noise){
                insideItsOwnNoise++;
            }
            // A TRADE HE THANKS YOU FOR. Justin's standing goal, 2026-09-06:
            // "win my fantasy league this year and next year, while having
            // people see me as a good trading partner." That last clause is not
            // decoration - this is a KEEPER league, the same eleven managers
            // every season, so a reputation is an asset that compounds into
            // exactly the "next year" he named. A trade qualifies when it helps
            // him on the number he can check himself, empties nobody's lineup,
            // does not visibly grab the earlier pick - AND survives its own
            // error bar.
            //
            // That last clause was missing and it mattered: fifteen of the
            // twenty offers in the default view had gains the model could not
            // tell from zero. A view built to be the trustworthy one was three
            // quarters unreadable, which is worse than no default at all,
            // because it is the screen he would act from.
            // DOES IT THIN A POSITION THAT IS ALREADY HURT?
            //
            // Justin, 2026-09-07, on the one trade this page recommended: "the
            // Kelce downs trade seems bad." He was right and the reason was a
            // blind spot: trade valuations run on SEASON projections and never
            // look at injury_status, which the lineup tab had learned to do that
            // same morning. Six of his seven receivers were Questionable, only
            // Jordan Addison untagged, and the board cheerfully proposed sending
            // one of the three he was starting.
            //
            // A position is THIN when the men who carry no injury tag cannot
            // fill the slots the lineup demands. Sending a body away from a thin
            // position is worse than the season projection says, because what is
            // scarce is not points, it is availability - and a tagged man is
            // still a lottery ticket that a traded man is not.
            String thins = thinnedByTrade(trade.give(), trade.get(), positionOf, mine);
            // TIERS, NOT A GATE. Justin, 2026-09-07: "i think it might be a tad
            // too strict in terms of not allowing other teams to make mistakes."
            // He is right, and the measurement agreed: of eighty-five trades,
            // fifty-seven failed because the man opposite loses on the SIMPLE
            // model - a best-legal-ten calculation almost nobody in this league
            // performs. Requiring him to be correct is a strange way to model
            // somebody who completes under one trade a season.
            //
            // Every test still runs; none of them hides a row any more. What
            // changes is that a trade is placed rather than rejected:
            //
            //   SEND  - good for me beyond the noise, visibly good for him, hurts
            //           neither roster, and does not read as a grab. Defensible
            //           to offer and to have offered.
            //   ASK   - good for me on average, safe for my roster, and there is
            //           SOME story he can tell himself: he gains on the full
            //           model, or barely loses on starters, or receives the
            //           earlier pick. This is the tier that allows for mistakes,
            //           and it is where most real trades in this league live.
            //   NO    - it costs me, or it guts a position I cannot cover.
            //
            // The reputation rule survives intact: nothing reaches SEND that
            // grabs the earlier pick or leaves him worse on the number he can
            // check, and ASK is labelled as a long shot rather than a fair deal.
            boolean safeForMe = thins == null && !hole && trade.myGain() > 0;
            boolean visiblyGoodForHim = himSimple > 0 && !himHole;
            // ...but never one that guts HIS lineup. Three ASK rows offered him a
            // trade whose simple reading is -106 because it takes his only
            // defence: the full model likes it only because it refills the empty
            // slot off the wire for free. That is the same "empties a slot"
            // error I fixed on my own side and then let stand on his, and it is
            // not a mistake he will make - it is one he will notice.
            boolean aStoryHeCanTell = !himHole && (trade.theirGain() > 0 || himSimple > -2.0
                    || optics.gap() <= -TradeMarket.OPTICS_GRAB);
            String tier = !safeForMe ? "no"
                    : !noise && visiblyGoodForHim && optics.gap() <= TradeMarket.OPTICS_GRAB ? "send"
                    : aStoryHeCanTell ? "ask" : "no";
            boolean fair = tier.equals("send");
            // WHAT THE TRADE COSTS YOU NEXT MARCH, shown rather than buried.
            //
            // Justin: "where is the keeper value". It was nowhere - charged
            // inside the full-model gain and never displayed, so a row reading
            // +22.7 for 2026 and +8.6 on the full model gave no way to see the
            // 14.1 between them or what it was for.
            //
            // And the amount is not the man's own surplus, which is the part
            // worth showing. You keep TWO, so parting with Tuten does not cost
            // his +33.8 - it costs the drop in your best two, 66.6 to 52.5,
            // because Purdy backfills as the second keeper. Fourteen, not
            // thirty-four. Nobody would derive that from the man's own number.
            double hisRate = tradesPerYear.getOrDefault(trade.withManager(), 0.0);
            int[] record = tradeRecord.getOrDefault(trade.withManager(), new int[]{0, 0});
            double hisBest = elsewhere.getOrDefault(trade.withManager(), 0.0);
            double hisBestNaive = naive.getOrDefault(trade.withManager(), 0.0);
            double hisEdge = trade.theirGain() - hisBest;
            tradesJson.append(written++ == 0 ? "" : ",").append(String.format(
                    "{\"give\":%s,\"get\":%s,\"with\":%s,\"you\":%s,\"him\":%s,\"season\":%s,"
                            + "\"hisKeeper\":%s,\"hisKeeperTag\":%b,\"simple\":%s,\"hole\":%b,\"adpOut\":%s,\"adpIn\":%s,"
                            + "\"reads\":%s,\"men\":%d,\"reach\":%s,\"chain\":%s,"
                            + "\"himSimple\":%s,\"himSeason\":%s,\"himHole\":%b,"
                            + "\"hisBest\":%s,\"hisBestNaive\":%s,\"hisEdge\":%s,\"hisPartner\":%s,"
                            + "\"askPrice\":%s,\"overAsk\":%s,\"fair\":%b,\"hisRate\":%s,\"hisTrades\":%d,\"hisSeasons\":%d,"
                            + "\"low\":%s,\"high\":%s,\"noise\":%b,\"thins\":%s,\"tier\":%s,\"keeperCost\":%s}",
                    quote(label(trade.give(), nameOf)), quote(label(trade.get(), nameOf)),
                    quote(trade.withManager()), num(trade.myGain()), num(trade.theirGain()),
                    num(thisSeason), num(hisKeeper), TradeMarket.asksForAKeeper(hisKeeper),
                    num(simple), hole,
                    num(optics.mine()), num(optics.theirs()), quote(optics.verdict()), optics.menEachWay(),
                    reachOf.containsKey(trade) ? num(TradeMarket.reach(reachOf.get(trade))) : "null",
                    reachOf.containsKey(trade) ? chainJson(reachOf.get(trade), nameOf) : "null",
                    num(himSimple), num(himSeason), himHole, num(hisBest), num(hisBestNaive), num(hisEdge),
                    quote(market.partner().getOrDefault(trade.withManager(), "nobody")),
                    num(askPrice), num(trade.theirGain() - askPrice), fair,
                    num(hisRate), record[0], record[1], num(low), num(high), noise,
                    thins == null ? "null" : quote(thins), quote(tier), num(keeperCost)));
            if(fair){
                fairTrades++;
            }
            if(tier.equals("ask")){
                askTrades++;
            }
            if(hisEdge < 0){
                losesToElsewhere++;
            }
        }
        tradesJson.append("]");

        // ---- THE OTHER BOARD: trades that LOOK good to him and are not.
        //
        // Justin, 2026-09-06: "try to find trades where adp wise, it looks
        // advantageous, even though neither simple nor complex are advantageous."
        //
        // Every trade above had to clear `mutual()` - both sides gaining on the
        // model - so this set is precisely the one the main table throws away.
        // The premise is that draft position is what a trade is JUDGED on before
        // the season (which is why the ADP column exists at all) while the models
        // are what it is WORTH. Where those two disagree there is a trade he
        // reads as a win and the objective reads as a loss.
        //
        // Said plainly, because it should not be dressed up: ADP is the market's
        // aggregate opinion of these players and it is not obviously worse than
        // one repo's model. A row here means the two disagree - not that he is
        // being fooled. He may be right and the model wrong, and the honest way
        // to read the board is that it finds DISAGREEMENTS, then leaves the
        // judgement about who is correct where it belongs.
        double looksGoodBar = Double.parseDouble(System.getProperty("opticsBar", "25"));
        List<TradeMarket.Trade> mismatched = new ArrayList<>();
        for(TradeMarket.Trade trade : everySwap){
            if(trade.myGain() < 0.05 || trade.theirGain() > 0){
                continue;                    // I must gain and the full model must not like it for him
            }
            TradeMarket.Optics look = TradeMarket.optics(trade.give(), trade.get(), SleeperProjections::adpOf);
            if(look.gap() > -looksGoodBar){
                continue;                    // it does not visibly favour him on draft position
            }
            mismatched.add(trade);
        }
        mismatched.sort(Comparator.comparingDouble(TradeMarket.Trade::myGain).reversed());
        StringBuilder mirageJson = new StringBuilder("[");
        int mirages = 0;
        for(TradeMarket.Trade trade : mismatched){
            List<String> hisRoster = rosters.get(trade.withManager());
            List<String> hisAfter = TradeMarket.swap(hisRoster, trade.get(), trade.give());
            double himSimple = TradeMarket.simpleStarters(hisAfter, points, positionOf)
                    - hisSimpleBase.get(trade.withManager());
            if(himSimple > 0){
                continue;                    // the starters-only reading likes it, so it is not this board
            }
            List<String> after = TradeMarket.swap(rosters.get(me), trade.give(), trade.get());
            // AND IT MUST NOT GUT YOUR OWN LINEUP. Without this the board was
            // thirteen rows of "give away Baltimore Ravens" - the only defence -
            // each reading -95 on your own simple model. A trade that empties
            // your slot is not a clever read of the market, it is a trade you
            // would not make, and it drowned the two real rows.
            if(TradeMarket.slotsFilled(after, points, positionOf)
                    < TradeMarket.slotsFilled(rosters.get(me), points, positionOf)){
                continue;
            }
            double simple = TradeMarket.simpleStarters(after, points, positionOf)
                    - TradeMarket.simpleStarters(rosters.get(me), points, positionOf);
            TradeMarket.Optics look = TradeMarket.optics(trade.give(), trade.get(), SleeperProjections::adpOf);
            mirageJson.append(mirages++ == 0 ? "" : ",").append(String.format(
                    "{\"give\":%s,\"get\":%s,\"with\":%s,\"you\":%s,\"simple\":%s,"
                            + "\"him\":%s,\"himSimple\":%s,\"adpOut\":%s,\"adpIn\":%s,\"gap\":%s}",
                    quote(label(trade.give(), nameOf)), quote(label(trade.get(), nameOf)),
                    quote(trade.withManager()), num(trade.myGain()), num(simple),
                    num(trade.theirGain()), num(himSimple),
                    num(look.mine()), num(look.theirs()), num(look.gap())));
            if(mirages >= 40){
                break;
            }
        }
        mirageJson.append("]");

        // ---- NEXT YEAR: which two of his own men are the 2027 keepers.
        //
        // The surpluses were already computed and already priced into every
        // trade on the board - his side counts keeper value, so giving one away
        // shows up in the number. What was missing is him being able to SEE it.
        // A board that silently prices a keeper is a board that can talk him out
        // of one without ever naming which men are untouchable, and the goal is
        // to win this year AND next.
        //
        // Only the best TWO count, because that is the rule: two keepers a
        // season. A third-best surplus is worth nothing next March and pretending
        // otherwise would overprice a man he cannot actually keep.
        List<String> myMen = new ArrayList<>(rosters.getOrDefault(me, List.of()));
        myMen.sort(Comparator.comparingDouble((String id) -> -surplus.getOrDefault(id, 0.0)));
        StringBuilder keepersJson = new StringBuilder("[");
        int keeperRows = 0;
        for(String id : myMen){
            Double worth = surplus.get(id);
            Integer round = keeperRound.get(id);
            double margin = TradeMarket.keeperPointsRaw(keeperRound, points, bestByAdp,
                    everyPosition, configuration, slotOfPlayer, id);
            keepersJson.append(keeperRows == 0 ? "" : ",").append(String.format(
                    "{\"name\":%s,\"pos\":%s,\"round\":%s,\"surplus\":%s,\"margin\":%s,\"keep\":%b,\"refusal\":%s}",
                    quote(nameOf.getOrDefault(id, id)),
                    quote(positionOf.get(id) == null ? "?" : positionOf.get(id).name()),
                    round == null ? "null" : String.valueOf(round),
                    worth == null ? "null" : num(worth), num(margin),
                    keeperRows < 2 && worth != null && worth > 0,
                    keeperRefusal.containsKey(id) ? quote(keeperRefusal.get(id)) : "null"));
            keeperRows++;
        }
        keepersJson.append("]");

        // ---- what a piece is worth: who else could supply it
        StringBuilder supplyJson = new StringBuilder("[");
        Map<Position, Integer> starts = new TreeMap<>(Map.of(Position.QB, 1, Position.RB, 2,
                Position.WR, 3, Position.TE, 1, Position.DEF, 1));
        boolean firstSupply = true;
        for(Map.Entry<Position, Integer> entry : starts.entrySet()){
            int held = 0;
            for(String id : rosters.getOrDefault(me, List.of())){
                if(positionOf.get(id) == entry.getKey()){ held++; }
            }
            int sellers = TradeMarket.alternativeSellers(rosters, me, positionOf, entry.getKey(), entry.getValue());
            supplyJson.append(firstSupply ? "" : ",").append(String.format(
                    "{\"pos\":%s,\"held\":%d,\"start\":%d,\"sellers\":%d,\"verdict\":%s}",
                    quote(entry.getKey().name()), held, entry.getValue(), sellers,
                    quote(supplyVerdict(held, entry.getValue(), sellers))));
            firstSupply = false;
        }
        supplyJson.append("]");

        String json = String.format("{\"season\":%s,\"week\":%d,\"me\":%s,\"stamp\":%s,"
                        + "\"scenarios\":%d,\"keepers\":%b,\"lineupTotal\":%s,\"slots\":%d,"
                        + "\"lineup\":%s,\"trades\":%s,\"supply\":%s,\"faab\":%s,"
                        + "\"faabAll\":%d,\"faabContested\":%d,\"faabFree\":%s,\"costs\":[1,1.5,2,3],"
                        + "\"budget\":%d,\"wire\":%s,\"swapFloor\":%s,\"wireScenarios\":%d,\"lookahead\":%d,\"chainDepth\":%d,\"chainPool\":%d,\"pool\":%d,\"batnaPool\":%d,\"dataStamp\":%s,\"losesToElsewhere\":%d,\"insideItsOwnNoise\":%d,\"errorSeeds\":%d,\"keepers2027\":%s,\"fairTrades\":%d,\"askTrades\":%d,\"mirage\":%s,\"opticsBar\":%s,\"winAll\":%s,\"winContested\":%s}",
                quote(season), week, quote(me), quote(LocalDate.now().toString()),
                scenarios, withKeepers, num(lineup.starters()), lineup.starting().size(),
                lineupJson, tradesJson, supplyJson, faabGrid(allBand, costs),
                allPrices.size(), contestedPrices.size(),
                num(allPrices.isEmpty() ? 0 : allPrices.stream().filter(p -> p == 0).count() * 100.0 / allPrices.size()),
                budgetLeft, wireJson, num(swapFloor), wireScenarios, lookahead, chainDepth, chainPool, pool, batnaPool, quote(DataStamp.stamp()), losesToElsewhere, insideItsOwnNoise, errorSeeds, keepersJson, fairTrades, askTrades, mirageJson, num(looksGoodBar), winLadder(allBand), winLadder(contestedBand));

        Path target = Path.of("data", "console-" + season + "-w" + week + ".html");
        Files.writeString(target, page(json), StandardCharsets.UTF_8);
        System.out.printf("%d lineup rows, %d men worth adding, %d trades (%d lose to what he can"
                        + " get elsewhere), %d precomputed bids ($%d left)%n",
                mine.size(), wireRows, written, losesToElsewhere, 61 * 21 * costs.length, budgetLeft);
        System.out.println("written to " + target);
    }

    static String winLadder(FaabBid.Band band){
        int[] ladder = {0, 1, 2, 3, 5, 8, 13, 20, 35, 50};
        StringBuilder out = new StringBuilder("[");
        for(int i = 0; i < ladder.length; i++){
            out.append(i == 0 ? "" : ",").append(String.format("{\"bid\":%d,\"win\":%s}",
                    ladder[i], num(band.winChance(ladder[i]))));
        }
        return out.append("]").toString();
    }

    static String label(List<String> ids, Map<String, String> nameOf){
        List<String> names = new ArrayList<>();
        for(String id : ids){ names.add(nameOf.getOrDefault(id, id)); }
        return String.join(" + ", names);
    }

    static String page(String json){
        return PAGE.replace("__DATA__", json);
    }

    private static final String PAGE = """
<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>League console</title>
<style>
:root{--bg:#fbfbf9;--fg:#1a1a18;--dim:#6b6b66;--line:#e2e2dc;--card:#fff;--pos:#1a7f37;--neg:#b42318;--me:#fff8e1;--accent:#2b5c8a}
@media (prefers-color-scheme:dark){:root:not([data-theme=light]){--bg:#16161a;--fg:#e9e9e4;--dim:#9a9a94;--line:#2e2e34;--card:#1e1e23;--pos:#4ac26b;--neg:#f27a6e;--me:#2a2717;--accent:#7fb3e0}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Helvetica,Arial,sans-serif}
.wrap{max-width:1080px;margin:0 auto;padding:28px 20px 64px}
h1{font-size:22px;margin:0 0 2px;letter-spacing:-.01em}
.sub{color:var(--dim);font-size:13px;margin-bottom:20px}
nav{display:flex;gap:2px;border-bottom:1px solid var(--line);margin-bottom:22px;flex-wrap:wrap}
nav button{background:none;border:0;border-bottom:2px solid transparent;color:var(--dim);font:inherit;font-size:14px;padding:9px 14px;cursor:pointer}
nav button[aria-selected=true]{color:var(--fg);border-bottom-color:var(--accent);font-weight:600}
section{display:none}section.on{display:block}
table{border-collapse:collapse;width:100%;font-size:14px}
th,td{text-align:right;padding:7px 10px;border-bottom:1px solid var(--line);white-space:nowrap}
th{color:var(--dim);font-weight:500;font-size:12px;text-transform:uppercase;letter-spacing:.04em}
td.l,th.l{text-align:left}
tr.start td{font-weight:600}
tr.out td{color:var(--dim)}
.pos{color:var(--pos)}.neg{color:var(--neg)}
.note{color:var(--dim);font-size:13px;margin:14px 0 0;max-width:70ch}
.card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:18px;margin-bottom:18px}
.big{font-size:34px;font-weight:650;letter-spacing:-.02em}
.row{display:flex;gap:26px;flex-wrap:wrap;align-items:flex-end}
label{display:block;font-size:12px;color:var(--dim);text-transform:uppercase;letter-spacing:.04em;margin-bottom:6px}
input[type=range]{width:230px;accent-color:var(--accent)}
select,input[type=text]{background:var(--card);color:var(--fg);border:1px solid var(--line);border-radius:6px;padding:6px 9px;font:inherit;font-size:14px}
.scroll{overflow-x:auto}
.tag{font-size:11px;color:var(--dim);border:1px solid var(--line);border-radius:99px;padding:1px 8px;margin-left:6px}
tr.clickable{cursor:pointer}
tr.clickable:hover td{background:var(--card)}
.caret{color:var(--dim);font-size:10px;margin-left:4px}
.ladderbox{padding:6px 0 14px}
table.inner{width:auto;min-width:60%}
table.inner td{border:0;padding:3px 18px 3px 0;font-size:13px}
tr.ladder td{background:var(--card)}
th.side{border-bottom:1px solid var(--line);font-size:11px;letter-spacing:.06em;color:var(--fg)}
th.sub2{font-size:11px;font-weight:400;color:var(--dim);text-transform:none}
td.first,th.side+th.side,th.sub2:nth-child(4){border-left:1px solid var(--line)}
</style></head><body><div class="wrap">
<h1>League console</h1>
<div class="sub" id="sub"></div>
<nav id="tabs"></nav>
<section id="s-lineup" class="on"><div class="scroll"><table id="t-lineup"></table></div>
  <p class="note" id="doubt-note"></p>
  <p class="note">A man with no row in this week's projection feed is <b>not playing</b> &mdash; bye, inactive, or unpublished &mdash; which is a different statement from projected low. The odds beside a bench call are measured over five seasons: how often the man projected that much lower actually outscored the starter he could replace. 50% is a coin flip.</p></section>
<section id="s-wire">
  <div class="card"><div class="row">
    <div><label>FAAB left</label><div class="big" id="faabLeft">&mdash;</div><div class="sub" id="faabSub" style="margin:4px 0 0"></div></div>
    <div><label>Men on the wire worth a claim</label><div class="big" id="wireCount">&mdash;</div>
      <div class="sub" style="margin:4px 0 0">out of every free agent with a projection</div></div>
  </div></div>
  <div class="scroll"><table id="t-wire"></table></div>
  <p class="note" id="add-note"></p>
  <details style="margin-top:22px"><summary style="cursor:pointer;color:var(--dim);font-size:13px">What if a man were worth something else</summary>
  <div class="card" style="margin-top:12px"><div class="row">
    <div><label>What he is worth to your roster</label><input type="range" id="v" min="0" max="120" step="2" value="24"><div id="vlab" class="sub" style="margin:4px 0 0"></div></div>
    <div><label>FAAB left</label><input type="range" id="b" min="0" max="100" step="5" value="100"><div id="blab" class="sub" style="margin:4px 0 0"></div></div>
    <div><label>A dollar now is worth</label><select id="c"></select></div>
  </div>
  <div class="row" style="margin-top:20px">
    <div><label>Bid</label><div class="big" id="bid">&mdash;</div></div>
    <div><label>Chance of winning him</label><div class="big" id="win">&mdash;</div></div>
  </div></div>
  <div class="scroll"><table id="t-win"></table></div>
  <p class="note" id="wire-note"></p></details></section>
<section id="s-trades">
  <div class="row" style="margin-bottom:14px">
    <div><label>Only trades that also help 2026</label><select id="f26"><option value="1">yes</option><option value="0">show all</option></select></div>
    <div><label>How likely he says yes</label><select id="ffair"><option value="both">send + worth asking</option><option value="1">only the ones he thanks you for</option><option value="0">show everything</option></select></div>
    <div><label>He&rsquo;d need you for it</label><select id="fedge"><option value="0">show all</option><option value="1">only positive edge</option></select></div>
    <div><label>Manager</label><select id="fman"></select></div>
    <div><label>Search a name</label><input type="text" id="fname" placeholder="e.g. Tuten"></div>
    <div><label>Men each way</label><select id="fmen"><option value="0">any</option><option value="1">1 for 1</option><option value="2">2 for 2</option><option value="3">3 for 3</option></select></div>
    <div><label>Sort by</label><select id="fsort"><option value="you">your full</option><option value="reach">what it opens up</option><option value="simple">your simple</option><option value="season">your 2026</option><option value="him">his full</option><option value="himSimple">his simple</option><option value="hisEdge">his edge over elsewhere</option><option value="overAsk">his edge over selling them</option><option value="hisRate">how often he trades</option></select></div>
  </div>
  <div class="scroll"><table id="t-trades"></table></div>
  <p class="note" id="trade-note"></p></section>
<section id="s-mirage"><div class="scroll"><table id="t-mirage"></table></div>
  <p class="note" id="mirage-note"></p></section>
<section id="s-supply">
  <div class="scroll"><table id="t-keepers"></table></div>
  <p class="note" id="keeper-note"></p>
  <div class="scroll" style="margin-top:26px"><table id="t-supply"></table></div>
  <p class="note">A piece is only a chip if the other teams cannot supply it. A rival counts as an alternative seller when he carries more men at that position than the lineup starts.</p></section>
<script>
const D = __DATA__;
const f1 = n => (n>0?"+":"") + n.toFixed(1);
const sign = n => n>0 ? "pos" : n<0 ? "neg" : "";
document.getElementById("sub").textContent =
  D.me + " \\u00b7 " + D.season + " week " + D.week + " \\u00b7 built " + D.stamp +
  " \\u00b7 " + D.scenarios + " drawn seasons" + (D.keepers ? " \\u00b7 keeper value counted" : " \\u00b7 this season only");
const TABS = [["s-lineup","This week"],["s-wire","The wire"],["s-trades","Trades"],["s-mirage","Reads better than it is"],["s-supply","What you can sell"]];
const nav = document.getElementById("tabs");
TABS.forEach(([id,name],i)=>{ const b=document.createElement("button"); b.textContent=name;
  b.setAttribute("aria-selected", i===0); b.onclick=()=>{ TABS.forEach(([j])=>document.getElementById(j).classList.remove("on"));
  document.getElementById(id).classList.add("on"); [...nav.children].forEach(c=>c.setAttribute("aria-selected",c===b)); }; nav.appendChild(b); });

let h = "<tr><th class=l>Man</th><th class=l>Pos</th><th>Proj</th><th class=l>Status</th><th class=l>Verdict</th></tr>";
D.lineup.forEach(m=>{ const cls = !m.playing ? "out" : m.start ? "start" : "";
  const verdict = !m.playing ? "NOT PLAYING" : m.start ? "START"
    : "bench \\u00b7 " + m.gap.toFixed(1) + " behind, beats him " + Math.round(m.odds*100) + "% of the time";
  const doubt = m.status
    ? `<span class="tag neg">${m.status}</span>${m.instead?` <span class=sub>your ten are worth ${m.instead}; start him only if he is ${Math.round(m.breakEven*100)}% to play</span>`:""}`
    : "";
  h += `<tr class="${cls}"><td class=l>${m.name}</td><td class=l>${m.pos}</td><td>${m.playing?m.proj.toFixed(1):"\\u2014"}</td><td class=l>${doubt}</td><td class=l>${verdict}</td></tr>`; });
h += `<tr><td class=l colspan=2><b>Projected starters</b></td><td><b>${D.lineupTotal.toFixed(1)}</b></td><td class=l></td><td class=l>${D.slots} of ten slots</td></tr>`;
document.getElementById("t-lineup").innerHTML = h;
const doubts = D.lineup.filter(m=>m.start && m.status && m.instead);
document.getElementById("doubt-note").innerHTML = doubts.length
  ? `<b>${doubts.length} of your starters carry an injury tag.</b> The bar comes from REBUILDING your ten without him, not from naming a substitute &mdash; benching a man does not promote one specific other man, the lineup reshuffles and the effect cascades. An earlier version named "the best untagged bench man who shares his position or is flex-eligible", and since backs, receivers and tight ends are all flex-eligible it offered a running back to replace a receiver and a tight end to replace a back; neither can fill the slot. `
    + doubts.map(m=>`<b>${m.name}</b> (${m.status}) needs <b>${Math.round(m.breakEven*100)}%</b>`).join("; ")
    + `. What this still cannot know is the actual chance of him playing: that arrives in the inactive report about ninety minutes before kickoff, which is when the decision is made and not before.`
  : `No starter carries an injury tag.`;

document.getElementById("faabLeft").textContent = "$" + D.budget;
document.getElementById("faabSub").textContent = "$" + (100 - D.budget) + " of $100 already spent";
document.getElementById("wireCount").textContent = D.wire.filter(r=>!r.noise).length;
let a = "<tr><th class=l>Add</th><th class=l>Pos</th><th>Proj</th><th>Worth to you</th>"
  + "<th class=l>Instead of</th><th>Bid</th><th>Chance</th></tr>";
const ladderRow = (r,i) => {
  let L = `<tr class=ladder id="lad${i}" hidden><td class=l colspan=7><div class=ladderbox>`
    + `<div class=sub style="margin-bottom:8px">Every drop for <b>${r.name}</b> &mdash; the same claim against each man you hold. `
    + `The headline above is one row of this table, not a rating of the player.</div><table class=inner>`;
  r.ladder.forEach(x=>{
    const whole = x.whole===null ? "" :
      ` <span class=tag>empties a slot</span> <span class=sub>whole plan: then add ${x.thenAdd}, drop ${x.thenDrop} &rarr; ${f1(x.whole)}</span>`;
    L += `<tr><td class=l>drop ${x.drop}</td><td class="${sign(x.gain)}">${f1(x.gain)}</td><td class=l>${whole}</td></tr>`; });
  return L + `</table></div></td></tr>`; };
D.wire.forEach((r,i)=>{
  a += `<tr class="${r.noise?"out":""} clickable" data-lad="${i}"><td class=l>${r.name} <span class=caret>&#9656;</span></td><td class=l>${r.pos}</td><td>${r.proj.toFixed(1)}</td>`
    + `<td class="${r.noise?"":"pos"}">${f1(r.worth)}${r.noise?" <span class=tag>inside the noise</span>":""}</td>`
    + `<td class=l>${r.drop}${r.hole&&r.thenAdd?` + add ${r.thenAdd}, drop ${r.thenDrop} <span class=tag>whole plan</span>`:r.hole?" <span class=tag>empties a slot</span>":""}`
    + `${r.altDrop?`<div class=sub>or drop ${r.altDrop}, then add ${r.thenAdd||"a replacement"} and drop ${r.thenDrop||"someone"}: ${f1(r.altWorth)} for the whole plan</div>`:""}</td>`
    + `<td><b>$${r.bid}</b></td><td>${Math.round(r.win*100)}%</td></tr>`;
  a += ladderRow(r,i); });
if(!D.wire.length){ a += "<tr><td class=l colspan=7>Nothing on the wire improves this roster. Do not claim.</td></tr>"; }
document.getElementById("t-wire").innerHTML = a;
document.querySelectorAll("#t-wire tr.clickable").forEach(tr=>{ tr.onclick=()=>{
  const box = document.getElementById("lad"+tr.dataset.lad);
  box.hidden = !box.hidden;
  tr.querySelector(".caret").innerHTML = box.hidden ? "&#9656;" : "&#9662;"; }; });
document.getElementById("add-note").innerHTML =
  `<b>Worth to you</b> is computed, not asked for. For every free agent the model builds your roster with him and without the man he would displace, values both over ${D.wireScenarios} drawn seasons, and reports the difference &mdash; so the recommendation is the <i>pairing</i>, never the add on its own. <b>Instead of</b> is the cheapest drop that still fills all ten slots. Cutting someone you cannot replace &mdash; your only defence, say &mdash; is not a drop, it is the first half of a plan: your roster is full, so fielding a defence again costs another spot. Those show underneath priced as the WHOLE plan, both adds and both drops, never as the half that looks good on its own. The bid follows from the worth and the <b>$${D.budget} you actually have</b>, read off the rosters feed rather than typed in.<br><br>`
  + `A gain under <b>${D.swapFloor.toFixed(1)}</b> points is inside the objective's own seed-to-seed spread (ObjectiveStability), so it is the yardstick moving and not the roster improving &mdash; those rows are greyed and the headline counts only the men above it. That floor was measured over ${D.wireScenarios} drawn seasons, which is why this search runs at ${D.wireScenarios} and not the ${D.scenarios} the rest of the page uses: a noisier number does not get judged against a quieter number's yardstick. This is the same search <code>TuesdaySwap</code> runs in the terminal &mdash; the page calls <code>TuesdaySwap.search</code> itself rather than reimplementing it, over the same forty men per position &mdash; so the two agree to the decimal.`;

const csel = document.getElementById("c");
D.costs.forEach((c,i)=>{ const o=document.createElement("option"); o.value=i; o.textContent = c===1?"the same later (1.0x)":c+"x as much later"; csel.appendChild(o); });
const grid = new Map(D.faab.map(r=>[r.v+"|"+r.b+"|"+r.c, r]));
function bid(){ const v=+document.getElementById("v").value, b=+document.getElementById("b").value, c=+csel.value;
  document.getElementById("vlab").textContent = v + " points";
  document.getElementById("blab").textContent = "$" + b;
  const r = grid.get(v+"|"+b+"|"+c);
  document.getElementById("bid").textContent = r ? "$"+r.bid : "\\u2014";
  document.getElementById("win").textContent = r ? Math.round(r.win*100)+"%" : "\\u2014"; }
document.getElementById("b").value = Math.round(D.budget/5)*5;
["v","b","c"].forEach(id=>document.getElementById(id).addEventListener("input",bid)); bid();
let w = "<tr><th class=l>A bid of</th><th>beats a normal claim</th><th>beats a contested one</th></tr>";
D.winAll.forEach((r,i)=>{ w += `<tr><td class=l>$${r.bid}</td><td>${Math.round(r.win*100)}%</td><td>${Math.round(D.winContested[i].win*100)}%</td></tr>`; });
document.getElementById("t-win").innerHTML = w;
document.getElementById("wire-note").innerHTML =
  `Every bid above is <b>precomputed in Java</b> &mdash; ${D.faab.length.toLocaleString()} of them, one per value, budget and shadow price &mdash; and the sliders look them up. The page does no arithmetic of its own, which is what makes it checkable against the tools. Prices come from ${D.faabAll.toLocaleString()} settled contests in this league's own past, ${D.faabContested.toLocaleString()} of them contested. <b>${D.faabFree.toFixed(0)}% clear at nothing</b>, so most weeks the honest bid is $0 or $1. Nothing observable about a player predicts what he costs here, so this prices the field and your valuation &mdash; never the man.`;

const SHOWN = 40;
const mansel = document.getElementById("fman");
["everyone", ...new Set(D.trades.map(t=>t.with))].forEach(m=>{ const o=document.createElement("option"); o.textContent=m; mansel.appendChild(o); });
function trades(){ const only26 = document.getElementById("f26").value==="1";
  const man = mansel.value, q = document.getElementById("fname").value.toLowerCase();
  let rows = D.trades.filter(t => (!only26 || t.season>0) && (man==="everyone"||t.with===man)
    && (!q || (t.give+" "+t.get).toLowerCase().includes(q)));
  const want = document.getElementById("ffair").value;
  if(want==="1"){ rows = rows.filter(r=>r.fair); }
  else if(want==="both"){ rows = rows.filter(r=>r.tier!=="no"); }
  if(document.getElementById("fedge").value==="1"){ rows = rows.filter(r=>r.hisEdge>0); }
  const men = +document.getElementById("fmen").value;
  if(men) rows = rows.filter(r=>r.men===men);
  const key = document.getElementById("fsort").value;
  rows = rows.slice().sort((a,b)=>(b[key]===null?-1e9:b[key])-(a[key]===null?-1e9:a[key]));
  let t = "<tr><th class=l rowspan=2>You give</th><th class=l rowspan=2>You get</th>"
    + "<th colspan=4 class=side>YOU gain</th><th colspan=5 class=side>HE gains</th>"
    + "<th rowspan=2>Opens up</th><th rowspan=2>ADP out/in</th><th rowspan=2>He trades</th><th class=l rowspan=2>With &middot; how it reads</th></tr>"
    + "<tr><th class=sub2>simple</th><th class=sub2>full</th><th class=sub2>2026</th><th class=sub2>keeper cost</th>"
    + "<th class=sub2>simple</th><th class=sub2>full</th><th class=sub2>2026</th><th class=sub2>vs elsewhere</th><th class=sub2>vs selling them</th></tr>";
  rows.slice(0,SHOWN).forEach((r,i)=>{ t += `<tr class="${r.chain&&r.chain.length>1?"clickable":""} ${r.tier==="ask"?"out":""}" data-ch="${i}"><td class=l>${r.give}${r.chain&&r.chain.length>1?' <span class=caret>&#9656;</span>':""}</td><td class=l>${r.get}</td>`
    + `<td class="${r.hole?"":sign(r.simple)} first">${r.hole?'<span class=tag>slot</span>':f1(r.simple)}</td>`
    + `<td class="${r.noise?"":sign(r.you)}" title="across ${D.errorSeeds} seeds: ${f1(r.low)} to ${f1(r.high)}">`
    + `<b>${f1(r.you)}</b>${r.noise?' <span class=tag>noise</span>':` <span class=sub>&plusmn;${((r.high-r.low)/2).toFixed(1)}</span>`}</td>`
    + `<td class=${sign(r.season)}>${f1(r.season)}</td>`
    + `<td class="${r.keeperCost>0?"neg":""}">${r.keeperCost>0?"&minus;"+r.keeperCost.toFixed(1):"&mdash;"}</td>`
    + `<td class="${r.himHole?"":sign(r.himSimple)} first">${r.himHole?'<span class=tag>slot</span>':f1(r.himSimple)}</td>`
    + `<td class=${sign(r.him)}><b>${f1(r.him)}</b></td>`
    + `<td class=${sign(r.himSeason)}>${f1(r.himSeason)}</td>`
    + `<td class=${sign(r.hisEdge)} title="his best elsewhere ${f1(r.hisBest)}, before the recursion ${f1(r.hisBestNaive)}, partner ${r.hisPartner}">${f1(r.hisEdge)}</td>`
    + `<td class=${sign(r.overAsk)} title="selling those men one at a time would bring him ${f1(r.askPrice)}">${f1(r.overAsk)}</td>`
    + `<td class=${r.reach===null?"":sign(r.reach)}>${r.reach===null?"&mdash;":f1(r.reach)+(r.chain&&r.chain.length>1?` <span class=tag>${r.chain.length} deep</span>`:"")}</td>`
    + `<td>${r.adpOut.toFixed(0)} / ${r.adpIn.toFixed(0)}</td>`
    + `<td class="${r.hisRate<0.5?"neg":r.hisRate>=3?"pos":""}" title="${r.hisTrades} completed trades in ${r.hisSeasons} seasons">${r.hisRate.toFixed(2)}/yr</td>`
    + `<td class=l>${r.tier==="send"?"<b>SEND</b>":r.tier==="ask"?"<span class=tag>worth asking</span>":""} ${r.with} &middot; ${r.reads}${r.thins?` <span class="tag neg">thins ${r.thins}</span>`:""}`
    + (r.hisKeeperTag?'<span class="tag">his keeper</span>':'')+`</td></tr>`;
    if(r.chain && r.chain.length>1){
      t += `<tr class=ladder id="ch${i}" hidden><td class=l colspan=15><div class=ladderbox>`
        + `<div class=sub style="margin-bottom:8px">Step one is the trade in the row above &mdash; forced, whatever it is worth. Every step AFTER it is re-searched on the board the last one left and has to clear the ${D.swapFloor.toFixed(1)}-point floor.</div><table class=inner>`;
      r.chain.forEach((c,n)=>{ t += `<tr><td class=l>${n+1}. ${c.with}</td><td class=l>give ${c.give}</td><td class=l>get ${c.get}</td>`
        + `<td class=${sign(c.gain)}>${f1(c.gain)}</td><td><b>${f1(c.running)}</b> running</td></tr>`; });
      t += `</table></div></td></tr>`; } });
  if(!rows.length) t += "<tr><td class=l colspan=15>Nothing on the board fits that.</td></tr>";
  document.getElementById("t-trades").innerHTML = t;
  document.querySelectorAll("#t-trades tr.clickable").forEach(tr=>{ tr.onclick=()=>{
    const box = document.getElementById("ch"+tr.dataset.ch);
    box.hidden = !box.hidden;
    tr.querySelector(".caret").innerHTML = box.hidden ? "&#9656;" : "&#9662;"; }; });
  document.getElementById("trade-note").innerHTML =
    `${Math.min(rows.length, SHOWN)} of ${rows.length} matching offers shown (${D.trades.length} searched), one, two and three men each way, all good for <b>both</b> sides &mdash; an offer the other manager loses on is one he declines.<br><br>`
    + `<b>The same three numbers for both managers</b>, so a trade can be argued in whichever terms the man opposite actually uses. <b>SIMPLE</b> is what the best legal ten projects and nothing else &mdash; the number to put in a message, because anybody can check it. <b>FULL</b> prices a bench by how often it is promoted and draws whole historical seasons for injury and boom-or-bust; on his side it is also <i>loss-averse on keepers</i>. <b>2026</b> is this season alone, no keeper value either way. When simple and full disagree, that gap IS the argument: a trade worth +8.8 full and +0.0 simple is one where all the value is bench and injury risk, and no starters-only manager will ever see it. Where a trade <b>empties a slot</b> for either side the simple number is withheld and tagged rather than shown &mdash; sending away an only defence costs the whole slot, which reads as &minus;95 and means &ldquo;you would pick one up&rdquo;.<br><br>`
    + `<b>Your full gain carries its own error bar.</b> Each trade is re-valued under ${D.errorSeeds} seeds of the same objective &mdash; the search is not repeated, so this is the valuation's own wobble and not a different board &mdash; and the range is on hover. A trade whose gain goes negative on any seed is tagged <b>noise</b>: the model cannot tell it from zero, whatever the headline says. <b>${D.insideItsOwnNoise} of ${D.trades.length} are in that state.</b> This replaced a single 6.8-point floor, which was the wrong instrument: that number was measured on a roster MARGINAL at 480 drawn seasons, and a trade is a different quantity at a different count &mdash; measured spreads across sixty real trades ran from 0.4 to 18.0, so no one floor fits them.<br><br>`
    + `<b>KEEPER COST</b> is what the trade takes off next March, and it is charged inside your FULL number &mdash; the gap between <b>full</b> and <b>2026</b> is exactly it. It is NOT the man's own surplus: you keep two, so parting with your best keeper costs the drop in your best PAIR, because the third man backfills. Giving up Tuten (+33.8 on his own) costs 14.1, because Purdy steps up behind him. Nobody would derive that from the keeper table, which is why it is here.<br><br>`
    + `<b>A trade tagged "thins"</b> sends a body away from a position whose UNTAGGED men already cannot fill its slots. That is not in the projections, which price a Questionable starter exactly like a healthy one: what is scarce at such a position is availability, not points, and a doubtful man is still a ticket that a traded man is not. Those offers are excluded from this view entirely.<br><br>`
    + `<b>HE TRADES</b> is completed deals per season from this league's own log, and it is the column to read first. Everything else here models how a rival VALUES an offer; this is the only one that asks whether he does deals at all, and it is probably the larger term. Nothing on the board is worth more than a manager's willingness to open the message: the two biggest gains below go to somebody who has completed one trade in three seasons, while the most active traders sit lower down the table with offers that would actually be taken.<br><br>`
    + `<b>${D.fairTrades} to SEND and ${D.askTrades} WORTH ASKING</b>, of ${D.trades.length} searched. Nothing is hidden by a filter any more, because gating on every test at once assumed the man opposite is right about everything &mdash; and fifty-seven of these failed only because he loses on the best-legal-ten calculation, which almost nobody in this league performs. A <b>SEND</b> is defensible to offer and to have offered. <b>WORTH ASKING</b> is good for you, safe for your roster, and leaves him a story he can tell himself &mdash; he gains on the full model, or barely loses on starters, or receives the earlier pick. It is a long shot, not a fair deal, and it is greyed to say so.<br><br>`
    + `The old rule kept only offers that survive their own error bar, help him on the number he can check himself, empty nobody's lineup, and do not visibly grab the earlier pick &mdash; and that filter is ON by default.</b> This is a keeper league: the same eleven managers every season, so being somebody people want to deal with is an asset that compounds into next year rather than a nicety. A trade he thanks you for is worth more than a slightly better one he resents.<br><br>`
    + `<b>${D.losesToElsewhere} of the ${D.trades.length} offers lose to something he can already get from somebody else.</b> <b>VS ELSEWHERE</b> is the column that says so, and it is the one that decides whether an offer gets taken. His gain is not persuasive on its own: what matters is what it beats. Each rival's best mutually-good trade with somebody who is not you is computed the same way, and this is his gain from your offer minus that. <b>And that alternative is limited by everyone else's alternatives</b>: his best trade needs the manager across from HIM to prefer it to his own options. So the rivals are PAIRED OFF &mdash; each pair striking the deal with the most joint surplus to divide, best pairs forming first &mdash; and his fallback is what he gets from the partner he would actually end up with, not the best partner he can name. Hover a number to see that alongside the naive maximum, which credits him with deals the other man would decline. <b>A negative number means he has something better waiting and will not need you.</b> Two limits, both making the column optimistic: the search is size-balanced, so it cannot see the uneven deals where a manager sends two men for one and refills off the wire &mdash; anyone who can build those has better alternatives than this shows &mdash; and it runs at a pool of ${D.batnaPool}. So a thin edge here is not an edge.<br><br>`
    + `<b>VS SELLING THEM</b> is the same question asked one man at a time. Every player you are asking for has a price of his own: the best a straight one-for-one with somebody who is not you would bring his owner. Asking for two men is asking him to forgo two of those, so this is your offer minus their sum. One-for-one is what isolates a man's contribution &mdash; in a bundle the gain belongs to the pair and splitting it would be a choice rather than a measurement &mdash; which also makes it a floor: bundles can be worth more than their parts, and a man priced at nothing has no one-for-one buyer, not no value.<br><br>`
    + `<b>Opens up</b> answers a different question from <b>Full</b>: not what the trade is worth, but what the board looks like <i>after</i> it. Each of the top ${D.lookahead} is forced as step one and the chain re-searched from the board it leaves, up to ${D.chainDepth} deep, every step clearing the ${D.swapFloor.toFixed(1)}-point floor. The chain searches each side's best ${D.chainPool} men rather than the ${D.pool} the table above uses &mdash; seventy-two board re-searches at the full pool is hours of compute &mdash; so <b>Opens up is a floor on what the trade unlocks, not a ceiling</b>. A +5 that opens a +80 chain beats a +20 that opens a +45, and the greedy chain alone can never tell you that &mdash; it always takes the biggest step and so never finds out where the small one led. Click a row with a caret to see the sequence. Rows showing &mdash; were outside the top ${D.lookahead} by immediate gain and were not priced this way: a chain is a full re-search of every rival at every step.<br><br>`
    + `<b>ADP out/in</b> is how it reads before the season, when draft position is most of how a trade is judged: a lower number is an earlier pick, so asking for a much earlier one draws a no however good the arithmetic. His side is <b>loss-averse on keepers</b> &mdash; he feels every point of one he gives up and takes no credit for one he receives &mdash; so asking for his keeper is priced at what it costs him. <b>Acceptance itself is not modelled</b>: Sleeper records only completed trades, 51 in five seasons and not one refusal.`; }
["f26","ffair","fedge","fname","fmen","fsort"].forEach(id=>document.getElementById(id).addEventListener("input",trades));
mansel.addEventListener("change",trades); trades();

let m = "<tr><th class=l>You give</th><th class=l>You get</th><th>You full</th><th>You simple</th>"
  + "<th>He full</th><th>He simple</th><th>ADP out/in</th><th>Picks he gains</th><th class=l>With</th></tr>";
D.mirage.forEach(r=>{ m += `<tr><td class=l>${r.give}</td><td class=l>${r.get}</td>`
  + `<td class=pos>${f1(r.you)}</td><td class=${sign(r.simple)}>${f1(r.simple)}</td>`
  + `<td class=${sign(r.him)}>${f1(r.him)}</td><td class=${sign(r.himSimple)}>${f1(r.himSimple)}</td>`
  + `<td>${r.adpOut.toFixed(0)} / ${r.adpIn.toFixed(0)}</td><td class=pos>${(-r.gap).toFixed(0)}</td>`
  + `<td class=l>${r.with}</td></tr>`; });
if(!D.mirage.length) m += "<tr><td class=l colspan=9>Nothing on the board reads better than it is.</td></tr>";
document.getElementById("t-mirage").innerHTML = m;
document.getElementById("mirage-note").innerHTML =
  `<b>${D.mirage.length} of them</b>, and read the warning before the table. Trades the main table <b>throws away</b>. Every offer there had to gain for both sides on the model; these gain for you and <b>not</b> for him &mdash; neither on the full model nor on starters alone &mdash; while handing him the visibly earlier pick, by at least ${D.opticsBar.toFixed(0)} places of draft position. That is the gap between what a trade is JUDGED on before the season and what it is WORTH.<br><br>`
  + `<b>Read this board honestly, and think twice before sending from it.</b> ADP is the whole market's opinion of these players; the model is one repository's. A row here is a <b>disagreement</b> between them, not proof he is being fooled &mdash; he may be right and the model wrong, and on a player the market rates far above the projection that is the likelier way round.<br><br>`
  + `And there is a cost the numbers do not carry. This is a keeper league with the same eleven managers every year: a trade both models say he loses, sent because draft position makes it look otherwise, is how somebody stops being a person the league trades with. That price is paid next season and the one after, against a gain measured this week. The <b>Trades</b> tab defaults to the offers he would thank you for; this board exists because you asked what the gap between perception and value looks like, and the honest answer is that it is usually thin and rarely worth what sending it costs.`;

let k = "<tr><th class=l>Your man</th><th class=l>Pos</th><th>Keeper round</th><th>Surplus</th><th class=l>2027</th></tr>";
D.keepers2027.forEach(r=>{ k += `<tr class="${r.keep?"start":""}"><td class=l>${r.name}</td><td class=l>${r.pos}</td>`
  + `<td>${r.refusal?`<span class="tag neg">cannot</span>`:r.round===null?"&mdash;":"r"+r.round}</td>`
  + `<td class="${r.surplus===null?"":sign(r.surplus)}">${r.surplus===null?"&mdash;":r.surplus>0?f1(r.surplus):`<span class=sub>${f1(r.margin)} short</span>`}</td>`
  + `<td class=l>${r.keep?"<b>KEEP</b>":r.refusal?`<span class=sub>${r.refusal}</span>`:""}</td></tr>`; });
document.getElementById("t-keepers").innerHTML = k;
const kept = D.keepers2027.filter(r=>r.keep);
document.getElementById("keeper-note").innerHTML =
  `Priced off <b>this season's draft</b>, which is what next season's keeper costs are made of. The panel used to read the PREVIOUS draft &mdash; correct for the 2026 decision, made in August and already history, and wrong for 2027: a man drafted this year was not in that board at all and silently took the undrafted default. Ten of sixteen rounds were wrong in both directions.<br><br>`
  + `A man taken in the <b>first two rounds cannot be kept at any price</b>, and one kept this year costs a round MORE next year. <b>Surplus</b> is what a man is worth beyond the pick you spend to keep him, measured at his own position. Only the best <b>two</b> count, because two is what the rules allow &mdash; a third-best surplus is worth nothing next March, and pricing it as though it were would overvalue a man you cannot actually keep.<br><br>`
  + (kept.length
      ? `On today's roster your 2027 keepers are <b>${kept.map(r=>r.name+" (r"+r.round+", "+f1(r.surplus)+")").join("</b> and <b>")}</b>, worth <b>${f1(kept.reduce((a,r)=>a+r.surplus,0))}</b> together. Every trade on this page already counts that &mdash; giving one away shows up in your own gain &mdash; but the board will never tell you which men those are, so here they are. A trade that moves one of them is a decision about next season, not this one.`
      : `Nothing on this roster carries a positive keeper surplus, so no trade this season can cost you a 2027 keeper. That is worth knowing before you protect somebody out of habit.`);

let s = "<tr><th class=l>Position</th><th>You hold</th><th>You start</th><th>Rivals with a spare</th><th class=l>So</th></tr>";
D.supply.forEach(r=>{
  s += `<tr><td class=l>${r.pos}</td><td>${r.held}</td><td>${r.start}</td><td>${r.sellers} of 11</td><td class=l>${r.verdict}</td></tr>`; });
document.getElementById("t-supply").innerHTML = s;
</script></div></body></html>
""";
}
