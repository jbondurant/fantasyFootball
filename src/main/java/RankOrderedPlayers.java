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
