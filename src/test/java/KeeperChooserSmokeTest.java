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
    void keepersCostTheirOwnerAPickRatherThanBeingFree(){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        SleeperLeague league = SleeperLeague.getSeriousLeague();
        ArrayList<Keeper> declared = configuration.getTodaysKeepers();

        java.util.Map<String, Integer> keeperCount = new java.util.HashMap<>();
        for(Keeper keeper : declared){
            keeperCount.merge(keeper.humanWhoCanKeep, 1, Integer::sum);
        }

        int rounds = configuration.getDraftRounds();
        SimulationDraft.getSimulationPermPartialWithHardcodedKeepers(
                new java.util.HashSet<>(), HumanStrategy.nonPermutedPositions(1, 4, 4, 1),
                new ArrayList<>(), rounds, 18, declared);

        for(User user : league.sleeperDraftInfo.usersInfo){
            int kept = keeperCount.getOrDefault(user.userID, 0);
            Assertions.assertEquals(rounds, user.roster.draftedPlayers.size(),
                    HumanOfInterest.getHumanFromID(user.userID) + " should finish with one player per round");
            // Their keepers are on the roster, and they are not duplicated.
            long distinct = user.roster.draftedPlayers.stream().distinct().count();
            Assertions.assertEquals(user.roster.draftedPlayers.size(), distinct,
                    HumanOfInterest.getHumanFromID(user.userID) + " drafted the same player twice");
            if(kept > 0){
                Assertions.assertTrue(kept <= configuration.getMaxKeepers());
            }
        }
    }

    @Test
    void aKeeperIsNeverAlsoDraftedBySomeoneElse(){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        SleeperLeague league = SleeperLeague.getSeriousLeague();
        ArrayList<Keeper> declared = configuration.getTodaysKeepers();

        SimulationDraft.getSimulationPermPartialWithHardcodedKeepers(
                new java.util.HashSet<>(), HumanStrategy.nonPermutedPositions(1, 4, 4, 1),
                new ArrayList<>(), configuration.getDraftRounds(), 18, declared);

        for(Keeper keeper : declared){
            for(User user : league.sleeperDraftInfo.usersInfo){
                if(user.userID.equals(keeper.humanWhoCanKeep)){
                    continue;
                }
                Assertions.assertFalse(user.roster.draftedPlayers.contains(keeper.player),
                        HumanOfInterest.getHumanFromID(user.userID) + " drafted "
                                + keeper.player.lastName + ", who is somebody else's keeper");
            }
        }
    }

    @Test
    void aSimulatedTeamCanFieldEveryStartingSlot(){
        // The league starts QB, RB, RB, WR, WR, WR, TE, FLEX, FLEX, DEF. The
        // draft plan was built from a helper that only emits QB/RB/WR/TE, so
        // the simulated team never drafted a defense and scored a permanent
        // zero in that slot - which made keeping one look like the best move
        // available.
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        SleeperLeague league = SleeperLeague.getSeriousLeague();

        KeeperChooser.rank(configuration, 1, 3, 18);

        for(User user : league.sleeperDraftInfo.usersInfo){
            if(!user.userID.equals(HumanOfInterest.humanID())){
                continue;
            }
            java.util.Map<PlayerImportAndSetup.Position, Integer> have = new java.util.HashMap<>();
            for(Player player : user.roster.draftedPlayers){
                have.merge(player.position, 1, Integer::sum);
            }
            java.util.Map<PlayerImportAndSetup.Position, Integer> need = java.util.Map.of(
                    PlayerImportAndSetup.Position.QB, 1,
                    PlayerImportAndSetup.Position.RB, 2,
                    PlayerImportAndSetup.Position.WR, 3,
                    PlayerImportAndSetup.Position.TE, 1,
                    PlayerImportAndSetup.Position.DEF, 1);
            for(java.util.Map.Entry<PlayerImportAndSetup.Position, Integer> slot : need.entrySet()){
                Assertions.assertTrue(have.getOrDefault(slot.getKey(), 0) >= slot.getValue(),
                        "simulated roster cannot fill the " + slot.getKey() + " slot: got "
                                + have.getOrDefault(slot.getKey(), 0) + " of " + slot.getValue()
                                + ", roster is " + have);
            }
        }
    }

    @Test
    void scoringCountsTheLineupRatherThanTheWholeRoster(){
        // Sixteen drafted players, ten of whom start. A score that sums all
        // sixteen rewards depth that never plays.
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        SleeperLeague league = SleeperLeague.getSeriousLeague();

        double score = SimulationDraft.getSimulationPermPartialWithHardcodedKeepers(
                new java.util.HashSet<>(), HumanStrategy.nonPermutedPositions(1, 4, 4, 1),
                new ArrayList<>(), configuration.getDraftRounds(), 18,
                configuration.getTodaysKeepers()).scoreDraft();

        double wholeRoster = 0.0;
        java.util.ArrayList<Score> scoreList = SleeperLeague.getScoreList();
        for(User user : league.sleeperDraftInfo.usersInfo){
            if(user.userID.equals(HumanOfInterest.humanID())){
                for(Player player : user.roster.draftedPlayers){
                    wholeRoster += Player.scorePlayer(scoreList, player);
                }
            }
        }
        Assertions.assertTrue(score < wholeRoster,
                "the lineup score (" + Math.round(score) + ") should be below the whole-roster sum ("
                        + Math.round(wholeRoster) + ")");
        System.out.println("lineup " + Math.round(score) + " vs whole roster " + Math.round(wholeRoster));
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
