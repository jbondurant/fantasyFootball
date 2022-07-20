import java.util.ArrayList;

public class DraftReport {

    ArrayList<RoundReport> roundReports;

    public DraftReport(ArrayList<RoundReport> rr){
        roundReports = rr;
    }

    public void setEndScoreAll(double endDraftScore){
        for(RoundReport roundReport : roundReports){
            roundReport.setEndScore(endDraftScore);
        }
    }
}
