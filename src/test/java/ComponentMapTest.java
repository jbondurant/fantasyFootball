import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * COMPONENTS.md lists every runnable class exactly once. A main that appears,
 * disappears or is renamed without the map changing fails here, which is what
 * keeps the map from drifting the way every other hand-kept list in this repo
 * has (the pool test named two files of three; the keeper-basis test named
 * three of four).
 */
public class ComponentMapTest {

    @Test
    public void everyMainIsOnTheMapExactlyOnce() throws Exception {
        Set<String> mains = new TreeSet<>();
        try(var files = Files.list(Path.of("src", "main", "java"))){
            for(Path path : files.toList()){
                String name = path.getFileName().toString();
                if(name.endsWith(".java")
                        && KeeperBasisTest.codeOnly(Files.readString(path)).contains("static void main(")){
                    mains.add(name.substring(0, name.length() - 5));
                }
            }
        }
        String map = Files.readString(Path.of("COMPONENTS.md"));
        Matcher listed = Pattern.compile("^- `([A-Za-z0-9]+)` \u2014", Pattern.MULTILINE).matcher(map);
        Map<String, Integer> onMap = new TreeMap<>();
        while(listed.find()){
            onMap.merge(listed.group(1), 1, Integer::sum);
        }
        List<String> missing = mains.stream().filter(n -> !onMap.containsKey(n)).toList();
        List<String> gone = onMap.keySet().stream().filter(n -> !mains.contains(n)).toList();
        List<String> twice = onMap.entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey).toList();
        assertEquals(List.of(), missing, "runnable but not on the map - add each to its component in COMPONENTS.md");
        assertEquals(List.of(), gone, "on the map but no longer runnable - remove or rename in COMPONENTS.md");
        assertEquals(List.of(), twice, "listed more than once");
        assertTrue(mains.size() > 100, "the map is meant to cover the whole repo, not a sample: " + mains.size());
    }
}
