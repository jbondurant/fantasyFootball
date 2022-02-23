import java.util.Comparator;

public class ScoreNameIDComparator implements Comparator<Score> {
    @Override
    public int compare(Score s1, Score s2) {
        String idS1 = s1.player.sportRadarID;
        String idS2 = s2.player.sportRadarID;
        return idS1.compareTo(idS2);
    }
}
