import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The keeper chooser and the simulation underneath it, against the live league.
 */
@Tag("smoke")
class KeeperChooserSmokeTest {

    @Test
    void simulatedDraftsDoNotAccumulateRosters(){
        // Regression, and one I caused by caching SleeperLeague: the User
        // objects persist between simulations and addToRoster only appends, so
        // run two was scoring both drafts' picks. Measured 10, then 20, then 30.
        SleeperLeague league = SleeperLeague.getSeriousLeague();
        String myID = HumanOfInterest.humanID();

        for(int run = 0; run < 3; run++){
            SimulationDraft.getSimulationPermPartial(
                    HumanStrategy.nonPermutedPositions(1, 4, 4, 1), new ArrayList<>(), 14, false, 18);

            for(User user : league.sleeperDraftInfo.usersInfo){
                Assertions.assertEquals(14, user.roster.draftedPlayers.size(),
                        "run " + run + ": " + HumanOfInterest.getHumanFromID(user.userID)
                                + " came out of a 14 round draft with "
                                + user.roster.draftedPlayers.size() + " players");
                if(user.userID.equals(myID)){
                    Assertions.assertFalse(user.roster.draftedPlayers.contains(null));
                }
            }
        }
    }

    @Test
    void candidatesAreOnlyPlayersTheRulesAllow(){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        String myID = configuration.getMyID();

        List<String> rostered = configuration.getMyRosterPlayerIDs(myID);
        List<Keeper> eligible = KeeperChooser.eligibleCandidates(configuration, myID);

        Assertions.assertFalse(eligible.isEmpty());
        Assertions.assertTrue(eligible.size() <= rostered.size());

        for(Keeper keeper : eligible){
            Assertions.assertTrue(keeper.roundCanBeKept > KeeperPricing.HIGHEST_KEEPABLE_DRAFT_ROUND
                            || keeper.roundCanBeKept >= 1,
                    keeper.player.lastName + " priced at round " + keeper.roundCanBeKept);
        }

        System.out.println("eligible keepers on my roster: " + eligible.size()
                + " of " + rostered.size() + " rostered");
    }

    @Test
    void aFirstRoundPickCannotBeKept(){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        String myID = configuration.getMyID();

        for(Keeper keeper : KeeperChooser.eligibleCandidates(configuration, myID)){
            Assertions.assertNotEquals("Robinson", keeper.player.lastName,
                    "Bijan Robinson went in the first round and cannot be kept");
            Assertions.assertNotEquals("Jackson", keeper.player.lastName,
                    "Lamar Jackson went in the second round and cannot be kept");
        }
    }

    @Test
    void theChooserRanksPairsAndPrefersValue(){
        AAAConfiguration configuration = AAAConfiguration.getInstance();

        List<KeeperChooser.Option> ranked = KeeperChooser.rank(configuration, 8, 4, 18);

        Assertions.assertFalse(ranked.isEmpty());
        for(KeeperChooser.Option option : ranked){
            Assertions.assertEquals(configuration.getMaxKeepers(), option.keepers.size(),
                    "every option should fill the keeper slots: " + option.describe());
            Assertions.assertTrue(option.averageDraftScore > 0, option.describe());
        }
        // Descending.
        for(int i = 1; i < ranked.size(); i++){
            Assertions.assertTrue(ranked.get(i - 1).averageDraftScore >= ranked.get(i).averageDraftScore);
        }
        System.out.println("best of " + ranked.size() + " pairs: " + ranked.get(0).describe());
    }

    @Test
    void twoKeepersNeverShareARound(){
        AAAConfiguration configuration = AAAConfiguration.getInstance();

        for(KeeperChooser.Option option : KeeperChooser.rank(configuration, 1, 5, 18)){
            if(option.keepers.size() < 2){
                continue;
            }
            Assertions.assertNotEquals(option.keepers.get(0).roundCanBeKept,
                    option.keepers.get(1).roundCanBeKept,
                    "same-round bump was not applied: " + option.describe());
        }
    }
}
