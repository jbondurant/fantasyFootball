import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When he receives two men for one, he must cut one, and his gain is priced with
 * the cut. Every place that rebuilds his roster afterwards must rebuild the SAME
 * legal roster - TRAPS #144, where five places did not.
 */
public class HisCutTest {

    @Test
    public void aTwoForOneCarriesHisCutAndHisOutIsWhatLeaves(){
        // his roster: b1 (the man I get), c (worst), d; mine: x, y (I send two)
        Map<String, Double> points = Map.of("x", 50.0, "y", 40.0, "b1", 80.0, "c", 1.0, "d", 30.0, "m", 60.0);
        TradeMarket.Side flat = (before, out, in) -> 0;
        List<TradeMarket.Trade> trades = TradeMarket.unbalanced("me", "him", List.of("x", "y", "m"),
                List.of("b1", "c", "d"), flat, flat, 8, points);
        TradeMarket.Trade twoForOne = trades.stream()
                .filter(t -> t.give().size() == 2 && t.get().equals(List.of("b1")))
                .findFirst().orElseThrow();
        assertEquals(List.of("c"), twoForOne.hisCut(), "he cuts his lowest projected man not in the deal");
        assertEquals(List.of("b1", "c"), twoForOne.hisOut());
        List<String> hisAfter = TradeMarket.swap(List.of("b1", "c", "d"), twoForOne.hisOut(), twoForOne.give());
        assertEquals(3, hisAfter.size(), "he ends at the size he started, which is the point of the cut");
    }

    @Test
    public void aBalancedTradeCutsNobody(){
        TradeMarket.Trade balanced = new TradeMarket.Trade("him", List.of("a"), List.of("b"), 1, 1);
        assertEquals(List.of(), balanced.hisCut());
        assertEquals(List.of("b"), balanced.hisOut());
    }

    /**
     * No source rebuilds his roster from the bare {@code get()}: the pattern that
     * dropped his cut in five places at once. Comments are stripped first.
     */
    @Test
    public void nothingRebuildsHisRosterWithoutHisCut() throws IOException {
        Pattern bare = Pattern.compile("\\b\\w+\\.get\\(\\)\\s*,\\s*\\w+\\.give\\(\\)");
        List<String> offenders = new ArrayList<>();
        try(var files = Files.list(Path.of("src", "main", "java"))){
            for(Path file : files.filter(p -> p.toString().endsWith(".java")).toList()){
                String code = KeeperBasisTest.codeOnly(Files.readString(file));
                Matcher m = bare.matcher(code);
                while(m.find()){
                    offenders.add(file.getFileName() + ": " + m.group());
                }
            }
        }
        assertEquals(List.of(), offenders, "his side must be rebuilt with trade.hisOut(), not trade.get()");
    }
}
