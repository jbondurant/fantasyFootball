public class AAAConfiguration {

    private String leagueID;
    private String myUsername;
    private String myNameForLeague;

    public AAAConfiguration(String leagueID, String myUsername, String myNameForLeague){
        this.leagueID = leagueID;
        this.myUsername = myUsername;
        this.myNameForLeague = myNameForLeague;
    }

    public String getLeagueID() {
        return leagueID;
    }

    public String getMyID(){
        return InOutUtilities.getThisMonthsMyID(myUsername);
    }

    public String getMyNameForLeague(){
        return myNameForLeague;
    }

    public String getRosterWebURL(){
        return "https://api.sleeper.app/v1/league/" + this.leagueID + "/rosters";
    }

}
