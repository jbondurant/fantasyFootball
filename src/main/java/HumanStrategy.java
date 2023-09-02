import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class HumanStrategy extends Strategy {

    RankOrderedPlayers rankOrderedPlayers;
    ArrayList<Position> positionDraftOrder;
    final ArrayList<Position> initialPositionDraftOrder;

    //TODO my permutations must change based on fun or serious
    public HumanStrategy(RankOrderedPlayers rop, boolean isFun){
        rankOrderedPlayers = rop;
        positionDraftOrder = permutationGeneratorSerious();
        initialPositionDraftOrder = new ArrayList<>(Collections.unmodifiableList(positionDraftOrder));
    }

    public HumanStrategy(RankOrderedPlayers rop, boolean isFun, ArrayList<Position> permGiven){
        rankOrderedPlayers = rop;
        positionDraftOrder = permGiven;
        if(isFun) {
            positionDraftOrder = permGiven;
        }
        initialPositionDraftOrder = new ArrayList<>(Collections.unmodifiableList(positionDraftOrder));
    }


    public static HumanStrategy getFPHumanStrategyFunFromPerm(ArrayList<Position> permutationGiven){
        SleeperLeague funL = SleeperLeague.getFunLeague();
        LeagueScoringSettings funSettings = funL.league.leagueScoringSettings;
        FantasyProsScore funScores = new FantasyProsScore(funSettings);
        ArrayList<Score> funScoresList = funScores.fantasyProsScoreLeagueAdjusted;
        ScoreOrderedPlayers sop = new ScoreOrderedPlayers(funScoresList);
        RankOrderedPlayers rop = RankOrderedPlayers.scoreToRankOrderedPlayers(sop);
        HumanStrategy fppProjectionHumanStrategyFun = new HumanStrategy(rop, true, permutationGiven);
        return fppProjectionHumanStrategyFun;
    }

    public static HumanStrategy getFPHumanStrategySeriousFromPerm(ArrayList<Position> permutationGiven){
        SleeperLeague seriousL = SleeperLeague.getSeriousLeague();
        LeagueScoringSettings seriousSettings = seriousL.league.leagueScoringSettings;
        FantasyProsScore seriousScores = new FantasyProsScore(seriousSettings);
        ArrayList<Score> seriousScoresList = seriousScores.fantasyProsScoreLeagueAdjusted;
        ScoreOrderedPlayers sop = new ScoreOrderedPlayers(seriousScoresList);
        RankOrderedPlayers rop = RankOrderedPlayers.scoreToRankOrderedPlayers(sop);
        HumanStrategy fppProjectionHumanStrategySerious = new HumanStrategy(rop, false, permutationGiven);
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

    public static ArrayList<Position> permutationGeneratorSerious(){
        String[] version0StringArray = {"QB", "TE", "RB", "RB", "RB", "WR", "WR", "WR", "WR", "WR"};
        ArrayList<String> version = new ArrayList<String>();
        for(String s : version0StringArray){
            version.add(s);
        }
        Collections.shuffle(version);
        ArrayList<Position> permutation = new ArrayList<Position>();
        for(String string : version){
            Position pos = Position.valueOf(string);
            permutation.add(pos);
        }
        return permutation;
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
