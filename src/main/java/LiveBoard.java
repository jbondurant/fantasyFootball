import PlayerImportAndSetup.Position;
import java.util.*;

/**
 * The model at the table: this board, this roster, right now.
 *
 * Everything before this priced a HYPOTHETICAL board. BoardValue's shape assumed
 * the draft falls at ADP, which it never does; its adaptive arm read a
 * historical board; PairwiseOdds answers about pick numbers rather than about
 * the men actually left. This reads the live draft, takes the roster Justin
 * actually holds, and prices what is actually on the board.
 *
 * WHAT IT WILL NOT DO, and cannot. Every candidate is offered to
 * RosterRules.canDraft before it is priced, so the nonsense in TRAPS.md is not
 * avoided by good behaviour - it is unreachable. A third quarterback, a second
 * one before round 10, a seventeenth man, a pick in a keeper round, a plan that
 * strands the lineup: none of them can be produced, because the roster type
 * refuses to represent them.
 *
 * HOW IT PRICES. Marginal lineup points, the only currency that is at once
 * cross-position comparable and roster-aware:
 *
 *     value = mean over 600 worlds of the best legal lineup,
 *             each man drawn from what men of his POSITIONAL RANK really scored
 *
 * Ranks come from the live board - how many of that position have actually gone
 * - not from ADP. Depth is priced because those worlds are drawn from a pooled
 * distribution rather than a mean, so a deep back who beats a hurt starter in
 * some seasons is worth something in exactly those seasons. No weeks anywhere:
 * two numbers a season, his August rank and his February total.
 *
 * AND THE WAIT. Value alone says take the best man. What matters is the
 * difference between taking him now and taking whoever is left at the next pick,
 * with the drain rate for each position measured from this board rather than
 * assumed equal - assuming it equal is what made the first adaptive arm draft
 * TE TE QB QB.
 *
 *   ./gradlew run -Pmain=LiveBoard -Pkeepers=Tuten,Purdy [-PdraftId=<id>]
 */
public class LiveBoard {

    public static void main(String[] args) throws Exception {
        System.setProperty("scheduleRounds", System.getProperty("scheduleRounds", "16"));
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        String draftID = System.getProperty("draftId", configuration.getDraftID());

        List<Keeper> myKeepers = DraftPlanner.keepersFromProperty(configuration);
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, myKeepers,
                choice, earliness);
        DraftSimulator simulator = planner.simulator();

        // Sixteen seasons of what men at each rank really returned.
        Map<String, List<DetectionLag.Man>> wider = NflverseBoards.usable(null);
        List<String> order = new ArrayList<>(new TreeMap<>(wider).keySet());
        List<PairwiseOdds.Man> men = PairwiseOdds.nflverseMen(wider, order);
        Map<Position, double[]> curve = RankDraft.pointsByRank(men);
        Map<Position, List<List<Double>>> pools = BoardValue.pools(men);

        List<String> taken = LiveDraft.livePicks(draftID);
        DraftSimulator.SimState state = simulator.stateAfter(taken);
        DraftSimulator.Slot slot = simulator.slotOf(state);

        List<String> mine = new ArrayList<>(planner.myKeeperIDs());
        for(String id : taken){
            Integer at = state.takenAtOf(id);
            if(at != null && simulator.slotAt(at) != null
                    && planner.me().equals(simulator.slotAt(at).manager())){
                mine.add(id);
            }
        }

        // Always price MY next pick, never whichever pick the draft happens to
        // be on. Before the draft starts slotOf() returns pick 1, which is not
        // Justin's, and the first version cheerfully priced taking a man at a
        // pick he does not own against waiting until one he does.
        int onPick = slot == null ? 200 : slot.pickNumber();
        int pick = onPick;
        for(int p = onPick; p <= 200; p++){
            DraftSimulator.Slot mineAt = simulator.slotAt(p);
            if(mineAt != null && planner.me().equals(mineAt.manager())){
                pick = p;
                break;
            }
        }
        DraftSimulator.Slot mineSlot = simulator.slotAt(pick);
        int round = mineSlot == null ? 16 : mineSlot.round();
        if(pick != onPick){
            System.out.printf("%n(the draft is on pick %d, which is not mine -"
                    + " pricing my next, pick %d)%n", onPick, pick);
        }
        System.out.printf("%nTHE BOARD AS IT IS%n%n");
        System.out.printf("pick %d, round %d, %d men gone. my roster (%d): %s%n",
                pick, round, taken.size(), mine.size(), shape(mine));

        // What the rules permit here, before anything is priced.
        RosterRules rules = RosterRules.live();
        RosterRules.Roster roster = rules.justins();
        for(String id : mine){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player == null || planner.myKeeperIDs().contains(id)){
                continue;
            }
            Integer at = state.takenAtOf(id);
            int taken_at = at == null ? 1 : simulator.slotAt(at).round();
            if(roster.canDraft(player.position, taken_at)){
                roster = roster.draft(player.firstName + " " + player.lastName,
                        player.position, taken_at);
            }
        }
        List<Position> legal = roster.legalAt(round);
        System.out.printf("the rules allow here: %s%n", legal);
        System.out.printf("still needed for a legal lineup: %s%n%n", roster.stillNeeds());

        // My roster as (position, rank) on the live board.
        List<BoardValue.Slot> held = new ArrayList<>();
        for(String id : mine){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null){
                held.add(new BoardValue.Slot(player.position,
                        depth(planner, taken, player.position, id)));
            }
        }

        int next = nextPickAfter(simulator, state, planner, pick);
        System.out.printf("%-5s %-24s %8s %10s %10s   %s%n", "POS", "BEST AVAILABLE",
                "RANK", "ADDS NOW", "VS WAIT", "verdict");

        Map<Position, Double> urgency = new EnumMap<>(Position.class);
        Map<Position, Double> adds = new EnumMap<>(Position.class);
        Map<Position, String> best = new EnumMap<>(Position.class);
        for(Position position : new Position[]{Position.RB, Position.WR, Position.TE,
                Position.QB, Position.DEF}){
            String candidate = bestAvailable(planner, taken, position);
            if(candidate == null){
                continue;
            }
            String why = roster.whyNotDraft(position, round);
            if(why != null){
                Player player = Player.getPlayerFromSIDV2(candidate);
                System.out.printf("%-5s %-24s %8s %10s %10s   REFUSED: %s%n", position,
                        player == null ? candidate
                                : player.firstName + " " + player.lastName,
                        "-", "-", "-", why);
                continue;
            }
            int rank = depth(planner, taken, position, candidate);
            int later = next < 0 ? rank : rank + drain(planner, taken, position, pick, next);
            double base = BoardValue.empirical(held, pools, curve, order.size());
            List<BoardValue.Slot> now = new ArrayList<>(held);
            now.add(new BoardValue.Slot(position, rank));
            double addsNow = BoardValue.empirical(now, pools, curve, order.size()) - base;
            List<BoardValue.Slot> then = new ArrayList<>(held);
            then.add(new BoardValue.Slot(position, later));
            double wait = addsNow
                    - (BoardValue.empirical(then, pools, curve, order.size()) - base);
            urgency.put(position, wait);
            adds.put(position, addsNow);
            best.put(position, candidate);
            Player player = Player.getPlayerFromSIDV2(candidate);
            System.out.printf("%-5s %-24s %8d %10.1f %10.1f   %s%n", position,
                    player == null ? candidate : player.firstName + " " + player.lastName,
                    rank, addsNow, wait,
                    wait > 15 ? "TAKE NOW" : wait > 5 ? "lean take" : "he keeps");
        }

        // Rank on the cost of waiting, and break ties on raw value - otherwise
        // an empty board, where nothing has drained yet and every wait costs
        // zero, is decided by whichever position the loop reached first.
        String verdict = urgency.entrySet().stream()
                .max(Comparator.<Map.Entry<Position, Double>>comparingDouble(
                                e -> Math.round(e.getValue() * 10) / 10.0)
                        .thenComparingDouble(e -> adds.getOrDefault(e.getKey(), 0.0)))
                .map(e -> e.getKey().toString())
                .orElse("nothing legal");
        System.out.printf("%n   the model takes: %s%n", verdict);
        System.out.printf("%nADDS NOW is what he adds to the season your STARTERS score, over the%n"
                + "man the wire would give you. VS WAIT is that minus what the best of his%n"
                + "position would add at pick %s - so it is the cost of waiting, and it is%n"
                + "what to rank on. A position the rules refuse is never priced at all.%n",
                next < 0 ? "(none left)" : String.valueOf(next));
    }

    /** How many of a position have gone, plus one - his rank on THIS board. */
    static int depth(DraftPlanner planner, List<String> taken, Position position, String him){
        int gone = 0;
        for(String id : taken){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null && player.position == position && !id.equals(him)){
                gone++;
            }
        }
        return gone + 1;
    }

    /**
     * How many more of this position go before my next pick.
     *
     * Two sources, blended, because neither survives alone. What the room has
     * ACTUALLY done is the right signal but at pick 7 it rests on six
     * observations, and at pick 1 on none - the first version of this returned
     * zero for every position at an empty board, which says waiting is free and
     * is obviously false. What ADP EXPECTS is available before a pick is made
     * and is the same per-position rate the historical arm uses.
     *
     * So ADP is the prior and the room is the evidence, with the room taking
     * over as the draft supplies it. The one thing that must not happen is a
     * single rate shared across positions: that assumption is what drafted
     * TE TE QB QB, and it is the only signal this model has.
     */
    static int drain(DraftPlanner planner, List<String> taken, Position position,
                     int pick, int next){
        int gone = 0;
        for(String id : taken){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null && player.position == position){
                gone++;
            }
        }
        double observed = taken.isEmpty() ? 0 : (double) gone / taken.size();
        double expected = adpRate(planner, position, pick, next);
        // Half weight to the room after about thirty picks, which is roughly
        // when a run on a position stops being three men in a row.
        double trust = taken.size() / (taken.size() + 30.0);
        double rate = trust * observed + (1 - trust) * expected;
        return Math.max(0, (int) Math.round(rate * (next - pick)));
    }

    /** The share of picks between here and my next that ADP gives this position. */
    static double adpRate(DraftPlanner planner, Position position, int pick, int next){
        int between = 0;
        for(String id : planner.points().keySet()){
            Player player = Player.getPlayerFromSIDV2(id);
            double adp = SleeperProjections.adpOf(id);
            if(player != null && player.position == position
                    && adp >= pick && adp < next){
                between++;
            }
        }
        return next <= pick ? 0 : (double) between / (next - pick);
    }

    static String bestAvailable(DraftPlanner planner, List<String> taken, Position position){
        Set<String> gone = new HashSet<>(taken);
        String best = null;
        double most = -1;
        for(Map.Entry<String, Double> entry : planner.points().entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player == null || player.position != position || gone.contains(entry.getKey())){
                continue;
            }
            if(entry.getValue() > most){
                most = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    static int nextPickAfter(DraftSimulator simulator, DraftSimulator.SimState state,
                             DraftPlanner planner, int pick){
        for(int at = pick + 1; at <= 200; at++){
            DraftSimulator.Slot slot = simulator.slotAt(at);
            if(slot != null && planner.me().equals(slot.manager())){
                return at;
            }
        }
        return -1;
    }

    static String shape(List<String> roster){
        StringBuilder out = new StringBuilder();
        for(String id : roster){
            Player player = Player.getPlayerFromSIDV2(id);
            out.append(out.isEmpty() ? "" : " ").append(player == null ? "?" : player.position);
        }
        return out.toString();
    }
}
