import java.util.ArrayList;
import java.util.List;

public class Keeper {

    /**
     * What a keeper nobody drafted costs: a fixed 10th-round pick.
     *
     * The code used to apply this 10 as a ceiling on every keeper as well,
     * which is not a rule the league has. Six seasons of drafts contain 31
     * keepers costing more than a 10th, out to a 16th, so a player taken in the
     * 14th costs a 14th - cheaper than an undrafted one, which is the point.
     */
    public static final int UNDRAFTED_ROUND_COST = 10;

    public String humanWhoCanKeep;
    public Player player;
    public int roundCanBeKept;

    public static List<Keeper> getCopyOfList(ArrayList<Keeper> keepers){
        List<Keeper> copy = new ArrayList<Keeper>();
        for(Keeper keeper : keepers){
            copy.add(getCopy(keeper));
        }
        return copy;
    }

    public static Keeper getCopy(Keeper keeper){
        return new Keeper(keeper.humanWhoCanKeep, keeper.player, keeper.roundCanBeKept);
    }

    public Keeper(String hwck, Player p, int rcbk){
        humanWhoCanKeep = hwck;
        player = p;
        roundCanBeKept = rcbk;
    }

    /**
     * Every keeper declared in the league, priced off last season's draft.
     * This used to be a hand-typed list of 2022 players and stopped being true
     * the moment anyone changed their mind.
     */
    public static ArrayList<Keeper> allPotentialKeepers(){
        return AAAConfiguration.getInstance().getTodaysKeepers();
    }
}
