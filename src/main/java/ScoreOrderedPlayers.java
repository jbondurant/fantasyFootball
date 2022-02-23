import java.util.ArrayList;
import java.util.PriorityQueue;

public class ScoreOrderedPlayers {

    //perhaps rename this class

    PriorityQueue<Score> quarterbacks;
    PriorityQueue<Score> runningBacks;
    PriorityQueue<Score> wideReceivers;
    PriorityQueue<Score> tightEnds;
    PriorityQueue<Score> defenses;

    public ScoreOrderedPlayers(ArrayList<Score> scores){
        ArrayList<Score> qbs = new ArrayList<Score>();
        ArrayList<Score> rbs = new ArrayList<Score>();
        ArrayList<Score> wrs = new ArrayList<Score>();
        ArrayList<Score> tes = new ArrayList<Score>();
        ArrayList<Score> defs = new ArrayList<Score>();

        for(Score score : scores){

            Position pos = Position.OTHER;
            if(score.player != null){
                pos = score.player.position;
            }

            if(pos.equals(Position.QB)){
                qbs.add(score);
            }
            else if(pos.equals(Position.RB)){
                rbs.add(score);
            }
            else if(pos.equals(Position.WR)){
                wrs.add(score);
            }
            else if(pos.equals(Position.TE)){
                tes.add(score);
            }
            else if(pos.equals(Position.DEF)){
                defs.add(score);
            }
        }
        PriorityQueue<Score> qbsOrdered = new PriorityQueue<Score>(5,new ScoreComparator());
        PriorityQueue<Score> rbsOrdered = new PriorityQueue<Score>(5,new ScoreComparator());
        PriorityQueue<Score> wrsOrdered = new PriorityQueue<Score>(5,new ScoreComparator());
        PriorityQueue<Score> tesOrdered = new PriorityQueue<Score>(5,new ScoreComparator());
        PriorityQueue<Score> defsOrdered = new PriorityQueue<Score>(5,new ScoreComparator());

        qbsOrdered.addAll(qbs);
        rbsOrdered.addAll(rbs);
        wrsOrdered.addAll(wrs);
        tesOrdered.addAll(tes);
        defsOrdered.addAll(defs);

        quarterbacks = qbsOrdered;
        runningBacks = rbsOrdered;
        wideReceivers = wrsOrdered;
        tightEnds = tesOrdered;
        defenses = defsOrdered;


    }

    public static void compareTwoOrders(ScoreOrderedPlayers sop1, ScoreOrderedPlayers sop2){

        int iterationEndQB = 40;
        int iterationEndRB = 75;
        int iterationEndWR = 75;
        int iterationEndTE = 40;
        int iterationEndDEF = 32;

        ArrayList<Rank> s1qb = Rank.scoringToRanking(sop1.quarterbacks);
        ArrayList<Rank> s2qb = Rank.scoringToRanking(sop2.quarterbacks);

        ArrayList<Rank> s1rb = Rank.scoringToRanking(sop1.runningBacks);
        ArrayList<Rank> s2rb = Rank.scoringToRanking(sop2.runningBacks);

        ArrayList<Rank> s1wr = Rank.scoringToRanking(sop1.wideReceivers);
        ArrayList<Rank> s2wr = Rank.scoringToRanking(sop2.wideReceivers);

        ArrayList<Rank> s1te = Rank.scoringToRanking(sop1.tightEnds);
        ArrayList<Rank> s2te = Rank.scoringToRanking(sop2.tightEnds);

        ArrayList<Rank> s1def = Rank.scoringToRanking(sop1.defenses);
        ArrayList<Rank> s2def = Rank.scoringToRanking(sop2.defenses);


        printDelimiter();
        compareOrdersHelper(s1qb, s2qb, iterationEndQB);
        printDelimiter();
        compareOrdersHelper(s1rb, s2rb, iterationEndRB);
        printDelimiter();
        compareOrdersHelper(s1wr, s2wr, iterationEndWR);
        printDelimiter();
        compareOrdersHelper(s1te, s2te, iterationEndTE);
        printDelimiter();
        compareOrdersHelper(s1def, s2def, iterationEndDEF);
        printDelimiter();



    }

    public static void printDelimiter(){
        System.out.println("---------------------------------");
        System.out.println("---------------------------------");
    }

    public static void compareOrdersHelper(ArrayList<Rank> r1, ArrayList<Rank> r2, int stop){
        int i=0;
        for(Rank rank1 : r1){
            Player p = rank1.player;
            int rankNum1 = rank1.rankNum;

            for(Rank rank2 : r2){
                if(rank2.player.sportRadarID.equals(p.sportRadarID)){
                    int rankNum2 = rank2.rankNum;
                    int diff = rankNum2 - rankNum1;
                    System.out.println(p.firstName + " " + p.lastName + ",\t" + rankNum1 + ",\t" + rankNum2 + ",\t diff:\t" + diff);
                    break;
                }
            }
            i++;
            if(i > stop){
                break;
            }
        }
    }


}
