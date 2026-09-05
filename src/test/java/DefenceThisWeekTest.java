import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * The live pick must be the pick WireRateStress backtested, or the 8.03 a week
 * it measured applies to nobody.
 */
public class DefenceThisWeekTest {

    private static DefenceThisWeek.Defence def(String name, double adp, Double form, boolean mine, boolean free){
        return new DefenceThisWeek.Defence(name, name, adp, form, form == null ? 0 : 3, mine, free);
    }

    @Test
    public void beforeTheLagThePickIsTheBestFreeDefenceByPreseasonAdp(){
        List<DefenceThisWeek.Defence> pool = List.of(
                def("Rams", 97.1, null, false, false),      // best ADP but rostered
                def("Texans", 107.6, null, false, true),
                def("Seahawks", 115.1, null, false, true),
                def("Ravens", 132.4, null, true, false));
        // his own Ravens are in the choice set, so a better-ranked free man must beat
        // him on ADP to be picked - the tool must never advise trading down
        assertEquals("Texans", DefenceThisWeek.pick(pool, 0, 2).name(),
                "week 1: best by ADP among the men he can start, and Texans (107.6) beat his Ravens (132.4)");
        List<DefenceThisWeek.Defence> heldIsBest = List.of(
                def("Rams", 97.1, null, false, false),
                def("Ravens", 132.4, null, true, false),
                def("Lions", 174.7, null, false, true));
        assertEquals("Ravens", DefenceThisWeek.pick(heldIsBest, 0, 2).name(),
                "the real week-1 board: every better defence rostered, so start his own rather than trade down");
    }

    @Test
    public void afterTheLagThePickIsFormAmongTheFreeMen(){
        List<DefenceThisWeek.Defence> pool = List.of(
                def("Texans", 107.6, 6.0, false, true),
                def("Seahawks", 115.1, 11.5, false, true),
                def("Bears", 219.0, 9.0, false, true),
                def("Chiefs", 140.0, 30.0, false, false),   // best form, but somebody owns him
                def("Ravens", 132.4, 4.0, true, false));
        assertEquals("Seahawks", DefenceThisWeek.pick(pool, 2, 2).name(),
                "form leader among the men he can start, not the best form in the league");
        List<DefenceThisWeek.Defence> mineIsHot = List.of(
                def("Texans", 107.6, 6.0, false, true),
                def("Ravens", 132.4, 14.0, true, false));
        assertEquals("Ravens", DefenceThisWeek.pick(mineIsHot, 2, 2).name(),
                "his own man wins when his own form is best");
    }

    @Test
    public void aManWithNoFormYetCannotWinOnIt(){
        List<DefenceThisWeek.Defence> pool = List.of(
                def("Texans", 107.6, null, false, true),
                def("Bears", 219.0, 3.0, false, true));
        assertEquals("Bears", DefenceThisWeek.pick(pool, 3, 2).name(),
                "a null form is no evidence, not a high score");
        assertEquals("Ravens", DefenceThisWeek.pick(List.of(def("Ravens", 132.4, 9.0, true, false)), 3, 2).name(),
                "holding one and no free men is still a startable defence");
        assertNull(DefenceThisWeek.pick(List.of(def("Rams", 97.1, 9.0, false, false)), 3, 2),
                "somebody else's defence is not a pick");
    }
}
