import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One draft, replayed on a past season's real board and graded on what those
 * players really did.
 *
 * The same game PlanBacktest plays - my slot, a serpentine order, eleven
 * opponents taking best available by ADP, and a week-by-week best legal lineup
 * scored on outcomes - rebuilt on {@link EraBoards} so it can run on seasons
 * back to 2010 rather than the five with FantasyPros CSVs on disk. Three
 * differences, each forced by leaving 2021:
 *
 *   - the number of weeks comes from the season, not from a constant 18;
 *   - points are league-scored (a passing touchdown is worth 6), because
 *     ScoringAudit showed grading on pts_half_ppr costs every starting
 *     quarterback 55-66 points a season;
 *   - the round count is a knob, because a 2012 board is not deep enough to
 *     supply a sixteen-round draft and pretending otherwise hands out phantom
 *     players.
 *
 * Deterministic on purpose. Opponents follow ADP exactly, so two plans compared
 * on one season differ only by their own choices - no Monte Carlo noise to
 * confuse with a regime effect, which is the question this exists to answer.
 */
public class EraGame {

    /** My seat. Slot 7 of 12, as in the real league. */
    public static final int SLOT = 7;

    /** The starting lineup: QB, RB, RB, WR, WR, WR, TE, FLEX, FLEX, DEF. */
    public static final int STARTERS = 10;

    /**
     * THE KEEPER DECISION, made explicitly because making it by accident would
     * answer the wrong question.
     *
     * Justin's 2026 draft is not a plain draft. He holds Purdy and Tuten before
     * it starts, at a cost of rounds 12 and 13, so he begins with a quarterback
     * and a running back in hand and eleven live picks in front of him. No
     * historical season has that structure. Replaying those seasons as plain
     * drafts would score plans against a DIFFERENT problem - one where a
     * quarterback still has to be found - and the answer would not transfer.
     *
     * So the structure is reproduced: on every historical board I am handed a
     * quarterback and a running back for free, and draft rounds 1-11.
     *
     * WHICH two? Matched on positional ADP rank, not on cost. A keeper's whole
     * point is being worth more than his price, so "the quarterback available at
     * pick 151" would be QB25 and nothing like Purdy. Purdy is the 9th
     * quarterback on the 2026 board and Tuten the 23rd running back, so the
     * historical keepers are that season's QB9 and RB23 - {@link EraKeepers}
     * reads those ranks off the live board rather than hardcoding them.
     *
     * They cost nothing because rounds 12 and 13 are past the end of an
     * eleven-round game, which is exactly their real cost to Justin's nine
     * meaningful rounds. Every plan is handed the same two men, so the
     * comparison between plans stays clean.
     *
     * The keeperless variant remains one flag away (-PnoKeepers), and
     * RegimeShift reports whether the regime verdict depends on this choice.
     */
    public static List<String> keepers(EraBoards.Board board){
        if(Boolean.getBoolean("noKeepers")){
            return new ArrayList<>();
        }
        List<String> held = new ArrayList<>();
        int[] ranks = EraKeepers.ranks();
        String quarterback = atPositionalRank(board, Position.QB, ranks[0]);
        String runningBack = atPositionalRank(board, Position.RB, ranks[1]);
        if(quarterback != null){
            held.add(quarterback);
        }
        if(runningBack != null){
            held.add(runningBack);
        }
        return held;
    }

    /** The nth player at a position in ADP order, or null if the board is short. */
    public static String atPositionalRank(EraBoards.Board board, Position position,
                                          int rank){
        int seen = 0;
        for(String id : board.ids()){
            if(board.positionOf().get(id) == position && ++seen == rank){
                return id;
            }
        }
        return null;
    }

    /** Serpentine pick numbers for my slot. */
    public static int[] myPicks(int rounds){
        int[] picks = new int[rounds];
        for(int round = 1; round <= rounds; round++){
            int within = round % 2 == 1 ? SLOT : EraBoards.TEAMS - SLOT + 1;
            picks[round - 1] = (round - 1) * EraBoards.TEAMS + within;
        }
        return picks;
    }

    /** Players a 12-team draft of this many rounds removes from the board. */
    public static int consumed(int rounds){
        return rounds * EraBoards.TEAMS;
    }

    /**
     * Who this plan ends up with.
     *
     * The eleven opponents never draft a defence, which is what the real league
     * does inside the rounds that matter, and what PlanBacktest assumes for the
     * same reason. If my plan asks for a position the board has run out of, it
     * falls back to best available rather than passing.
     */
    public static List<String> draft(EraBoards.Board board, List<Position> plan){
        return draft(board, plan, keepers(board));
    }

    public static List<String> draft(EraBoards.Board board, List<Position> plan,
                                     List<String> keepers){
        Set<String> gone = new HashSet<>(keepers);
        List<String> mine = new ArrayList<>(keepers);
        Set<Integer> mySlots = new HashSet<>();
        for(int pick : myPicks(plan.size())){
            mySlots.add(pick);
        }
        int taken = 0;
        int last = myPicks(plan.size())[plan.size() - 1];
        for(int pick = 1; pick <= last; pick++){
            if(mySlots.contains(pick)){
                // A null in the plan means "best available", the positionless
                // null hypothesis - and it means best available SKILL, so the
                // baseline cannot stumble into a second defence.
                Position wanted = plan.get(taken);
                String choice = wanted == null ? bestAvailableSkill(board, gone)
                        : bestAvailable(board, gone, wanted);
                if(choice == null){
                    choice = bestAvailableSkill(board, gone);
                }
                if(choice != null){
                    mine.add(choice);
                    gone.add(choice);
                }
                taken++;
            }
            else {
                String other = bestAvailableSkill(board, gone);
                if(other != null){
                    gone.add(other);
                }
            }
        }
        return mine;
    }

    public static String bestAvailable(EraBoards.Board board, Set<String> gone,
                                       Position position){
        for(String id : board.ids()){
            if(!gone.contains(id)
                    && (position == null || board.positionOf().get(id) == position)){
                return id;
            }
        }
        return null;
    }

    public static String bestAvailableSkill(EraBoards.Board board, Set<String> gone){
        for(String id : board.ids()){
            if(!gone.contains(id) && board.positionOf().get(id) != Position.DEF){
                return id;
            }
        }
        return null;
    }

    /**
     * What this roster really scored, week by week.
     *
     * Starters are picked by PRESEASON board rank, never by what they went on
     * to score that week - the same rule PlanBacktest settled on, because
     * sorting by realised points is perfect hindsight start/sit and it flatters
     * whichever strategy stacked a position hardest. A man with no entry that
     * week did not play and cannot be started; his slot goes empty, which is
     * what a bye or an injury costs a real roster.
     */
    public static double seasonPoints(EraBoards.Board board, List<String> roster){
        Map<String, Integer> boardRank = new HashMap<>();
        for(int i = 0; i < board.ids().size(); i++){
            boardRank.put(board.ids().get(i), i);
        }
        double total = 0;
        for(Map<String, Double> points : board.weekly()){
            Map<Position, List<String>> up = new EnumMap<>(Position.class);
            for(String id : roster){
                if(points.get(id) != null){
                    up.computeIfAbsent(board.positionOf().get(id),
                            u -> new ArrayList<>()).add(id);
                }
            }
            for(List<String> ids : up.values()){
                ids.sort(Comparator.comparingInt(
                        id -> boardRank.getOrDefault(id, Integer.MAX_VALUE)));
            }
            List<String> flex = new ArrayList<>();
            total += fill(up.get(Position.QB), 1, null, points);
            total += fill(up.get(Position.RB), 2, flex, points);
            total += fill(up.get(Position.WR), 3, flex, points);
            total += fill(up.get(Position.TE), 1, flex, points);
            total += fill(up.get(Position.DEF), 1, null, points);
            flex.sort(Comparator.comparingInt(
                    id -> boardRank.getOrDefault(id, Integer.MAX_VALUE)));
            for(int slot = 0; slot < 2 && slot < flex.size(); slot++){
                total += points.getOrDefault(flex.get(slot), 0.0);
            }
        }
        return total;
    }

    static double fill(List<String> available, int slots, List<String> flex,
                       Map<String, Double> points){
        int size = available == null ? 0 : available.size();
        double scored = 0;
        for(int slot = 0; slot < slots && slot < size; slot++){
            scored += points.getOrDefault(available.get(slot), 0.0);
        }
        if(flex != null){
            for(int extra = slots; extra < size; extra++){
                flex.add(available.get(extra));
            }
        }
        return scored;
    }

    /** Draft this plan on this board and score it. */
    public static double score(EraBoards.Board board, List<Position> plan){
        return seasonPoints(board, draft(board, plan));
    }

    public static double score(EraBoards.Board board, List<Position> plan,
                               List<String> keepers){
        return seasonPoints(board, draft(board, plan, keepers));
    }

    /**
     * The null hypothesis: never think about position at all, just take the
     * best man left. Every plan is reported as points above this, because raw
     * season totals are not comparable across eras - the league scored far more
     * in 2024 than in 2013 - and the difference from a fixed reference is.
     */
    public static double bestAvailableScore(EraBoards.Board board, int rounds,
                                            List<String> keepers){
        List<Position> plan = new ArrayList<>();
        for(int round = 1; round < rounds; round++){
            plan.add(null);
        }
        plan.add(Position.DEF);
        return seasonPoints(board, draft(board, plan, keepers));
    }
}
