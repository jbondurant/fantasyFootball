import java.util.ArrayList;
import java.util.concurrent.Callable;

public class RunDraftWithKeepersTask implements Callable<DraftRunsResults> {

    int numSimulations;
    int roundPick;
    ArrayList<Position> desiredPositions;
    LiveDraftInfo ldifb;
    int qbADPChange;
    ArrayList<Keeper> keepers;
    int minMaxStartSize;

    RunDraftWithKeepersTask(int numSimulations,
                            int roundPick,
                            ArrayList<Position> desiredPositions,
                            LiveDraftInfo ldifb,
                            int qbADPChange,
                            ArrayList<Keeper> keepers,
                            int minMaxStartSize){
        this.numSimulations = numSimulations;
        this.roundPick = roundPick;
        this.desiredPositions = (ArrayList<Position>) Position.getCopy(desiredPositions);
        this.ldifb = LiveDraftInfo.getCopy(ldifb);
        this.qbADPChange = qbADPChange;
        this.keepers = (ArrayList<Keeper>) Keeper.getCopyOfList(keepers);
        this.minMaxStartSize = minMaxStartSize;
    }
    @Override
    public DraftRunsResults call() throws Exception {
        return OnTheFlySimulationRunner.runDraftsWithKeepers(numSimulations, roundPick, desiredPositions, ldifb, qbADPChange, keepers, minMaxStartSize);
    }
}
