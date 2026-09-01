import PlayerImportAndSetup.Position;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * A PICK THE BOARD DOES NOT CARRY MUST NOT BE FILED IN ROUND ONE.
 *
 * minePicks was rewritten to attribute Justin's roster by SEAT, so a man the
 * simulator's board has never heard of - anyone past ADP 250 - now reaches
 * rulesRoster, which the old attribution silently dropped. rulesRoster was
 * never adjusted for it and still reads:
 *
 *     Integer at = state.takenAtOf(id);
 *     int taken_at = at == null ? 1 : simulator.slotAt(at).round();
 *
 * `at` is null for exactly those men, so every one of them is filed in ROUND
 * ONE. Two things follow, both measured by this test on a real board:
 *
 *   1. Round one is normally already spent, so the rules decline him and the
 *      screen prints "ON MY ROSTER BUT OUTSIDE THE RULES - counted anyway:
 *      WR <name> (round 1): round 1 is already spent". The pick was completely
 *      ordinary. At a sixty-second clock that reads as the tool telling him his
 *      own pick was illegal.
 *
 *   2. His real round is never marked spent, so roundsRemaining() reports one
 *      more pick than he owns.
 *
 * What this is NOT: a relaxed strand check. whyNotDraft counts only remaining
 * rounds AFTER the round being asked about, and the phantom is always an early
 * round, so legalAt is unchanged at every round - which
 * theLedgerFixDoesNotMoveWhatIsLegal pins so the fix cannot quietly become a
 * change to the recommendation.
 */
public class OffBoardRoundLedgerTest {

    private static DraftPlanner planner;
    private static DraftSimulator simulator;

    private static synchronized void warm() throws Exception {
        if(planner != null){
            return;
        }
        System.setProperty("scheduleRounds", "16");
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        planner = DraftPlanner.forCurrentSeason(configuration,
                DraftPlanner.keepersFromProperty(configuration), choice, earliness);
        simulator = planner.simulator();
    }

    /** A real, projected man the simulator's board does not carry. */
    private static String offBoardMan(){
        for(Map.Entry<String, Double> entry : planner.points().entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player != null && player.position == Position.WR
                    && SleeperProjections.adpOf(entry.getKey()) > 300){
                return entry.getKey();
            }
        }
        return null;
    }

    /** His first `seats` live picks, with one of them off the board. */
    private static List<String> boardWithOffBoardPickAt(int seats, int offBoardSeat){
        List<String> board = new ArrayList<>(simulator.initialState().boardView());
        List<String> taken = new ArrayList<>();
        int fill = 0;
        int mineSeen = 0;
        for(int p = 1; p <= 200 && mineSeen < seats; p++){
            DraftSimulator.Slot seat = simulator.slotAt(p);
            if(seat == null || seat.keeperSlot()){
                continue;
            }
            if(planner.me().equals(seat.manager())){
                mineSeen++;
                if(mineSeen == offBoardSeat){
                    taken.add(offBoardMan());
                    continue;
                }
            }
            taken.add(board.get(fill++));
        }
        return taken;
    }

    @Test
    public void everyRoundHeHasSpentIsMarkedSpent() throws Exception {
        warm();
        assertNotNull(offBoardMan(), "the fixture needs a projected man past ADP 300");
        List<String> taken = boardWithOffBoardPickAt(6, 2);
        DraftSimulator.SimState state = simulator.stateAfter(taken);
        List<String> mine = LiveBoard.minePicks(planner, simulator, taken);
        RosterRules.Roster roster = LiveBoard.rulesRoster(planner, simulator, state,
                mine, new ArrayList<>());

        List<Integer> spent = roster.roundsSpent();
        assertEquals(new HashSet<>(spent).size(), spent.size(),
                "two of his men are filed in the same round - " + spent
                        + " - because a man the board does not carry is filed in"
                        + " round one whatever seat he really came from");
        assertEquals(16 - roster.size(), roster.roundsRemaining().size(),
                "he has " + (16 - roster.size()) + " seats left but the ledger"
                        + " offers " + roster.roundsRemaining().size()
                        + ": " + roster.roundsRemaining());
    }

    @Test
    public void anOrdinaryPickIsNotReportedAsOutsideTheRules() throws Exception {
        warm();
        List<String> taken = boardWithOffBoardPickAt(6, 2);
        DraftSimulator.SimState state = simulator.stateAfter(taken);
        List<String> mine = LiveBoard.minePicks(planner, simulator, taken);
        List<String> declined = new ArrayList<>();
        LiveBoard.rulesRoster(planner, simulator, state, mine, declined);

        // ASSERT THE FAULT, NOT THE FIXTURE. This used to require `declined`
        // to be EMPTY, and that depends on which men the day-cached board
        // happens to deal into his seats: on 2026-09-01 it dealt him Justin
        // Herbert, and with Purdy kept a second quarterback before round 10 is
        // refused CORRECTLY. The test failed on a right answer.
        //
        // The fault this test exists for is the round ledger filing an
        // off-board man in a round that is already taken, which reads on
        // screen as the tool calling one of his own picks illegal. That
        // message is the thing to forbid, and forbidding it still fails if the
        // ledger regresses - the pre-fix run printed exactly
        // "WR Durron Neal (round 1): round 1 is already spent".
        for(String why : declined){
            assertFalse(why.contains("already spent"),
                    "the round ledger filed one of his men in a round that was"
                            + " already taken, so the screen calls his own pick"
                            + " illegal: " + why);
            assertFalse(why.contains("comes before round"),
                    "the round ledger filed one of his men behind a round he"
                            + " had already used: " + why);
        }
    }

    /**
     * THE FIX MUST NOT MOVE THE RECOMMENDATION.
     *
     * legalAt gates which positions get priced at all, so if repairing the
     * ledger changed it, this would be a model change wearing a bug fix's
     * clothes. Measured across every round of the draft.
     */
    @Test
    public void theLedgerFixDoesNotMoveWhatIsLegal() throws Exception {
        warm();
        List<String> taken = boardWithOffBoardPickAt(6, 2);
        DraftSimulator.SimState state = simulator.stateAfter(taken);
        List<String> mine = LiveBoard.minePicks(planner, simulator, taken);
        RosterRules.Roster shipped = LiveBoard.rulesRoster(planner, simulator, state,
                mine, new ArrayList<>());

        // The same men, each filed in the next round that is genuinely free -
        // which is what the seat order says they cost.
        RosterRules.Roster honest = RosterRules.live().justins();
        int round = 1;
        for(String id : mine){
            if(planner.myKeeperIDs().contains(id)){
                continue;
            }
            Player player = Player.getPlayerFromSIDV2(id);
            while(honest.roundsSpent().contains(round)){
                round++;
            }
            honest = honest.holdAnyway(player.firstName + " " + player.lastName,
                    player.position, round++);
        }
        for(int at = 1; at <= 16; at++){
            assertEquals(honest.legalAt(at), shipped.legalAt(at),
                    "repairing the round ledger changed what is legal at round "
                            + at + " - that is a recommendation change, not a fix");
        }
    }
}
