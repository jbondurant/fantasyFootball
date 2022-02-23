import java.util.ArrayList;

public class League {

    LeagueScoringSettings leagueScoringSettings;
    ArrayList<User> users;
    ArrayList<Player> undraftedPlayers;




    public League(LeagueScoringSettings lss, ArrayList<User> u, ArrayList<Player> up){
        leagueScoringSettings = lss;
        users = u;
        undraftedPlayers = up;
    }

}
