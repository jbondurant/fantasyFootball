import PlayerImportAndSetup.Position;
import java.io.*;
import java.util.*;

/**
 * DOES TONIGHT'S PICK SURVIVE A CHANGE OF PROJECTION FEED?
 *
 * The whole board rests on ONE set of numbers - Rotowire via Sleeper. Every
 * rank, every curve, every END TEAM number is downstream of it. SourceSensitivity
 * asks this of Model A's committed SEQUENCE; nothing has ever asked it of the
 * board model, which is the thing Justin actually reads at the table.
 *
 * The three automatic shops disagree by 40-65 points on elite players, so this
 * is not a hypothetical. If the verdict at pick 7 flips between them, he should
 * know that before he sits down; if it holds, that is worth just as much.
 *
 *   ./gradlew run -Pmain=BoardSourceCheck -Pkeepers=Tuten,Purdy -q
 */
public class BoardSourceCheck {
    public static void main(String[] args) throws Exception {
        System.setProperty("scheduleRounds", "16");
        String[] sources = System.getProperty("sources",
                "sleeper;espn;cbs;blend:sleeper,espn,cbs").split(";");
        PrintStream real = System.out;
        PrintStream quiet = new PrintStream(OutputStream.nullOutputStream());

        real.printf("%nthe board model's verdict at pick 7, under each projection feed.%n"
                + "everything on the table is downstream of these numbers.%n%n");
        real.printf("%-26s %-6s %-24s%n", "SOURCE", "TAKES",
                "BEST LIKELY THERE AT 7");

        Map<String, Position> verdicts = new LinkedHashMap<>();
        for(String source : sources){
            System.setProperty("projections", source);
            AAAConfiguration configuration = AAAConfiguration.getInstance();
            int last = Integer.parseInt(configuration.getSeason()) - 1;
            Position advised;
            String man;
            try {
                System.setOut(quiet);
                Map<String, Double> earliness =
                        SelectionModel.qbEarliness(configuration, last);
                ChoiceModel choice =
                        BoostedSelectionModel.fitShipped(configuration, last, earliness);
                DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration,
                        DraftPlanner.keepersFromProperty(configuration), choice, earliness);
                DraftSimulator simulator = planner.simulator();
                LiveBoard.warmSurvival(planner, simulator);
                Set<String> kept = LiveBoard.kept(configuration);
                Map<Position, double[]> curve = LiveBoard.thisYear(planner, kept);
                Map<String, List<DetectionLag.Man>> wider = NflverseBoards.usable(null);
                List<String> order = new ArrayList<>(new TreeMap<>(wider).keySet());
                List<PairwiseOdds.Man> men = PairwiseOdds.nflverseMen(wider, order);
                Map<Position, List<List<Double>>> pools =
                        new EnumMap<>(BoardValue.pools(men, curve));
                List<List<Double>> defence = LiveBoard.defenceScatter();
                if(!defence.isEmpty()){
                    pools.put(Position.DEF, defence);
                }
                LiveDraft.freezeWith(new ArrayList<>());
                advised = LiveBoard.answer(configuration, planner, simulator,
                        configuration.getDraftID(), curve, pools, order, men, kept);
                // WHO IS LIKELY TO BE THERE AT SEVEN, not who is best today.
                //
                // bestAvailable on an empty board returns the best man in
                // football at that position, which here was Jahmyr Gibbs -
                // exactly the answer Justin has already objected to once:
                // "why are we looking at gibbs and nacua? those two will almost
                // never be available at my pick 7". LiveBoard's own table does
                // not make that mistake; my column label did.
                man = null;
                if(advised != null){
                    double best = -1;
                    for(Map.Entry<String, Double> entry : planner.points().entrySet()){
                        Player who = Player.getPlayerFromSIDV2(entry.getKey());
                        if(who == null || who.position != advised
                                || kept.contains(entry.getKey())){
                            continue;
                        }
                        double survives = LiveBoard.SURVIVAL == null ? 1.0
                                : 1.0 - LiveBoard.SURVIVAL.probabilityGone(
                                        entry.getKey(), 7);
                        if(survives >= 0.5 && entry.getValue() > best){
                            best = entry.getValue();
                            man = entry.getKey();
                        }
                    }
                }
                System.setOut(real);
            }
            catch(Exception broke){
                System.setOut(real);
                real.printf("%-26s %-6s %s%n", source, "-",
                        "FEED UNAVAILABLE: " + broke);
                continue;
            }
            finally {
                System.setOut(real);
                LiveDraft.thaw();
            }
            Player player = man == null ? null : Player.getPlayerFromSIDV2(man);
            real.printf("%-26s %-6s %-24s%n", source,
                    advised == null ? "-" : advised,
                    player == null ? "-" : player.firstName + " " + player.lastName);
            verdicts.put(source, advised);
        }

        Set<Position> distinct = new HashSet<>(verdicts.values());
        real.printf("%n%d feeds answered, %d distinct verdicts.%n",
                verdicts.size(), distinct.size());
        if(distinct.size() == 1 && verdicts.size() > 1){
            real.printf("%nTHE PICK DOES NOT DEPEND ON THE FEED. Three shops that%n"
                    + "disagree by 40-65 points on elite players all reach the same%n"
                    + "position at pick 7. That is worth more than it looks: it means%n"
                    + "the verdict is being driven by the SHAPE of the positional%n"
                    + "curves, which the shops agree about, and not by anyone's%n"
                    + "point estimate of a particular man.%n");
        }
        else if(verdicts.size() > 1){
            real.printf("%nTHE PICK DEPENDS ON WHOSE NUMBERS YOU USE: %s.%n"
                    + "That is a fragility worth knowing before sitting down - the%n"
                    + "model is confident, but its confidence is inherited from one%n"
                    + "feed rather than earned across them.%n", verdicts);
        }
    }
}
