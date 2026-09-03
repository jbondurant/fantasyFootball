import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One test per entry in TRAPS.md section A, named after the trap.
 *
 * The bar Justin set in the header of that file: "these must be structurally
 * impossible, not merely avoided. A model that could draft three quarterbacks
 * but happens not to is not fixed." So most of these do not assert that some
 * model behaved - they assert that the ROSTER TYPE has no reachable state with
 * the illegal property, by exhausting every roster the public API can build.
 *
 * Offline. The arithmetic tests construct the league explicitly; only
 * theSixteenComesFromTheLeagueNotFromThisConstant reads the live config, and it
 * falls back to today's cache like everything else in the repo.
 */
class RosterRulesTest {

    /** Justin's league, stated so the arithmetic tests never need a network. */
    private static RosterRules league(){
        return RosterRules.of(RosterRules.FALLBACK_SLOTS,
                RosterRules.FALLBACK_TEAMS, RosterRules.FALLBACK_SLOT);
    }

    /**
     * Nine skill rounds onto Justin's keepers - the state a real stash decision
     * is taken from. Taking a quarterback at round 10 out of an EMPTY roster is
     * refused for a different reason (it strands eight slots behind four picks),
     * and testing the stash rule from there would prove nothing about the stash
     * rule.
     */
    private static RosterRules.Roster afterNineSkillRounds(){
        return league().justins().draftPlan(
                RosterRules.parse("RB WR RB WR WR WR TE RB WR"));
    }

    /**
     * Every roster reachable by drafting a full plan onto Justin's live picks.
     *
     * The state a further pick can depend on is exactly the count vector - the
     * ceilings, the shortfalls and the picks-still-owed are all functions of it,
     * and the round is the number of men taken so far - so memoising on counts
     * exhausts the reachable space rather than sampling it.
     */
    private static List<RosterRules.Roster> everyReachableRoster(){
        RosterRules rules = league();
        List<RosterRules.Roster> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<RosterRules.Roster> frontier = new ArrayList<>();
        frontier.add(rules.justins());
        while(!frontier.isEmpty()){
            List<RosterRules.Roster> next = new ArrayList<>();
            for(RosterRules.Roster roster : frontier){
                found.add(roster);
                List<Integer> left = roster.roundsRemaining();
                if(left.isEmpty()){
                    continue;
                }
                int round = left.get(0);
                for(Position position : rules.startingPositions()){
                    if(!roster.canDraft(position, round)){
                        continue;
                    }
                    RosterRules.Roster grown = roster.draft(position, round);
                    StringBuilder key = new StringBuilder();
                    for(Position count : rules.startingPositions()){
                        key.append(grown.count(count)).append(',');
                    }
                    if(seen.add(key.toString())){
                        next.add(grown);
                    }
                }
            }
            frontier = next;
        }
        return found;
    }

    // ------------------------------------------------------------------- A1

    /**
     * A1. "Three quarterbacks. The roster starts ONE. Justin keeps Purdy. Any
     * model that drafts two has bought a third quarterback."
     *
     * Not "no model does this" - no roster CAN. Two proofs: the induction step
     * (from two quarterbacks, no round in the season admits a third), and the
     * exhaustive one (of every roster reachable from Justin's keepers by any
     * fourteen-pick plan, none holds three).
     */
    @Test
    void trapA1_theRosterTypeCannotRepresentThreeQuarterbacks(){
        RosterRules rules = league();
        Assertions.assertEquals(2, rules.ceiling(Position.QB),
                "one starter plus one keeper stash, and no FLEX door for a third");

        // The induction step. A roster grows one man at a time, so if no legal
        // move takes a two-quarterback roster to three, three is unreachable.
        RosterRules.Roster two = afterNineSkillRounds().draft(Position.QB, 10);
        Assertions.assertEquals(2, two.count(Position.QB));
        for(int round = 1; round <= rules.rounds(); round++){
            Assertions.assertFalse(two.canDraft(Position.QB, round),
                    "round " + round + " let a third quarterback onto the roster");
        }
        Assertions.assertThrows(RosterRules.IllegalRoster.class,
                () -> afterNineSkillRounds().draft(Position.QB, 10).draft(Position.QB, 11));
        // Nor through the wire, which is the other door onto a roster.
        Assertions.assertThrows(RosterRules.IllegalRoster.class,
                () -> two.stream("some backup", Position.QB));

        // And the exhaustive one.
        List<RosterRules.Roster> all = everyReachableRoster();
        Assertions.assertTrue(all.size() > 1000,
                "the search should be exhausting a real space, not a handful");
        for(RosterRules.Roster roster : all){
            Assertions.assertTrue(roster.count(Position.QB) <= 2,
                    "reachable roster with " + roster.count(Position.QB)
                            + " quarterbacks: " + roster);
        }
    }

    // ------------------------------------------------------------------- A2

    /**
     * A2. "Two quarterbacks inside the first ten rounds. Even two total is only
     * defensible as a next-year keeper stash, late."
     */
    @Test
    void trapA2_noSecondQuarterbackInsideTheFirstTenRounds(){
        RosterRules rules = league();
        RosterRules.Roster justin = rules.justins();
        Assertions.assertEquals(1, justin.count(Position.QB), "Purdy is already the first");

        for(int round = 1; round < RosterRules.EARLIEST_STASH_ROUND; round++){
            Assertions.assertFalse(justin.canDraft(Position.QB, round),
                    "round " + round + " admitted a second quarterback");
            Assertions.assertFalse(justin.legalAt(round).contains(Position.QB));
        }
        Assertions.assertTrue(afterNineSkillRounds()
                        .canDraft(Position.QB, RosterRules.EARLIEST_STASH_ROUND),
                "round 10 is where RUNBOOK.md:191 puts the stash band");

        // The shapes that actually did this. RankDraft's and BoardValue's early
        // second quarterback are refused at the pick, not scored and regretted.
        RosterRules.IllegalRoster refused = Assertions.assertThrows(
                RosterRules.IllegalRoster.class,
                () -> rules.justins().draftPlan(RosterRules.parse(
                        "RB WR RB WR WR WR TE QB QB RB WR TE RB DEF")));
        Assertions.assertTrue(refused.getMessage().contains("stash"), refused.getMessage());

        // Exhaustively: nobody reaches two quarterbacks with a pick before ten.
        for(RosterRules.Roster roster : everyReachableRoster()){
            if(roster.count(Position.QB) == 2){
                int drafted = roster.men().stream()
                        .filter(man -> man.position() == Position.QB
                                && man.origin() == RosterRules.Origin.DRAFTED)
                        .mapToInt(RosterRules.Man::round).min().orElse(99);
                Assertions.assertTrue(drafted >= RosterRules.EARLIEST_STASH_ROUND,
                        "a second quarterback at round " + drafted);
            }
        }
    }

    // ------------------------------------------------------------------- A3

    /**
     * A3. "Keepers cost picks, at named rounds. Tuten costs round 12, Purdy
     * round 13. That is why there are 14 live picks and a 35-pick gap between
     * 127 and 162."
     *
     * The fourteen numbers are not typed here as a list to be matched - they are
     * the sixteen serpentine picks of slot 7 with rounds 12 and 13 removed. The
     * literals below are the assertion, not the source.
     */
    @Test
    void trapA3_keepersCostPicksAtNamedRounds(){
        RosterRules rules = league();
        List<RosterRules.Man> keepers = RosterRules.justinsKeepers();

        Assertions.assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 14, 15, 16),
                rules.livePickRounds(keepers), "rounds 12 and 13 are spent, not picked");
        Assertions.assertEquals(
                List.of(7, 18, 31, 42, 55, 66, 79, 90, 103, 114, 127, 162, 175, 186),
                rules.livePicks(keepers));
        Assertions.assertEquals(14, rules.livePicks(keepers).size(),
                "fourteen live picks, not sixteen");
        Assertions.assertEquals(35, 162 - 127, "the gap the keepers leave");

        // The schedule matches PlanBacktest's, which is the one the backtests use.
        List<Integer> backtest = new ArrayList<>();
        for(int pick : PlanBacktest.MY_PICKS){
            backtest.add(pick);
        }
        Assertions.assertEquals(backtest, rules.livePicks(keepers),
                "the two pick schedules in this repo must be the same schedule");

        // And a keeper's round buys nothing: you cannot pick in it.
        for(int spent : List.of(12, 13)){
            Assertions.assertFalse(rules.justins().canDraft(Position.RB, spent),
                    "round " + spent + " belongs to a keeper");
        }
        RosterRules.IllegalRoster tooMany = Assertions.assertThrows(
                RosterRules.IllegalRoster.class,
                () -> rules.justins().draftPlan(RosterRules.parse(
                        "RB RB RB RB RB WR WR WR WR WR WR TE TE DEF WR")));
        Assertions.assertTrue(tooMany.getMessage().contains("15-pick plan"),
                tooMany.getMessage());
    }

    // ------------------------------------------------------------------- A4

    /**
     * A4. "Keepers are ON the roster and OFF the board. Until 2026-08-31 the
     * backtest charged the two rounds and never delivered the two men."
     *
     * The type makes the pair inseparable: holding() is the only way a keeper
     * enters, and it puts the man on the roster and takes his round out of
     * livePickRounds in the same call.
     */
    @Test
    void trapA4_keepersAreOnTheRosterAndOffTheBoard(){
        RosterRules rules = league();
        RosterRules.Roster justin = rules.justins();

        Assertions.assertEquals(2, justin.size(), "two men, before a single pick");
        Assertions.assertEquals(1, justin.count(Position.QB), "Purdy");
        Assertions.assertEquals(1, justin.count(Position.RB), "Tuten");
        Assertions.assertEquals(List.of(12, 13), justin.roundsSpent());
        Assertions.assertEquals(14, justin.roundsRemaining().size());

        // The lineup they leave: QB 0, RB 1, WR 3, TE 1, DEF 1 more men.
        Assertions.assertEquals(
                Map.of(Position.QB, 0, Position.RB, 1, Position.WR, 3,
                        Position.TE, 1, Position.DEF, 1),
                justin.stillNeeds());

        // Which is what makes a plan drafting NO quarterback legal for Justin.
        RosterRules.Roster noQb = justin.draftPlan(RosterRules.parse(
                "RB WR WR WR RB WR TE RB WR TE WR RB WR DEF"));
        Assertions.assertTrue(noQb.fieldsLegalLineup(),
                "Purdy fills the QB slot: " + noQb.whyNotLegal());
        Assertions.assertEquals(16, noQb.size());
    }

    // ------------------------------------------------------------------- A5

    /**
     * A5. "The roster is sixteen: ten starters, six bench. Fourteen picks plus
     * two keepers fills it exactly. There is no spare spot."
     */
    @Test
    void trapA5_theRosterIsSixteenWithNoSpareSpot(){
        RosterRules rules = league();
        Assertions.assertEquals(16, rules.size());
        Assertions.assertEquals(10, rules.startingSlots());
        Assertions.assertEquals(6, rules.benchSlots());
        Assertions.assertEquals(2, rules.flexSlots());
        Assertions.assertEquals(16, rules.rounds(), "one round per spot");

        Assertions.assertEquals(rules.size(),
                RosterRules.justinsKeepers().size()
                        + rules.livePicks(RosterRules.justinsKeepers()).size(),
                "2 keepers + 14 picks fills it EXACTLY - that is why there is no spare");

        RosterRules.Roster full = rules.justins().draftPlan(RosterRules.parse(
                "RB WR RB WR WR WR TE RB WR TE WR RB WR DEF"));
        Assertions.assertEquals(16, full.size());
        Assertions.assertTrue(full.full());
        for(int round = 1; round <= rules.rounds(); round++){
            Assertions.assertFalse(full.canDraft(Position.RB, round),
                    "a seventeenth man went onto a sixteen-man roster at round " + round);
        }
        // Every reachable roster stays inside the sixteen.
        for(RosterRules.Roster roster : everyReachableRoster()){
            Assertions.assertTrue(roster.size() <= 16, roster.toString());
        }
    }

    // ------------------------------------------------------------------- A6

    /**
     * A6. "A streamed player occupies one of those sixteen. Picking a defence
     * off waivers means DROPPING somebody. Crediting a streamed defence on top
     * of a full roster hands the strategy a player nobody has."
     */
    @Test
    void trapA6_aStreamedPlayerOccupiesOneOfTheSixteen(){
        RosterRules rules = league();

        // First: the roster PlanBacktest streams onto - full, sixteen men, no
        // defence anywhere - cannot be built at all. Drafting no defence does
        // not produce a roster that needs streaming, it produces no roster.
        RosterRules.IllegalRoster noDefence = Assertions.assertThrows(
                RosterRules.IllegalRoster.class,
                () -> rules.justins().draftPlan(RosterRules.parse(
                        "RB WR RB WR WR WR TE RB WR TE WR RB WR RB")));
        Assertions.assertTrue(noDefence.getMessage().contains("strands the lineup"),
                noDefence.getMessage());

        // So the rule is tested on a roster somebody could own: sixteen men,
        // defence drafted at round 14, and a receiver picked up off the wire.
        RosterRules.Roster full = rules.justins().draftPlan(RosterRules.parse(
                "RB WR RB WR WR WR TE RB WR TE WR DEF WR RB"));
        Assertions.assertTrue(full.full());
        Assertions.assertTrue(full.fieldsLegalLineup());

        RosterRules.Streamed streamed = full.stream("wire receiver", Position.WR);
        Assertions.assertNotNull(streamed.dropped(), "a full roster must drop somebody");
        Assertions.assertEquals(16, streamed.roster().size(),
                "the roster is still sixteen - the streamed man did not arrive free");
        Assertions.assertEquals(Position.RB, streamed.dropped().position());
        Assertions.assertEquals(16, streamed.dropped().round(),
                "the man dropped is the LAST one drafted");
        Assertions.assertEquals(RosterRules.Origin.DRAFTED, streamed.dropped().origin());
        Assertions.assertEquals(1, rules.dropsToStream(16));

        // A keeper is never the man dropped - his round is already spent.
        Assertions.assertNotEquals(RosterRules.Origin.KEPT, streamed.dropped().origin());
        Assertions.assertEquals(1, streamed.roster().count(Position.QB));
        Assertions.assertEquals(8, streamed.roster().count(Position.WR));

        // Streaming is not a way round the ceiling either. A SECOND quarterback
        // off the wire is legal - that is bye-week cover, and Purdy is the only
        // one on the roster. A third is the same wasted spot as a drafted third,
        // and is refused in the same words.
        RosterRules.Roster twoQbs = full.stream("bye-week cover", Position.QB).roster();
        Assertions.assertEquals(2, twoQbs.count(Position.QB));
        Assertions.assertEquals(16, twoQbs.size());
        RosterRules.IllegalRoster third = Assertions.assertThrows(
                RosterRules.IllegalRoster.class,
                () -> twoQbs.stream("another backup", Position.QB));
        Assertions.assertTrue(third.getMessage().contains("ceiling"), third.getMessage());

        // But a roster that is NOT full has a spot, and the stream costs nobody.
        RosterRules.Roster short1 = rules.justins().draftPlan(RosterRules.parse(
                "RB WR RB WR WR WR TE RB WR TE WR DEF"));
        Assertions.assertEquals(14, short1.size());
        Assertions.assertEquals(0, rules.dropsToStream(14),
                "two bench spots are open, so this stream displaces nobody");
        Assertions.assertNull(short1.stream("wire receiver", Position.WR).dropped());
        Assertions.assertEquals(15, short1.stream("wire receiver", Position.WR)
                .roster().size());
    }

    // ------------------------------------------------------------------- A7

    /**
     * A7. "A roster with no tight end - or no quarterback - is not legal.
     * ShapeSensitivity.legal() tested only for a defence and waved through
     * rosters that field nobody at TE, then scored the empty slot at zero."
     *
     * The strong form: a finished roster that cannot field ten starters is not
     * merely detected, it is unreachable. The type refuses the pick that STRANDS
     * the lineup, several rounds before the hole would appear.
     */
    @Test
    void trapA7_aRosterWithNoTightEndOrNoQuarterbackIsNotLegal(){
        RosterRules rules = league();

        // Detected: an empty-handed roster names what it is missing.
        RosterRules.Roster nobody = rules.empty();
        Assertions.assertFalse(nobody.fieldsLegalLineup());
        Assertions.assertTrue(nobody.whyNotLegal().contains("QB"), nobody.whyNotLegal());
        Assertions.assertEquals(Map.of(Position.QB, 1, Position.RB, 2, Position.WR, 3,
                Position.TE, 1, Position.DEF, 1), nobody.stillNeeds());

        // Unreachable: the plan that skips the tight end is refused mid-draft,
        // at the pick that made a legal lineup impossible - not at the end.
        RosterRules.IllegalRoster stranded = Assertions.assertThrows(
                RosterRules.IllegalRoster.class,
                () -> rules.justins().draftPlan(RosterRules.parse(
                        "RB WR RB WR WR WR WR RB WR WR WR RB WR DEF")));
        Assertions.assertTrue(stranded.getMessage().contains("strands the lineup"),
                stranded.getMessage());

        // And a no-keeper roster is refused a plan with no quarterback the same way.
        RosterRules.IllegalRoster noQb = Assertions.assertThrows(
                RosterRules.IllegalRoster.class,
                () -> rules.empty().draftPlan(RosterRules.parse(
                        "RB WR RB WR WR WR TE RB WR TE WR RB WR DEF WR RB")));
        Assertions.assertTrue(noQb.getMessage().contains("strands the lineup"),
                noQb.getMessage());

        // Exhaustively: no FINISHED roster this type can build is illegal.
        int finished = 0;
        for(RosterRules.Roster roster : everyReachableRoster()){
            if(roster.size() == rules.size()){
                finished++;
                Assertions.assertTrue(roster.fieldsLegalLineup(),
                        "reachable full roster that cannot field ten: "
                                + roster.whyNotLegal() + " " + roster.shape());
            }
        }
        Assertions.assertTrue(finished > 100, "only " + finished + " finished rosters found");
    }

    // ------------------------------------------------- the layer's own footing

    /**
     * The sixteen is read from Sleeper, not typed. FALLBACK_SLOTS exists only so
     * an offline tool still runs; this is what stops it drifting in silence, the
     * same job EraKeepers.FALLBACK has.
     */
    @Test
    void theSixteenComesFromTheLeagueNotFromThisConstant(){
        RosterRules live = RosterRules.live();
        if(!live.readFromLeague()){
            System.out.println("Sleeper unreachable and no cache - fallback not cross-checked");
            return;
        }
        Assertions.assertEquals(16, live.size());
        Assertions.assertEquals(10, live.startingSlots());
        Assertions.assertEquals(6, live.benchSlots());
        Assertions.assertEquals(2, live.flexSlots());
        Assertions.assertEquals(1, live.startersAt(Position.QB));
        Assertions.assertEquals(2, live.startersAt(Position.RB));
        Assertions.assertEquals(3, live.startersAt(Position.WR));
        Assertions.assertEquals(1, live.startersAt(Position.TE));
        Assertions.assertEquals(1, live.startersAt(Position.DEF));
        Assertions.assertEquals(12, live.teams());
        Assertions.assertEquals(7, live.mySlot(), "slot 7 in 2026");
        RosterRules fallback = league();
        for(Position position : Position.values()){
            Assertions.assertEquals(fallback.startersAt(position),
                    live.startersAt(position),
                    "FALLBACK_SLOTS has drifted from the live league at " + position);
        }
    }

    /**
     * PlanBacktest.requiredPicks() now asks this layer, so the two cannot
     * disagree. These are the numbers it must keep producing on both settings of
     * -PholdKeepers: the flag is what says whether Justin's keepers are on the
     * roster, and the answer moves with it.
     */
    @Test
    void requiredPicksIsTheSameArithmeticAsThisLayer(){
        String was = System.getProperty("holdKeepers");
        try {
            System.setProperty("holdKeepers", "true");
            Assertions.assertEquals(
                    Map.of(Position.QB, 0, Position.RB, 1, Position.WR, 3,
                            Position.TE, 1, Position.DEF, 1),
                    PlanBacktest.requiredPicks(),
                    "with the keepers held, no quarterback is owed");
            Assertions.assertEquals(PlanBacktest.requiredPicks(),
                    league().justins().stillNeeds());

            System.setProperty("holdKeepers", "false");
            Assertions.assertEquals(
                    Map.of(Position.QB, 1, Position.RB, 2, Position.WR, 3,
                            Position.TE, 1, Position.DEF, 1),
                    PlanBacktest.requiredPicks(),
                    "without them, the plan owes its own quarterback and both backs");
            Assertions.assertEquals(PlanBacktest.requiredPicks(),
                    league().empty().stillNeeds());
        }
        finally {
            if(was == null){
                System.clearProperty("holdKeepers");
            }
            else {
                System.setProperty("holdKeepers", was);
            }
        }
    }

    /**
     * The ceilings are functions of the lineup, not five numbers somebody typed.
     * Change the lineup and they move; that is the test.
     */
    @Test
    void ceilingsAreDerivedFromTheLineupNotTyped(){
        RosterRules rules = league();
        Assertions.assertEquals(2, rules.ceiling(Position.QB));
        Assertions.assertEquals(2, rules.ceiling(Position.DEF));
        // 16 minus what every other named slot needs.
        Assertions.assertEquals(16 - (1 + 2 + 1 + 1), rules.ceiling(Position.WR));
        Assertions.assertEquals(16 - (1 + 3 + 1 + 1), rules.ceiling(Position.RB));
        Assertions.assertEquals(16 - (1 + 2 + 3 + 1), rules.ceiling(Position.TE));

        // A superflex league starts two quarterbacks, so the ceiling is three.
        List<String> superflex = new ArrayList<>(RosterRules.FALLBACK_SLOTS);
        superflex.set(superflex.indexOf("BN"), "QB");
        RosterRules two = RosterRules.of(superflex, 12, 7);
        Assertions.assertEquals(2, two.startersAt(Position.QB));
        Assertions.assertEquals(3, two.ceiling(Position.QB),
                "two starters plus one stash - the constant is the stash, not the ceiling");
        Assertions.assertEquals(5, two.benchSlots());
        Assertions.assertTrue(two.justins().canDraft(Position.QB, 3),
                "in a superflex league the second quarterback is a STARTER, not a stash");

        // A league with no FLEX gives its skill positions no second door either.
        List<String> noFlex = new ArrayList<>(RosterRules.FALLBACK_SLOTS);
        noFlex.replaceAll(slot -> slot.equals("FLEX") ? "BN" : slot);
        RosterRules flat = RosterRules.of(noFlex, 12, 7);
        Assertions.assertEquals(0, flat.flexSlots());
        Assertions.assertEquals(3, flat.mostOnFieldAtOnce(Position.WR),
                "no flex means a fourth receiver can never play");
    }

    /**
     * A CHARACTERISATION of a bug this layer does not fix, pinned so it cannot
     * be forgotten. It passes today because the contradiction is real.
     *
     * PlanBacktest.MY_PICKS is the schedule of a man who KEEPS - fourteen picks,
     * rounds 12 and 13 sold. But holdKeepers() is off by default, so by default
     * the backtest pays that price and puts nobody on the roster: fourteen men
     * on a sixteen-man roster, and requiredPicks() asking for a quarterback that
     * Purdy is standing right next to. TRAPS.md A4 is fixed only behind a flag
     * nobody passes.
     */
    @Test
    void theBacktestDefaultPaysForKeepersItNeverReceives(){
        RosterRules rules = league();
        String was = System.getProperty("holdKeepers");
        try {
            System.clearProperty("holdKeepers");
            Assertions.assertFalse(PlanBacktest.holdKeepers(), "off by default");

            Assertions.assertEquals(rules.livePicks(RosterRules.justinsKeepers()).size(),
                    PlanBacktest.MY_PICKS.length,
                    "the default path uses the pick schedule of a man WITH keepers");
            Assertions.assertEquals(1,
                    PlanBacktest.requiredPicks().get(Position.QB),
                    "while asking for the lineup of a man WITHOUT them");
            Assertions.assertEquals(2,
                    rules.size() - PlanBacktest.MY_PICKS.length,
                    "leaving two roster spots that no keeper and no pick ever fills");
        }
        finally {
            if(was != null){
                System.setProperty("holdKeepers", was);
            }
        }
    }

    /** A league that starts no kicker cannot roster one. */
    @Test
    void aPositionTheLeagueDoesNotStartHasCeilingZero(){
        RosterRules rules = league();
        Assertions.assertEquals(0, rules.ceiling(Position.OTHER));
        Assertions.assertFalse(rules.justins().canDraft(Position.OTHER, 5));
        Assertions.assertTrue(rules.justins().whyNotDraft(Position.OTHER, 5)
                .contains("starts no"));
    }
}
