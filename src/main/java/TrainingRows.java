import java.util.*;
/**
 * How many selections does the SHIPPED room model actually train on?
 *
 * DIAGNOSTIC.md, the agent definition and a memory file all said "435 rows" for
 * most of 2026-09-01. That was BoostLab's 2024 GATE set - seasons 2021-2023,
 * rounds 1-13, 423 selections - not the shipped fit, which is 2021-2025 at the
 * sixteen-round schedule. The data-limit conclusion survives (the noise floor
 * was measured on the shipped fit), but the number underneath it was the wrong
 * population's. TRAPS #59.
 *
 *   ./gradlew run -Pmain=TrainingRows -q
 */
public class TrainingRows {
    public static void main(String[] args) throws Exception {
        System.setProperty("scheduleRounds", "16");
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        // -PtrainTo=2026 counts the current season too, once its draft-night feed is frozen
        int last = Integer.getInteger("trainTo", Integer.parseInt(configuration.getSeason()) - 1);
        Map<String, Double> qb = SelectionModel.qbEarliness(configuration, last);
        List<SelectionModel.Observation> shipped = SelectionModel.loadObservations(
                configuration, 2021, last, qb,
                SelectionModel.positionEarliness(configuration, last, PlayerImportAndSetup.Position.TE),
                SelectionModel.positionEarliness(configuration, last, PlayerImportAndSetup.Position.RB),
                false, SelectionModel.trainRounds());
        System.out.printf("shipped fit: seasons 2021-%d, rounds 1-%d -> %d selections%n",
                last, SelectionModel.trainRounds(), shipped.size());
        System.setProperty("scheduleRounds", "9");
        List<SelectionModel.Observation> gate = SelectionModel.loadObservations(
                configuration, 2021, 2023, qb,
                SelectionModel.positionEarliness(configuration, 2023, PlayerImportAndSetup.Position.TE),
                SelectionModel.positionEarliness(configuration, 2023, PlayerImportAndSetup.Position.RB),
                false, SelectionModel.TRAIN_ROUNDS);
        System.out.printf("BoostLab's 2024 gate: seasons 2021-2023, rounds 1-%d -> %d selections"
                + "  <- the '435' I have been quoting%n", SelectionModel.TRAIN_ROUNDS, gate.size());
    }
}
