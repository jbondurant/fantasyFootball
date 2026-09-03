import java.util.Collection;

/**
 * What a roster is worth - the seam that lets the 1-16 model use a different
 * objective from Model A without disturbing it.
 *
 * Model A scores a roster as the best nine SEASON TOTALS. That is the right
 * objective for the nine-round game it was built for and it stays exactly as
 * it is. It cannot, however, see a bench player at all: a season total has
 * already absorbed the weeks a starter missed, so there is no week left over
 * for anyone to fill, which is why LiveInsurance found every candidate worth
 * 0% of a start.
 *
 * The 1-16 model scores the same roster as the points its STARTERS score
 * across the season, which is a sum of weekly maxima and therefore sees the
 * bench. Same search, same board, different scoring rule.
 */
public interface RosterValue {

    double of(Collection<String> roster);

    /** A short name for output, so a table can say which rule produced it. */
    String label();
}
