import java.util.Comparator;

public class FPRosterSeriousStartingQBComparator implements Comparator<ScoredRoster> {
    @Override
    public int compare(ScoredRoster r1, ScoredRoster r2) {
        double bestQBR1 = 0.0;
        double bestQBR2 = 0.0;

        for(Score score : r1.draftedPlayersWithProj){
            if(score.player.position.equals(Position.QB)){
                if(score.score > bestQBR1){
                    bestQBR1 = score.score;
                }
            }
        }

        for(Score score : r2.draftedPlayersWithProj){
            if(score.player.position.equals(Position.QB)){
                if(score.score > bestQBR2){
                    bestQBR2 = score.score;
                }
            }
        }

        if(bestQBR1 > bestQBR2){
            return -1;
        }
        if (bestQBR1 < bestQBR2) {
            return 1;
        }

        return 0;
    }
}
