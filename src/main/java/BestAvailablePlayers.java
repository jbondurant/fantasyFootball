public class BestAvailablePlayers {
    public Rank quarterbackRT1;
    public Rank runningBackRT1;
    public Rank wideReceiverRT1;
    public Rank tightEndRT1;
    public Rank defenseRT1;

    public Rank quarterbackRT2;
    public Rank runningBackRT2;
    public Rank wideReceiverRT2;
    public Rank tightEndRT2;
    public Rank defenseRT2;

    //TODO switch class to get top 3 at each position?

    public BestAvailablePlayers(RankOrderedPlayers rtop){
        quarterbackRT1 = rtop.quarterbacks.peek();
        runningBackRT1 = rtop.runningBacks.peek();
        wideReceiverRT1 = rtop.wideReceivers.peek();
        tightEndRT1 = rtop.tightEnds.peek();
        defenseRT1 = rtop.defenses.peek();

        //quarterbackRT2 = rtop.quarterbacks
    }
}
