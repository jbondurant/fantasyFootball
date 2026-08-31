import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TRAPS.md F27: prose drift.
 *
 * Three times in three days a comment described a mechanism the code did not
 * implement - most damagingly a comment that specifically DENIED the hindsight
 * sitting twelve lines below it. Comments on an objective are load-bearing:
 * they are what gets quoted when somebody asks what the model believes.
 *
 * A comment cannot be type-checked, so none of this is exact. What CAN be
 * checked mechanically is the narrow class of claim that has actually gone
 * wrong here, and each check below is one of those:
 *
 *   1. a flag the prose promises must exist in the code
 *   2. a flag the code reads must be forwarded by the build, or it is a
 *      default nobody can leave
 *   3. a measured constant must have exactly one home
 *   4. a dispatcher documented as switchable must consult its switch
 *   5. a comment DENYING hindsight must not sit above code that performs it
 *
 * These are lints, not proofs. Each one carries an allowlist, and putting an
 * entry in an allowlist is the deliberate act that a silent default was not.
 */
class ProseDriftTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    private static List<Path> mainSources(){
        try(Stream<Path> files = Files.walk(MAIN)){
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        catch(IOException unreadable){
            throw new AssertionError("cannot walk " + MAIN, unreadable);
        }
    }

    private static String read(Path path){
        try {
            return Files.readString(path);
        }
        catch(IOException unreadable){
            throw new AssertionError("cannot read " + path, unreadable);
        }
    }

    // =====================================================================
    // 1. A flag the prose promises must exist.
    // =====================================================================

    /**
     * `-Psomething` written in a comment, in the RUNBOOK or in build.gradle is a
     * promise that a knob exists. If no source file so much as contains the
     * string, the knob is imaginary and every reader who reaches for it silently
     * gets the default instead.
     *
     * This is deliberately the loosest possible test - it asks only that the
     * name appear as a literal SOMEWHERE in main - because a lint that is easy
     * to satisfy honestly is one nobody is tempted to disable.
     */
    @Test
    void everyFlagThePromiseMentionsExistsSomewhereInTheCode(){
        Set<String> promised = new TreeSet<>();
        Pattern flag = Pattern.compile("-P([a-z][A-Za-z]*)");
        List<Path> documents = new ArrayList<>(mainSources());
        for(String extra : new String[]{"RUNBOOK.md", "README.md", "build.gradle"}){
            Path path = Path.of(extra);
            if(Files.exists(path)){
                documents.add(path);
            }
        }
        for(Path path : documents){
            Matcher matcher = flag.matcher(read(path));
            while(matcher.find()){
                promised.add(matcher.group(1));
            }
        }
        // `main` is Gradle's own JavaExec property, not a model knob
        promised.remove("main");

        Set<String> literals = new LinkedHashSet<>();
        Pattern quoted = Pattern.compile("\"([a-zA-Z]+)\"");
        for(Path path : mainSources()){
            Matcher matcher = quoted.matcher(read(path));
            while(matcher.find()){
                literals.add(matcher.group(1));
            }
        }

        Set<String> imaginary = new TreeSet<>(promised);
        imaginary.removeAll(literals);
        assertEquals(Set.of(), imaginary,
                "these flags are documented but no code reads them, so using one"
                        + " silently gets the default: " + imaginary);
    }

    // =====================================================================
    // 2. A flag the code reads must be forwarded by the build.
    // =====================================================================

    /**
     * Gradle hands -D to its own daemon, not to the JVM it forks, so build.gradle
     * forwards each knob by name. A flag missing from that list cannot be set at
     * all from the command line - it is a constant wearing a flag's clothes,
     * which is the -Pdeviate footgun with the safety catch removed.
     */
    @Test
    void everyFlagTheCodeReadsIsForwardedByTheBuild(){
        Set<String> read = new TreeSet<>();
        Pattern property = Pattern.compile(
                "(?:System\\.getProperty|Boolean\\.getBoolean|Integer\\.getInteger"
                        + "|Long\\.getLong)\\(\\s*\"([a-zA-Z]+)\"");
        for(Path path : mainSources()){
            Matcher matcher = property.matcher(read(path));
            while(matcher.find()){
                read.add(matcher.group(1));
            }
        }
        // read through a constant rather than a literal
        read.add(LeagueActuals.FLAG);
        // read through ShapeSearch's flag(name, fallback) helper
        read.addAll(List.of("restarts", "randomShapes", "tieBand", "slateRows"));

        Set<String> forwarded = forwardedByBuild();
        Set<String> unreachable = new TreeSet<>(read);
        unreachable.removeAll(forwarded);

        assertEquals(Set.of(), unreachable,
                "these flags are read by main code but build.gradle never forwards"
                        + " them, so -P<name> does nothing: " + unreachable);
    }

    /** And nothing is forwarded that no longer exists, or the list becomes fiction. */
    @Test
    void theBuildForwardsNothingThatVanished(){
        Set<String> literals = new LinkedHashSet<>();
        Pattern quoted = Pattern.compile("\"([a-zA-Z]+)\"");
        for(Path path : mainSources()){
            Matcher matcher = quoted.matcher(read(path));
            while(matcher.find()){
                literals.add(matcher.group(1));
            }
        }
        Set<String> stale = new TreeSet<>(forwardedByBuild());
        stale.removeAll(literals);
        assertEquals(Set.of(), stale,
                "build.gradle forwards knobs no source mentions: " + stale);
    }

    private static Set<String> forwardedByBuild(){
        String gradle = read(Path.of("build.gradle"));
        int from = gradle.indexOf("['sims'");
        int to = gradle.indexOf("].each", from);
        assertTrue(from > 0 && to > from,
                "the knob-forwarding list in build.gradle has been restructured;"
                        + " this lint needs updating with it");
        // comments inside the list explain why entries were added or removed, and
        // they quote the names - so they are stripped, or a knob deleted from the
        // list would still read as forwarded from the line recording its deletion
        StringBuilder code = new StringBuilder();
        for(String line : gradle.substring(from, to).lines().toList()){
            int comment = line.indexOf("//");
            code.append(comment < 0 ? line : line.substring(0, comment)).append('\n');
        }
        Set<String> forwarded = new TreeSet<>();
        Matcher matcher = Pattern.compile("'([a-zA-Z]+)'").matcher(code);
        while(matcher.find()){
            forwarded.add(matcher.group(1));
        }
        return forwarded;
    }

    // =====================================================================
    // 3. A measured constant has exactly one home.
    // =====================================================================

    /**
     * The pooled-top-quartile wire estimator - sort the realised rates, keep the
     * best quarter - must live in exactly one place.
     *
     * It is the calculation TRAPS.md C13 is about, and there is now a
     * -PhonestWire override beside it. A second copy elsewhere does not get the
     * override, so flipping the switch would move half the repo and leave the
     * other half quietly on the old number, with nothing to show which table was
     * which.
     */
    @Test
    void theHindsightWireEstimatorHasExactlyOneHome(){
        Pattern estimator = Pattern.compile(
                "sort\\(Comparator\\.reverseOrder\\(\\)\\)[\\s\\S]{0,400}?"
                        + "subList\\(0,[^)]*?(?:size\\(\\)\\s*/\\s*4|best)");
        List<String> homes = new ArrayList<>();
        for(Path path : mainSources()){
            if(estimator.matcher(read(path)).find()){
                homes.add(path.getFileName().toString());
            }
        }
        assertEquals(List.of("WeeklyStarterValue.java"), homes,
                "the top-quartile wire estimator appears in " + homes + ". Only"
                        + " WeeklyStarterValue.wireRates carries the -PhonestWire"
                        + " override, so every other copy is a second, unswitchable"
                        + " definition of the same number.");
    }

    // =====================================================================
    // 4. A switchable dispatcher must consult its switch.
    // =====================================================================

    /**
     * LeagueActuals promises that every grader calls a dispatcher "so one switch
     * moves all of it at once". A dispatcher that stopped consulting enabled()
     * would keep that promise in the prose and break it in the code - and the
     * symptom would be a table half in one scoring and half in the other.
     */
    @Test
    void everyLeagueActualsDispatcherConsultsTheFlag(){
        String source = read(MAIN.resolve("LeagueActuals.java"));
        for(String dispatcher : new String[]{"seasonPoints", "seasonDefencePoints",
                "weeklyPoints"}){
            int at = source.indexOf("public static Map<String, Double> " + dispatcher + "(");
            assertTrue(at > 0, "dispatcher " + dispatcher + " is gone");
            String body = source.substring(at, Math.min(source.length(), at + 400));
            int end = body.indexOf("\n    }");
            assertTrue(body.substring(0, end < 0 ? body.length() : end).contains("enabled()"),
                    dispatcher + " no longer consults LeagueActuals.enabled(), so"
                            + " -PleagueScoredActuals moves only part of the repo");
        }
    }

    // =====================================================================
    // 5. A comment denying hindsight must not sit above code performing it.
    // =====================================================================

    /**
     * THE ONE THAT COST THE MOST, as a lint.
     *
     * A comment saying "chosen on expected, not on what he went on to score" sat
     * twelve lines above a sort on realised rates. Nothing caught it for days.
     *
     * The rule: inside the objective-bearing files, if a comment DENIES
     * hindsight and a realised-order sort follows within the window, the denial
     * must be withdrawn - by a comment line that SHOUTS the word HINDSIGHT as
     * its first word.
     *
     * The marker is a shout rather than a phrase on purpose. Prose that mentions
     * hindsight in passing is exactly what the drifted comment did - it named
     * the fault while denying committing it - so any mention would clear every
     * case including the one that cost the days. A line beginning HINDSIGHT is
     * a deliberate declaration nobody writes by accident, and writing one is the
     * cheapest possible way to answer this lint honestly.
     *
     * False positives are expected and are the price. The answer to one is to
     * write down what the code actually does, which is the outcome wanted.
     */
    @Test
    void aCommentDenyingHindsightMustNotSitAboveARealisedSort(){
        String[] denials = {
                "not on what the player went on to score",
                "not on what they went on to score",
                "sorted by expected",
                "sort by expected",
                "sorts by expected",
                "chosen on expected",
                "cannot see the future",
                "never uses information from the future",
                "no hindsight",
                "hindsight-free"};
        String[] performs = {
                "sort(Comparator.reverseOrder())",
                "sort(Collections.reverseOrder())"};

        List<String> drifted = new ArrayList<>();
        for(Path path : objectiveBearing()){
            List<String> lines = read(path).lines().toList();
            for(int line = 0; line < lines.size(); line++){
                String lower = lines.get(line).toLowerCase(Locale.ROOT);
                if(!isComment(lines.get(line)) || !containsAny(lower, denials)){
                    continue;
                }
                if(declaredNearby(lines, line)){
                    continue;                        // the denial is withdrawn
                }
                for(int ahead = line + 1;
                        ahead < Math.min(lines.size(), line + WINDOW); ahead++){
                    String text = lines.get(ahead);
                    if(!isComment(text) && containsAny(text, performs)){
                        drifted.add(path.getFileName() + ":" + (line + 1)
                                + " denies hindsight, and line " + (ahead + 1)
                                + " sorts on realised order");
                        break;
                    }
                }
            }
        }
        assertEquals(List.of(), drifted,
                "a comment denies a mechanism the code below it performs. Either"
                        + " fix the code, or open a comment line with the word"
                        + " HINDSIGHT and say what it does:\n  "
                        + String.join("\n  ", drifted));
    }

    /** A comment line whose first word is HINDSIGHT, within the window either way. */
    private static boolean declaredNearby(List<String> lines, int denial){
        Pattern shout = Pattern.compile("^\\s*(?://|\\*)\\s*HINDSIGHT\\b");
        int from = Math.max(0, denial - WINDOW);
        int to = Math.min(lines.size(), denial + WINDOW);
        for(int line = from; line < to; line++){
            if(shout.matcher(lines.get(line)).find()){
                return true;
            }
        }
        return false;
    }

    /** How far a comment's claim is taken to reach. The known case was twelve lines. */
    private static final int WINDOW = 30;

    /**
     * The files whose comments are quoted when somebody asks what the model
     * believes. The lint is scoped rather than global on purpose: run everywhere
     * it produces noise nobody reads, and a lint nobody reads is worse than none.
     */
    private static List<Path> objectiveBearing(){
        List<Path> files = new ArrayList<>();
        for(String name : new String[]{"WeeklyStarterValue", "PlanBacktest", "BoardValue",
                "RankDraft", "PolicyBacktest", "PowerBacktest", "LineupPromotion",
                "DetectionLag", "EraGame", "TeOrDepth", "StartingLineup",
                "LeagueActuals", "WireRateStress", "BustBoomValue"}){
            Path path = MAIN.resolve(name + ".java");
            if(Files.exists(path)){
                files.add(path);
            }
        }
        return files;
    }

    private static boolean isComment(String line){
        String trimmed = line.trim();
        return trimmed.startsWith("//") || trimmed.startsWith("*")
                || trimmed.startsWith("/*");
    }

    private static boolean containsAny(String haystack, String[] needles){
        for(String needle : needles){
            if(haystack.contains(needle)){
                return true;
            }
        }
        return false;
    }
}
