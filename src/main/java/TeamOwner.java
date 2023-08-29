import java.util.ArrayList;
import java.util.PriorityQueue;

public class TeamOwner {

    private final String name;
    private final double points;
    private TeamOwner(String name, double points){
        this.name = name;
        this.points = points;
    }

    public static TeamOwner initializeTeamOwnerFromSleeperUserID(String userID, double points){
        return new TeamOwner(rightPadding(HumanOfInterest.getHumanFromID(userID)), points);
    }

    public static String rightPadding(String input)
    {
        int L = 20;
        char ch = ' ';
        return String.format("%" + (-L) + "s", input);
    }

    public String getName(){
        return name;
    }

    public double getPoints(){
        return points;
    }

    public static void printTeamOwnersByPoints(ArrayList<TeamOwner> teamOwners){
        PriorityQueue<TeamOwner> allTeamOwners = new PriorityQueue<>(5,new TeamOwnerComparator());
        allTeamOwners.addAll(teamOwners);

        while(!allTeamOwners.isEmpty()) {
            TeamOwner to = allTeamOwners.remove();
            System.out.println(to.getName() + "ROS best lineup score:\t" + to.getPoints());
        }
    }
}
