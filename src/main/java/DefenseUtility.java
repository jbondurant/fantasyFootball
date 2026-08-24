/**
 * Team defenses have no sportradar id. This used to scrape one out of
 * FantasyPros' dst cheatsheet, which meant loading any player at all required a
 * successful scrape; FantasyPros stopped publishing the "sportsdata_id" field
 * in 2025 and that took the whole program down with it.
 *
 * Sleeper already identifies a defense by its team abbreviation ("HOU"), so
 * that is the id now. No network call, nothing to break.
 */
public class DefenseUtility {

    public static String getDefenseID(String teamAbr){
        if(teamAbr == null || teamAbr.isEmpty()){
            return null;
        }
        return teamAbr;
    }

}
