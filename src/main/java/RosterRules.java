import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The single authority on what a legal Justin roster is and what a pick costs.
 *
 * TRAPS.md section A is a list of seven ways the roster arithmetic in this repo
 * has been wrong. Justin's standard for the fix: "a model that COULD draft
 * three quarterbacks and happens not to is not fixed." So this is not a
 * validator that models call after the fact - it is a type that refuses to
 * represent the illegal roster in the first place. {@link Roster} has no public
 * constructor and no setter; the only way to grow one is {@link Roster#draft}
 * and {@link Roster#stream}, and both refuse the pick rather than record it.
 *
 * Everything below is DERIVED. The three inputs are:
 *
 *   1. the league's own roster_positions array, read from Sleeper the way
 *      LeagueRules.java reads it - QB RB RB WR WR WR TE FLEX FLEX DEF + 6 BN;
 *   2. who Justin already holds and what round each one costs;
 *   3. one law about depth, stated below.
 *
 * The sixteen, the ten starters, the six bench, the fourteen live picks, the
 * 35-pick gap, "QB 0 RB 1 WR 3 TE 1 DEF 1 more men", and every positional
 * ceiling fall out of those. No count in this file is typed twice.
 *
 *   ./gradlew run -Pmain=RosterRules
 *
 * prints the derivation and audits every plan the repo currently ships.
 */
public final class RosterRules {

    // ---------------------------------------------------------------- inputs

    /**
     * The league's slots, measured 2026-08-31 from Sleeper league 1390416723210952704.
     *
     * A FALLBACK, not the source. Same pattern as EraKeepers.FALLBACK: the live
     * config is authoritative and this only exists so a tool on a plane still
     * runs against the right league instead of throwing. The test
     * {@code theSixteenComesFromTheLeagueNotFromThisConstant} asserts the two
     * agree, so it can never drift in silence.
     */
    static final List<String> FALLBACK_SLOTS = List.of(
            "QB", "RB", "RB", "WR", "WR", "WR", "TE", "FLEX", "FLEX", "DEF",
            "BN", "BN", "BN", "BN", "BN", "BN");

    /** Twelve teams, Justin in slot 7 (CLAUDE.md; confirmed by the draft order). */
    static final int FALLBACK_TEAMS = 12;
    static final int FALLBACK_SLOT = 7;

    /**
     * What Justin holds and what it costs him.
     *
     * The rounds are the commissioner's hand entry, which has slipped three
     * times - KeeperAudit is the tool that checks them against Sleeper. This
     * class does not verify them; it only insists that whatever they are, the
     * man is ON the roster and the round is OFF the schedule. Both halves,
     * always, together. Charging the round without delivering the man is
     * TRAPS.md A4 and it survived in this repo until 2026-08-31.
     */
    public static List<Man> justinsKeepers(){
        return List.of(new Man("Tuten", Position.RB, Origin.KEPT, 12),
                new Man("Purdy", Position.QB, Origin.KEPT, 13));
    }

    /**
     * The one law that is not read off the lineup: how deep a position is worth
     * going when the flexes cannot reach it.
     *
     * A quarterback and a defence have exactly one door into the lineup each.
     * Nothing about a second one can ever be started while the first is
     * healthy, so his only value is as a NEXT-YEAR KEEPER STASH - Justin's own
     * rule, RUNBOOK.md:191, "take a young QB when the keeper case is the point,
     * not the lineup". One stash is a plan; two is a wasted spot on a sixteen-man
     * roster. Hence 1.
     *
     * This single constant is what produces the "at most 2 quarterbacks TOTAL,
     * including keepers" ceiling. It is not typed per position - RB, WR and TE
     * never touch it, because the flexes give them a second and third door and
     * their depth is bounded by the roster instead (see {@link #ceiling}).
     */
    static final int STASH_PER_UNFLEXABLE_POSITION = 1;

    /**
     * The round a stash is allowed to be taken, RUNBOOK.md:191: "Rounds 10-12
     * is the band where the QB keeper term peaks."
     *
     * A second quarterback before this is not a stash, it is a model that has
     * mistaken a backup for a starter - TRAPS.md A2, which RankDraft did by
     * pricing the wait in raw points and BoardValue did by pricing a backup at
     * 88 points at every pick.
     */
    static final int EARLIEST_STASH_ROUND = 10;

    // ------------------------------------------------------------------ state

    private final List<String> slots;
    private final int teams;
    private final int mySlot;
    private final boolean fromLeague;

    private RosterRules(List<String> slots, int teams, int mySlot, boolean fromLeague){
        if(slots.isEmpty()){
            throw new IllegalArgumentException("a league with no roster slots is not a league");
        }
        this.slots = List.copyOf(slots);
        this.teams = teams;
        this.mySlot = mySlot;
        this.fromLeague = fromLeague;
    }

    private static RosterRules live;

    /**
     * The real league, read from Sleeper (and from today's cache after the
     * first call, like every other feed in this repo).
     */
    public static synchronized RosterRules live(){
        if(live == null){
            live = read();
        }
        return live;
    }

    private static RosterRules read(){
        try {
            AAAConfiguration configuration = AAAConfiguration.getInstance();
            List<String> read = new ArrayList<>();
            for(JsonElement slot : configuration.getLeagueJson()
                    .getAsJsonArray("roster_positions")){
                read.add(slot.getAsString());
            }
            int teams = configuration.getLeagueJson().getAsJsonObject("settings")
                    .get("num_teams").getAsInt();
            int slot;
            try {
                slot = configuration.getMyDraftSlot();
            }
            catch(RuntimeException orderNotSetYet){
                slot = FALLBACK_SLOT;
            }
            return new RosterRules(read, teams, slot, true);
        }
        catch(RuntimeException unreachable){
            return new RosterRules(FALLBACK_SLOTS, FALLBACK_TEAMS, FALLBACK_SLOT, false);
        }
    }

    /** An arbitrary league, for tests and for asking what-if about the shape. */
    public static RosterRules of(List<String> slots, int teams, int mySlot){
        return new RosterRules(slots, teams, mySlot, false);
    }

    /** True when {@link #live()} actually reached Sleeper rather than the fallback. */
    public boolean readFromLeague(){
        return fromLeague;
    }

    // ------------------------------------------------------- the sixteen spots

    /** Every spot on the roster. Sixteen. */
    public int size(){
        return slots.size();
    }

    /** Slots that score. Ten: QB RB RB WR WR WR TE FLEX FLEX DEF. */
    public int startingSlots(){
        return (int) slots.stream().filter(slot -> !slot.equals("BN")).count();
    }

    /** Slots that do not. Six. */
    public int benchSlots(){
        return size() - startingSlots();
    }

    /** Named starting slots at a position, before the flexes are handed out. */
    public int startersAt(Position position){
        return (int) slots.stream().filter(slot -> slot.equals(position.name())).count();
    }

    /** Two. */
    public int flexSlots(){
        return (int) slots.stream().filter(slot -> slot.equals("FLEX")).count();
    }

    /** Positions a FLEX can hold: everything that starts, except QB and DEF. */
    public boolean flexEligible(Position position){
        return startersAt(position) > 0 && position != Position.QB && position != Position.DEF;
    }

    /** Every position the league actually starts, in slot order. */
    public List<Position> startingPositions(){
        List<Position> found = new ArrayList<>();
        for(Position position : Position.values()){
            if(startersAt(position) > 0){
                found.add(position);
            }
        }
        return found;
    }

    /** The most men at this position who could be on the field at once. */
    public int mostOnFieldAtOnce(Position position){
        return startersAt(position) + (flexEligible(position) ? flexSlots() : 0);
    }

    /**
     * The most men at this position a legal roster may hold, keepers included.
     *
     * Two cases, and the difference between them is the whole point:
     *
     *   NO FLEX ACCESS (QB, DEF) - one door into the lineup, so depth beyond
     *     the starter cannot play. Ceiling is the starter plus one stash: TWO
     *     quarterbacks total. With Purdy kept that is at most ONE drafted, which
     *     is TRAPS.md A1 made arithmetic rather than remembered.
     *
     *   FLEX ACCESS (RB, WR, TE) - the flexes give a fourth receiver or a third
     *     back a real route into the lineup, so there is no depth argument
     *     against them. The only bound is the roster itself: you may not take so
     *     many that the other starting slots can no longer be filled. Nothing is
     *     typed here either - it is sixteen minus what every other slot needs.
     */
    public int ceiling(Position position){
        if(startersAt(position) == 0){
            return 0;                       // this league starts no kicker
        }
        if(!flexEligible(position)){
            // Starters plus one stash. Note this permits a SECOND DEFENCE from
            // round 10, which is wrong in football and right here: a stash is a
            // next-year keeper and nobody keeps a defence, since preseason
            // defence ranking correlates 0.277 with the season against 0.578
            // for the skill positions. But that is a MEASURED fact about a
            // position, not a property of the lineup, and this method is
            // deliberately a function of the lineup alone - typing DEF here
            // fails ceilingsAreDerivedFromTheLineupNotTyped, which exists to
            // stop exactly that creeping in.
            //
            // So the appetite belongs one layer up, where it already is:
            // BoardValue.MOST caps DEF at one. LiveBoard now consults it.
            return startersAt(position) + STASH_PER_UNFLEXABLE_POSITION;
        }
        int neededElsewhere = 0;
        for(Position other : startingPositions()){
            if(other != position){
                neededElsewhere += startersAt(other);
            }
        }
        return size() - neededElsewhere;
    }

    // ------------------------------------------------------- the pick schedule

    /** Sixteen rounds, because there are sixteen spots and no spare one. */
    public int rounds(){
        return size();
    }

    public int teams(){
        return teams;
    }

    public int mySlot(){
        return mySlot;
    }

    /** Overall pick number, snaking. Delegates - this is not a third copy. */
    public int pickNumber(int round){
        return AAAConfiguration.pickNumber(round, mySlot, teams);
    }

    /** Rounds a set of keepers takes off the schedule. */
    public static Set<Integer> keeperRounds(Collection<Man> held){
        Set<Integer> rounds = new TreeSet<>();
        for(Man man : held){
            rounds.add(man.round());
        }
        return rounds;
    }

    /**
     * The rounds Justin actually picks in, given who he keeps.
     *
     * Sixteen rounds minus the rounds the keepers cost. With Tuten at 12 and
     * Purdy at 13 that is fourteen rounds: 1-11, 14, 15, 16. A model that
     * assumes sixteen picks, or that spreads the keeper cost evenly, is drafting
     * a different league - TRAPS.md A3.
     */
    public List<Integer> livePickRounds(Collection<Man> held){
        Set<Integer> spent = keeperRounds(held);
        List<Integer> live = new ArrayList<>();
        for(int round = 1; round <= rounds(); round++){
            if(!spent.contains(round)){
                live.add(round);
            }
        }
        return live;
    }

    /**
     * The overall pick numbers of those rounds: 7, 18, 31, 42, 55, 66, 79, 90,
     * 103, 114, 127, 162, 175, 186. The 35-pick gap between 127 and 162 is
     * rounds 12 and 13 leaving the schedule, and it is computed here, not typed.
     */
    public List<Integer> livePicks(Collection<Man> held){
        List<Integer> picks = new ArrayList<>();
        for(int round : livePickRounds(held)){
            picks.add(pickNumber(round));
        }
        return picks;
    }

    // --------------------------------------------------------- the roster type

    public enum Origin {
        /** Already owned. On the roster from pick one, off the board forever. */
        KEPT,
        /** Bought with one of the live picks. */
        DRAFTED,
        /** Picked up off waivers, which costs a spot and therefore a man. */
        STREAMED
    }

    public record Man(String name, Position position, Origin origin, int round){}

    /** Thrown instead of building a roster nobody could actually own. */
    public static final class IllegalRoster extends IllegalArgumentException {
        public IllegalRoster(String why){
            super(why);
        }
    }

    /** What a stream cost: the new roster, and the man it displaced (or null). */
    public record Streamed(Roster roster, Man dropped){}

    /** An empty roster in this league. */
    public Roster empty(){
        return new Roster(List.of());
    }

    /**
     * A roster that already holds these men - the ONLY way keepers enter, and
     * it puts them on the roster and takes their rounds off the schedule in the
     * same call, because the two have to move together.
     */
    public Roster holding(Collection<Man> held){
        List<Man> kept = new ArrayList<>();
        Set<Integer> rounds = new LinkedHashSet<>();
        for(Man man : held){
            if(man.round() < 1 || man.round() > rounds()){
                throw new IllegalRoster(man.name() + " costs round " + man.round()
                        + ", which is not one of the " + rounds() + " rounds");
            }
            if(!rounds.add(man.round())){
                throw new IllegalRoster("two keepers cannot both cost round " + man.round());
            }
            Man kept1 = new Man(man.name(), man.position(), Origin.KEPT, man.round());
            kept.add(kept1);
        }
        Roster roster = new Roster(kept);
        for(Position position : Position.values()){
            if(roster.count(position) > ceiling(position)){
                throw new IllegalRoster("keeping " + roster.count(position) + " at "
                        + position + " already breaks the ceiling of " + ceiling(position));
            }
        }
        return roster;
    }

    /** Justin's actual roster on the morning of the draft: Tuten and Purdy. */
    public Roster justins(){
        return holding(justinsKeepers());
    }

    public static List<Position> parse(String shape){
        List<Position> positions = new ArrayList<>();
        for(String token : shape.trim().split("\\s+")){
            if(!token.isEmpty()){
                positions.add(Position.valueOf(token));
            }
        }
        return positions;
    }

    /**
     * A roster. There is no public constructor, no setter and no list to hand
     * in - the only routes are {@link RosterRules#empty()},
     * {@link RosterRules#holding} and the methods below, and every one of them
     * refuses rather than records. That is the difference between a model that
     * does not draft three quarterbacks and one that cannot.
     */
    public final class Roster {

        private final List<Man> men;

        private Roster(List<Man> men){
            this.men = List.copyOf(men);
        }

        public List<Man> men(){
            return men;
        }

        public RosterRules rules(){
            return RosterRules.this;
        }

        public int size(){
            return men.size();
        }

        public boolean full(){
            return size() >= RosterRules.this.size();
        }

        public int count(Position position){
            return (int) men.stream().filter(man -> man.position() == position).count();
        }

        public List<Integer> roundsSpent(){
            return men.stream().map(Man::round).sorted().toList();
        }

        /** The rounds still to come after the ones already spent. */
        public List<Integer> roundsRemaining(){
            List<Integer> spent = roundsSpent();
            List<Integer> left = new ArrayList<>();
            for(int round = 1; round <= rounds(); round++){
                if(!spent.contains(round)){
                    left.add(round);
                }
            }
            return left;
        }

        /**
         * How many more men each named starting slot still wants.
         *
         * Supersedes PlanBacktest.requiredPicks(). With Tuten and Purdy held
         * this is QB 0, RB 1, WR 3, TE 1, DEF 1 - and it is subtraction from the
         * league's own slot counts, not a map anybody typed. The flexes are not
         * counted: they take the surplus, and {@link #fieldsLegalLineup} is what
         * checks the surplus exists.
         */
        public Map<Position, Integer> stillNeeds(){
            Map<Position, Integer> need = new EnumMap<>(Position.class);
            for(Position position : startingPositions()){
                need.put(position, Math.max(0, startersAt(position) - count(position)));
            }
            return need;
        }

        /** Men left over once every named starting slot is filled. */
        public int flexSurplus(){
            int surplus = 0;
            for(Position position : startingPositions()){
                if(flexEligible(position)){
                    surplus += Math.max(0, count(position) - startersAt(position));
                }
            }
            return surplus;
        }

        /**
         * Can this roster field the league's ten starters?
         *
         * TRAPS.md A7: ShapeSensitivity.legal() tested only for a defence and
         * waved through rosters that field nobody at tight end, then scored the
         * empty slot at zero. This checks every named slot and the flexes.
         */
        public boolean fieldsLegalLineup(){
            return whyNotLegal() == null;
        }

        /** Why not, in words, or null if it is legal. */
        public String whyNotLegal(){
            for(Map.Entry<Position, Integer> short1 : stillNeeds().entrySet()){
                if(short1.getValue() > 0){
                    return "fields nobody at " + short1.getKey() + " for "
                            + short1.getValue() + " of its " + startersAt(short1.getKey())
                            + " slots, and an empty slot scores zero";
                }
            }
            if(flexSurplus() < flexSlots()){
                return "only " + flexSurplus() + " men spare for " + flexSlots()
                        + " FLEX slots";
            }
            return null;
        }

        /**
         * The fewest further picks that could still fill every empty named slot.
         * Used to refuse a pick that STRANDS the lineup - the point at which
         * "no tight end" stops being a thing you check afterwards and becomes a
         * thing the type will not let you reach.
         */
        private int picksStillOwed(){
            int owed = 0;
            for(int missing : stillNeeds().values()){
                owed += missing;
            }
            return owed + Math.max(0, flexSlots() - flexSurplus());
        }

        /** Null if this pick is legal; otherwise the reason it is not. */
        public String whyNotDraft(Position position, int round){
            if(startersAt(position) == 0){
                return "this league starts no " + position;
            }
            if(round < 1 || round > rounds()){
                return "round " + round + " does not exist - there are " + rounds()
                        + " rounds, one per roster spot";
            }
            if(roundsSpent().contains(round)){
                return "round " + round + " is already spent"
                        + (men.stream().anyMatch(m -> m.round() == round
                        && m.origin() == Origin.KEPT)
                        ? " on a keeper, and a keeper's round buys no pick" : "");
            }
            int last = men.stream().filter(man -> man.origin() == Origin.DRAFTED)
                    .mapToInt(Man::round).max().orElse(0);
            if(round < last){
                return "round " + round + " comes before round " + last
                        + ", which has already been used - a draft runs forwards";
            }
            if(full()){
                return "the roster is " + RosterRules.this.size() + " and it is full";
            }
            if(count(position) + 1 > ceiling(position)){
                return "that is " + (count(position) + 1) + " at " + position
                        + " and the ceiling is " + ceiling(position)
                        + (flexEligible(position) ? "" : " - " + position
                        + " cannot reach a FLEX, so beyond the starter plus one"
                        + " keeper stash he can never play");
            }
            if(!flexEligible(position) && count(position) >= startersAt(position)
                    && round < EARLIEST_STASH_ROUND){
                return "a second " + position + " is only ever a next-year keeper"
                        + " stash (RUNBOOK.md:191), and round " + round
                        + " is before round " + EARLIEST_STASH_ROUND;
            }
            Roster after = new Roster(append(new Man(position.name().toLowerCase(),
                    position, Origin.DRAFTED, round)));
            int roundsLeft = 0;
            for(int later : after.roundsRemaining()){
                if(later > round){
                    roundsLeft++;
                }
            }
            if(after.picksStillOwed() > roundsLeft){
                return "taking " + position + " here strands the lineup: "
                        + after.picksStillOwed() + " slots would still be empty with "
                        + roundsLeft + " picks left";
            }
            return null;
        }

        public boolean canDraft(Position position, int round){
            return whyNotDraft(position, round) == null;
        }

        /** Every position that could legally be taken at this round. */
        public List<Position> legalAt(int round){
            List<Position> legal = new ArrayList<>();
            for(Position position : startingPositions()){
                if(canDraft(position, round)){
                    legal.add(position);
                }
            }
            return legal;
        }

        public Roster draft(String name, Position position, int round){
            String why = whyNotDraft(position, round);
            if(why != null){
                throw new IllegalRoster(why);
            }
            return new Roster(append(new Man(name, position, Origin.DRAFTED, round)));
        }

        public Roster draft(Position position, int round){
            return draft(position.name().toLowerCase() + (count(position) + 1), position, round);
        }

        /**
         * A man the rules would REFUSE, who is on the roster anyway.
         *
         * Sleeper is the authority on what Justin owns, not this type. When the
         * live board reads back a pick these rules would have declined - a
         * second tight end, a man in a round the rules reserve - he is still on
         * the roster, and the alternative to recording him is AMNESIA: the
         * caller printed him to a list and dropped him, so the quarterback
         * ceiling of two was counted against one, and `full()` read fifteen on
         * a roster of sixteen. The second adversarial pass found exactly that:
         * `the rules allow here: [QB, RB, WR, TE, DEF]` with two quarterbacks
         * already held, with only BoardValue.MOST standing between that and a
         * third; and a seventeenth man priced past the end of the draft.
         *
         * This is deliberately NOT draft(). draft() still refuses, so no model
         * can plan an illegal roster through it - that guarantee is the point
         * of the type. This records a fact about the world that has already
         * happened, and it is named so that using it to dodge a refusal reads
         * as obviously wrong.
         */
        public Roster holdAnyway(String name, Position position, int round){
            return new Roster(append(new Man(name, position, Origin.DRAFTED, round)));
        }

        /**
         * Run a whole plan - a list of positions, one per LIVE pick, in order.
         * This is the shape every model in the repo speaks in, mapped onto the
         * rounds the keepers left behind. It throws on the first pick that is
         * not legal, naming the round, so a bad plan cannot be scored by
         * accident.
         */
        public Roster draftPlan(List<Position> plan){
            List<Integer> rounds = roundsRemaining();
            if(plan.size() > rounds.size()){
                throw new IllegalRoster("a " + plan.size() + "-pick plan, but only "
                        + rounds.size() + " picks left - keepers already spent "
                        + roundsSpent());
            }
            Roster roster = this;
            for(int i = 0; i < plan.size(); i++){
                roster = roster.draft(plan.get(i), rounds.get(i));
            }
            return roster;
        }

        /**
         * Pick a man up off waivers.
         *
         * TRAPS.md A6: a streamed player OCCUPIES one of the sixteen. On a full
         * roster that means dropping somebody, and the man dropped is the last
         * one drafted - never a keeper, whose round is already spent. Crediting
         * a streamed defence on top of a full roster hands the strategy a player
         * nobody has; it inflated streaming by four points a season.
         *
         * On a roster that is NOT yet full, the stream is genuinely free,
         * because there is a spot for him. Which is where this parts company
         * with PlanBacktest.seasonPoints - see {@link #dropsToStream}.
         */
        public Streamed stream(String name, Position position){
            if(count(position) + 1 > ceiling(position) && !full()){
                throw new IllegalRoster("streaming that is " + (count(position) + 1)
                        + " at " + position + " and the ceiling is " + ceiling(position));
            }
            if(!full()){
                return new Streamed(new Roster(append(
                        new Man(name, position, Origin.STREAMED, 0))), null);
            }
            Man dropped = null;
            for(Man man : men){
                if(man.origin() != Origin.KEPT
                        && (dropped == null || man.round() >= dropped.round())){
                    dropped = man;
                }
            }
            if(dropped == null){
                throw new IllegalRoster("a full roster of keepers has nobody to drop");
            }
            List<Man> after = new ArrayList<>(men);
            after.remove(dropped);
            after.add(new Man(name, position, Origin.STREAMED, 0));
            Roster grown = new Roster(after);
            // The wire is not a way round the ceiling. Streaming a third
            // quarterback is the same wasted spot as drafting one, so it is
            // refused in the same words.
            if(grown.count(position) > ceiling(position)){
                throw new IllegalRoster("streaming that is " + grown.count(position)
                        + " at " + position + " and the ceiling is " + ceiling(position));
            }
            return new Streamed(grown, dropped);
        }

        private List<Man> append(Man man){
            List<Man> grown = new ArrayList<>(men);
            grown.add(man);
            return grown;
        }

        public String shape(){
            StringBuilder shape = new StringBuilder();
            for(Man man : men){
                if(man.origin() == Origin.DRAFTED){
                    shape.append(shape.isEmpty() ? "" : " ").append(man.position());
                }
            }
            return shape.toString();
        }

        @Override
        public String toString(){
            return size() + "/" + RosterRules.this.size() + " " + men;
        }
    }

    /**
     * How many men a roster of this size must drop to stream one player.
     *
     * The rule PlanBacktest.seasonPoints implements inline, pulled out so every
     * scorer can charge it the same way - and so the one case it gets wrong is
     * visible: a roster of fourteen has two empty bench spots, and a stream into
     * an empty spot costs nobody.
     */
    public int dropsToStream(int menHeld){
        return menHeld >= size() ? 1 : 0;
    }

    // ------------------------------------------------------------------- print

    /** {verdict, why} for one plan on one starting roster. */
    private static String[] verdict(Roster from, List<Position> plan){
        try {
            String illegal = from.draftPlan(plan).whyNotLegal();
            return new String[]{illegal == null ? "legal" : "ILLEGAL",
                    illegal == null ? "" : illegal};
        }
        catch(IllegalRoster refused){
            return new String[]{"REFUSED", refused.getMessage()};
        }
    }

    public static void main(String[] args){
        RosterRules rules = live();
        System.out.printf("%nROSTER RULES - the arithmetic every model should route through%n");
        System.out.printf("read from %s%n%n", rules.readFromLeague()
                ? "the live Sleeper league" : "the FALLBACK constant - Sleeper was unreachable");

        System.out.printf("the roster is %d: %d starters + %d bench%n",
                rules.size(), rules.startingSlots(), rules.benchSlots());
        System.out.printf("%-6s %8s %8s %9s %9s   %s%n", "POS", "starts", "+flex",
                "on field", "ceiling", "why that ceiling");
        for(Position position : rules.startingPositions()){
            System.out.printf("%-6s %8d %8s %9d %9d   %s%n", position,
                    rules.startersAt(position),
                    rules.flexEligible(position) ? String.valueOf(rules.flexSlots()) : "-",
                    rules.mostOnFieldAtOnce(position), rules.ceiling(position),
                    rules.flexEligible(position)
                            ? "16 minus what the other slots need"
                            : "starter + 1 keeper stash; no FLEX door");
        }

        Roster justin = rules.justins();
        System.out.printf("%nkeepers: ");
        for(Man man : justin.men()){
            System.out.printf("%s (%s, round %d)  ", man.name(), man.position(), man.round());
        }
        System.out.printf("%n%d live picks in rounds %s%n",
                rules.livePickRounds(justinsKeepers()).size(),
                rules.livePickRounds(justinsKeepers()));
        List<Integer> picks = rules.livePicks(justinsKeepers());
        System.out.printf("overall: %s%n", picks);
        for(int i = 1; i < picks.size(); i++){
            int gap = picks.get(i) - picks.get(i - 1);
            if(gap > rules.teams() * 2){
                System.out.printf("   a %d-pick gap between %d and %d - that is rounds %s%n",
                        gap, picks.get(i - 1), picks.get(i), keeperRounds(justinsKeepers()));
            }
        }
        System.out.printf("still needs: %s%n", justin.stillNeeds());
        System.out.printf("at most %d drafted QB, and not before round %d%n",
                rules.ceiling(Position.QB) - justin.count(Position.QB), EARLIEST_STASH_ROUND);

        System.out.printf("%n%nEVERY PLAN THIS REPO SHIPS, against these rules%n");
        System.out.printf("PlanBacktest.STRATEGIES, run twice: once as Justin"
                + " (Tuten and Purdy held,%nwhich is what -PholdKeepers=true does) and once as"
                + " the backtest's DEFAULT,%nwhich charges rounds 12 and 13 and delivers"
                + " nobody.%n%n");
        System.out.printf("%-26s %-9s %-9s  %s%n", "PLAN", "AS JUSTIN", "NO KEEPERS",
                "WHY IT IS REFUSED AS JUSTIN");
        for(Map.Entry<String, String> strategy : PlanBacktest.STRATEGIES.entrySet()){
            if(strategy.getValue() == null){
                continue;
            }
            List<Position> plan = parse(strategy.getValue());
            String[] held = verdict(rules.justins(), plan);
            String[] alone = verdict(rules.empty(), plan);
            System.out.printf("%-26s %-9s %-9s  %s%n", strategy.getKey(),
                    held[0], alone[0], held[1]);
        }
        System.out.printf("%nREFUSED = the roster type will not build it at all."
                + " ILLEGAL = it builds%nbut cannot field ten starters."
                + " A plan legal in one column and not the other is%nnot a plan with"
                + " a bug in it - it is a plan written for the other roster.%n");
    }
}
