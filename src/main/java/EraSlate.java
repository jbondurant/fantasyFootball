import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

/**
 * The WHOLE league's keeper slate, transplanted onto a historical board.
 *
 * EraKeepers reproduces MY two. This reproduces all twenty-four, because this
 * is a twelve-team keeper league and every team keeps two. Until 2026-09-01 the
 * backtest stripped only Purdy and Tuten, so every score in the repo was
 * measured drafting against a board about twenty-two men deeper than the one
 * Justin will actually face - and most of the missing twenty-two are
 * top-of-board men: Nacua, Taylor, Bowers, Smith-Njigba, McBride, Achane,
 * Collins, LaPorta.
 *
 * TWO THINGS ARE TRANSPLANTED, and the second is as important as the first.
 *
 * 1. THE MEN. By POSITIONAL ADP RANK, never by price, for exactly the reason
 *    EraKeepers gives: a keeper is worth keeping BECAUSE his cost is nothing
 *    like his value, so copying Bucky Irving's 13th-round price onto 2015 hands
 *    that season a replacement-level back and calls it a keeper. Copying the
 *    RANK - the 8th back, the 3rd tight end - reproduces the thing that
 *    actually shapes a draft: which men are missing from the top of the board.
 *
 * 2. THE PICKS. A keeper spends its owner's pick in the round it costs, so
 *    twenty-four selections never happen. Removing the men without removing the
 *    picks is a different and equally wrong board - twenty-four extra men
 *    consumed by the eleven other managers, all of them from the bottom, which
 *    would make the late rounds look barren rather than the early rounds thin.
 *    The pick numbers come from the league's own draft order via
 *    AAAConfiguration.keeperOccupiedPickNumbers(), the same call the live tools
 *    use, so the slate and the schedule cannot disagree.
 *
 * The eleven other managers are still modelled as best-available: they hold
 * their two, but nothing in this backtest ever looked at their rosters, so
 * holding them changes their behaviour only through the picks they no longer
 * make. That is the honest limit of this change and it is stated rather than
 * hidden.
 *
 * MY OWN TWO ARE UNTOUCHED. Their ranks come from EraKeepers, not from this
 * class's own measurement, so the men PlanBacktest.keeperIDs hands Justin are
 * the same men before and after - and EraSlateTest pins that.
 *
 *   ./gradlew run -Pmain=EraSlate -q
 */
public class EraSlate {

    /**
     * One kept man as a STRUCTURE rather than as a name: whose he is, what he
     * plays, where he sits on his position's ADP list, and which round he costs.
     */
    public record Held(String manager, String name, Position position, int rank,
                       int round, boolean mine){}

    /**
     * -PleagueKeepers=true. Off leaves every existing number untouched.
     *
     * Default OFF deliberately: every backtest figure on record - the plan at
     * 2007, BoardValue at 1935 - was measured on the two-keeper board, and a
     * flag that changed under a reader would make those numbers unreproducible
     * on the night they matter.
     */
    public static boolean enabled(){
        return Boolean.getBoolean("leagueKeepers");
    }

    private static List<Held> cached;
    private static Set<Integer> cachedPicks;

    /** The twenty-four, measured once off the live board. */
    public static synchronized List<Held> structure(){
        if(cached == null){
            cached = measure();
        }
        return cached;
    }

    static List<Held> measure(){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        return slate(configuration.getTodaysKeepers(),
                positionalRanks(FFCalculatorSD.adpBySleeperID(configuration.getSeason())),
                configuration.getMyID(), EraKeepers.ranks(),
                keeper -> SleeperProjections.adpOf(keeper.player.sleeperIDString));
    }

    /**
     * The slate, as a function of its inputs and nothing else.
     *
     * Split out of measure() so the two rules that matter - my own two come
     * from EraKeepers whatever this board says, and a keeper the board does not
     * list still occupies a slot - can be tested without a network anywhere
     * near them.
     */
    static List<Held> slate(List<Keeper> keepers, Ranked board, String me,
                            int[] mineRanks, java.util.function.ToDoubleFunction<Keeper> adp){
        Map<String, Integer> rankOf = board.rank();
        List<Held> raw = new ArrayList<>();
        List<Keeper> unranked = new ArrayList<>();
        for(Keeper keeper : keepers){
            Player player = keeper.player;
            if(player == null || player.position == null){
                continue;
            }
            boolean mine = me != null && me.equals(keeper.humanWhoCanKeep);
            Integer rank = rankOf.get(player.sleeperIDString);
            // MY OWN TWO COME FROM EraKeepers, not from this measurement, so
            // whatever else moves, the men Justin holds do not.
            if(mine){
                rank = player.position == Position.QB ? mineRanks[0] : mineRanks[1];
            }
            if(rank == null){
                unranked.add(keeper);
                continue;
            }
            raw.add(new Held(keeper.humanWhoCanKeep,
                    player.firstName + " " + player.lastName, player.position, rank,
                    keeper.roundCanBeKept, mine));
        }
        // A KEEPER THE ADP BOARD DOES NOT LIST is not a man to drop: he is a
        // man the market ranks below everyone it does list. Dropping him would
        // quietly shrink the slate and hand a keeper back to the board. He goes
        // one past the deepest man the board ranks at his position - past the
        // BOARD's deepest, not past the deepest KEEPER, or a position with no
        // listed keeper would put him at rank 1 and make an unlisted man the
        // best in football. Ordered among his fellow unlisted by the league's
        // own ADP, so the order is measured rather than incidental.
        Map<Position, Integer> deepest = new EnumMap<>(board.deepest());
        unranked.sort(Comparator.comparingDouble(adp));
        for(Keeper keeper : unranked){
            Player player = keeper.player;
            int rank = deepest.merge(player.position, 1, Integer::sum);
            raw.add(new Held(keeper.humanWhoCanKeep,
                    player.firstName + " " + player.lastName, player.position, rank,
                    keeper.roundCanBeKept,
                    me != null && me.equals(keeper.humanWhoCanKeep)));
        }
        return resolve(raw);
    }

    /**
     * Positional rank on this season's half-PPR ADP board.
     *
     * The same board EraKeepers reads - fantasyfootballcalculator's twelve-team
     * half-PPR aggregate - so Purdy measures QB9 here for the same reason he
     * measures QB9 there. Joined by sleeper id rather than by name suffix, so
     * two men whose surnames end alike cannot collide.
     */
    /** Where every man sits on his position's list, and how deep each list goes. */
    record Ranked(Map<String, Integer> rank, Map<Position, Integer> deepest){}

    static Ranked positionalRanks(Map<String, Double> adp){
        List<String> ids = new ArrayList<>(adp.keySet());
        ids.sort(Comparator.comparingDouble(adp::get));
        Map<Position, Integer> seen = new EnumMap<>(Position.class);
        Map<String, Integer> rank = new HashMap<>();
        for(String id : ids){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player == null || player.position == null){
                continue;
            }
            rank.put(id, seen.merge(player.position, 1, Integer::sum));
        }
        return new Ranked(rank, seen);
    }

    /**
     * No two keepers may claim the same (position, rank).
     *
     * They cannot on one board - a rank is held by one man - but two boards are
     * being read: mine come from EraKeepers and the other twenty-two from this
     * class. If those ever disagree about who QB9 is, the transplant would hand
     * one historical man to two managers and quietly return twenty-three
     * keepers. A collider is pushed down to the next free rank at his position,
     * which is what the board would have said anyway, and the shift is printed
     * rather than swallowed.
     */
    static List<Held> resolve(List<Held> raw){
        List<Held> ordered = new ArrayList<>(raw);
        // Mine first, so a collision always moves somebody else.
        ordered.sort(Comparator.comparing(Held::position)
                .thenComparing((Held held) -> !held.mine())
                .thenComparingInt(Held::rank));
        Map<Position, Set<Integer>> used = new EnumMap<>(Position.class);
        List<Held> out = new ArrayList<>();
        for(Held held : ordered){
            Set<Integer> taken = used.computeIfAbsent(held.position(),
                    position -> new HashSet<>());
            int rank = held.rank();
            while(taken.contains(rank)){
                rank++;
            }
            taken.add(rank);
            out.add(rank == held.rank() ? held
                    : new Held(held.manager(), held.name(), held.position(), rank,
                            held.round(), held.mine()));
        }
        out.sort(Comparator.comparing(Held::position).thenComparingInt(Held::rank));
        return out;
    }

    /**
     * Which man on a historical board fills each slot of the slate.
     *
     * Pure, and takes the slate as an argument, so the transplant can be tested
     * on a fixture board without a network anywhere near it.
     */
    static Map<Held, String> on(List<Held> slate, PlanBacktest.Board board){
        Map<Position, Map<Integer, Held>> want = new EnumMap<>(Position.class);
        for(Held held : slate){
            want.computeIfAbsent(held.position(), position -> new HashMap<>())
                    .put(held.rank(), held);
        }
        Map<Held, String> out = new LinkedHashMap<>();
        Map<Position, Integer> seen = new EnumMap<>(Position.class);
        for(String id : board.ids()){
            Position position = board.positionOf().get(id);
            if(position == null){
                continue;
            }
            int rank = seen.merge(position, 1, Integer::sum);
            Held held = want.getOrDefault(position, Map.of()).get(rank);
            if(held != null){
                out.put(held, id);
            }
        }
        return out;
    }

    public static Map<Held, String> on(PlanBacktest.Board board){
        return on(structure(), board);
    }

    /** Every man kept league-wide on this board - nobody may draft one. */
    public static List<String> heldOn(PlanBacktest.Board board){
        return new ArrayList<>(on(board).values());
    }

    /** The two of them that are Justin's, which go onto his roster. */
    public static List<String> mineOn(PlanBacktest.Board board){
        List<String> out = new ArrayList<>();
        for(Map.Entry<Held, String> entry : on(board).entrySet()){
            if(entry.getKey().mine()){
                out.add(entry.getValue());
            }
        }
        return out;
    }

    /**
     * The overall pick numbers a keeper spends, which select nobody.
     *
     * Straight from the league's own draft order. Justin's two - rounds 12 and
     * 13, picks 138 and 151 - are in here too, and they are the reason
     * PlanBacktest.MY_PICKS has a 35-pick hole. Until this flag existed the
     * simulation let "the other eleven" draft at those two picks, which is a
     * small version of the same fault: a pick that does not happen was taking a
     * man off the board.
     */
    public static synchronized Set<Integer> occupiedPicks(){
        if(cachedPicks == null){
            cachedPicks = new HashSet<>(
                    AAAConfiguration.getInstance().keeperOccupiedPickNumbers());
        }
        return Collections.unmodifiableSet(cachedPicks);
    }

    public static String describe(){
        List<Held> slate = structure();
        int mine = 0;
        for(Held held : slate){
            if(held.mine()){
                mine++;
            }
        }
        return String.format("league keeper slate: %d men off the board by positional"
                + " ADP rank (%d mine), %d picks spent", slate.size(), mine,
                occupiedPicks().size());
    }

    public static void main(String[] args) throws Exception {
        List<Held> slate = structure();
        System.out.printf("%n%s%n%n", describe());
        System.out.printf("%-24s %-4s %6s %6s  %s%n", "KEEPER", "POS", "RANK",
                "ROUND", "OWNER");
        Set<Integer> picks = occupiedPicks();
        for(Held held : slate){
            System.out.printf("%-24s %-4s %6s %6d  %s%n", held.name(),
                    held.position(), held.position() + "" + held.rank(), held.round(),
                    held.mine() ? held.manager() + "  (MINE)" : held.manager());
        }
        System.out.printf("%npicks these keepers spend, which select nobody:%n   %s%n",
                new java.util.TreeSet<>(picks));
        System.out.printf("%nand what that structure lands on, season by season:%n%n");
        List<PlanBacktest.Board> boards = new ArrayList<>();
        for(java.io.File file : new java.io.File("data").listFiles()){
            if(file.getName().matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                PlanBacktest.Board board = PlanBacktest.board(file,
                        file.getName().split("-")[3]);
                if(board != null && board.ids().size() > 150){
                    boards.add(board);
                }
            }
        }
        boards.sort(Comparator.comparing(PlanBacktest.Board::season));
        for(PlanBacktest.Board board : boards){
            Map<Held, String> landed = on(slate, board);
            System.out.printf("%s  %d of %d slots filled from a %d-man board%n",
                    board.season(), landed.size(), slate.size(), board.ids().size());
            StringBuilder names = new StringBuilder();
            for(Map.Entry<Held, String> entry : landed.entrySet()){
                Player player = Player.getPlayerFromSIDV2(entry.getValue());
                names.append(String.format("      %-4s %-24s %s%n",
                        entry.getKey().position() + "" + entry.getKey().rank(),
                        player == null ? entry.getValue()
                                : player.firstName + " " + player.lastName,
                        entry.getKey().mine() ? "(MINE)" : ""));
            }
            System.out.print(names);
        }
    }
}
