package DateStuff;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

public class DateUtility {

    public static String getThisMonth(){
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM");
        Date date = Calendar.getInstance().getTime();
        String strDate = dateFormat.format(date);
        return strDate;
    }

    public static String getTodaysDate(){
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        Date date = Calendar.getInstance().getTime();
        String strDate = dateFormat.format(date);
        return strDate;
    }

}
