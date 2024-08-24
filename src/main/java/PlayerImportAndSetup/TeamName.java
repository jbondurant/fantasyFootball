package PlayerImportAndSetup;

import java.util.HashMap;
import java.util.Map;

public enum TeamName {
    FREE_AGENT,
    ARIZONA,
    ATLANTA,
    BALTIMORE,
    BUFFALO,
    CAROLINA,
    CHICAGO,
    CINCINNATI,
    CLEVELAND,
    DALLAS,
    DENVER,
    DETROIT,
    GREEN_BAY,
    HOUSTON,
    INDIANAPOLIS,
    JACKSONVILLE,
    KANSAS_CITY,
    LOS_ANGELES_CHARGERS,
    LOS_ANGELES_RAMS,
    LAS_VEGAS,
    MIAMI,
    MINNESOTA,
    NEW_ENGLAND,
    NEW_ORLEANS,
    NEW_YORK_GIANTS,
    NEW_YORK_JETS,
    PHILADELPHIA,
    PITTSBURGH,
    SEATTLE,
    SAN_FRANCISCO,
    TAMPA_BAY,
    TENNESSEE,
    WASHINGTON;

    private static final Map<String, TeamName> teamCityFantasyProsToFull = new HashMap<>();

    static {
        teamCityFantasyProsToFull.put("", FREE_AGENT);
        teamCityFantasyProsToFull.put("FA", FREE_AGENT);

        teamCityFantasyProsToFull.put("ARI", ARIZONA);
        teamCityFantasyProsToFull.put("ATL", ATLANTA);
        teamCityFantasyProsToFull.put("BAL", BALTIMORE);
        teamCityFantasyProsToFull.put("BUF", BUFFALO);

        teamCityFantasyProsToFull.put("CAR", CAROLINA);
        teamCityFantasyProsToFull.put("CHI", CHICAGO);
        teamCityFantasyProsToFull.put("CIN", CINCINNATI);
        teamCityFantasyProsToFull.put("CLE", CLEVELAND);

        teamCityFantasyProsToFull.put("DAL", DALLAS);
        teamCityFantasyProsToFull.put("DEN", DENVER);
        teamCityFantasyProsToFull.put("DET", DETROIT);
        teamCityFantasyProsToFull.put("GB", GREEN_BAY);

        teamCityFantasyProsToFull.put("HOU", HOUSTON);
        teamCityFantasyProsToFull.put("IND", INDIANAPOLIS);
        teamCityFantasyProsToFull.put("JAC", JACKSONVILLE);
        teamCityFantasyProsToFull.put("JAX", JACKSONVILLE);
        teamCityFantasyProsToFull.put("KC", KANSAS_CITY);

        teamCityFantasyProsToFull.put("LAC", LOS_ANGELES_CHARGERS);
        teamCityFantasyProsToFull.put("LAR", LOS_ANGELES_RAMS);
        teamCityFantasyProsToFull.put("LV", LAS_VEGAS);
        teamCityFantasyProsToFull.put("OAK", LAS_VEGAS);
        teamCityFantasyProsToFull.put("MIA", MIAMI);

        teamCityFantasyProsToFull.put("MIN", MINNESOTA);
        teamCityFantasyProsToFull.put("NE", NEW_ENGLAND);
        teamCityFantasyProsToFull.put("NO", NEW_ORLEANS);
        teamCityFantasyProsToFull.put("NYG", NEW_YORK_GIANTS);

        teamCityFantasyProsToFull.put("NYJ", NEW_YORK_JETS);
        teamCityFantasyProsToFull.put("PHI", PHILADELPHIA);
        teamCityFantasyProsToFull.put("PIT", PITTSBURGH);
        teamCityFantasyProsToFull.put("SEA", SEATTLE);

        teamCityFantasyProsToFull.put("SF", SAN_FRANCISCO);
        teamCityFantasyProsToFull.put("TB", TAMPA_BAY);
        teamCityFantasyProsToFull.put("TEN", TENNESSEE);
        teamCityFantasyProsToFull.put("WAS", WASHINGTON);
    }

    public static TeamName shortTeamNameToFullTeamName(String fantasyProsTeamName){
        return teamCityFantasyProsToFull.get(fantasyProsTeamName);
    }
}
