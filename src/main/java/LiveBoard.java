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

    /**
     * Position caps INCLUDING the defence, which PairwiseOdds.CAP does not have.
     *
     * That map was built for the pairwise-odds work, which deliberately covered
     * only the skill positions, and this class then used it to decide who goes
     * in the 2026 value curve. So defences were absent from the curve, drew a
     * level of zero, and were worth literally nothing - which is why the DEF row
     * read 0.0 at every round.
     *
     * I explained that 0.0 three times tonight as the interchangeability
     * result: defences are a coin flip, the bench is fungible, streaming is
     * free. All of that is true and none of it was the reason. The reason was
     * that the position was not in the model. A whole-draft dry run found it in
     * one pass, having survived every single-pick check.
     *
     * Thirty-two, because there are thirty-two of them.
     */
    static final Map<Position, Integer> CAP = new EnumMap<>(Map.of(
            Position.QB, 32, Position.RB, 60, Position.WR, 72,
            Position.TE, 32, Position.DEF, 32));

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
        // THIS YEAR'S CURVE. The level and the shape come from the 2026 board
        // being drafted, not from a sixteen-year average of boards that are not
        // it. A year where the backs fall off a cliff at RB8 and a year where
        // they glide to RB30 must not produce the same list, and they did while
        // this read historical mean points by rank.
        Set<String> kept = kept(configuration);
        System.out.printf("%d men are already kept league-wide and cannot be drafted%n",
                kept.size());
        Map<Position, double[]> curve = thisYear(planner, kept);
        // LAST SIXTEEN YEARS' UNCERTAINTY, as ratios rather than points, so what
        // is imported is how far outcomes scatter around a rank - never where
        // the rank sits.
        Map<Position, List<List<Double>>> pools =
                new EnumMap<>(BoardValue.pools(men, curve));
        List<List<Double>> defence = defenceScatter();
        if(!defence.isEmpty()){
            pools.put(Position.DEF, defence);
            System.out.printf("defence scatter from %d ranks of sleeper actuals%n",
                    defence.size() - 1);
        }

        // WARM ONCE, ANSWER MANY TIMES.
        //
        // Justin has sixty seconds a pick. A cold run is 29.8 of them, and
        // almost all of that is what comes BEFORE this line: fitting the
        // opponent model, loading sixteen seasons of nflverse, building the
        // scatter pools. None of it changes during a draft. Paying it once and
        // then looping is what DraftNight does and is the only reason it is
        // usable at a table.
        System.out.printf("%nengine warm - paid ONCE, not per pick%n");
        System.out.println("press enter to re-read the board and answer; q to quit");
        java.io.BufferedReader keyboard = new java.io.BufferedReader(
                new java.io.InputStreamReader(System.in));
        boolean first = true;
        while(true){
            if(!first){
                System.out.printf("%n[enter] refresh  |  q quit > ");
                System.out.flush();
                String line = keyboard.readLine();
                if(line == null || line.trim().equalsIgnoreCase("q")){
                    System.out.println("done - good luck.");
                    return;
                }
            }
            first = false;
            long began = System.nanoTime();
            answer(configuration, planner, simulator, draftID, curve, pools, order,
                    men, kept);
            System.out.printf("%n(answered in %.1fs)%n", (System.nanoTime() - began) / 1e9);
        }
    }

    /** One pick's answer, on the board as it stands right now. */
    static void answer(AAAConfiguration configuration, DraftPlanner planner,
                       DraftSimulator simulator, String draftID,
                       Map<Position, double[]> curve,
                       Map<Position, List<List<Double>>> pools, List<String> order,
                       List<PairwiseOdds.Man> men, Set<String> kept) throws Exception {
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
        // IS THE SIMULATOR STILL IN STEP WITH THE REAL DRAFT?
        //
        // DraftSimulator.stateAfter advances its schedule only for a man on our
        // board. Its own comment claims the opposite - "anything not on our
        // board (a defense, a kicker, an unknown id) advances the schedule
        // without touching the pool" - and the code is the authority: the
        // increment sits inside the board.contains guard.
        //
        // Keepers are fine, because the loop above skips their keeper slots, so
        // each keeper pick consumes exactly one. What drifts is a real pick of a
        // man our board does not carry - a kicker, someone past the ADP cut, an
        // id we do not know. He spends a live pick and the schedule does not
        // move, so every later pick is priced one seat early, silently.
        //
        // Not fixed here. That increment is core, Model A shares it, and the
        // draft is tonight; a wrong fix costs more than the fault, whose
        // exposure is low with 237 men on the board for 168 live picks. So it
        // is DETECTED instead. Wrong and loud beats wrong and quiet.
        //
        // THE DETECTOR ITSELF WAS WRONG, and loudly. It compared the slot's
        // PICK NUMBER against the pick COUNT, and those are only the same
        // question while no keeper slot has gone by. A keeper slot is a pick
        // number that consumes no pick; this league has twenty-four of them and
        // the earliest is pick 32, so from round 3 onward the two quantities
        // part company on a perfectly clean board and never rejoin. Measured by
        // ModelAAudit on a clean 168-pick replay: it would have fired at 137 of
        // 169 refreshes, starting at 31 picks in, telling Justin all night to
        // distrust a tool that was working. DraftNight.scheduleDrift counts
        // LIVE slots, which is the quantity taken.size() actually measures, and
        // fires 0 times on the same replay.
        String drift = DraftNight.scheduleDrift(simulator, state, taken.size());
        if(drift != null){
            System.out.print(drift);
            System.out.printf("   *** Every number below is priced for the wrong slot -"
                    + " trust the%n   *** RUNBOOK until it clears.%n");
        }
        int onPick = slot == null ? 200 : slot.pickNumber();
        int pick = onPick;
        for(int p = onPick; p <= 200; p++){
            DraftSimulator.Slot mineAt = simulator.slotAt(p);
            // A KEEPER SLOT IS NOT A PICK. Rounds 12 and 13 belong to Justin
            // and select nobody - Tuten sits in pick 138 and Purdy in 151 - so
            // scanning for "a slot that is mine" finds them and prices a pick
            // that does not exist. Measured: from pick 113 until about 152 the
            // tool answered "nothing legal" on every refresh, which is exactly
            // the stretch where the tight end and defence still have to be
            // found. slotOf() already asks keeperSlot(); this scan never did.
            if(mineAt != null && planner.me().equals(mineAt.manager())
                    && !mineAt.keeperSlot()){
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
        List<String> declined = new ArrayList<>();
        RosterRules.Roster roster = rulesRoster(planner, simulator, state, mine, declined);
        if(!declined.isEmpty()){
            System.out.printf("%nON MY ROSTER BUT OUTSIDE THE RULES - counted anyway:%n");
            for(String why : declined){
                System.out.printf("   %s%n", why);
            }
        }
        List<Position> legal = roster.legalAt(round);
        System.out.printf("the rules allow here: %s%n", legal);
        System.out.printf("still needed for a legal lineup: %s%n", roster.stillNeeds());
        System.out.printf("THE PLAN says: %s%n%n", Tomorrow.PLAN.getOrDefault(round, "-"));

        // THE BOARD AS IT WILL BE, weighted by how often it turns out that way.
        //
        // Justin: why are we looking at Gibbs and Nacua, those two will almost
        // never be available at my pick 7. Right - the draft had not started, so
        // `taken` was empty and "best available" was the best man in football.
        //
        // The first fix assumed everyone with an ADP under my pick was gone,
        // which is a hard cutoff and false in both directions: a man at ADP 6.9
        // is not certainly gone and one at 7.1 is not certainly there. So this
        // SIMULATES the picks between here and my seat, many times, using the
        // opponent model the rest of the repo is fitted on, and asks what is
        // actually on the board when I get there.
        //
        // What comes back is a distribution rather than a name. A position whose
        // best man is the same in every trial is a different proposition from one
        // that could be four different players, and the second number is what
        // tells them apart.
        // Every man at each position, best projection first. Rank in this list
        // IS the index into the value curve, so the two can never disagree.
        Map<Position, List<String>> ordered = new EnumMap<>(Position.class);
        for(Map.Entry<String, Double> entry : planner.points().entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(kept.contains(entry.getKey())){
                continue;               // on somebody's roster, not on the board
            }
            if(player != null && CAP.containsKey(player.position)){
                ordered.computeIfAbsent(player.position, u -> new ArrayList<>())
                        .add(entry.getKey());
            }
        }
        for(List<String> ids : ordered.values()){
            ids.sort(Comparator.comparingDouble(
                    (String id) -> planner.points().getOrDefault(id, 0.0)).reversed());
        }
        int trials = Integer.getInteger("waitTrials", 200);
        Map<Position, List<Integer>> ranksAt = new EnumMap<>(Position.class);
        Map<Position, Map<String, Integer>> whoAt = new EnumMap<>(Position.class);
        if(taken.size() < pick - 1){
            for(int trial = 0; trial < trials; trial++){
                Random random = new Random(661_000L + 7919L * trial);
                DraftSimulator.SimState branch = state.copy();
                while(simulator.slotOf(branch) != null
                        && simulator.slotOf(branch).pickNumber() < pick){
                    simulator.simulateOneFrom(branch, random);
                }
                for(Position position : new Position[]{Position.RB, Position.WR,
                        Position.TE, Position.QB, Position.DEF}){
                    // His own PROJECTION RANK, which is what indexes the value
                    // curve. The first version counted taken men encountered
                    // during a HashMap walk, which has no order, so the rank it
                    // reported was noise - and it showed as Nacua being "WR3"
                    // while also being the best receiver on the board in every
                    // trial, which cannot both be true.
                    List<String> byProjection = ordered.get(position);
                    if(byProjection == null){
                        continue;
                    }
                    for(int rank = 1; rank <= byProjection.size(); rank++){
                        String id = byProjection.get(rank - 1);
                        if(branch.takenAtOf(id) == null){
                            ranksAt.computeIfAbsent(position, u -> new ArrayList<>())
                                    .add(rank);
                            whoAt.computeIfAbsent(position, u -> new HashMap<>())
                                    .merge(id, 1, Integer::sum);
                            break;
                        }
                    }
                }
            }
            System.out.printf("%d men actually gone; the %d picks before mine simulated"
                    + " %d times%n", taken.size(), pick - 1 - taken.size(), trials);
        }

        // My roster as (position, rank) on the live board.
        List<BoardValue.Slot> held = new ArrayList<>();
        // A MAN I ALREADY HOLD IS PRICED BY WHO HE IS, NOT BY WHO ELSE WENT.
        //
        // This used to call depth(), which counts how many of a position have
        // LEFT THE BOARD - so a man on my own roster was repriced by other
        // people's picks. Tuten read RB1 before a single pick was made, and
        // RB55 by round 15: the best back in football, then nearly nothing,
        // without ever playing a down. Worse, every back I hold got the SAME
        // rank, and BoardValue.drawn is keyed on position-and-rank, so they all
        // drew identically in all six hundred worlds - my roster's composition
        // was invisible to the model that is supposed to be roster-aware.
        //
        // His rank is his own place on the projection curve and it does not
        // move. Clamped at the cap because past it the curve has no entry and a
        // real man would price at zero.
        Map<String, Integer> rankOf = projectionRanks(planner, kept);
        for(String id : mine){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null){
                int cap = CAP.getOrDefault(player.position, 1);
                held.add(new BoardValue.Slot(player.position,
                        Math.min(cap, rankOf.getOrDefault(id, cap))));
            }
        }

        int next = nextPickAfter(simulator, state, planner, pick);
        System.out.printf("%-5s %-24s %6s %6s %8s %7s %7s %6s %14s%n", "POS",
                "LIKELIEST THERE", "RANK", "ODDS", "ADDS NOW", "VS WAIT", "END",
                "SWING", "NEXT CLIFF");

        Map<Position, Double> urgency = new EnumMap<>(Position.class);
        Set<Position> refused = new HashSet<>();
        Map<Position, Double> adds = new EnumMap<>(Position.class);
        Map<Position, String> best = new EnumMap<>(Position.class);
        for(Position position : new Position[]{Position.RB, Position.WR, Position.TE,
                Position.QB, Position.DEF}){
            Map<String, Integer> seenAt = whoAt.get(position);
            String candidate = seenAt == null || seenAt.isEmpty()
                    ? bestAvailable(planner, taken, position)
                    : seenAt.entrySet().stream().max(Map.Entry.comparingByValue())
                            .map(Map.Entry::getKey).orElse(null);
            if(candidate == null){
                continue;
            }
            // How often the man named here is really the one waiting, and how
            // deep the position is when I get there, averaged over the trials.
            List<Integer> ranks = ranksAt.get(position);
            int howOften = seenAt == null ? 0 : seenAt.getOrDefault(candidate, 0);
            double share = ranks == null || ranks.isEmpty() ? 1
                    : (double) howOften / ranks.size();
            // The rules say what is LEGAL; MOST says what is WANTED. LiveBoard
            // consulted only the first, so a second defence - legal from round
            // 10, because a stash is starters-plus-one and the rules cannot
            // know that nobody keeps a defence - was offerable. MOST has capped
            // DEF at one all along and this loop never asked it.
            int already = 0;
            for(BoardValue.Slot slot2 : held){
                if(slot2.position() == position){
                    already++;
                }
            }
            String why = already >= BoardValue.MOST.getOrDefault(position, 99)
                    ? "already hold " + already + ", which is all this roster wants"
                    : roster.whyNotDraft(position, round);
            if(why != null){
                Player player = Player.getPlayerFromSIDV2(candidate);
                System.out.printf("%-5s %-24s %8s %10s %10s   REFUSED: %s%n", position,
                        player == null ? candidate
                                : player.firstName + " " + player.lastName,
                        "-", "-", "-", why);
                continue;
            }
            int rank = ranks == null || ranks.isEmpty()
                    ? depth(planner, taken, position, candidate)
                    : (int) Math.round(ranks.stream().mapToInt(Integer::intValue)
                            .average().orElse(1));
            int later = next < 0 ? rank : rank + drain(planner, taken, position, pick, next);
            double base = BoardValue.empirical(held, pools, curve, order.size(), true);
            List<BoardValue.Slot> now = new ArrayList<>(held);
            now.add(new BoardValue.Slot(position, rank));
            double addsNow = BoardValue.empirical(now, pools, curve, order.size(), true) - base;
            List<BoardValue.Slot> then = new ArrayList<>(held);
            then.add(new BoardValue.Slot(position, later));
            double wait = addsNow
                    - (BoardValue.empirical(then, pools, curve, order.size(), true) - base);
            // Rank on the ROSTER I END WITH, not on the drop I avoid. Ranking
            // by the drop scored 1916 in backtest and bought tight ends in
            // round 2; rolling the rest of the draft out and valuing the
            // finished roster scored 2008, which ties the plan. The cliff still
            // prints, because it is what makes the number legible, but it no
            // longer decides on its own.
            // Justin's rule, asked and answered: maximise the mean, but refuse
            // a path whose bad world sits more than the bar below its own
            // average. Measured as a no-op above 250 on everything this model
            // builds, so it is a guard rather than a lever - if it ever starts
            // refusing things, that is worth reading.
            double[] both = rolloutStats(planner, taken, curve, pools, order.size(),
                    held, position, rank, pick);
            boolean fragile = BoardValue.tooFragile(both);
            double finished = both[0];
            if(fragile){
                refused.add(position);
            }
            Valley cliff = nextValley(tiers(curve, pools, position,
                    Double.parseDouble(System.getProperty("tierBar", "0.40"))), rank);
            boolean crosses = cliff != null && later > cliff.afterRank();
            // The cliff, not the odds, is what decides this. Crossing one means
            // the man you come back to is on the far side of a real drop; not
            // crossing one means the board is flat there and waiting is cheap
            // however unlikely the later man is to be better.
            if(crosses){
                wait += cliff.drop() / 2;
            }
            urgency.put(position, finished);
            adds.put(position, addsNow);
            best.put(position, candidate);
            Player player = Player.getPlayerFromSIDV2(candidate);
            String where = cliff == null ? "none ahead"
                    : String.format("after %s%d%s", position, cliff.afterRank(),
                            crosses ? " CROSSED" : "");
            // END was %7.0f, which printed WR 1921 and TE 1921 at pick 66 and
            // then chose TE with nothing on screen to explain it - 13 of 84
            // priced picks in the audit showed two rows tied to the printed
            // digit. Now that the legend correctly says END TEAM decides, the
            // reader must be able to see WHICH end team is larger.
            System.out.printf("%-5s %-24s %6d %5.0f%% %8.1f %7.1f %7.1f %5.0f%% %14s %s%n",
                    position,
                    player == null ? candidate : player.firstName + " " + player.lastName,
                    rank, 100 * share, addsNow, wait, both[0],
                    100 * (both[0] - both[1]) / both[0],
                    where, fragile ? "REFUSED fragile" : "");
        }

        // A GUARD THAT REFUSES EVERYTHING IS NOT A GUARD.
        //
        // The fragility filter used to set a refused path to -1e9. When it
        // refused SOME positions that ranks them last, which is the intent. But
        // it refuses ALL of them from about round 5 on, and then every value is
        // -1e9, they tie, and the verdict fell through to the tie-break on ADDS
        // NOW - which is the greedy urgency rule this file's own comments say
        // was replaced because it scored 1916 and spent round 2 on a tight end.
        // So at the picks where the guard bit hardest, the tool printed a
        // verdict from an objective it had abandoned, and said END TEAM above it.
        //
        // Measured by an adversarial audit: it binds on 12 of 12 openings at
        // pick 7. I had reported it as a no-op, from a BACKTEST sweep where it
        // genuinely is one - the live board values a man at about 300 where the
        // backtest values him at 130, and 15% of a mean is a different thing in
        // each. The same units mistake as the 300-point absolute bar, one level
        // up.
        //
        // Now: refusal ranks a position last only while something survives. If
        // everything is refused the guard has no information, so rank on END
        // among all of them and SAY so, rather than quietly changing objective.
        List<Position> ranked = new ArrayList<>(urgency.keySet());
        boolean allRefused = refused.size() == urgency.size() && !urgency.isEmpty();
        ranked.sort(Comparator.<Position>comparingInt(
                        p -> !allRefused && refused.contains(p) ? 1 : 0)
                .thenComparing(Comparator.comparingDouble(
                        (Position p) -> urgency.getOrDefault(p, -1e9)).reversed()));
        String verdict = ranked.isEmpty() ? "nothing legal" : ranked.get(0).toString();
        if(allRefused){
            System.out.printf("%n   every position is over the %.0f%% swing bar, so the bar"
                    + " cannot%n   discriminate here - ranking on END TEAM instead. This is"
                    + " the%n   guard admitting it has nothing to say, not a warning about"
                    + " the pick.%n", 100 * BoardValue.fragilityBar());
        }
        System.out.printf("%n   the model takes: %s%n", verdict);
        System.out.printf("%nNEXT CLIFF is the one that decides this. A position's value does not%n"
                + "slide, it steps: the raw rank curve falls off at a few places and is%n"
                + "flat between them. CROSSED means my next pick lands on the far side of%n"
                + "the next step, which is the only version of 'expensive to wait' that%n"
                + "means anything. A smooth odds curve cannot show this - it is monotone%n"
                + "by construction and was measured flattening the real cliff by five%n"
                + "points - which is why the odds are an ingredient here and not the answer.%n");
        // THE LEGEND NAMED THE WRONG COLUMN. It used to end "VS WAIT ... is
        // what to rank on", and the code has never ranked on VS WAIT - it
        // ranks on END TEAM, as the comment at the sort says outright. They
        // disagree in practice: at pick 79 of the audit's seed 0, VS WAIT
        // picks the receiver and END TEAM picks the defence, so a reader
        // following the printed instruction takes a different player than the
        // tool recommends. Six instances of prose drift in this project and
        // this is the first one in text Justin actually reads.
        System.out.printf("%nEND TEAM is what the verdict ranks on: the whole season my%n"
                + "STARTING NINE scores if I take him here and play the draft out.%n"
                + "That is the number to compare across rows.%n"
                + "%nADDS NOW is what he adds over the man the wire would give you, and%n"
                + "VS WAIT is that minus what the best of his position would add at%n"
                + "pick %s - the cost of waiting. They are the WHY behind END TEAM,%n"
                + "not the ranking: where they disagree with END TEAM, END TEAM wins.%n"
                + "A position the rules refuse is never priced at all.%n",
                next < 0 ? "(none left)" : String.valueOf(next));
    }

    /**
     * Where a position falls off, and whether waiting walks you off it.
     *
     * Justin: the odds that the man at 18 beats the man at 7 are "useless on
     * their own, because positional scarcity and roster composition, valleys,
     * matter so much more". He is right, and the odds curve is the worst
     * possible instrument for this - it is monotone and smoothed by
     * construction, so it CANNOT represent a cliff, and it was measured
     * flattening the real one at the top of the board by five points.
     *
     * So valleys are found on the RAW mean-by-rank curve, unsmoothed, and a
     * drop counts as a valley when it is more than twice the typical drop for
     * that position. What matters is not how big the next drop is but whether
     * MY next pick is on the far side of it.
     */
    record Valley(int afterRank, double drop){}

    /**
     * Tiers the way Boris Chen builds them: groups that are statistically
     * INDISTINGUISHABLE, not groups separated by a big enough gap.
     *
     * Justin asked for cliffs that come out similar to or better than his tiers.
     * His method clusters players whose expert rankings overlap - two men are in
     * one tier when you cannot tell them apart, and a tier boundary is where you
     * can. That is a different question from "is this drop bigger than X", and
     * it is the right one: a 7-point gap between two men who each swing 60
     * points is nothing, and a 20-point gap between two men who barely swing is
     * a wall.
     *
     * A threshold on drops cannot express that, and mine did not: it called the
     * 7-point step after Gibbs a cliff and buried the 36.9-point step after
     * Robinson in a list of fifteen. The isotonic pass that was supposed to save
     * it is a no-op here - thisYear() sorts projections, so the curve is already
     * monotone and there is nothing for pool-adjacent-violators to pool. It
     * earns its keep on historical actuals, which are noisy, and does nothing on
     * a sorted projection curve.
     *
     * So this asks the question directly, using both halves of the model: what
     * are the odds the man one rank BELOW outscores the man above him, given
     * this year's projected gap and sixteen years of measured scatter at those
     * ranks? Near a half means the same tier. Well under a half means a wall.
     * No threshold on points anywhere - the units are probability, so a
     * quarterback and a defence are judged on the same scale.
     */
    static List<Valley> tiers(Map<Position, double[]> curve,
                              Map<Position, List<List<Double>>> pools, Position position,
                              double bar){
        double[] level = curve.get(position);
        if(level == null){
            return List.of();
        }
        // Against the TIER LEADER, not against the next man down.
        //
        // The first version compared each rank to rank+1 and found no tiers at
        // all in backs or receivers, which is correct and useless: adjacent
        // ranks are always near coin-flips once sixteen years of scatter are in
        // the comparison. Boris Chen does not ask whether you can tell RB7 from
        // RB8, he asks whether you can tell RB8 from the best man in RB8's
        // group. Difference accumulates down a tier until it is visible, and
        // where it becomes visible is the wall.
        List<Valley> walls = new ArrayList<>();
        int leader = 1;
        for(int rank = 2; rank < level.length; rank++){
            if(level[rank] <= 0 || level[leader] <= 0){
                continue;
            }
            int below = 0;
            for(int world = 0; world < BoardValue.WORLDS; world++){
                double best = BoardValue.drawn(pools, position, leader, world, curve, true);
                double him = BoardValue.drawn(pools, position, rank, world, curve, true);
                if(him > best){
                    below++;
                }
            }
            double beats = (double) below / BoardValue.WORLDS;
            if(beats < bar){
                // He is distinguishable from his leader, so the tier ended at
                // the man above him and he starts the next one.
                walls.add(new Valley(rank - 1, level[rank - 1] - level[rank]));
                leader = rank;
            }
        }
        return walls;
    }

    /** Valleys in THIS year's curve, isotonic so noise pools away and steps stay. */
    static List<Valley> valleys(Map<Position, double[]> curve, Position position){
        double[] raw = curve.get(position);
        if(raw == null){
            return List.of();
        }
        double[] mean = new double[Math.max(0, raw.length - 1)];
        double[] weight = new double[mean.length];
        for(int i = 0; i < mean.length; i++){
            mean[i] = raw[i + 1];
            weight[i] = raw[i + 1] > 0 ? 1 : 0;
        }
        double[] fitted = PairwiseOdds.isotonicDecreasing(mean, weight);
        List<Double> steps = new ArrayList<>();
        for(int i = 0; i + 1 < fitted.length; i++){
            double drop = fitted[i] - fitted[i + 1];
            if(drop > 0){
                steps.add(drop);
            }
        }
        if(steps.isEmpty()){
            return List.of();
        }
        Collections.sort(steps);
        double median = steps.get(steps.size() / 2);
        // A CLIFF IS BIG RELATIVE TO THE LEVEL, not merely above the 75th
        // percentile of steps.
        //
        // Justin, on being told the running back cliff was after RB1: "isn't
        // the cliff after gibbs and robinson". He was right and the detector
        // was wrong. A percentile bar found FIFTEEN valleys in a sixty-rank
        // curve, at which point the word means nothing, and it fired on the
        // 7-point step after Gibbs while the real 36.9-point step after
        // Robinson was just another entry in the list.
        //
        // 7.0 off 299.9 is two per cent and is rounding. 36.9 off 292.9 is
        // thirteen per cent and is a tier boundary. So a step qualifies only if
        // it is at least a twentieth of what the rank above it is worth AND
        // several times the typical step - the first test is what makes it a
        // cliff, the second stops a flat tail full of tiny numbers producing
        // proportionally large ones.
        List<Valley> found = new ArrayList<>();
        for(int i = 0; i + 1 < fitted.length; i++){
            double drop = fitted[i] - fitted[i + 1];
            if(fitted[i] <= 0){
                continue;
            }
            if(drop >= 0.05 * fitted[i] && drop >= 3 * median){
                found.add(new Valley(i + 1, drop));
            }
        }
        return found;
    }

    static List<Valley> valleysHistorical(List<PairwiseOdds.Man> men, Position position){
        int cap = CAP.getOrDefault(position, 0);
        double[] total = new double[cap + 2];
        double[] seen = new double[cap + 2];
        for(PairwiseOdds.Man man : men){
            if(man.position() == position && man.rank() >= 1 && man.rank() <= cap){
                total[man.rank()] += man.points();
                seen[man.rank()]++;
            }
        }
        double[] mean = new double[cap + 1];
        double[] weight = new double[cap + 1];
        for(int rank = 1; rank <= cap; rank++){
            mean[rank - 1] = seen[rank] == 0 ? 0 : total[rank] / seen[rank];
            weight[rank - 1] = seen[rank];
        }
        // ISOTONIC, which is the smoother that does both jobs at once.
        //
        // Justin: "it should be smoothed to fix granularity of not having 100+
        // sample years, but it should be able to show valleys." Sixteen seasons
        // gives about sixteen men per rank, so the raw curve - which the first
        // version of this read - is mostly noise, and any two adjacent ranks
        // differ by more sampling error than football. But a moving average
        // fixes that by destroying exactly what we are looking for: it was
        // measured smearing the real cliff at the top of the board by five
        // points.
        //
        // Pool-adjacent-violators does neither. It forces the curve
        // non-increasing by merging ranks that disagree into FLAT RUNS, and it
        // leaves the drops between those runs exactly where they are. The flat
        // runs are the tiers and the steps between them are the valleys - so
        // the tier structure is not assumed or hand-drawn, it is what is left
        // after the noise is pooled away. Weighted by how many men each rank
        // actually had, so a thin rank cannot invent a cliff.
        double[] fitted = PairwiseOdds.isotonicDecreasing(mean, weight);

        List<Double> steps = new ArrayList<>();
        for(int i = 0; i + 1 < fitted.length; i++){
            double drop = fitted[i] - fitted[i + 1];
            if(drop > 0){
                steps.add(drop);
            }
        }
        if(steps.isEmpty()){
            return List.of();
        }
        Collections.sort(steps);
        // A step counts as a valley when it is bigger than three quarters of the
        // steps this position has - so "cliff" means steep FOR THIS POSITION
        // rather than steep in points, which would find them all at running back.
        double bar = Math.max(steps.get((int) (steps.size() * 0.75)), 4);
        List<Valley> found = new ArrayList<>();
        for(int i = 0; i + 1 < fitted.length; i++){
            double drop = fitted[i] - fitted[i + 1];
            if(drop >= bar){
                found.add(new Valley(i + 1, drop));
            }
        }
        return found;
    }

    /** The first cliff strictly after my current rank, if there is one. */
    static Valley nextValley(List<Valley> valleys, int rank){
        for(Valley valley : valleys){
            if(valley.afterRank() >= rank){
                return valley;
            }
        }
        return null;
    }

    /**
     * Take him, fill the rest of the draft greedily by value, and score the team.
     *
     * This is the rule that scored 2008 against the plan's 2050 - a tie - where
     * ranking by the drop scored 1916 and spent round 2 on a tight end. Board
     * depth for future picks advances at each position's OWN ADP rate, never a
     * shared one: assuming a shared rate is what once drafted TE TE QB QB.
     */
    static double[] rolloutStats(DraftPlanner planner, List<String> taken,
                                 Map<Position, double[]> curve,
                                 Map<Position, List<List<Double>>> pools, int count,
                                 List<BoardValue.Slot> held, Position first, int firstRank,
                                 int fromPick){
        return BoardValue.stats(rolloutRoster(planner, taken, curve, pools, count,
                held, first, firstRank, fromPick), pools, curve, count, true);
    }

    static double rollout(DraftPlanner planner, List<String> taken,
                          Map<Position, double[]> curve,
                          Map<Position, List<List<Double>>> pools, int count,
                          List<BoardValue.Slot> held, Position first, int firstRank,
                          int fromPick){
        List<BoardValue.Slot> roster = new ArrayList<>(held);
        roster.add(new BoardValue.Slot(first, firstRank));
        Map<Position, Integer> mine = new EnumMap<>(Position.class);
        for(BoardValue.Slot slot : roster){
            mine.merge(slot.position(), 1, Integer::sum);
        }
        int[] picks = {7, 18, 31, 42, 55, 66, 79, 90, 103, 114, 127, 162, 175, 186};

        // THE TAIL MUST END WITH A LEGAL LINEUP.
        //
        // This loop was pure marginal capped by MOST, with no legality
        // constraint at all, so it frequently finished with NO DEFENCE - a
        // defence's marginal is about twenty points and a skill man's is larger
        // for most of the draft, so the greedy tail simply never got round to
        // one. BoardValue.oneSeason then charges those rosters the "drop your
        // weakest man to stream" penalty.
        //
        // That made NOT taking the defence now look like never taking it, so
        // taking it now won. Measured by the second adversarial pass: a defence
        // in round 7 or 8 in five of six drafts, against DRAFT-READY's claim
        // that the model refuses a defence before round ten. At seed 0 pick 79
        // the table showed WR Alec Pierce adding 144.6 with his cliff CROSSED
        // against a defence adding 19.9 whose VS WAIT was 0.0 - and took the
        // defence, on one point in 2004 against a 125-point bar.
        //
        // This is TRAPS A7 living inside the quantity the verdict ranks on. It
        // is also the answer to the DryRun round-8 defence I left open: not a
        // structural truth about constant marginals, a rollout that was allowed
        // to imagine an illegal roster.
        //
        // The requirement is DERIVED FROM THE LINEUP, never typed per position
        // - the same discipline RosterRules.ceiling() holds to.
        Map<Position, Integer> required = RosterRules.live().empty().stillNeeds();
        int seatsLeft = 0;
        for(int pick : picks){
            if(pick > fromPick){
                seatsLeft++;
            }
        }
        for(int pick : picks){
            if(pick <= fromPick || roster.size() >= 16){
                continue;
            }
            seatsLeft--;
            // How many of the remaining seats are already spoken for by named
            // starting slots this roster has not filled. When that uses up
            // everything left, the tail may only take what it still owes.
            int owed = 0;
            for(Map.Entry<Position, Integer> need : required.entrySet()){
                owed += Math.max(0, need.getValue()
                        - mine.getOrDefault(need.getKey(), 0));
            }
            boolean mustFill = owed > seatsLeft;
            double base = BoardValue.empirical(roster, pools, curve, count, true);
            Position best = null;
            double most = -1e9;
            int bestRank = 1;
            for(Position position : new Position[]{Position.RB, Position.WR,
                    Position.TE, Position.QB, Position.DEF}){
                if(mine.getOrDefault(position, 0) >= BoardValue.MOST.get(position)){
                    continue;
                }
                if(mustFill && mine.getOrDefault(position, 0)
                        >= required.getOrDefault(position, 0)){
                    continue;   // no seats to spare for a position already filled
                }
                int rank = expectedRank(planner, taken, position, pick);
                double[] mean = curve.get(position);
                if(mean == null || rank >= mean.length){
                    continue;
                }
                List<BoardValue.Slot> trial = new ArrayList<>(roster);
                trial.add(new BoardValue.Slot(position, rank));
                double adds = BoardValue.empirical(trial, pools, curve, count, true) - base;
                if(adds > most){
                    most = adds;
                    best = position;
                    bestRank = rank;
                }
            }
            if(best == null){
                break;
            }
            roster.add(new BoardValue.Slot(best, bestRank));
            mine.merge(best, 1, Integer::sum);
        }
        return BoardValue.empirical(roster, pools, curve, count, true);
    }

    /** The same rollout, handing back the roster so it can be judged twice. */
    static List<BoardValue.Slot> rolloutRoster(DraftPlanner planner, List<String> taken,
                                               Map<Position, double[]> curve,
                                               Map<Position, List<List<Double>>> pools,
                                               int count, List<BoardValue.Slot> held,
                                               Position first, int firstRank, int fromPick){
        List<BoardValue.Slot> roster = new ArrayList<>(held);
        roster.add(new BoardValue.Slot(first, firstRank));
        Map<Position, Integer> mine = new EnumMap<>(Position.class);
        for(BoardValue.Slot slot : roster){
            mine.merge(slot.position(), 1, Integer::sum);
        }
        int[] picks = {7, 18, 31, 42, 55, 66, 79, 90, 103, 114, 127, 162, 175, 186};

        // THE TAIL MUST END WITH A LEGAL LINEUP.
        //
        // This loop was pure marginal capped by MOST, with no legality
        // constraint at all, so it frequently finished with NO DEFENCE - a
        // defence's marginal is about twenty points and a skill man's is larger
        // for most of the draft, so the greedy tail simply never got round to
        // one. BoardValue.oneSeason then charges those rosters the "drop your
        // weakest man to stream" penalty.
        //
        // That made NOT taking the defence now look like never taking it, so
        // taking it now won. Measured by the second adversarial pass: a defence
        // in round 7 or 8 in five of six drafts, against DRAFT-READY's claim
        // that the model refuses a defence before round ten. At seed 0 pick 79
        // the table showed WR Alec Pierce adding 144.6 with his cliff CROSSED
        // against a defence adding 19.9 whose VS WAIT was 0.0 - and took the
        // defence, on one point in 2004 against a 125-point bar.
        //
        // This is TRAPS A7 living inside the quantity the verdict ranks on. It
        // is also the answer to the DryRun round-8 defence I left open: not a
        // structural truth about constant marginals, a rollout that was allowed
        // to imagine an illegal roster.
        //
        // The requirement is DERIVED FROM THE LINEUP, never typed per position
        // - the same discipline RosterRules.ceiling() holds to.
        Map<Position, Integer> required = RosterRules.live().empty().stillNeeds();
        int seatsLeft = 0;
        for(int pick : picks){
            if(pick > fromPick){
                seatsLeft++;
            }
        }
        for(int pick : picks){
            if(pick <= fromPick || roster.size() >= 16){
                continue;
            }
            seatsLeft--;
            // How many of the remaining seats are already spoken for by named
            // starting slots this roster has not filled. When that uses up
            // everything left, the tail may only take what it still owes.
            int owed = 0;
            for(Map.Entry<Position, Integer> need : required.entrySet()){
                owed += Math.max(0, need.getValue()
                        - mine.getOrDefault(need.getKey(), 0));
            }
            boolean mustFill = owed > seatsLeft;
            double base = BoardValue.empirical(roster, pools, curve, count, true);
            Position best = null;
            double most = -1e9;
            int bestRank = 1;
            for(Position position : new Position[]{Position.RB, Position.WR,
                    Position.TE, Position.QB, Position.DEF}){
                if(mine.getOrDefault(position, 0) >= BoardValue.MOST.get(position)){
                    continue;
                }
                if(mustFill && mine.getOrDefault(position, 0)
                        >= required.getOrDefault(position, 0)){
                    continue;   // no seats to spare for a position already filled
                }
                int rank = expectedRank(planner, taken, position, pick);
                double[] mean = curve.get(position);
                if(mean == null || rank >= mean.length){
                    continue;
                }
                List<BoardValue.Slot> trial = new ArrayList<>(roster);
                trial.add(new BoardValue.Slot(position, rank));
                double adds = BoardValue.empirical(trial, pools, curve, count, true) - base;
                if(adds > most){
                    most = adds;
                    best = position;
                    bestRank = rank;
                }
            }
            if(best == null){
                break;
            }
            roster.add(new BoardValue.Slot(best, bestRank));
            mine.merge(best, 1, Integer::sum);
        }
        return roster;
    }

    /**
     * How deep a position will be at a later pick - counting who has REALLY
     * gone, not only who ADP expects to have gone.
     *
     * This took a `taken` list and never read it. Every future rank in the
     * rollout came from ADP alone, so the rollout planned against a board that
     * existed only in August: by round 8 the real board and the ADP board have
     * diverged badly, and a run on a position is invisible. It showed up as
     * DryRun drafting a DEFENCE at round 8 once it started using this rule -
     * a pick every measurement in the repo says is wrong, produced by a rollout
     * that could not see what had actually been drafted.
     *
     * Now: a man is gone if he is really gone, OR if ADP expects him gone by
     * that pick and we have no evidence either way. The first term is fact and
     * the second is the prior it falls back to for picks that have not happened.
     */
    static int expectedRank(DraftPlanner planner, List<String> taken,
                            Position position, int pick){
        Set<String> already = taken == null ? Set.of() : new HashSet<>(taken);
        int gone = 0;
        for(String id : planner.points().keySet()){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player == null || player.position != position){
                continue;
            }
            if(already.contains(id) || SleeperProjections.adpOf(id) < pick){
                gone++;
            }
        }
        return gone + 1;
    }

    /**
     * The 2026 projection curve: what each positional rank is worth THIS year.
     *
     * Justin: the model "should produce a different average list of positions
     * each season due to the curves of player projection dropoffs being
     * different for each position for each year". This is the input that makes
     * that true. Ranks are by projection within a position, so index r is what
     * the rth best man at that position is projected for on the board in front
     * of him - cliffs, plateaus and all.
     */
    /**
     * Everyone already kept, by ANY team. None of them can be drafted.
     *
     * Justin: puka is a keeper, that's an issue with the model. It was the worst
     * one found all session. The draftable pool was built from planner.points(),
     * which is every man with a projection, so twenty-four men who are already on
     * somebody's roster were being ranked, tiered and recommended - and all three
     * of the model's picks at 7 were kept men. Nacua, Taylor and Bowers are on
     * other people's teams.
     *
     * This is not a small correction to the board, it is a different board:
     * twenty-four men gone before a pick is made, most of them from the top.
     */
    /**
     * Scatter for defences, which the nflverse harvest cannot supply.
     *
     * nflverse stats_player_week lists individual men - CB, DE, DL - and has no
     * team defences at all, so PairwiseOdds.nflverseMen excluding them was right
     * for that source. The consequence was that defences had no measured
     * uncertainty anywhere, and once they were put into the 2026 curve they were
     * treated as CERTAIN while every other man was uncertain.
     *
     * Sleeper does have them, league-scored, and the FFC boards carry defence
     * ADP: thirteen seasons join, twelve to twenty-three ranked defences a year
     * against twenty-nine to thirty-two scored. Same shape as the skill pools -
     * a ratio to what that rank was centrally worth, pooled over a
     * neighbourhood - so a defence now carries real spread instead of a
     * pretence of certainty.
     */
    static List<List<Double>> defenceScatter(){
        Map<Integer, List<Double>> byRank = new HashMap<>();
        try {
            Map<String, EraBoards.Board> boards = EraBoards.usable("ppr",
                    EraIngest.MIN_RATE, EraIngest.minDepth());
            for(Map.Entry<String, EraBoards.Board> entry : boards.entrySet()){
                Map<String, Double> points = LeagueActuals.seasonDefencePoints(entry.getKey());
                int rank = 0;
                for(String id : entry.getValue().ids()){
                    if(entry.getValue().positionOf().get(id) != Position.DEF){
                        continue;
                    }
                    rank++;
                    Double scored = points.get(id);
                    if(scored != null && scored > 0){
                        byRank.computeIfAbsent(rank, u -> new ArrayList<>()).add(scored);
                    }
                }
            }
        }
        catch(Exception unavailable){
            return List.of();
        }
        if(byRank.isEmpty()){
            return List.of();
        }
        int cap = CAP.get(Position.DEF);
        double[] mean = new double[cap + 1];
        for(int rank = 1; rank <= cap; rank++){
            List<Double> seen = byRank.getOrDefault(rank, List.of());
            mean[rank] = seen.isEmpty() ? 0 : seen.stream()
                    .mapToDouble(Double::doubleValue).average().orElse(0);
        }
        List<List<Double>> out = new ArrayList<>();
        for(int rank = 0; rank <= cap; rank++){
            List<Double> pool = new ArrayList<>();
            int half = Math.max(6, (int) Math.round(rank * 0.25));
            for(int r = Math.max(1, rank - half); r <= Math.min(cap, rank + half); r++){
                double middle = mean[r];
                if(middle <= 0){
                    continue;
                }
                for(double scored : byRank.getOrDefault(r, List.of())){
                    pool.add(scored / middle);
                }
            }
            out.add(pool);
        }
        return out;
    }

    static Set<String> kept(AAAConfiguration configuration){
        Set<String> out = new HashSet<>();
        for(Keeper keeper : configuration.getTodaysKeepers()){
            if(keeper.player != null && keeper.player.sleeperIDString != null){
                out.add(keeper.player.sleeperIDString);
            }
        }
        return out;
    }

    static Map<Position, double[]> thisYear(DraftPlanner planner, Set<String> kept){
        Map<Position, List<Double>> byPosition = new EnumMap<>(Position.class);
        for(Map.Entry<String, Double> entry : planner.points().entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(kept.contains(entry.getKey())){
                continue;
            }
            if(player != null && CAP.containsKey(player.position)){
                byPosition.computeIfAbsent(player.position, u -> new ArrayList<>())
                        .add(entry.getValue());
            }
        }
        Map<Position, double[]> curve = new EnumMap<>(Position.class);
        for(Map.Entry<Position, List<Double>> entry : byPosition.entrySet()){
            List<Double> values = entry.getValue();
            values.sort(Comparator.reverseOrder());
            int cap = CAP.get(entry.getKey());
            double[] out = new double[cap + 2];
            for(int rank = 1; rank <= cap && rank <= values.size(); rank++){
                out[rank] = values.get(rank - 1);
            }
            curve.put(entry.getKey(), out);
        }
        return curve;
    }

    /**
     * Every man's own rank at his position, kept men included.
     *
     * Kept men are not on the draftable board but they ARE on somebody's
     * roster, and Justin's two are on his. Their quality is their place among
     * everyone, so this ranks the whole projection pool rather than the
     * draftable subset - Tuten is the same back whether or not he can be
     * drafted.
     */
    /**
     * A held man's rank must index the SAME list the curve was built from.
     *
     * This ranked the whole of planner.points() while thisYear() builds the
     * curve from the DRAFTABLE pool - keepers removed. So every man on Justin's
     * roster indexed a list he was then priced against by twenty-four players
     * who are not in it, and every one of them came out low: Ja'Marr Chase
     * priced at 227.5 against his own projection of 256.6, a 29-point error on
     * the best receiver on the board; Fannin -13.9; Tuten -15.7; Purdy -10.7.
     * The moment Justin drafted the best receiver available, the model priced
     * him as WR2.
     *
     * Justin's own two keepers stay in the ranking because they occupy slots on
     * HIS roster and have to be priced. Everyone else's keeper never occupies a
     * slot of his, so removing them is what makes his men's ranks line up with
     * the curve.
     */
    static Map<String, Integer> projectionRanks(DraftPlanner planner, Set<String> kept){
        Map<Position, List<Map.Entry<String, Double>>> byPosition =
                new EnumMap<>(Position.class);
        for(Map.Entry<String, Double> entry : planner.points().entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(kept.contains(entry.getKey())){
                continue;
            }
            if(player != null && CAP.containsKey(player.position)){
                byPosition.computeIfAbsent(player.position, u -> new ArrayList<>())
                        .add(entry);
            }
        }
        Map<String, Integer> rankOf = new HashMap<>();
        for(List<Map.Entry<String, Double>> men : byPosition.values()){
            men.sort(Map.Entry.<String, Double>comparingByValue().reversed());
            for(int i = 0; i < men.size(); i++){
                rankOf.put(men.get(i).getKey(), i + 1);
            }
        }

        // JUSTIN'S OWN KEEPERS ARE SLOTTED IN, NOT INSERTED.
        //
        // They have to be priced - they occupy two slots on the roster being
        // scored - but they are not in the curve, so putting them into the
        // sorted list above shifts every man below them by one and prices HIM
        // wrong instead. Measured: with Purdy in the list, Michael Penix came
        // out 51.1 points off his own projection. A keeper's rank is therefore
        // how many draftable men beat him, plus one, which leaves every other
        // rank exactly where it was. He then reads the curve at the draftable
        // man nearest him, which is the closest honest price available for
        // somebody who is not on the board.
        for(String id : planner.myKeeperIDs()){
            Player player = Player.getPlayerFromSIDV2(id);
            Double his = planner.points().get(id);
            if(player == null || his == null || !CAP.containsKey(player.position)){
                continue;
            }
            int better = 0;
            for(Map.Entry<String, Double> man
                    : byPosition.getOrDefault(player.position, List.of())){
                if(man.getValue() > his){
                    better++;
                }
            }
            rankOf.put(id, better + 1);
        }
        return rankOf;
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
        gone.addAll(kept(AAAConfiguration.getInstance()));
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
            // Same keeper-slot fault as the scan above: rounds 12 and 13 are
            // mine and select nobody, so "my next pick" must skip them or the
            // wait is priced against a pick that never happens.
            if(slot != null && planner.me().equals(slot.manager())
                    && !slot.keeperSlot()){
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

    /**
     * Justin's roster under the rules, as the board model sees it.
     *
     * Extracted so that nothing else has to rebuild it. Draft2026 needs the
     * same roster to say WHY Model A is silent at round 8, and a second copy
     * of this loop is exactly how the two would drift apart - which is the
     * failure this repo has hit six times.
     *
     * Men the rules decline are appended to `declined` and still counted, for
     * the reason the loop body gives.
     */
    static RosterRules.Roster rulesRoster(DraftPlanner planner, DraftSimulator simulator,
            DraftSimulator.SimState state, List<String> mine, List<String> declined){
        RosterRules.Roster roster = RosterRules.live().justins();
        for(String id : mine){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player == null || planner.myKeeperIDs().contains(id)){
                continue;
            }
            Integer at = state.takenAtOf(id);
            int taken_at = at == null ? 1 : simulator.slotAt(at).round();
            // A REFUSED PICK MUST STILL COUNT. This used to be an `if` with no
            // `else`, so a man the rules would have declined left NO TRACE on
            // the rules roster - and the quarterback ceiling of two was then
            // counted against one. That is a route to a third quarterback
            // through the roster type's own refusal becoming amnesia, which is
            // exactly the "structurally impossible" claim TRAPS A1 makes.
            //
            // He is on the real roster whatever the rules think, so the rules
            // roster must know about him. Where they refuse, say so out loud
            // rather than silently dropping him: a disagreement between the
            // rules and the board is a fact about the draft, not noise.
            if(roster.canDraft(player.position, taken_at)){
                roster = roster.draft(player.firstName + " " + player.lastName,
                        player.position, taken_at);
            }
            else {
                // COUNT HIM ANYWAY. The comment above has always said the rules
                // roster must know about him; until now the code only printed
                // him and moved on, which is the amnesia TRAPS A1 names.
                declined.add(player.position + " " + player.firstName + " "
                        + player.lastName + " (round " + taken_at + "): "
                        + roster.whyNotDraft(player.position, taken_at));
                roster = roster.holdAnyway(player.firstName + " "
                        + player.lastName, player.position, taken_at);
            }
        }
        return roster;
    }

    /**
     * What Justin's starting nine is still missing, by position.
     *
     * Empty means the nine is genuinely full. Draft2026 used to assert that at
     * round 8 without checking, and on the RUNBOOK's own recommended shape -
     * tight end deferred to round 8 - it was false.
     */
    static Map<Position, Integer> stillNeeds(DraftPlanner planner,
            DraftSimulator simulator, String draftID) throws Exception {
        List<String> taken = LiveDraft.livePicks(draftID);
        DraftSimulator.SimState state = simulator.stateAfter(taken);
        List<String> mine = new ArrayList<>(planner.myKeeperIDs());
        for(String id : taken){
            Integer at = state.takenAtOf(id);
            if(at != null && simulator.slotAt(at) != null
                    && planner.me().equals(simulator.slotAt(at).manager())
                    && !mine.contains(id)){
                mine.add(id);
            }
        }
        Map<Position, Integer> short1 = new EnumMap<>(
                rulesRoster(planner, simulator, state, mine, new ArrayList<>())
                        .stillNeeds());
        short1.values().removeIf(missing -> missing <= 0);
        return short1;
    }
}
