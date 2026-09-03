import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Where the historical keeper structure gets its shape: the positional ADP
 * ranks of Justin's real keepers.
 *
 * Purdy and Tuten cost rounds 13 and 12, but cost is not what makes them the
 * right stand-ins for a historical season. A keeper is worth keeping precisely
 * because his price is nothing like his market value, so copying the PRICE onto
 * an old board hands 2015-me the 25th quarterback and answers a question nobody
 * asked. Copying the VALUE - the 9th quarterback and the 23rd running back on
 * the board - reproduces the thing that actually shapes the plan: a startable
 * quarterback and a flex-grade back already in hand.
 *
 * Read off the live board rather than hardcoded, so the ranks follow the market
 * as it drifts, and printed by every tool that uses them so a number can always
 * be traced back to the board that produced it.
 */
public class EraKeepers {

    /**
     * Measured 2026-08-30: Purdy QB9 (adp 86.9), Tuten RB23 (adp 53.0).
     *
     * Only a fallback for when the live board cannot be fetched. Kept so a
     * regime result is never silently computed against different keepers than
     * the run beside it.
     */
    static final int[] FALLBACK = {9, 23};

    private static int[] cached;

    /** {quarterback rank, running back rank} on the current board. */
    public static synchronized int[] ranks(){
        if(cached == null){
            cached = measure();
        }
        return cached;
    }

    static int[] measure(){
        try {
            String data = InOutUtilities.getTodaysWebPage(
                    FFCalculatorSD.getWebURLSerious(),
                    FFCalculatorSD.filepathStartSerious + FFCalculatorSD.getSeason());
            JsonObject board = JsonParser.parseString(data).getAsJsonObject();
            List<JsonObject> players = new ArrayList<>();
            for(JsonElement element : board.getAsJsonArray("players")){
                players.add(element.getAsJsonObject());
            }
            players.sort(Comparator.comparingDouble(p -> p.get("adp").getAsDouble()));
            String[] wanted = keeperNames();
            int[] ranks = FALLBACK.clone();
            for(String position : new String[]{"QB", "RB"}){
                int rank = 0;
                for(JsonObject player : players){
                    if(!player.get("position").getAsString().equals(position)){
                        continue;
                    }
                    rank++;
                    String name = player.get("name").getAsString();
                    for(String keeper : wanted){
                        if(name.toLowerCase().endsWith(keeper.toLowerCase())){
                            ranks[position.equals("QB") ? 0 : 1] = rank;
                        }
                    }
                }
            }
            return ranks;
        }
        catch(RuntimeException unreachable){
            return FALLBACK.clone();
        }
    }

    /** -Pkeepers=Tuten,Purdy, the same knob the planner takes. */
    static String[] keeperNames(){
        String declared = System.getProperty("keepers", "Tuten,Purdy");
        String[] names = declared.split(",");
        for(int i = 0; i < names.length; i++){
            names[i] = names[i].trim();
        }
        return names;
    }

    public static String describe(){
        int[] ranks = ranks();
        return String.format("keeper structure: QB%d and RB%d held from the start"
                + " (%s on the 2026 board), eleven live picks",
                ranks[0], ranks[1], String.join(" + ", keeperNames()));
    }
}
