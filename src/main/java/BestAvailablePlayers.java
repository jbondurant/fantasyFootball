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

    public Rank quarterbackRT3;
    public Rank runningBackRT3;
    public Rank wideReceiverRT3;
    public Rank tightEndRT3;
    public Rank defenseRT3;

    //TODO switch class to get top 3 at each position?

    public BestAvailablePlayers(RankOrderedPlayers rtop){
        quarterbackRT1 = rtop.quarterbacks.remove();
        runningBackRT1 = rtop.runningBacks.remove();
        wideReceiverRT1 = rtop.wideReceivers.remove();
        tightEndRT1 = rtop.tightEnds.remove();
        defenseRT1 = rtop.defenses.remove();

        quarterbackRT2 = rtop.quarterbacks.remove();
        runningBackRT2 = rtop.runningBacks.remove();
        wideReceiverRT2 = rtop.wideReceivers.remove();
        tightEndRT2 = rtop.tightEnds.remove();
        defenseRT2 = rtop.defenses.remove();

        quarterbackRT3 = rtop.quarterbacks.remove();
        runningBackRT3 = rtop.runningBacks.remove();
        wideReceiverRT3 = rtop.wideReceivers.remove();
        tightEndRT3 = rtop.tightEnds.remove();
        defenseRT3 = rtop.defenses.remove();

        rtop.quarterbacks.add(quarterbackRT1);
        rtop.quarterbacks.add(quarterbackRT2);
        rtop.quarterbacks.add(quarterbackRT3);

        rtop.runningBacks.add(runningBackRT1);
        rtop.runningBacks.add(runningBackRT2);
        rtop.runningBacks.add(runningBackRT3);

        rtop.wideReceivers.add(wideReceiverRT1);
        rtop.wideReceivers.add(wideReceiverRT2);
        rtop.wideReceivers.add(wideReceiverRT3);

        rtop.tightEnds.add(tightEndRT1);
        rtop.tightEnds.add(tightEndRT2);
        rtop.tightEnds.add(tightEndRT3);

        rtop.defenses.add(defenseRT1);
        rtop.defenses.add(defenseRT2);
        rtop.defenses.add(defenseRT3);
;
    }
}
