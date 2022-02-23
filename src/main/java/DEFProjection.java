public class DEFProjection {

    double sack;
    double interception;
    double fr;
    double ff;
    double td;
    double safety;
    double pa;
    double ydsagn;
    double fpts;

    Player player;

    public DEFProjection(double[] proj, Player p){
        sack = proj[0];
        interception = proj[1];
        fr = proj[2];
        ff = proj[3];
        td = proj[4];
        safety = proj[5];
        pa = proj[6];
        ydsagn = proj[7];
        fpts = proj[8];

        player = p;
    }
}
