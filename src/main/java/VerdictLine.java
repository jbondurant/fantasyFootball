import java.util.List;

import PlayerImportAndSetup.Position;

/**
 * ONE LINE THAT SAYS WHAT THE SCREEN DECIDED.
 *
 * On draft night 2026 four of Justin's questions were about reading the
 * screen: the board model said one position, Model A's committee said another
 * twenty seconds later, and nothing named the disagreement. The tables stay;
 * these lines sit under them and say, in order: what the board model decided
 * and how firmly (SEPARATED by how much, or a TIE among which positions), and
 * then whether Model A agrees, leans inside the tie, or SPLITS from it - with
 * both margins, so the size of the disagreement is on screen.
 *
 * The two engines score different objectives on purpose (TwoObjectivesTest):
 * END TEAM is the ten-slot season, Model A's means are the best nine skill
 * slots. Their margins are printed side by side, never blended, and the
 * written rule stands: the board model's END TEAM ranks.
 */
public class VerdictLine {

    /** The board model's decision: top position, the positions inside its noise, its lead over the runner-up. */
    public record Board(Position top, List<Position> tied, double gap, double se, Position runnerUp) {}

    /** Model A's committee: the consensus, whether every engine agreed, and lookahead-2's top two means. */
    public record Vote(Position consensus, boolean unanimous, double topMean, double secondMean, Position second) {}

    static String board(Board b){
        if(b == null){
            return "";
        }
        if(b.tied().isEmpty()){
            return String.format("VERDICT: %s - SEPARATED from %s by %.1f (+/- %.1f, paired 2 s.e.)",
                    b.top(), b.runnerUp() == null ? "everyone" : b.runnerUp(), b.gap(), b.se());
        }
        StringBuilder names = new StringBuilder(b.top().toString());
        for(Position p : b.tied()){
            names.append(" / ").append(p);
        }
        return String.format("VERDICT: TIE - %s inside the noise (leader by %.1f +/- %.1f); the men decide, not the position",
                names, b.gap(), b.se());
    }

    static String together(Board b, Vote v){
        if(b == null || v == null || v.consensus() == null){
            return "";
        }
        double lean = v.topMean() - v.secondMean();
        String voice = v.unanimous() ? "every engine" : "the committee";
        if(v.consensus() == b.top()){
            return String.format("MODEL A AGREES: %s (%s; lookahead-2 %+.1f over %s)",
                    v.consensus(), voice, lean, v.second() == null ? "the field" : v.second());
        }
        if(b.tied().contains(v.consensus())){
            return String.format("MODEL A LEANS %s inside the board model's tie (%s; %+.1f over %s) - either is defensible",
                    v.consensus(), voice, lean, v.second() == null ? "the field" : v.second());
        }
        return String.format("SPLIT: board model %s (%+.1f END TEAM over %s), Model A %s (%s; %+.1f skill-nine over %s)"
                        + " - the written rule ranks on the board model; both margins sit inside the 125-point season bar",
                b.top(), b.gap(), b.runnerUp() == null ? "the field" : b.runnerUp(),
                v.consensus(), voice, lean, v.second() == null ? "the field" : v.second());
    }
}
