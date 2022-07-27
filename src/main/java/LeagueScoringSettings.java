public class LeagueScoringSettings {

    double passYard;
    double passTD;
    double interception;
    double rushYard;
    double rushTD;
    double reception;
    double receivingYard;
    double receivingTD;
    double fumbleLost;


    public LeagueScoringSettings(double[] values){
        passYard = values[0];
        passTD = values[1];
        interception = values[2];

        rushYard = values[3];
        rushTD = values[4];

        reception = values[5];
        receivingYard = values[6];
        receivingTD = values[7];

        fumbleLost = values[8];
    }


    public static LeagueScoringSettings defaultScoringSettings(){
        double[] values = {0.04, 0.4, -1.0, 0.1, 6.0, 0.5, 0.1, 6.0, -2.0};
        LeagueScoringSettings defaultScoring = new LeagueScoringSettings(values);
        return defaultScoring;
    }



    //TODO maybe get league settings from sleeper league



}
