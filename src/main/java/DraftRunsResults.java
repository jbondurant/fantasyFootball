import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;

public class DraftRunsResults {

    List<List<Position>> allDraftStartPositions;
    HashMap<List<Position>, Double> headToAverageScore;

    List<Position> positionsToDraft;
    HashMap<Position, Double> startToAverageScore;

    int numRuns;

    public DraftRunsResults(List<List<Position>> allDraftStartPositions,
                            HashMap<List<Position>, Double> headToAverageScore,
                            List<Position> positionsToDraft,
                            HashMap<Position, Double> startToAverageScore,
                            int numRuns){
        this.allDraftStartPositions = allDraftStartPositions;
        this.headToAverageScore = headToAverageScore;
        this.positionsToDraft = positionsToDraft;
        this.startToAverageScore = startToAverageScore;
        this.numRuns = numRuns;
    }

}
