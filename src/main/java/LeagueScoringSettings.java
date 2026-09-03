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

    // ---------------------------------------------------------------------
    // Categories the PROJECTION feed never publishes, so the fields above did
    // not need them. Real stat lines do publish them, and the moment outcomes
    // are scored from components rather than read off pts_half_ppr they matter.
    //
    // Every default below is THE VALUE pts_half_ppr ITSELF ASSUMES, measured
    // against five seasons of real stat lines by ScoringRuleAudit - not the
    // value Sleeper offers a new league. That makes the fallback safe in the
    // only way that counts: a category this league does not list contributes
    // exactly zero difference between the two scorings, so an unread setting
    // can never invent a mismatch that is not there.
    // ---------------------------------------------------------------------

    /** EVERY fumble, not only the lost ones. This league charges both. */
    double fumble;

    /** Return touchdowns and loose-ball plays credited to a skill player. */
    double specialTeamsTD;
    double specialTeamsForcedFumble;
    double specialTeamsFumbleRecovery;
    double fumbleRecoveryTD;

    // Team defence.
    double sack;
    double defenceInterception;
    double fumbleRecovery;
    double defenceTD;
    double safety;
    double blockedKick;
    double forcedFumble;
    double defenceSpecialTeamsTD;
    double defenceSpecialTeamsForcedFumble;
    double defenceSpecialTeamsFumbleRecovery;

    /** Points-allowed bands, in Sleeper's order: 0, 1-6, 7-13, 14-20, 21-27, 28-34, 35+. */
    double pointsAllowed0;
    double pointsAllowed1to6;
    double pointsAllowed7to13;
    double pointsAllowed14to20;
    double pointsAllowed21to27;
    double pointsAllowed28to34;
    double pointsAllowed35plus;


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

        applyHalfPprFeedDefaults();
    }

    /**
     * Set every category the projection feed does not publish to what
     * pts_half_ppr assumes.
     *
     * Called from the constructor so an object built the old positional way -
     * every test, every caller that predates the defence categories - scores a
     * defence exactly as Sleeper's own field does, and therefore changes no
     * number anywhere until a caller deliberately reads the league's settings.
     */
    private void applyHalfPprFeedDefaults(){
        fumble = 0.0;
        specialTeamsTD = 6.0;
        specialTeamsForcedFumble = 1.0;
        specialTeamsFumbleRecovery = 1.0;
        fumbleRecoveryTD = 6.0;

        sack = 1.0;
        defenceInterception = 2.0;
        fumbleRecovery = 2.0;
        defenceTD = 6.0;
        safety = 2.0;
        blockedKick = 2.0;
        forcedFumble = 1.0;
        defenceSpecialTeamsTD = 6.0;
        defenceSpecialTeamsForcedFumble = 1.0;
        defenceSpecialTeamsFumbleRecovery = 1.0;

        pointsAllowed0 = 10.0;
        pointsAllowed1to6 = 7.0;
        pointsAllowed7to13 = 4.0;
        pointsAllowed14to20 = 0.0;   // the league pays 1; the feed pays nothing
        pointsAllowed21to27 = 0.0;
        pointsAllowed28to34 = -1.0;
        pointsAllowed35plus = -4.0;
    }

    /**
     * The scoring Sleeper's precomputed pts_half_ppr field actually applies.
     *
     * Not Sleeper's advertised default for a new league - what the number in
     * the feed is measurably made of. ScoringRuleAudit reconstructs five
     * seasons of real stat lines from these values and reports how many land
     * exactly; that reconstruction is the evidence for every entry here.
     */
    public static LeagueScoringSettings halfPprFeed(){
        return new LeagueScoringSettings(
                new double[]{0.04, 4.0, -1.0, 0.1, 6.0, 0.5, 0.1, 6.0, -2.0});
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

        // Read the rest by name too, each falling back to what pts_half_ppr
        // assumes, so an unlisted category is a no-op rather than a phantom gap.
        LeagueScoringSettings feed = halfPprFeed();
        settings.fumble = setting(scoringSettings, "fum", feed.fumble);
        settings.specialTeamsTD = setting(scoringSettings, "st_td", feed.specialTeamsTD);
        settings.specialTeamsForcedFumble =
                setting(scoringSettings, "st_ff", feed.specialTeamsForcedFumble);
        settings.specialTeamsFumbleRecovery =
                setting(scoringSettings, "st_fum_rec", feed.specialTeamsFumbleRecovery);
        settings.fumbleRecoveryTD =
                setting(scoringSettings, "fum_rec_td", feed.fumbleRecoveryTD);

        settings.sack = setting(scoringSettings, "sack", feed.sack);
        settings.defenceInterception = setting(scoringSettings, "int", feed.defenceInterception);
        settings.fumbleRecovery = setting(scoringSettings, "fum_rec", feed.fumbleRecovery);
        settings.defenceTD = setting(scoringSettings, "def_td", feed.defenceTD);
        settings.safety = setting(scoringSettings, "safe", feed.safety);
        settings.blockedKick = setting(scoringSettings, "blk_kick", feed.blockedKick);
        settings.forcedFumble = setting(scoringSettings, "ff", feed.forcedFumble);
        settings.defenceSpecialTeamsTD =
                setting(scoringSettings, "def_st_td", feed.defenceSpecialTeamsTD);
        settings.defenceSpecialTeamsForcedFumble =
                setting(scoringSettings, "def_st_ff", feed.defenceSpecialTeamsForcedFumble);
        settings.defenceSpecialTeamsFumbleRecovery =
                setting(scoringSettings, "def_st_fum_rec", feed.defenceSpecialTeamsFumbleRecovery);

        settings.pointsAllowed0 = setting(scoringSettings, "pts_allow_0", feed.pointsAllowed0);
        settings.pointsAllowed1to6 =
                setting(scoringSettings, "pts_allow_1_6", feed.pointsAllowed1to6);
        settings.pointsAllowed7to13 =
                setting(scoringSettings, "pts_allow_7_13", feed.pointsAllowed7to13);
        settings.pointsAllowed14to20 =
                setting(scoringSettings, "pts_allow_14_20", feed.pointsAllowed14to20);
        settings.pointsAllowed21to27 =
                setting(scoringSettings, "pts_allow_21_27", feed.pointsAllowed21to27);
        settings.pointsAllowed28to34 =
                setting(scoringSettings, "pts_allow_28_34", feed.pointsAllowed28to34);
        settings.pointsAllowed35plus =
                setting(scoringSettings, "pts_allow_35p", feed.pointsAllowed35plus);
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

    /**
     * A passing touchdown was 0.4 here - a tenth of its value - while every
     * other entry was a correct standard figure, so it was a slipped decimal
     * rather than a choice. Nothing calls this today, which is what made it
     * worth fixing: dead code is read as a reference for what the defaults
     * ARE, and the next caller would have inherited quarterbacks worth a tenth
     * of themselves with nothing to make the error visible. This league pays 6,
     * but this is the generic fallback, so it carries the standard 4.
     */
    public static LeagueScoringSettings defaultScoringSettings(){
        double[] values = {0.04, 4.0, -1.0, 0.1, 6.0, 0.5, 0.1, 6.0, -2.0};
        LeagueScoringSettings defaultScoring = new LeagueScoringSettings(values);
        return defaultScoring;
    }

}
