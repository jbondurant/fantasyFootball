public class BestAvailablePlayers {
    RankTier quarterbackRT;
    RankTier runningBackRT;
    RankTier wideReceiverRT;
    RankTier tightEndRT;
    RankTier defenseRT;

    public BestAvailablePlayers(RankTier qb, RankTier rb, RankTier wr, RankTier te, RankTier def){
        quarterbackRT = qb;
        runningBackRT = rb;
        wideReceiverRT = wr;
        tightEndRT = te;
        defenseRT = def;
    }

    public BestAvailablePlayers(RankTierOrderedPlayers rtop){
        quarterbackRT = rtop.quarterbacks.peek();
        runningBackRT = rtop.runningBacks.peek();
        wideReceiverRT = rtop.wideReceivers.peek();
        tightEndRT = rtop.tightEnds.peek();
        defenseRT = rtop.defenses.peek();
    }
}
