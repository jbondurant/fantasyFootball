import java.util.*;

public class HumanStrategy extends Strategy {

    RankOrderedPlayers rankOrderedPlayers;
    ArrayList<Position> positionDraftOrder;
    final ArrayList<Position> initialPositionDraftOrder;

    //TODO my permutations must change based on fun or serious
    public HumanStrategy(RankOrderedPlayers rop, boolean isFun){
        rankOrderedPlayers = rop;
        positionDraftOrder = permutationGeneratorSerious();
        if(isFun) {
            positionDraftOrder = permutationGeneratorFun();
        }
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

    public static ArrayList<Position> nonPermutedSerious1261(){
        String[] version0StringArray = {"QB", "TE", "RB", "RB", "WR", "WR", "WR", "WR", "WR", "WR"};
        ArrayList<String> version = new ArrayList<String>();
        for(String s : version0StringArray){
            version.add(s);
        }
        ArrayList<Position> permutation = new ArrayList<Position>();
        for(String string : version){
            Position pos = Position.valueOf(string);
            permutation.add(pos);
        }
        return permutation;
    }

    public static ArrayList<Position> nonPermutedSerious1351(){
        String[] version0StringArray = {"QB", "TE", "RB", "RB", "RB", "WR", "WR", "WR", "WR", "WR"};
        ArrayList<String> version = new ArrayList<String>();
        for(String s : version0StringArray){
            version.add(s);
        }
        ArrayList<Position> permutation = new ArrayList<Position>();
        for(String string : version){
            Position pos = Position.valueOf(string);
            permutation.add(pos);
        }
        return permutation;
    }

    public static ArrayList<Position> nonPermutedSerious1441(){
        String[] version0StringArray = {"QB", "TE", "RB", "RB", "RB", "RB", "WR", "WR", "WR", "WR"};
        ArrayList<String> version = new ArrayList<String>();
        for(String s : version0StringArray){
            version.add(s);
        }
        ArrayList<Position> permutation = new ArrayList<Position>();
        for(String string : version){
            Position pos = Position.valueOf(string);
            permutation.add(pos);
        }
        return permutation;
    }

    public static ArrayList<Position> nonPermutedFun2241(){
        String[] version0StringArray = {"QB", "QB", "TE", "RB", "RB",  "WR", "WR", "WR", "WR"};
        ArrayList<String> version = new ArrayList<String>();
        for(String s : version0StringArray){
            version.add(s);
        }
        ArrayList<Position> permutation = new ArrayList<Position>();
        for(String string : version){
            Position pos = Position.valueOf(string);
            permutation.add(pos);
        }
        return permutation;
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

    public static ArrayList<Position> permutationGeneratorFun(){
        String[] versionString = {"QB", "QB", "RB", "RB", "WR", "WR", "WR", "WR"};
        ArrayList<String> version = new ArrayList<String>();
        for(String s : versionString){
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
