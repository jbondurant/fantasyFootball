import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * THE PAGE SEARCHES AS WIDE AS THE BOARD IT CLAIMS TO BE.
 *
 * `pool` bounds the men each side offers into a two-for-two or three-for-three.
 * It was a literal default in two places and they disagreed: TradeMarket 8,
 * LeagueConsole 6. Nothing documented the difference, and the effect was that
 * the artifact Justin reads was a strict subset of the terminal tool - 81 trades
 * against 182 on the same rosters, missing every multi-player deal built on his
 * seventh and eighth men.
 *
 * A literal that must match another literal in a different file is a divergence
 * waiting to happen, which is why this checks that neither main carries one.
 */
public class SearchWidthTest {

    private static String code(String name) throws Exception {
        Path path = Path.of("src", "main", "java", name);
        assertTrue(Files.exists(path), name + " has moved; update this test rather than dropping it");
        return KeeperBasisTest.codeOnly(Files.readString(path));
    }

    @Test
    public void bothMainsTakeTheirPoolFromTheOneConstant() throws Exception {
        List<String> literal = new ArrayList<>();
        for(String name : List.of("LeagueConsole.java", "TradeMarket.java")){
            String source = code(name);
            if(!source.contains("Integer.getInteger(\"pool\", TradeMarket.DEFAULT_POOL)")
                    && !source.contains("Integer.getInteger(\"pool\", DEFAULT_POOL)")){
                literal.add(name);
            }
        }
        assertEquals(List.of(), literal,
                "these set a pool default of their own, so the page and the board can search"
                        + " different widths again without anything saying so");
    }

    /**
     * And the constant is wide enough to reach the depth this league trades in.
     * Eleven of its 51 completed trades moved four players and six moved five or
     * more; a pool that stops at a roster's top few cannot build those.
     */
    @Test
    public void thePoolReachesPastTheStarters(){
        assertTrue(TradeMarket.DEFAULT_POOL >= 8,
                "a pool of " + TradeMarket.DEFAULT_POOL + " cannot offer a manager's depth into a"
                        + " multi-player deal, which is the shape this league actually accepts");
    }

    /** The combinatorics the pool controls, so the cost of raising it is stated. */
    @Test
    public void raisingThePoolCostsWhatTheComboMathSays(){
        assertEquals(15, choose(6, 2));
        assertEquals(28, choose(8, 2));
        assertEquals(20, choose(6, 3));
        assertEquals(56, choose(8, 3));
        // per rival: pairs-of-pairs and triples-of-triples, both sides
        assertEquals(225, choose(6, 2) * choose(6, 2), "two-for-twos a rival at pool 6");
        assertEquals(784, choose(8, 2) * choose(8, 2), "and at pool 8");
        assertEquals(400, choose(6, 3) * choose(6, 3), "three-for-threes at pool 6");
        assertEquals(3136, choose(8, 3) * choose(8, 3), "and at pool 8");
    }

    private static int choose(int n, int k){
        int out = 1;
        for(int i = 0; i < k; i++){
            out = out * (n - i) / (i + 1);
        }
        return out;
    }
}
