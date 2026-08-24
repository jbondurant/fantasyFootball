import java.util.ArrayList;
import java.util.List;

public class Keeper {

    /** A keeper's price is capped at the last round worth spending. */
    public static final int MAX_ROUND_COST = 10;

    /** What a keeper who was never drafted (waiver pickup) costs. */
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
        if(rcbk > MAX_ROUND_COST){
            rcbk = MAX_ROUND_COST;
        }
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
