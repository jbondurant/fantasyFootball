import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Take him now, or gamble he lasts until your next pick?
 *
 * The question a replacement level cannot answer. Josh Allen projects 415 and
 * the next quarterback down is 380 - so missing him costs 35 points, but taking
 * him early costs whatever else that pick could have bought. Whether to wait
 * depends entirely on how likely he is to survive, which is a number, not a
 * feeling.
 *
 *     ./gradlew run -Pmain=WaitOrTake
 */
public class WaitOrTake {

    /**
     * Draws per question. Converged by about 3000 - at 600 the answer wobbled
     * a point either side, at 12000 different seeds agree to half a point, and
     * the whole run costs under two seconds. Override with -Ptrials.
     */
    private static final int TRIALS = Integer.getInteger("trials", 4000);
    private static final long SEED = 20260824L;

    /** Fitted from the league's own drafts; the pasted constants are gone. */
    public static Map<Position, Double> leagueBias(){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int lastCompleted = Integer.parseInt(configuration.getSeason()) - 1;
        return ManagerProfiles.fitThroughSeason(configuration, lastCompleted).leagueBiasMap();
    }

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        Map<String, Double> points = SleeperProjections.parseTodaysWebPage();
        AvailabilityModel model = AvailabilityModel.build(points, leagueBias());

        int rounds = configuration.getDraftRounds();
        List<Integer> myPicks = new ArrayList<>();
        for(int round = 1; round <= Math.min(rounds, 10); round++){
            myPicks.add(configuration.pickNumberFor(round));
        }

        System.out.println("Your picks: " + myPicks + "\n");

        // The players worth agonising over: best projected at each position.
        for(Position position : List.of(Position.QB, Position.RB, Position.WR, Position.TE)){
            List<String> top = new ArrayList<>();
            for(String sleeperID : model.known()){
                Player player = Player.getPlayerFromSIDV2(sleeperID);
                if(player != null && player.position.equals(position)){
                    top.add(sleeperID);
                }
            }
            top.sort(Comparator.comparingDouble(model::pointsOf).reversed());

            System.out.println(position + " - chance he is still there at each of your picks");
            System.out.printf("   %-22s %7s", "PLAYER", "PROJ");
            for(int pick : myPicks.subList(0, Math.min(6, myPicks.size()))){
                System.out.printf(" %6s", "p" + pick);
            }
            System.out.println();
            for(String sleeperID : top.subList(0, Math.min(4, top.size()))){
                Player player = Player.getPlayerFromSIDV2(sleeperID);
                System.out.printf("   %-22s %7.0f",
                        player.firstName + " " + player.lastName, model.pointsOf(sleeperID));
                for(int pick : myPicks.subList(0, Math.min(6, myPicks.size()))){
                    System.out.printf(" %5.0f%%",
                            100 * model.probabilityAvailable(sleeperID, pick, TRIALS, SEED));
                }
                System.out.println();
            }
            System.out.println();
        }

        // The actual decision, for the player who prompted it.
        System.out.println("Waiting a round, in points:\n");
        System.out.printf("   %-22s %-6s %10s %10s %10s   %s%n",
                "PLAYER", "POS", "TAKE NOW", "IF WAIT", "COST", "verdict");
        for(Position position : List.of(Position.QB, Position.RB, Position.WR, Position.TE)){
            String best = null;
            for(String sleeperID : model.known()){
                Player player = Player.getPlayerFromSIDV2(sleeperID);
                if(player == null || !player.position.equals(position)){
                    continue;
                }
                if(best == null || model.pointsOf(sleeperID) > model.pointsOf(best)){
                    best = sleeperID;
                }
            }
            if(best == null){
                continue;
            }
            for(int i = 0; i + 1 < Math.min(3, myPicks.size()); i++){
                int now = myPicks.get(i);
                int next = myPicks.get(i + 1);
                double here = model.probabilityAvailable(best, now, TRIALS, SEED);
                if(here < 0.10){
                    continue;   // not a decision you get to make
                }
                double takeNow = model.pointsOf(best);
                double ifWait = model.expectedIfYouWait(best, position, next, TRIALS, SEED);
                Player player = Player.getPlayerFromSIDV2(best);
                System.out.printf("   %-22s %-6s %10.0f %10.0f %10.0f   %s%n",
                        player.firstName + " " + player.lastName + " @p" + now,
                        position, takeNow, ifWait, takeNow - ifWait,
                        takeNow - ifWait > 15 ? "take him" : "waiting is cheap");
            }
        }
    }

}
