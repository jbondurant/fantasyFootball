public class QBProjection {

    double attPass;
    double cmp;
    double yds;
    double tds;
    double ints;
    double attRush;
    double rushYds;
    double rushTds;
    double fl;
    double fpts;

    Player player;


    public QBProjection(double[] proj, Player p){
        attPass = proj[0];
        cmp = proj[1];
        yds = proj[2];
        tds = proj[3];
        ints = proj[4];
        attRush = proj[5];
        rushYds = proj[6];
        rushTds = proj[7];
        fl = proj[8];
        fpts = proj[9];

        player = p;
    }



}
