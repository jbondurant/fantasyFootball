import java.util.Collection;
import java.util.Map;

/**
 * Model A's rule, unchanged: the best legal nine out of season totals.
 *
 * Wrapped rather than rewritten. Every existing caller keeps the behaviour it
 * has been validated with, and the 1-16 work cannot disturb the tool Justin
 * drafts with on Tuesday.
 */
public class SeasonTotalValue implements RosterValue {

    private final Map<String, Double> points;

    public SeasonTotalValue(Map<String, Double> points){
        this.points = points;
    }

    @Override
    public double of(Collection<String> roster){
        return StartingLineup.bestNine(roster, points);
    }

    @Override
    public String label(){
        return "best-nine season totals";
    }
}
