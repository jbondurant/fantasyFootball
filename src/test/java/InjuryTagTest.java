import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/** The live screen tags a man's name with Sleeper's injury designation. */
public class InjuryTagTest {

    @Test
    public void healthyMenCarryNoTag(){
        assertEquals("", LiveBoard.injuryTag(null));
        assertEquals("", LiveBoard.injuryTag(""));
    }

    @Test
    public void designationsAreShortAndUnmistakable(){
        assertEquals(" [Q]", LiveBoard.injuryTag("Questionable"));
        assertEquals(" [D]", LiveBoard.injuryTag("Doubtful"));
        assertEquals(" [O]", LiveBoard.injuryTag("Out"));
        assertEquals(" [IR]", LiveBoard.injuryTag("IR"));
        assertEquals(" [PUP]", LiveBoard.injuryTag("PUP"));
        assertEquals(" [NA]", LiveBoard.injuryTag("NA"), "the commissioner exempt list reads NA on Sleeper");
    }
}
