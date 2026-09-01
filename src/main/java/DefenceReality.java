import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.*;

/**
 * THREE CLAIMS ABOUT DEFENCES, CHECKED.
 *
 * Justin, 2026-09-01: "Defenses tend to be picked later in my league, and their
 * scores appear to plateau for 2026, and historically I believe defense
 * projections and adps are more meaningless than for other positions: those are
 * all things you can go verify/refute."
 *
 * They are, so this does. Each section answers one claim, from committed data:
 *
 *   1  WHEN do defences actually go in HIS league? - the five real drafts
 *   2  Does the 2026 defence curve PLATEAU? - this year's projection curve,
 *      against the other positions on the same scale
 *   3  Are defence ADPs and projections LESS PREDICTIVE than other positions? -
 *      rank correlation of preseason order with what the season did, by
 *      position, over the era boards
 *
 * The model currently takes a defence around round 9 and DefenceTiming puts the
 * flat peak at round 7. Justin's rule is that anything inside round 9 is a clear
 * mistake, rounds 10-13 need explaining, and 14-16 is sensible. If these three
 * claims hold, the model is wrong and the objective is why.
 *
 *   ./gradlew run -Pmain=DefenceReality -Pkeepers=Tuten,Purdy -q
 */
public class DefenceReality {

    public static void main(String[] args) throws Exception {
        LiveSetup setup = LiveSetup.forTonight();
        AAAConfiguration configuration = setup.configuration;

        whenDefencesReallyGo(configuration);
        doesTheCurvePlateau(setup);
        howPredictiveIsEachPosition();
        doesTheSimulatedRoomAgree(setup);
        whatTheMarketSaysThisYear(setup);
        howFarThisLeagueDriftsFromTheMarket(setup);
    }

    /**
     * CLAIM 6: by how much does THIS LEAGUE disagree with the national market,
     * per position?
     *
     * Justin: "my league historically disagrees with that market and that
     * should be part of the model." Quite right, and it is the criticism that
     * kills the floor - a floor says "never before round 10", which is a
     * constraint; the league taking defences consistently later than ADP says
     * is a FACT, and facts belong in the model where they can also explain the
     * two rounds of drift the floor never touched.
     *
     * For every position, over every stored draft: the pick a man really went
     * at, minus the pick the market said he would go at. Positive means this
     * league waits longer than the market.
     */
    private static void howFarThisLeagueDriftsFromTheMarket(LiveSetup setup){
        System.out.printf("%n%n=== 6. HOW FAR THIS LEAGUE DRIFTS FROM THE MARKET ===%n%n");
        Map<Position, List<Double>> drift = MarketDrift.measurePerPick();
        System.out.printf("%-5s %8s %14s %s%n", "POS", "n", "median ratio",
                "reading (keeper-corrected, relative)");
        for(Position position : new Position[]{Position.RB, Position.WR, Position.TE,
                Position.QB, Position.DEF}){
            List<Double> values = drift.get(position);
            if(values == null || values.isEmpty()){
                continue;
            }
            List<Double> sorted = new ArrayList<>(values);
            Collections.sort(sorted);
            double median = sorted.get(sorted.size() / 2);
            double mean = sorted.stream().mapToDouble(Double::doubleValue)
                    .average().orElse(0);
            System.out.printf("%-5s %8d %14.2f %s%n", position, values.size(), median,
                    Math.abs(median - 1.0) < 0.12 ? "follows the market"
                            : median > 1 ? "waits LONGER than the market says"
                                    : "reaches EARLIER than the market says");
        }
        System.out.printf("%nratio of the live pick a man really went at to the live pick%n"
                + "he was expected at ONCE THE KEEPERS ARE OFF THE BOARD. 1.00 is the%n"
                + "market exactly. the first version of this measured absolute picks%n"
                + "against an uncorrected baseline and reported the whole league%n"
                + "reaching, which was two dozen absent players, not a habit.%n");
    }

    /**
     * CLAIM 5: is the 2026 BOARD itself telling the room to take defences early?
     *
     * The simulated room can only pick from a choice set of the sixty best
     * remaining by ADP. If this year's defence ADPs sit early, defences enter
     * that set early and no amount of feature work will keep the room off them
     * - the market, not the model, would be the thing saying "take one".
     */
    private static void whatTheMarketSaysThisYear(LiveSetup setup){
        System.out.printf("%n%n=== 5. WHAT THE 2026 MARKET SAYS ===%n%n");
        List<double[]> defences = new ArrayList<>();
        for(String id : setup.planner.points().keySet()){
            Player player = Player.getPlayerFromSIDV2(id);
            double adp = SleeperProjections.adpOf(id);
            if(player != null && player.position == Position.DEF && adp > 0){
                defences.add(new double[]{adp});
            }
        }
        defences.sort(Comparator.comparingDouble(a -> a[0]));
        System.out.printf("the ten earliest defence ADPs on the 2026 board:%n   ");
        for(int i = 0; i < Math.min(10, defences.size()); i++){
            System.out.printf("%.0f ", defences.get(i)[0]);
        }
        System.out.printf("%n%nround of the earliest defence by ADP: %.0f%n",
                defences.isEmpty() ? -1 : Math.ceil(defences.get(0)[0] / 12.0));
        System.out.printf("his league has never taken one before round 10 (pick 109).%n");
    }

    /**
     * CLAIM 4, MINE NOT HIS: does the SIMULATED room draft each position when
     * the real one does?
     *
     * This is the mechanism that would actually explain the model reaching for
     * a defence in round 9. It does not decide by consulting a rule about
     * defences; it simulates the other eleven managers and asks what survives.
     * If the simulated room takes defences earlier than Justin's real room ever
     * has, the model correctly concludes it must reach - from a false premise.
     *
     * The same question for every position, because a fix that names DEF is a
     * rule rather than a model.
     */
    private static void doesTheSimulatedRoomAgree(LiveSetup setup){
        System.out.printf("%n%n=== 4. DOES THE SIMULATED ROOM DRAFT WHEN THE REAL ONE"
                + " DOES? ===%n%n");
        DraftSimulator simulator = setup.simulator;
        Map<Position, List<Integer>> simulated = new EnumMap<>(Position.class);
        for(int trial = 0; trial < 40; trial++){
            Map<String, Integer> takenAt = simulator.simulateOnce(
                    new Random(8_800_000L + 7919L * trial));
            for(Map.Entry<String, Integer> entry : takenAt.entrySet()){
                Player player = Player.getPlayerFromSIDV2(entry.getKey());
                DraftSimulator.Slot slot = simulator.slotAt(entry.getValue());
                if(player != null && slot != null){
                    simulated.computeIfAbsent(player.position, u -> new ArrayList<>())
                            .add(slot.round());
                }
            }
        }
        Map<Position, List<Integer>> real = realRoundsByPosition();
        System.out.printf("%-5s %9s %9s %10s   %9s %9s %10s%n", "POS",
                "REAL med", "earliest", "in 1-9", "SIM med", "earliest", "in 1-9");
        for(Position position : new Position[]{Position.RB, Position.WR, Position.TE,
                Position.QB, Position.DEF}){
            List<Integer> sim = simulated.get(position);
            List<Integer> was = real.get(position);
            if(sim == null || sim.isEmpty() || was == null || was.isEmpty()){
                continue;
            }
            Collections.sort(sim);
            Collections.sort(was);
            System.out.printf("%-5s %9d %9d %9.0f%%   %9d %9d %9.0f%%%n", position,
                    was.get(was.size() / 2), was.get(0),
                    100.0 * was.stream().filter(r -> r <= 9).count() / was.size(),
                    sim.get(sim.size() / 2), sim.get(0),
                    100.0 * sim.stream().filter(r -> r <= 9).count() / sim.size());
        }
        // EVERY POSITION, NOT JUST THE ONE WITH THE OBVIOUS SYMPTOM.
        //
        // Justin: "isn't the model requiring fine tuning for defenses which
        // makes me question the credibility of the other positions". The right
        // answer to that is not reassurance, it is the same table for all five
        // - if the room model is wrong somewhere else it should be visible in
        // exactly this form, and it is: look at the tight ends.
        System.out.printf("%nWHOLE DISTRIBUTION, REAL vs SIM, EVERY POSITION%n");
        System.out.printf("%-5s %-22s %s%n", "POS", "REAL   1-7 8-9 10-13 14-16",
                "SIM    1-7 8-9 10-13 14-16   worst band gap");
        int[][] allBands = {{1, 7}, {8, 9}, {10, 13}, {14, 16}};
        for(Position position : new Position[]{Position.RB, Position.WR, Position.TE,
                Position.QB, Position.DEF}){
            List<Integer> was = real.get(position);
            List<Integer> now = simulated.get(position);
            if(was == null || now == null || was.isEmpty() || now.isEmpty()){
                continue;
            }
            StringBuilder realRow = new StringBuilder();
            StringBuilder simRow = new StringBuilder();
            double worst = 0;
            for(int[] band : allBands){
                double r = 100.0 * was.stream()
                        .filter(x -> x >= band[0] && x <= band[1]).count() / was.size();
                double m = 100.0 * now.stream()
                        .filter(x -> x >= band[0] && x <= band[1]).count() / now.size();
                realRow.append(String.format("%5.0f", r));
                simRow.append(String.format("%5.0f", m));
                worst = Math.max(worst, Math.abs(r - m));
            }
            System.out.printf("%-5s %-22s %-22s %5.0f points%s%n", position,
                    realRow.toString(), simRow.toString(), worst,
                    worst > 15 ? "   <- WRONG" : worst > 10 ? "   <- off" : "");
        }

        // WHERE exactly are the remaining early defences? "rounds 1-9" lumps a
        // pick at round 9 - which the 2026 market itself puts at ADP 98 - in
        // with one at round 4, and those are very different faults.
        List<Integer> simDef = simulated.get(Position.DEF);
        List<Integer> realDef = real.get(Position.DEF);
        if(simDef != null && realDef != null){
            System.out.printf("%nDEFENCES BY BAND, real against simulated:%n");
            System.out.printf("%-10s %10s %10s%n", "BAND", "REAL", "SIM");
            int[][] bands = {{1, 7}, {8, 9}, {10, 13}, {14, 16}};
            for(int[] band : bands){
                long was = realDef.stream()
                        .filter(r -> r >= band[0] && r <= band[1]).count();
                long now = simDef.stream()
                        .filter(r -> r >= band[0] && r <= band[1]).count();
                System.out.printf("rounds %-4s %9.0f%% %9.0f%%%n",
                        band[0] + "-" + band[1],
                        100.0 * was / realDef.size(), 100.0 * now / simDef.size());
            }
        }
        System.out.printf("%ncompare the DEF row against section 1: in five real drafts%n"
                + "the earliest defence ever taken was round 10 and NONE went in%n"
                + "rounds 1-9. if the simulated room takes them earlier, the model is%n"
                + "reaching for a defence because it believes it has to.%n");
    }

    /** CLAIM 1: defences go late in this league. */
    private static void whenDefencesReallyGo(AAAConfiguration configuration){
        System.out.printf("%n=== 1. WHEN DEFENCES REALLY GO, in this league ===%n%n");
        System.out.printf("%-8s %-34s %s%n", "SEASON", "ROUNDS THE DEFENCES WENT IN",
                "earliest");
        List<Integer> everyRound = new ArrayList<>();
        for(String season : configuration.getPreviousSeasons()){
            DraftBacktest.Season past;
            try {
                past = new DraftBacktest.Season(configuration, season);
            }
            catch(RuntimeException unavailable){
                continue;
            }
            List<Integer> rounds = new ArrayList<>();
            for(JsonElement element : past.picks){
                JsonObject pick = element.getAsJsonObject();
                if(!pick.has("player_id") || pick.get("player_id").isJsonNull()){
                    continue;
                }
                Player player = Player.getPlayerFromSIDV2(
                        pick.get("player_id").getAsString());
                if(player == null || player.position != Position.DEF){
                    continue;
                }
                if(pick.has("round") && !pick.get("round").isJsonNull()){
                    rounds.add(pick.get("round").getAsInt());
                }
            }
            Collections.sort(rounds);
            everyRound.addAll(rounds);
            System.out.printf("%-8s %-34s %s%n", season, rounds,
                    rounds.isEmpty() ? "-" : "round " + rounds.get(0));
        }
        Collections.sort(everyRound);
        if(everyRound.isEmpty()){
            System.out.printf("%n   no defence picks found in the stored drafts.%n");
            return;
        }
        int inNine = 0;
        int tenToThirteen = 0;
        for(int round : everyRound){
            if(round <= 9){
                inNine++;
            }
            else if(round <= 13){
                tenToThirteen++;
            }
        }
        System.out.printf("%n   %d defences drafted across those seasons.%n", everyRound.size());
        System.out.printf("   earliest ever: round %d.  median: round %d.%n",
                everyRound.get(0), everyRound.get(everyRound.size() / 2));
        System.out.printf("   rounds 1-9: %d (%.0f%%)   rounds 10-13: %d (%.0f%%)"
                        + "   rounds 14-16: %d (%.0f%%)%n",
                inNine, 100.0 * inNine / everyRound.size(),
                tenToThirteen, 100.0 * tenToThirteen / everyRound.size(),
                everyRound.size() - inNine - tenToThirteen,
                100.0 * (everyRound.size() - inNine - tenToThirteen) / everyRound.size());
    }

    /** CLAIM 2: the 2026 defence curve plateaus. */
    private static void doesTheCurvePlateau(LiveSetup setup){
        System.out.printf("%n%n=== 2. DOES THE 2026 CURVE PLATEAU? ===%n%n");
        System.out.printf("drop from the best man at a position to the Nth, as a %% of the best."
                + "%na position that plateaus loses little as you go down it.%n%n");
        System.out.printf("%-5s %8s %8s %8s %8s %8s%n", "POS", "rank1", "to 4", "to 8",
                "to 12", "to 16");
        for(Position position : new Position[]{Position.RB, Position.WR, Position.TE,
                Position.QB, Position.DEF}){
            double[] curve = setup.curve.get(position);
            if(curve == null || curve.length < 17 || curve[1] <= 0){
                continue;
            }
            System.out.printf("%-5s %8.0f %7.0f%% %7.0f%% %7.0f%% %7.0f%%%n", position,
                    curve[1],
                    100 * (curve[1] - curve[4]) / curve[1],
                    100 * (curve[1] - curve[8]) / curve[1],
                    100 * (curve[1] - curve[12]) / curve[1],
                    100 * (curve[1] - curve[16]) / curve[1]);
        }
    }

    /** CLAIM 3: defence ADP predicts the season worse than other positions do. */
    private static void howPredictiveIsEachPosition(){
        System.out.printf("%n%n=== 3. HOW PREDICTIVE IS PRESEASON ORDER, BY POSITION ===%n%n");
        System.out.printf("spearman of preseason board order against what the season really%n"
                + "did, per position, over the era boards. 1.0 is perfect, 0.0 is noise.%n%n");
        Map<String, EraBoards.Board> boards;
        try {
            boards = EraBoards.usable("ppr", EraIngest.MIN_RATE, EraIngest.minDepth());
        }
        catch(Exception unavailable){
            System.out.printf("   era boards unavailable: %s%n", unavailable);
            return;
        }
        Map<Position, List<Double>> byPosition = new EnumMap<>(Position.class);
        for(Map.Entry<String, EraBoards.Board> entry : boards.entrySet()){
            EraBoards.Board board = entry.getValue();
            Map<String, Double> skill = board.seasonPoints();
            Map<String, Double> defence = LeagueActuals.seasonDefencePoints(entry.getKey());
            for(Position position : new Position[]{Position.RB, Position.WR,
                    Position.TE, Position.QB, Position.DEF}){
                List<String> men = new ArrayList<>();
                for(String id : board.ids()){
                    if(board.positionOf().get(id) == position){
                        men.add(id);
                    }
                }
                List<double[]> pairs = new ArrayList<>();
                for(int i = 0; i < men.size(); i++){
                    Double scored = position == Position.DEF
                            ? defence.get(men.get(i)) : skill.get(men.get(i));
                    if(scored != null && scored > 0){
                        pairs.add(new double[]{i + 1, scored});
                    }
                }
                if(pairs.size() >= 8){
                    byPosition.computeIfAbsent(position, u -> new ArrayList<>())
                            .add(spearman(pairs));
                }
            }
        }
        System.out.printf("%-5s %10s %10s %s%n", "POS", "seasons", "spearman", "");
        for(Position position : new Position[]{Position.RB, Position.WR, Position.TE,
                Position.QB, Position.DEF}){
            List<Double> values = byPosition.get(position);
            if(values == null || values.isEmpty()){
                continue;
            }
            double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            System.out.printf("%-5s %10d %10.3f %s%n", position, values.size(), mean,
                    position == Position.DEF ? "  <- the claim is that this is worst" : "");
        }
    }

    /** What round each position really went in, across the stored drafts. */
    private static Map<Position, List<Integer>> realRoundsByPosition(){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        Map<Position, List<Integer>> real = new EnumMap<>(Position.class);
        for(String season : configuration.getPreviousSeasons()){
            DraftBacktest.Season past;
            try {
                past = new DraftBacktest.Season(configuration, season);
            }
            catch(RuntimeException unavailable){
                continue;
            }
            for(JsonElement element : past.picks){
                JsonObject pick = element.getAsJsonObject();
                if(!pick.has("player_id") || pick.get("player_id").isJsonNull()
                        || !pick.has("round") || pick.get("round").isJsonNull()){
                    continue;
                }
                // A KEEPER IS NOT A DRAFT DECISION. He occupies a round the
                // commissioner assigned him; nobody chose him there against the
                // rest of the board. The SIMULATION never drafts keepers - they
                // are pre-assigned - so counting them on the real side compares
                // a distribution containing twenty-four keepers against one
                // containing none. Seven of this year's are tight ends.
                com.google.gson.JsonElement isKeeper = pick.get("is_keeper");
                if(isKeeper != null && !isKeeper.isJsonNull() && isKeeper.getAsBoolean()){
                    continue;
                }
                Player player = Player.getPlayerFromSIDV2(
                        pick.get("player_id").getAsString());
                if(player != null){
                    real.computeIfAbsent(player.position, u -> new ArrayList<>())
                            .add(pick.get("round").getAsInt());
                }
            }
        }
        return real;
    }

    /** Rank correlation between preseason order and realised points. */
    private static double spearman(List<double[]> pairs){
        int n = pairs.size();
        List<double[]> byPoints = new ArrayList<>(pairs);
        byPoints.sort((a, b) -> Double.compare(b[1], a[1]));
        Map<Double, Integer> outcomeRank = new HashMap<>();
        for(int i = 0; i < n; i++){
            outcomeRank.put(byPoints.get(i)[1], i + 1);
        }
        double sum = 0;
        for(double[] pair : pairs){
            double d = pair[0] - outcomeRank.get(pair[1]);
            sum += d * d;
        }
        return 1 - (6 * sum) / ((double) n * (n * n - 1));
    }
}
