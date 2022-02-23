public class User {

    String userID;
    int draftPosition;

    Roster roster;

    //TODO is this correct?
    Strategy strategy;


    public User(String uid, int dp){
        userID = uid;
        draftPosition = dp;
        roster = new Roster();
    }

    public void addToRoster(Player p){
        roster.addPlayer(p);
    }


    public void setRoster(Roster r){
        this.roster = r;
    }

    public void setStrategy(Strategy s){
        strategy = s;
    }

}
