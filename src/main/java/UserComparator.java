import java.util.Comparator;

public class UserComparator implements Comparator<User> {
    @Override
    public int compare(User u1, User u2) {
        if(u1.draftPosition < u2.draftPosition){
            return -1;
        }
        return 1;
    }
}
