import java.util.*;

public class HumanStrategy extends Strategy {

    RankTierOrderedPlayers rankTierOrderedPlayers;
    ArrayList<Position> positionDraftOrder;
    final ArrayList<Position> initialPositionDraftOrder;

    //TODO my permutations must change based on fun or serious
    public HumanStrategy(RankOrderedPlayers rop, boolean isFun){
        rankTierOrderedPlayers = new RankTierOrderedPlayers(rop);
        positionDraftOrder = permutationGeneratorSerious();
        if(isFun) {
            positionDraftOrder = permutationGeneratorFun();
        }
        initialPositionDraftOrder = new ArrayList<>(Collections.unmodifiableList(positionDraftOrder));
    }

    public HumanStrategy(RankOrderedPlayers rop, boolean isFun, ArrayList<Position> permGiven){
        rankTierOrderedPlayers = new RankTierOrderedPlayers(rop);
        positionDraftOrder = permGiven;
        if(isFun) {
            positionDraftOrder = permGiven;
        }
        initialPositionDraftOrder = new ArrayList<>(Collections.unmodifiableList(positionDraftOrder));
    }


    public static HumanStrategy getFPProjectionHumanStrategyFun(){

        SleeperLeague funL = SleeperLeague.getFunLeague();
        LeagueScoringSettings funSettings = funL.league.leagueScoringSettings;
        FantasyProsScore funScores = new FantasyProsScore(funSettings);
        ArrayList<Score> funScoresList = funScores.fantasyProsScoreLeagueAdjusted;
        ScoreOrderedPlayers sop = new ScoreOrderedPlayers(funScoresList);

        RankOrderedPlayers rop = RankOrderedPlayers.scoreToRankOrderedPlayers(sop);
        HumanStrategy fppProjectionHumanStrategyFun = new HumanStrategy(rop, true);
        return fppProjectionHumanStrategyFun;
    }

    public static HumanStrategy getFPProjectionHumanStrategySerious(){

        SleeperLeague seriousL = SleeperLeague.getSeriousLeague();
        LeagueScoringSettings seriousSettings = seriousL.league.leagueScoringSettings;
        FantasyProsScore seriousScores = new FantasyProsScore(seriousSettings);
        ArrayList<Score> seriousScoresList = seriousScores.fantasyProsScoreLeagueAdjusted;
        ScoreOrderedPlayers sop = new ScoreOrderedPlayers(seriousScoresList);

        RankOrderedPlayers rop = RankOrderedPlayers.scoreToRankOrderedPlayers(sop);
        HumanStrategy fppProjectionHumanStrategySerious = new HumanStrategy(rop, false);
        return fppProjectionHumanStrategySerious;
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

    public static ArrayList<Position> nonPermutedSerious(){
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

    public static ArrayList<Position> nonPermutedSeriousNoR3(){
        String[] version0StringArray = {"QB", "RB", "RB", "WR", "WR", "WR", "WR"};
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

    public static ArrayList<Position> nonPermutedFun(){
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

    public static ArrayList<Position> nonPermutedFunNoR3() throws Exception {
        Exception e = new Exception();
        throw e;
        //TODO find which pos to take out
        /*
        String[] version0StringArray = {"QB", "QB", "TE", "RB", "RB", "WR", "WR", "WR", "WR", "WR"};
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

         */
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
        /*String[] version1StringArray = {"QB", "QB", "TE", "RB", "RB", "RB", "WR", "WR", "WR", "WR"};
        String[] version2StringArray = {"QB", "QB", "TE", "RB", "RB", "WR", "WR", "WR", "WR", "WR"};
        ArrayList<String> version1String = new ArrayList<String>();
        ArrayList<String> version2String = new ArrayList<String>();
        for(String s : version1StringArray){
            version1String.add(s);
        }
        for(String s : version2StringArray){
            version2String.add(s);
        }
        ArrayList<String> version = version1String;


        double rand = Math.random();
        if(rand > 0.5){
            version = version2String;
        }
        */

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

    public static void main(String[] args){
        HumanStrategy h = getFPProjectionHumanStrategyFun();
        HumanStrategy h2 = getFPProjectionHumanStrategySerious();
        int a=1;
    }

    public static int[] getTopTiers(HumanStrategy humanStrategy){
        //int[] topTiers = new int[5];
        RankTierOrderedPlayers rankTierOrderedPlayers = humanStrategy.rankTierOrderedPlayers;
        int[] topTiers = new int[4];
        topTiers[0] = rankTierOrderedPlayers.quarterbacks.peek().tierNum;
        topTiers[1] = rankTierOrderedPlayers.runningBacks.peek().tierNum;
        topTiers[2] = rankTierOrderedPlayers.wideReceivers.peek().tierNum;
        topTiers[3] = rankTierOrderedPlayers.tightEnds.peek().tierNum;
        //topTiers[4] = rankTierOrderedPlayers.defenses.peek().tierNum;
        return topTiers;
    }

    @Override
    public Player selectPlayer() {
        Position pos = positionDraftOrder.remove(0);
        Player p = rankTierOrderedPlayers.removeTopPlayerOfPos(pos);
        return p;
    }

    @Override
    public void removeDraftedPlayer(Player p) {
        rankTierOrderedPlayers.removePlayer(p);
    }
}
