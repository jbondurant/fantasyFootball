import org.checkerframework.checker.units.qual.A;
import org.checkerframework.checker.units.qual.K;

import java.util.ArrayList;

public class Keeper {

    public String humanWhoCanKeep;
    public Player player;
    public int roundCanBeKept;

    public Keeper(String hwck, Player p, int rcbk){
        humanWhoCanKeep = hwck;
        player = p;
        if(rcbk > 10){
            rcbk = 10;
        }
        roundCanBeKept = rcbk;
    }
    public static ArrayList<Keeper> hardcodedAllPotentialKeepers(){
        Player p1 = Player.getPlayerFromSID(7588); //Javonte Williams
        int r1 = 6;
        Player p2 = Player.getPlayerFromSID(7611); //Rhamondre Stevenson
        int r2 = 10;
        Player p3 = Player.getPlayerFromSID(6943); //Gabriel Davis
        int r3 = 10;
        Player p4 = Player.getPlayerFromSID(7547); //Amon-Ra St. Brown
        int r4 = 4;
        Player p5 = Player.getPlayerFromSID(6794); //Justin Jefferson
        int r5 = 3;
        Player p6 = Player.getPlayerFromSID(7553); //Kyle Pitts
        int r6 = 4;
        Player p7 = Player.getPlayerFromSID(6770); //Joe Burrow
        int r7 = 10;
        Player p8 = Player.getPlayerFromSID(7564); //Ja'Marr Chase
        int r8 = 6;
        Player p9 = Player.getPlayerFromSID(4039); //Cooper Kupp
        int r9 = 4;
        Player p10 = Player.getPlayerFromSID(5001); //Dalton Schultz
        int r10 = 10;
        Player p11 = Player.getPlayerFromSID(5872); //Deebo Samuel
        int r11 = 7;
        Player p12 = Player.getPlayerFromSID(4137); //James Conner
        int r12 = 8;
        String h1 = "452603383455412224";
        String h2 = "459267987174584320";
        String h3 = "464471023782195200";
        String h4 = "603709077557669888";
        String h5 = "604136387620438016";
        String h6 = "605534791072305152";
        String h7 = "606234521821577216";
        String h8 = "724919475115225088";
        String h9 = "725379562434830336";
        String h10 = "725953800816373760";
        String h11 = "740473448551366656";
        String h12 = "853719913725030400";
        Keeper k1 = new Keeper(h1, p1, r1);
        Keeper k2 = new Keeper(h2, p2, r2);
        Keeper k3 = new Keeper(h3, p3, r3);
        Keeper k4 = new Keeper(h4, p4, r4);
        Keeper k5 = new Keeper(h5, p5, r5);
        Keeper k6 = new Keeper(h6, p6, r6);
        Keeper k7 = new Keeper(h7, p7, r7);
        Keeper k8 = new Keeper(h8, p8, r8);
        Keeper k9 = new Keeper(h9, p9, r9);
        Keeper k10 = new Keeper(h10, p10, r10);
        Keeper k11 = new Keeper(h11, p11, r11);
        Keeper k12 = new Keeper(h12, p12, r12);
        ArrayList<Keeper> keepers = new ArrayList<>();
        keepers.add(k1);
        keepers.add(k2);
        keepers.add(k3);
        keepers.add(k4);
        keepers.add(k5);
        keepers.add(k6);
        keepers.add(k7);
        keepers.add(k8);
        keepers.add(k9);
        keepers.add(k10);
        keepers.add(k11);
        keepers.add(k12);
        return keepers;
    }
}
