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

    public static FlexProjection getFromWR(double[] proj, Player p){
        double[] proj8 = new double[8];
        proj8[0] = proj[3];
        proj8[1] = proj[4];
        proj8[2] = proj[5];
        proj8[3] = proj[0];
        proj8[4] = proj[1];
        proj8[5] = proj[2];
        proj8[6] = proj[6];
        proj8[7] = proj[7];
        return new FlexProjection(proj8, p);
    }

    public static FlexProjection getFromTE(double[] proj, Player p){
        double[] proj8 = new double[8];
        proj8[0] = 0.0;
        proj8[1] = 0.0;
        proj8[2] = 0.0;
        proj8[3] = proj[0];
        proj8[4] = proj[1];
        proj8[5] = proj[2];
        proj8[6] = proj[3];
        proj8[7] = proj[4];

        return new FlexProjection(proj8, p);
    }


}
