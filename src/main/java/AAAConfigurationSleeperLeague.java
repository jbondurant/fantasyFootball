/**
 * The only file that needs editing from one season to the next: point it at the
 * new league id. Sleeper rolls a keeper league over into a fresh league id every
 * year, and the new league links back to the old one via previous_league_id, so
 * everything else (draft id, last season's draft, the other eleven humans) falls
 * out of the API.
 */
public class AAAConfigurationSleeperLeague extends AAAConfiguration{

    // 2026 season. Previous: 1249220546315423744 (2025), 980889732034994176 (2024).
    private static final String LEAGUE_ID = "1390416723210952704";
    private static final String MY_USERNAME = "justinb314";

    private static final String MY_NAME_FOR_LEAGUE = "leagueRostersCurrentSerious";

    public AAAConfigurationSleeperLeague() {
        super(LEAGUE_ID, MY_USERNAME, MY_NAME_FOR_LEAGUE);
    }


}
