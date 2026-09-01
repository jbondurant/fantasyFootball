import java.util.*;

/**
 * Score every configuration the model can be run in, so no decision waits on a
 * run.
 *
 * Justin: if you need my decision for only like one thing, can you work on
 * either possible answer. This is that, generalised. Three agents are working
 * on questions whose answers change which settings are right - whether the
 * scorer itself uses hindsight, whether a 24-keeper board reorders the models,
 * what the bench parameter should be fitted to - and each answer would
 * otherwise be followed by another hour of runs before anything could be
 * recommended.
 *
 * So the landscape gets measured NOW, across the settings that already exist as
 * flags, and when an agent reports the recommendation is a table lookup rather
 * than a fresh experiment.
 *
 * Read-only with respect to the model: it runs BoardValue as a subprocess with
 * different flags and collects what comes back. Nothing here can change an
 * answer, only reveal one.
 *
 *   ./gradlew run -Pmain=ConfigMatrix -q
 */
public class ConfigMatrix {

    record Run(String label, String... flags){}

    public static void main(String[] args) throws Exception {
        List<Run> runs = List.of(
                new Run("honest, maxTE 1  (shipped)", "-PmaxTE=1"),
                new Run("honest, maxTE 2", "-PmaxTE=2"),
                new Run("honest, maxTE 3", "-PmaxTE=3"),
                new Run("honest, no TE cap", "-PmaxTE=14"),
                new Run("honest, lostBelow 0.40", "-PlostBelow=0.40"),
                new Run("honest, lostBelow 0.70", "-PlostBelow=0.70"),
                new Run("honest, no fragility bar", "-Pfragile=99"),
                new Run("honest, rank on floor", "-Pfloor=true"),
                new Run("BEST BALL (hindsight fill)", "-PbestBall=true"),
                new Run("best ball, maxTE 3", "-PbestBall=true", "-PmaxTE=3"));

        System.out.printf("%nEVERY CONFIGURATION, SCORED%n%n");
        System.out.printf("%-32s %8s %8s%n", "SETTING", "MEAN", "WORST");
        for(Run run : runs){
            List<String> command = new ArrayList<>(List.of("./gradlew", "run",
                    "-Pmain=BoardValue", "-PholdKeepers=true", "-q", "--console=plain"));
            command.addAll(Arrays.asList(run.flags()));
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(new java.io.File("."));
            builder.redirectErrorStream(true);
            Process process = builder.start();
            double mean = 0;
            double worst = Double.MAX_VALUE;
            try(Scanner scanner = new Scanner(process.getInputStream())){
                while(scanner.hasNextLine()){
                    String line = scanner.nextLine().trim();
                    if(line.matches("^\\d{4}\\s+\\d+.*")){
                        worst = Math.min(worst, Double.parseDouble(line.split("\\s+")[1]));
                    }
                    if(line.startsWith("mean")){
                        String[] parts = line.split("\\s+");
                        if(parts.length > 1){
                            mean = Double.parseDouble(parts[1]);
                        }
                    }
                }
            }
            process.waitFor();
            System.out.printf("%-32s %8.0f %8.0f%n", run.label(), mean,
                    worst == Double.MAX_VALUE ? 0 : worst);
        }
        System.out.printf("%nthe committed plan, same conditions, is 2007 mean / 1755 worst.%n"
                + "the 95%% bar is 125 points, so read differences under that as ties and%n"
                + "use them to break a tie on something else - the floor, or whether the%n"
                + "setting is fitted rather than chosen.%n");
    }
}
