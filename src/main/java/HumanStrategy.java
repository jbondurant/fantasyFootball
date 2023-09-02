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
        SleeperLeague seriousL = SleeperLeague.getSeriousLeague();
        LeagueScoringSettings seriousSettings = seriousL.league.leagueScoringSettings;
        FantasyProsScore seriousScores = new FantasyProsScore(seriousSettings);
        ArrayList<Score> seriousScoresList = seriousScores.fantasyProsScoreLeagueAdjusted;
        ScoreOrderedPlayers sop = new ScoreOrderedPlayers(seriousScoresList);
        RankOrderedPlayers rop = RankOrderedPlayers.scoreToRankOrderedPlayers(sop);
        HumanStrategy fppProjectionHumanStrategySerious = new HumanStrategy(rop, permutationGiven);
        return fppProjectionHumanStrategySerious;
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

    @Override
    public Player selectPlayer() {
        Position pos = positionDraftOrder.remove(0);
        Player p = rankOrderedPlayers.removeTopPlayerOfPos(pos);
        return p;
    }

    @Override
    public void removeDraftedPlayer(Player p) {
        rankOrderedPlayers.removePlayer(p);
    }
}
