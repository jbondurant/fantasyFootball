import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The twenty-four-keeper board, pinned.
 *
 * Every one of these is a fault the two-keeper version could have made and that
 * a reader could not see from the printed mean. The slate is built and
 * transplanted on fixture data, so none of it needs a network.
 */
class EraSlateTest {

    // =====================================================================
    // The flag is off, and off means BYTE-IDENTICAL.
    // =====================================================================

    /**
     * Nothing may move under the other work in this tree. Every score on record
     * - the plan at 2007, BoardValue at 1935 - was measured with all three of
     * these false, and a default that drifted would make tonight's numbers
     * unreproducible on the one night they are read.
     */
    @Test
    void everyKeeperFlagIsOffByDefault(){
        assertFalse(EraSlate.enabled(), "-PleagueKeepers must default off");
        assertFalse(PlanBacktest.keeperRanks(), "-PkeeperRanks must default off");
        assertFalse(PlanBacktest.holdKeepers(), "-PholdKeepers must default off");
        assertEquals(Set.of(), PlanBacktest.spentPicks(),
                "with the flag off no pick is spent, so the other eleven draft"
                        + " at every pick exactly as they always did");
        assertEquals(List.of(), PlanBacktest.offBoard(board()),
                "with both flags off nobody is off the board");
        assertEquals(List.of(), PlanBacktest.heldByMe(board()),
                "with both flags off nobody is on my roster");
    }

    // =====================================================================
    // Rank, not round - the rule EraKeepers established.
    // =====================================================================

    /**
     * A slot asking for the 3rd back gets the 3rd back on THAT board, whoever
     * he is. Copying the ROUND instead is the fault EraKeepers names: Bucky
     * Irving costs a 13th, and a 13th-round back on an old board is a
     * replacement-level man being called a keeper.
     */
    @Test
    void theTransplantCopiesPositionalRankNotPrice(){
        List<EraSlate.Held> slate = List.of(
                new EraSlate.Held("them", "a back", Position.RB, 3, 13, false),
                new EraSlate.Held("them", "a receiver", Position.WR, 1, 13, false),
                new EraSlate.Held("them", "an end", Position.TE, 2, 3, false));
        Map<EraSlate.Held, String> landed = EraSlate.on(slate, board());
        assertEquals(3, landed.size());
        assertEquals("rb3", landed.get(slate.get(0)));
        assertEquals("wr1", landed.get(slate.get(1)));
        assertEquals("te2", landed.get(slate.get(2)));
    }

    /**
     * A rank deeper than the board has men at that position simply goes
     * unfilled - it must not silently fall through onto somebody else, which is
     * how a slate of twenty-four could hand one historical man to two managers.
     */
    @Test
    void aRankTheBoardCannotReachFillsNobody(){
        List<EraSlate.Held> slate = List.of(
                new EraSlate.Held("them", "too deep", Position.TE, 99, 5, false));
        assertEquals(Map.of(), EraSlate.on(slate, board()));
    }

    // =====================================================================
    // My own two are not allowed to move.
    // =====================================================================

    /**
     * Justin's keepers take their ranks from EraKeepers, NOT from this class's
     * measurement of the ADP board, so a slate of twenty-four hands him the
     * same two men PlanBacktest.keeperIDs always did. Here the ADP board is
     * made to disagree on purpose - it calls Purdy QB1 and Tuten RB1 - and the
     * slate must ignore it.
     */
    @Test
    void myOwnTwoComeFromEraKeepersWhateverTheBoardSays(){
        Map<String, Integer> rank = new HashMap<>();
        rank.put("purdy", 1);
        rank.put("tuten", 1);
        rank.put("nacua", 1);
        List<EraSlate.Held> slate = EraSlate.slate(
                List.of(keeper("me", "Brock", "Purdy", Position.QB, "purdy", 13),
                        keeper("me", "Bhayshul", "Tuten", Position.RB, "tuten", 12),
                        keeper("them", "Puka", "Nacua", Position.WR, "nacua", 13)),
                new EraSlate.Ranked(rank, Map.of(Position.QB, 1, Position.RB, 1,
                        Position.WR, 1)),
                "me", new int[]{9, 23}, keeper -> 0.0);
        assertEquals(9, rankOf(slate, "Brock Purdy"), "Purdy is QB9, not QB1");
        assertEquals(23, rankOf(slate, "Bhayshul Tuten"), "Tuten is RB23, not RB1");
        assertEquals(1, rankOf(slate, "Puka Nacua"), "everyone else is read off the board");
        assertEquals(2, slate.stream().filter(EraSlate.Held::mine).count());
    }

    /**
     * And the men that lands on are the men keeperIDs would have handed him:
     * the QB at positional rank 9 and the RB at rank 23, which is exactly the
     * walk keeperIDs does. The two-keeper path and the twenty-four-keeper path
     * must agree about Justin or the before/after comparison is measuring two
     * things at once.
     */
    @Test
    void mineOnAgreesWithTheTwoKeeperWalk(){
        PlanBacktest.Board board = deepBoard();
        List<EraSlate.Held> slate = List.of(
                new EraSlate.Held("me", "mine at QB", Position.QB, 9, 13, true),
                new EraSlate.Held("me", "mine at RB", Position.RB, 23, 12, true),
                new EraSlate.Held("them", "theirs", Position.WR, 1, 13, false));
        List<String> mine = new ArrayList<>();
        for(Map.Entry<EraSlate.Held, String> entry : EraSlate.on(slate, board).entrySet()){
            if(entry.getKey().mine()){
                mine.add(entry.getValue());
            }
        }
        assertEquals(List.of("qb9", "rb23"), sorted(mine));
        assertEquals(9, PlanBacktest.rankOn(board, "qb9"));
        assertEquals(23, PlanBacktest.rankOn(board, "rb23"));
    }

    // =====================================================================
    // Twenty-four slots must stay twenty-four.
    // =====================================================================

    /**
     * If the two boards being read ever disagree about who QB9 is, two keepers
     * claim one slot, the transplant returns twenty-three men and a kept player
     * quietly reappears on the board. The collider is pushed down instead, and
     * mine is never the one that moves.
     */
    @Test
    void twoKeepersMayNotClaimOneSlot(){
        List<EraSlate.Held> resolved = EraSlate.resolve(List.of(
                new EraSlate.Held("them", "collides", Position.QB, 9, 7, false),
                new EraSlate.Held("me", "mine", Position.QB, 9, 13, true),
                new EraSlate.Held("them", "also 10", Position.QB, 10, 4, false)));
        assertEquals(3, resolved.size());
        assertEquals(9, rankOf(resolved, "mine"), "mine never moves");
        assertEquals(List.of(9, 10, 11),
                resolved.stream().map(EraSlate.Held::rank).sorted().toList(),
                "each keeper ends on a rank of his own");
        assertEquals(3, EraSlate.on(resolved, deepBoard()).size(),
                "and all three still take a man off the board");
    }

    /** A keeper the ADP board never listed is deeper than everyone it did. */
    @Test
    void anUnlistedKeeperGoesPastTheDeepestManTheBoardRanks(){
        List<EraSlate.Held> slate = EraSlate.slate(
                List.of(keeper("them", "Un", "Listed", Position.TE, "missing", 11)),
                new EraSlate.Ranked(Map.of("someone", 1), Map.of(Position.TE, 30)),
                "me", new int[]{9, 23}, keeper -> 0.0);
        assertEquals(1, slate.size(), "he is not dropped - that would free him up");
        assertEquals(31, slate.get(0).rank(),
                "one past TE30, not rank 1, which would make him the best in football");
    }

    // =====================================================================
    // The picks the keepers spend.
    // =====================================================================

    /**
     * A keeper costs its owner a PICK as well as taking a man off the board.
     * Remove the men and keep the picks and the other eleven consume
     * twenty-four extra players off the bottom - a different wrong board, not a
     * smaller one - so draft() skips them. It must never skip one of MINE:
     * MY_PICKS is already the post-keeper schedule, with rounds 12 and 13 sold,
     * and dropping one of its fourteen would silently shorten Justin's draft.
     */
    @Test
    void spentPicksNeverEatOneOfMyFourteen(){
        PlanBacktest.Board board = deepBoard();
        List<String> drafted = PlanBacktest.draft(board, null);
        assertEquals(PlanBacktest.MY_PICKS.length, drafted.size(),
                "fourteen picks with the flag off, keepers held separately");
        assertEquals(drafted.size(), Set.copyOf(drafted).size(), "nobody twice");
    }

    /** rankOn counts the whole board, not who is left - the curve indexes the board. */
    @Test
    void rankOnIsAPositionsOwnRank(){
        PlanBacktest.Board board = deepBoard();
        assertEquals(1, PlanBacktest.rankOn(board, "qb1"));
        assertEquals(9, PlanBacktest.rankOn(board, "qb9"));
        assertEquals(23, PlanBacktest.rankOn(board, "rb23"));
    }

    // =====================================================================
    // fixtures
    // =====================================================================

    private static Keeper keeper(String manager, String first, String last,
                                 Position position, String id, int round){
        Player player = new Player(first, last, "FA", position, -1, -1, "sr-" + id, -1, id);
        return new Keeper(manager, player, round);
    }

    private static int rankOf(List<EraSlate.Held> slate, String name){
        for(EraSlate.Held held : slate){
            if(held.name().equals(name)){
                return held.rank();
            }
        }
        throw new AssertionError(name + " is not in the slate");
    }

    private static List<String> sorted(List<String> ids){
        List<String> out = new ArrayList<>(ids);
        out.sort(String::compareTo);
        return out;
    }

    /** A tiny board: rb1..rb5, wr1..wr5, te1..te5, qb1..qb5, in ADP order. */
    private static PlanBacktest.Board board(){
        return board(5);
    }

    /** Deep enough for a fourteen-pick draft and a rank-23 back. */
    private static PlanBacktest.Board deepBoard(){
        return board(40);
    }

    private static PlanBacktest.Board board(int deep){
        List<String> ids = new ArrayList<>();
        Map<String, Position> positionOf = new HashMap<>();
        for(int rank = 1; rank <= deep; rank++){
            for(Position position : new Position[]{Position.RB, Position.WR,
                    Position.TE, Position.QB, Position.DEF}){
                String id = position.toString().toLowerCase() + rank;
                ids.add(id);
                positionOf.put(id, position);
            }
        }
        List<Map<String, Double>> weekly = new ArrayList<>();
        for(int week = 0; week < WeeklyActuals.WEEKS; week++){
            Map<String, Double> points = new HashMap<>();
            for(String id : ids){
                points.put(id, 10.0);
            }
            weekly.add(points);
        }
        return new PlanBacktest.Board("fixture", ids, positionOf, weekly);
    }
}
