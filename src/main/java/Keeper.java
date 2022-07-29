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
}
