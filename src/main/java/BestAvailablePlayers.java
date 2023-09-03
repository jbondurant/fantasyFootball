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

    public static BestAvailablePlayers getCopy(BestAvailablePlayers baps) {
        return new BestAvailablePlayers(baps.quarterbackRT1, baps.runningBackRT1, baps.wideReceiverRT1, baps.tightEndRT1, baps.defenseRT1,
                baps.quarterbackRT2, baps.runningBackRT2, baps.wideReceiverRT2, baps.tightEndRT2, baps.defenseRT2,
                baps.quarterbackRT3, baps.runningBackRT3, baps.wideReceiverRT3, baps.tightEndRT3, baps.defenseRT3);
    }

    public BestAvailablePlayers(Rank qb1, Rank rb1, Rank wr1, Rank te1, Rank def1,
                                Rank qb2, Rank rb2, Rank wr2, Rank te2, Rank def2,
                                Rank qb3, Rank rb3, Rank wr3, Rank te3, Rank def3){
        this.quarterbackRT1 = qb1;
        this.runningBackRT1 = rb1;
        this.wideReceiverRT1 = wr1;
        this.tightEndRT1 = te1;
        this.defenseRT1 = def1;

        this.quarterbackRT2 = qb2;
        this.runningBackRT2 = rb2;
        this.wideReceiverRT2 = wr2;
        this.tightEndRT2 = te2;
        this.defenseRT2 = def2;

        this.quarterbackRT3 = qb3;
        this.runningBackRT3 = rb3;
        this.wideReceiverRT3 = wr3;
        this.tightEndRT3 = te3;
        this.defenseRT3 = def3;
    }

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
