import PlayerImportAndSetup.Position;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class HumanStrategy extends Strategy {

    RankOrderedPlayers rankOrderedPlayers;
    ArrayList<Position> positionDraftOrder;

    public HumanStrategy(RankOrderedPlayers rop, ArrayList<Position> permGiven){
        rankOrderedPlayers = rop;
        positionDraftOrder = permGiven;
    }

    public static HumanStrategy getFPHumanStrategySeriousFromPerm(ArrayList<Position> permutationGiven){
        return new HumanStrategy(RankOrderedPlayers.getRankOrderedPlayerFPSerious(), permutationGiven);
    }

    public static ArrayList<Position> nonPermutedPositions(int numQB,
                                                           int numRB,
                                                           int numWR,
                                                           int numTE){
        List<String> qbs = Collections.nCopies(numQB, "QB");
        List<String> rbs = Collections.nCopies(numRB, "RB");
        List<String> wrs = Collections.nCopies(numWR, "WR");
        List<String> tes = Collections.nCopies(numTE, "TE");
        List<String> all = Stream.of(qbs, rbs, wrs, tes)
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
        ArrayList<Position> a = (ArrayList<Position>) all.stream().map(Position::valueOf).collect(Collectors.toList());
        return a;
    }

    public static void main(String[] args){
        nonPermutedPositions(1,3,5,1);
    }

    /**
     * Takes the next position off the plan, or the best player left once the
     * plan runs out.
     *
     * The plan only covers as many picks as it was built with; simulating more
     * rounds than that used to throw IndexOutOfBoundsException out of
     * remove(0), which is what made the keeper chooser unrunnable.
     */
    @Override
    public Player selectPlayer() {
        if(positionDraftOrder.isEmpty()){
            return rankOrderedPlayers.removeBestAvailable();
        }
        Position pos = positionDraftOrder.remove(0);
        Player p = rankOrderedPlayers.removeTopPlayerOfPos(pos);
        if(p == null){
            // That position is exhausted; take the best of what is left.
            return rankOrderedPlayers.removeBestAvailable();
        }
        return p;
    }

    @Override
    public void removeDraftedPlayer(Player p) {
        rankOrderedPlayers.removePlayer(p);
    }
}
