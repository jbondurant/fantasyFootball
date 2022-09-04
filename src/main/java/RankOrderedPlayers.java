import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.PriorityQueue;

public class RankOrderedPlayers {

    PriorityQueue<Rank> quarterbacks;
    PriorityQueue<Rank> runningBacks;
    PriorityQueue<Rank> wideReceivers;
    PriorityQueue<Rank> tightEnds;
    PriorityQueue<Rank> defenses;

    public RankOrderedPlayers(ArrayList<Rank> ranking){
        ArrayList<Rank> qbs = new ArrayList<Rank>();
        ArrayList<Rank> rbs = new ArrayList<Rank>();
        ArrayList<Rank> wrs = new ArrayList<Rank>();
        ArrayList<Rank> tes = new ArrayList<Rank>();
        ArrayList<Rank> defs = new ArrayList<Rank>();
        for(Rank rank : ranking){
            Position pos = Position.OTHER;
            if(rank.player != null){
                pos = rank.player.position;
            }
            if(pos.equals(Position.QB)){
                qbs.add(rank);
            }
            else if(pos.equals(Position.RB)){
                rbs.add(rank);
            }
            else if(pos.equals(Position.WR)){
                wrs.add(rank);
            }
            else if(pos.equals(Position.TE)){
                tes.add(rank);
            }
            else if(pos.equals(Position.DEF)){
                defs.add(rank);
            }
        }
        PriorityQueue<Rank> qbRankQueue = new PriorityQueue<>(5, new RankComparator());
        PriorityQueue<Rank> rbRankQueue = new PriorityQueue<>(5, new RankComparator());
        PriorityQueue<Rank> wrRankQueue = new PriorityQueue<>(5, new RankComparator());
        PriorityQueue<Rank> teRankQueue = new PriorityQueue<>(5, new RankComparator());
        PriorityQueue<Rank> defRankQueue = new PriorityQueue<>(5, new RankComparator());
        qbRankQueue.addAll(qbs);
        rbRankQueue.addAll(rbs);
        wrRankQueue.addAll(wrs);
        teRankQueue.addAll(tes);
        defRankQueue.addAll(defs);
        quarterbacks = qbRankQueue;
        runningBacks = rbRankQueue;
        wideReceivers = wrRankQueue;
        tightEnds = teRankQueue;
        defenses = defRankQueue;
    }


    public static RankOrderedPlayers scoreToRankOrderedPlayers(ScoreOrderedPlayers sop){
        ArrayList<Rank> qbRankList = Rank.scoringToRanking(sop.quarterbacks);
        ArrayList<Rank> rbRankList = Rank.scoringToRanking(sop.runningBacks);
        ArrayList<Rank> wrRankList = Rank.scoringToRanking(sop.wideReceivers);
        ArrayList<Rank> teRankList = Rank.scoringToRanking(sop.tightEnds);
        ArrayList<Rank> defRankList = Rank.scoringToRanking(sop.defenses);

        ArrayList<Rank> allRanks = new ArrayList<Rank>();
        allRanks.addAll(qbRankList);
        allRanks.addAll(rbRankList);
        allRanks.addAll(wrRankList);
        allRanks.addAll(teRankList);
        allRanks.addAll(defRankList);

        RankOrderedPlayers rankOrderedPlayers = new RankOrderedPlayers(allRanks);
        return rankOrderedPlayers;

    }

    public static RankOrderedPlayers getRankOrderedPlayerHardcodedExperts(){
        RankOrderedPlayers rop = null;
        ArrayList<Rank> rankingForHardcodedChosenExperts = new ArrayList<>();

        String entireHTML = FantasyProsUtility.getTodaysWebPage();
        String ecrDataStart = entireHTML.split("var ecrData = ")[1].split("\"players\":")[1];
        String ecrData = ecrDataStart.split("var sosData")[0].split(",\"experts_available\":")[0];

        JsonParser jp = new JsonParser();
        JsonElement jsonElement = jp.parse(ecrData);
        JsonArray jsonPlayers = jsonElement.getAsJsonArray();

        ArrayList<Rank> todaysRankings = new ArrayList<Rank>();
        for (JsonElement jsonPlayer : jsonPlayers) {
            JsonObject apiObject = jsonPlayer.getAsJsonObject();

            String sportRadarID = "";
            if(!apiObject.get("sportsdata_id").isJsonNull()) {
                sportRadarID = apiObject.get("sportsdata_id").getAsString();
            }
            int fantasyProsID = -1;
            if(!apiObject.get("player_id").isJsonNull()) {
                fantasyProsID = apiObject.get("player_id").getAsInt();
            }

            if(!apiObject.get("pos_rank").isJsonNull()) {
                String posRank = apiObject.get("pos_rank").getAsString();
                if(posRank.toLowerCase().startsWith("dst")){
                    posRank = posRank.substring(3);
                    int rank = Integer.parseInt(posRank);
                    Player player = Player.getPlayer(sportRadarID);// sport radar and sport data seem used interchangeably...
                    Rank r = new Rank(rank, player);
                    rankingForHardcodedChosenExperts.add(r);
                }
                else if(posRank.toLowerCase().startsWith("qb") || posRank.toLowerCase().startsWith("rb") || posRank.toLowerCase().startsWith("wr") || posRank.toLowerCase().startsWith("te")){
                    posRank = posRank.substring(2);
                    int rank = Integer.parseInt(posRank);
                    Player player = Player.getPlayer(sportRadarID);
                    Rank r = new Rank(rank, player);
                    rankingForHardcodedChosenExperts.add(r);
                }
            }
        }
        rop = new RankOrderedPlayers(rankingForHardcodedChosenExperts);
        return rop;
    }

    public static RankOrderedPlayers getRankOrderedPlayerFPFun(){
        SleeperLeague funL = SleeperLeague.getFunLeague();
        LeagueScoringSettings funSettings = funL.league.leagueScoringSettings;
        FantasyProsScore funScores = new FantasyProsScore(funSettings);
        ArrayList<Score> funScoresList = funScores.fantasyProsScoreLeagueAdjusted;
        ScoreOrderedPlayers sop = new ScoreOrderedPlayers(funScoresList);

        RankOrderedPlayers rop = RankOrderedPlayers.scoreToRankOrderedPlayers(sop);
        return rop;
    }

    public static RankOrderedPlayers getRankOrderedPlayerFPSerious(){
        SleeperLeague seriousL = SleeperLeague.getSeriousLeague();
        LeagueScoringSettings seriousSettings = seriousL.league.leagueScoringSettings;
        FantasyProsScore seriousScores = new FantasyProsScore(seriousSettings);
        ArrayList<Score> seriousScoresList = seriousScores.fantasyProsScoreLeagueAdjusted;
        ScoreOrderedPlayers sop = new ScoreOrderedPlayers(seriousScoresList);

        RankOrderedPlayers rop = RankOrderedPlayers.scoreToRankOrderedPlayers(sop);
        return rop;
    }

    public boolean removePlayer(Player player){
        Position pos = player.position;
        if(pos.equals(Position.QB)){
            for(Rank rank : quarterbacks){
                if(rank.player.sportRadarID.equals(player.sportRadarID)){
                    return quarterbacks.remove(rank);
                }
            }
        }
        else if(pos.equals(Position.RB)){
            for(Rank rank : runningBacks){
                if(rank.player.sportRadarID.equals(player.sportRadarID)){
                    return runningBacks.remove(rank);
                }
            }
        }
        else if(pos.equals(Position.WR)){
            for(Rank rank : wideReceivers){
                if(rank.player.sportRadarID.equals(player.sportRadarID)){
                    return wideReceivers.remove(rank);
                }
            }
        }
        else if(pos.equals(Position.TE)){
            for(Rank rank : tightEnds){
                if(rank.player.sportRadarID.equals(player.sportRadarID)){
                    return tightEnds.remove(rank);
                }
            }
        }
        for(Rank rank : defenses){
            if(rank.player.sportRadarID.equals(player.sportRadarID)){
                return defenses.remove(rank);
            }
        }
        return false;
    }

    public Player removeTopPlayerOfPos(Position pos){
        if(pos.equals(Position.QB)){
            Rank r = quarterbacks.poll();
            return r.player;
        }
        else if(pos.equals(Position.RB)){
            Rank r = runningBacks.poll();
            return r.player;
        }
        else if(pos.equals(Position.WR)){
            Rank r = wideReceivers.poll();
            return r.player;
        }
        else if(pos.equals(Position.TE)){
            Rank r = tightEnds.poll();
            return r.player;
        }
        Rank r = defenses.poll();
        return r.player;
    }
}
