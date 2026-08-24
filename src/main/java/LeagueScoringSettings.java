import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class LeagueScoringSettings {

    double passYard;
    double passTD;
    double interception;
    double rushYard;
    double rushTD;
    double reception;
    double receivingYard;
    double receivingTD;
    double fumbleLost;

    // Two point conversions. The league pays 2 for each and the scoring used to
    // ignore all three, which quietly cost every player who converted one.
    double passTwoPoint;
    double rushTwoPoint;
    double receivingTwoPoint;


    public LeagueScoringSettings(double[] values){
        passYard = values[0];
        passTD = values[1];
        interception = values[2];

        rushYard = values[3];
        rushTD = values[4];

        reception = values[5];
        receivingYard = values[6];
        receivingTD = values[7];

        fumbleLost = values[8];

        passTwoPoint = 2.0;
        rushTwoPoint = 2.0;
        receivingTwoPoint = 2.0;
    }

    /**
     * Read the league's scoring_settings by name.
     *
     * The positional double[] above meant adding a category shifted every index
     * after it, which is how the two point conversions came to be dropped.
     * Missing categories fall back to the Sleeper default rather than throwing,
     * so a league that does not score something still loads.
     */
    public static LeagueScoringSettings fromSleeperScoringSettings(JsonObject scoringSettings){
        double[] values = {
                setting(scoringSettings, "pass_yd", 0.04),
                setting(scoringSettings, "pass_td", 4.0),
                setting(scoringSettings, "pass_int", -1.0),
                setting(scoringSettings, "rush_yd", 0.1),
                setting(scoringSettings, "rush_td", 6.0),
                setting(scoringSettings, "rec", 0.5),
                setting(scoringSettings, "rec_yd", 0.1),
                setting(scoringSettings, "rec_td", 6.0),
                setting(scoringSettings, "fum_lost", -2.0)
        };
        LeagueScoringSettings settings = new LeagueScoringSettings(values);
        settings.passTwoPoint = setting(scoringSettings, "pass_2pt", 2.0);
        settings.rushTwoPoint = setting(scoringSettings, "rush_2pt", 2.0);
        settings.receivingTwoPoint = setting(scoringSettings, "rec_2pt", 2.0);
        return settings;
    }

    private static double setting(JsonObject scoringSettings, String key, double fallback){
        if(scoringSettings == null){
            return fallback;
        }
        JsonElement element = scoringSettings.get(key);
        if(element == null || element.isJsonNull()){
            return fallback;
        }
        return element.getAsDouble();
    }

    public static LeagueScoringSettings defaultScoringSettings(){
        double[] values = {0.04, 0.4, -1.0, 0.1, 6.0, 0.5, 0.1, 6.0, -2.0};
        LeagueScoringSettings defaultScoring = new LeagueScoringSettings(values);
        return defaultScoring;
    }

}
