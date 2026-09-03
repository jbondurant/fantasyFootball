import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.*;

/**
 * HOW FAR THIS LEAGUE DRIFTS FROM THE NATIONAL MARKET, BY POSITION.
 *
 * Justin, 2026-09-01: "my league historically disagrees with that market and
 * that should be part of the model."
 *
 * The simulated room picks from a choice set ordered by national ADP, and the
 * model's market signals - f0's ADP rank, f10's fall past ADP - are that same
 * national number. When a league systematically waits longer at a position than
 * the market does, every one of those signals is wrong for it in the same
 * direction, and no positional intercept can fix a signal that is mis-scaled
 * rather than mis-levelled.
 *
 * So measure the disagreement and correct the ADP with it. For every position,
 * over every stored draft: the pick a man really went at minus the pick the
 * market said. Positive means this league waits.
 *
 * This is not a rule about defences. It is one measurement per position, most
 * of them near zero, applied the same way to all of them - and it replaces the
 * floor, which said "never before round 10" without ever explaining why.
 */
public final class MarketDrift {

    private static Map<Position, Double> drift;

    /** Median drift as a RATIO, per position. Above 1 means the league waits. */
    public static synchronized Map<Position, Double> perPosition(){
        if(drift != null){
            return drift;
        }
        Map<Position, List<Double>> raw = measurePerPick();
        Map<Position, Double> out = new EnumMap<>(Position.class);
        for(Map.Entry<Position, List<Double>> entry : raw.entrySet()){
            List<Double> values = new ArrayList<>(entry.getValue());
            if(values.size() < 20){
                continue;               // too few to trust a positional shift
            }
            Collections.sort(values);
            out.put(entry.getKey(), values.get(values.size() / 2));
        }
        drift = out;
        return drift;
    }

    /**
     * Every observed drift, by position - KEEPER-CORRECTED and RELATIVE.
     *
     * Justin, on the first version: "the reaches are due to keepers, and they
     * can't be absolute values given reaches in early rounds are more
     * significant." Both true, and both fatal to what it measured.
     *
     * THE KEEPER ARTEFACT. Twenty-four of the best players never reach the
     * board. Everyone else's real pick number is therefore earlier than his
     * national ADP by construction - not because this league reaches, but
     * because two dozen better men are absent. Skipping the keepers' own picks,
     * which the first version did, does nothing about this: it is the BASELINE
     * that moves. The fix is to ask where a man was expected to go among the
     * players actually available, which is his rank on the board with the
     * keepers removed.
     *
     * THE UNIT. Twelve picks at pick 10 is a different animal from twelve picks
     * at pick 150. Drift is reported as a RATIO of where he really went to
     * where he was expected - 1.0 is the market, 2.0 is twice as long a wait -
     * so an early reach counts for what it is.
     */
    static Map<Position, List<Double>> measurePerPick(){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        Map<Position, List<Double>> raw = new EnumMap<>(Position.class);
        for(String season : configuration.getPreviousSeasons()){
            DraftBacktest.Season past;
            try {
                past = new DraftBacktest.Season(configuration, season);
            }
            catch(RuntimeException unavailable){
                continue;
            }
            // Who was really available: everyone the market ranked, less the
            // men somebody kept.
            List<Map.Entry<String, Double>> available = new ArrayList<>();
            for(Map.Entry<String, Double> entry : past.adp.entrySet()){
                if(entry.getValue() > 0 && !past.keptIDs.contains(entry.getKey())){
                    available.add(entry);
                }
            }
            available.sort(Map.Entry.comparingByValue());
            Map<String, Integer> expectedOrder = new HashMap<>();
            for(int i = 0; i < available.size(); i++){
                expectedOrder.put(available.get(i).getKey(), i + 1);
            }
            // Where each man really went, counted in LIVE picks - keeper slots
            // select nobody and must not advance the count.
            List<JsonObject> live = new ArrayList<>();
            for(JsonElement element : past.picks){
                JsonObject pick = element.getAsJsonObject();
                JsonElement keeper = pick.get("is_keeper");
                boolean kept = keeper != null && !keeper.isJsonNull()
                        && keeper.getAsBoolean();
                if(!kept && pick.has("player_id") && !pick.get("player_id").isJsonNull()
                        && pick.has("pick_no") && !pick.get("pick_no").isJsonNull()){
                    live.add(pick);
                }
            }
            live.sort(Comparator.comparingInt(o -> o.get("pick_no").getAsInt()));
            for(int i = 0; i < live.size(); i++){
                String id = live.get(i).get("player_id").getAsString();
                Player player = Player.getPlayerFromSIDV2(id);
                Integer expected = expectedOrder.get(id);
                if(player == null || expected == null || expected <= 0){
                    continue;
                }
                raw.computeIfAbsent(player.position, u -> new ArrayList<>())
                        .add((i + 1) / (double) expected);
            }
        }
        return raw;
    }

    /**
     * This league's own view of where a man goes: market ADP times its drift.
     *
     * MULTIPLICATIVE, because the drift is a ratio. Justin: "they can't be
     * absolute values given reaches in early rounds are more significant" - a
     * twelve-pick shift is enormous at pick 10 and nothing at pick 150, so a
     * constant offset mis-states both ends. A ratio scales with where the man
     * sits, which is the only form that can be right at both.
     */
    public static double leagueAdp(String sleeperID, double marketAdp){
        if(Boolean.getBoolean("noDrift")){
            return marketAdp;
        }
        Player player = Player.getPlayerFromSIDV2(sleeperID);
        Double ratio = player == null ? null : perPosition().get(player.position);
        return ratio == null ? marketAdp : marketAdp * ratio;
    }
}
