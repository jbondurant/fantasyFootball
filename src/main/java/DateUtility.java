import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

public class DateUtility {

    public static DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

    public static String getTodaysDate(){
        Date date = Calendar.getInstance().getTime();
        String strDate = dateFormat.format(date);
        return strDate;
    }

    public static boolean isStringToday(String dateString){
        String strDate = getTodaysDate();
        if(strDate.equals(dateString)){
            return true;
        }
        return false;
    }

}
