import PlayerImportAndSetup.Position;

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

    /** Players ranked by their projected points under this league's scoring. */
    public static RankOrderedPlayers getRankOrderedPlayerFPSerious(){
        ScoreOrderedPlayers sop = new ScoreOrderedPlayers(SleeperLeague.getScoreList());
        return RankOrderedPlayers.scoreToRankOrderedPlayers(sop);
    }

    public boolean removePlayer(Player player){
        if(player == null || player.sportRadarID == null){
            return false;
        }
        Position pos = player.position;
        if(pos.equals(Position.QB)){
            for(Rank rank : quarterbacks){
                if(player.sportRadarID.equals(rank.player.sportRadarID)){
                    return quarterbacks.remove(rank);
                }
            }
        }
        else if(pos.equals(Position.RB)){
            for(Rank rank : runningBacks){
                if(player.sportRadarID.equals(rank.player.sportRadarID)){
                    return runningBacks.remove(rank);
                }
            }
        }
        else if(pos.equals(Position.WR)){
            for(Rank rank : wideReceivers){
                if(player.sportRadarID.equals(rank.player.sportRadarID)){
                    return wideReceivers.remove(rank);
                }
            }
        }
        else if(pos.equals(Position.TE)){
            for(Rank rank : tightEnds){
                if(player.sportRadarID.equals(rank.player.sportRadarID)){
                    return tightEnds.remove(rank);
                }
            }
        }
        for(Rank rank : defenses){
            if(player.sportRadarID.equals(rank.player.sportRadarID)){
                return defenses.remove(rank);
            }
        }
        return false;
    }

    public Player removeTopPlayerOfPos(Position pos){
        Rank r;
        if(pos.equals(Position.QB)){
            r = quarterbacks.poll();
        }
        else if(pos.equals(Position.RB)){
            r = runningBacks.poll();
        }
        else if(pos.equals(Position.WR)){
            r = wideReceivers.poll();
        }
        else if(pos.equals(Position.TE)){
            r = tightEnds.poll();
        }
        else {
            r = defenses.poll();
        }
        // A simulated draft can run the board dry at a position.
        return r == null ? null : r.player;
    }
}
