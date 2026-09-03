import java.util.*;

/** Every keeper declared in this league, not just mine. */
public class WhoIsKept {
    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        List<Keeper> all = configuration.getTodaysKeepers();
        System.out.printf("%n%d keepers declared league-wide%n%n", all.size());
        for(Keeper keeper : all){
            Player player = keeper.player;
            System.out.printf("   %-24s %-4s round %-3d %s%n",
                    player == null ? "?" : player.firstName + " " + player.lastName,
                    player == null ? "?" : player.position.toString(),
                    keeper.roundCanBeKept, keeper.humanWhoCanKeep);
        }
    }
}
