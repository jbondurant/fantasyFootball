import java.util.PriorityQueue;

public class RankTierOrderedPlayers {


    public PriorityQueue<RankTier> quarterbacks;
    public PriorityQueue<RankTier> runningBacks;
    public PriorityQueue<RankTier> wideReceivers;
    public PriorityQueue<RankTier> tightEnds;
    public PriorityQueue<RankTier> defenses;

    public RankTierOrderedPlayers(RankOrderedPlayers rop){
        PriorityQueue<Rank> qbRankOnly = rop.quarterbacks;
        PriorityQueue<Rank> rbRankOnly = rop.runningBacks;
        PriorityQueue<Rank> wrRankOnly = rop.wideReceivers;
        PriorityQueue<Rank> teRankOnly = rop.tightEnds;
        PriorityQueue<Rank> defRankOnly = rop.defenses;

        int[] qbRankTier = RankTier.qbTiers;
        int[] rbRankTier = RankTier.rbTiers;
        int[] wrRankTier = RankTier.wrTiers;
        int[] teRankTier = RankTier.teTiers;
        int[] defRankTier = RankTier.defTiers;

        quarterbacks = RankTier.makeTiersPosRankQueue(qbRankOnly, qbRankTier);
        runningBacks = RankTier.makeTiersPosRankQueue(rbRankOnly, rbRankTier);
        wideReceivers = RankTier.makeTiersPosRankQueue(wrRankOnly, wrRankTier);
        tightEnds = RankTier.makeTiersPosRankQueue(teRankOnly, teRankTier);
        defenses = RankTier.makeTiersPosRankQueue(defRankOnly, defRankTier);

    }

    public boolean removePlayer(Player player){
        Position pos = player.position;
        if(pos.equals(Position.QB)){
            for(RankTier rankTier : quarterbacks){
                if(rankTier.player.sportRadarID.equals(player.sportRadarID)){
                    return quarterbacks.remove(rankTier);
                }
            }
        }
        else if(pos.equals(Position.RB)){
            for(RankTier rankTier : runningBacks){
                if(rankTier.player.sportRadarID.equals(player.sportRadarID)){
                    return runningBacks.remove(rankTier);
                }
            }
        }
        else if(pos.equals(Position.WR)){
            for(RankTier rankTier : wideReceivers){
                if(rankTier.player.sportRadarID.equals(player.sportRadarID)){
                    return wideReceivers.remove(rankTier);
                }
            }
        }
        else if(pos.equals(Position.TE)){
            for(RankTier rankTier : tightEnds){
                if(rankTier.player.sportRadarID.equals(player.sportRadarID)){
                    return tightEnds.remove(rankTier);
                }
            }
        }
        for(RankTier rankTier : defenses){
            if(rankTier.player.sportRadarID.equals(player.sportRadarID)){
                return defenses.remove(rankTier);
            }
        }
        return false;

    }

    public Player removeTopPlayerOfPos(Position pos){
        if(pos.equals(Position.QB)){
            RankTier rt = quarterbacks.poll();
            return rt.player;
        }
        else if(pos.equals(Position.RB)){
            RankTier rt = runningBacks.poll();
            return rt.player;
        }
        else if(pos.equals(Position.WR)){
            RankTier rt = wideReceivers.poll();
            return rt.player;
        }
        else if(pos.equals(Position.TE)){
            RankTier rt = tightEnds.poll();
            return rt.player;
        }
        RankTier rt = defenses.poll();
        return rt.player;
    }



}
