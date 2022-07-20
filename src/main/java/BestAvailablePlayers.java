public class BestAvailablePlayers {
    public RankTier quarterbackRT1;
    public RankTier runningBackRT1;
    public RankTier wideReceiverRT1;
    public RankTier tightEndRT1;
    public RankTier defenseRT1;

    public RankTier quarterbackRT2;
    public RankTier runningBackRT2;
    public RankTier wideReceiverRT2;
    public RankTier tightEndRT2;
    public RankTier defenseRT2;

    //TODO switch class to get top 3 at each position?

    public BestAvailablePlayers(RankTierOrderedPlayers rtop){
        quarterbackRT1 = rtop.quarterbacks.peek();
        runningBackRT1 = rtop.runningBacks.peek();
        wideReceiverRT1 = rtop.wideReceivers.peek();
        tightEndRT1 = rtop.tightEnds.peek();
        defenseRT1 = rtop.defenses.peek();

        //quarterbackRT2 = rtop.quarterbacks
    }
}
