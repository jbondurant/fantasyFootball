import java.util.ArrayList;

public class HumanOfInterest {

    public static String humanID = "604136387620438016"; //me

    //public static String humanID = "452603383455412224"; //KevinDA
    //public static String humanID = "459267987174584320"; //d0ddi
    //public static String humanID = "464471023782195200"; //itsabust
    //public static String humanID = "603709077557669888"; //Renteez
    //public static String humanID = "604136387620438016"; //justinb314
    //public static String humanID = "605534791072305152"; //Tsayydeja
    //public static String humanID = "606234521821577216"; //tommyrads
    //public static String humanID = "724919475115225088"; //JakeSK
    //public static String humanID = "725379562434830336"; //jerem9604
    //public static String humanID = "725953800816373760"; //Hamrliks
    //public static String humanID = "740473448551366656"; //patekxwater
    //public static String humanID = "853719913725030400"; //BHier


    //604136387620438016 is Justin

    //452603383455412224 is Kevin

    public static ArrayList<String> getThreeNewUserIDsHardcoded(){
        ArrayList<String> allUserIDs = new ArrayList<>();
        allUserIDs.add("605534791072305152");
        allUserIDs.add("740473448551366656");
        allUserIDs.add("853719913725030400");
        return allUserIDs;
    }

    public static ArrayList<String> getAllUserIDsHardcoded(){
        ArrayList<String> allUserIDs = new ArrayList<>();
        allUserIDs.add("452603383455412224");
        allUserIDs.add("459267987174584320");
        allUserIDs.add("464471023782195200");
        allUserIDs.add("603709077557669888");
        allUserIDs.add("604136387620438016");
        allUserIDs.add("605534791072305152");
        allUserIDs.add("606234521821577216");
        allUserIDs.add("724919475115225088");
        allUserIDs.add("725379562434830336");
        allUserIDs.add("725953800816373760");
        allUserIDs.add("740473448551366656");
        allUserIDs.add("853719913725030400");
        return allUserIDs;
    }

    public static String getHumanFromID(String userID){
        if(userID.equals("452603383455412224")){ return "Kevin";}
        else if(userID.equals("459267987174584320")){ return "d0ddi";}
        else if(userID.equals("464471023782195200")){ return "itsabust";}
        else if(userID.equals("603709077557669888")){ return "Renteez";}
        else if(userID.equals("604136387620438016")){ return "justinb314";}
        else if(userID.equals("605534791072305152")){ return "Tsayydeja";}
        else if(userID.equals("606234521821577216")){ return "tommyrads";}
        else if(userID.equals("724919475115225088")){ return "JakeSK";}
        else if(userID.equals("725379562434830336")){ return "jerem9604";}
        else if(userID.equals("725953800816373760")){ return "Hamrliks";}
        else if(userID.equals("740473448551366656")){ return "patekxwater";}
        else if(userID.equals("853719913725030400")){ return "BHier";}
        return "user not found";
    }



}
