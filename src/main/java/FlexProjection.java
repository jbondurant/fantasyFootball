public class FlexProjection {

    double rushATT;
    double rushYDS;
    double rushTDS;
    double rec;
    double recYDS;
    double recTDS;
    double fl;
    double fpts;

    Player player;

    public FlexProjection(double[] proj, Player p){
        rushATT = proj[0];
        rushYDS = proj[1];
        rushTDS = proj[2];
        rec = proj[3];
        recYDS = proj[4];
        recTDS = proj[5];
        fl = proj[6];
        fpts = proj[7];

        player = p;
    }


}
