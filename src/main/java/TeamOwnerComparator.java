import java.util.Comparator;

public class TeamOwnerComparator implements Comparator<TeamOwner> {
    @Override
    public int compare(TeamOwner to1, TeamOwner to2) {
        if(to1.getPoints() < to2.getPoints()){
            return 1;
        }
        else if(to1.getPoints() > to2.getPoints()){
            return -1;
        }
        return 0;
    }
}
