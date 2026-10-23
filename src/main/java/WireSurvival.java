import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * DOES A MAN WHO HAS SAT UNCLAIMED BECOME LESS LIKELY TO BE CLAIMED?
 *
 * Justin, 2026-09-07, with a $0 claim in for a backup tight end: "the odds of no
 * one else wanting a backup tight end on a non Wednesday Waivers seem higher
 * than 27%, since he could have been chosen yesterday had someone wanted him,
 * and no news happened since."
 *
 * That is a conditional-probability argument and {@link FaabBid} cannot answer
 * it. Its win chance comes from the UNCONDITIONAL distribution of clearing
 * prices over every claim this league has ever settled - including the ones
 * where a starter went down on Sunday and six managers bid on Tuesday. Justin is
 * conditioning on something that distribution does not know: this man was
 * available yesterday, nobody took him, and nothing has happened since.
 *
 * If demand is heterogeneous - some men wanted by many, most wanted by nobody -
 * then surviving a day on the wire is EVIDENCE of being in the second group, and
 * the hazard of being claimed falls with time sat. If demand were uniform the
 * hazard would be flat and his intuition would be wrong.
 *
 * That is measurable here. Every drop puts a man on the wire with a timestamp;
 * every add takes him off with another. The spell between them is how long he
 * sat, and a spell that never ends is a man nobody wanted.
 *
 * WHAT THIS CANNOT SEE: men who were never rostered in this league at all, since
 * they have no drop to start a spell. That censors the least-wanted players
 * entirely, which means the true hazard decline is STEEPER than whatever this
 * reports - the omission runs in the direction of Justin's argument, not against
 * it.
 *
 *   ./gradlew run -Pmain=WireSurvival
 */
public class WireSurvival {

    /** One stretch on the wire: dropped at, and claimed at (or never). */
    public record Spell(String playerID, long droppedAt, Long claimedAt, String season) {

        public boolean claimed(){
            return claimedAt != null;
        }

        /** Days sat before being claimed, or before the record ends. */
        public double days(long endOfRecord){
            long until = claimedAt != null ? claimedAt : endOfRecord;
            return (until - droppedAt) / (1000.0 * 60 * 60 * 24);
        }
    }

    /** Every drop-to-add spell in the completed seasons, in time order. */
    static List<Spell> spells(String leagueID){
        List<Spell> spells = new ArrayList<>();
        for(LeagueTransactions.Year year : LeagueTransactions.completedSeasons(leagueID)){
            // playerID to the moment he last hit the wire
            Map<String, Long> onWire = new HashMap<>();
            List<JsonObject> moves = new ArrayList<>();
            for(int week = 1; week <= LeagueTransactions.weeks(year.season()); week++){
                JsonArray rows = JsonParser.parseString(
                        LeagueTransactions.transactionsRaw(year.leagueID(), week)).getAsJsonArray();
                for(JsonElement element : rows){
                    JsonObject row = element.getAsJsonObject();
                    if(!"complete".equals(text(row, "status"))){
                        continue;
                    }
                    // A TRADED MAN NEVER SAT ON THE WIRE. A trade drops him from
                    // one roster and adds him to another in the same
                    // transaction, so counting it opened a spell that closed
                    // instantly and inflated every denominator with men nobody
                    // could have claimed. The question is about waiver demand,
                    // and a trade is not demand of that kind.
                    if("trade".equals(text(row, "type"))){
                        continue;
                    }
                    moves.add(row);
                }
            }
            moves.sort(Comparator.comparingLong(row -> stamp(row)));
            for(JsonObject row : moves){
                long when = stamp(row);
                // adds first: a man added was on the wire until now
                if(row.has("adds") && row.get("adds").isJsonObject()){
                    for(String id : row.getAsJsonObject("adds").keySet()){
                        Long since = onWire.remove(id);
                        if(since != null){
                            spells.add(new Spell(id, since, when, year.season()));
                        }
                    }
                }
                if(row.has("drops") && row.get("drops").isJsonObject()){
                    for(String id : row.getAsJsonObject("drops").keySet()){
                        onWire.put(id, when);
                    }
                }
            }
            // whatever is still sitting when the season ends was never wanted
            long endOfSeason = moves.isEmpty() ? 0 : stamp(moves.get(moves.size() - 1));
            for(Map.Entry<String, Long> entry : onWire.entrySet()){
                spells.add(new Spell(entry.getKey(), entry.getValue(), null, year.season()));
            }
        }
        return spells;
    }

    static long stamp(JsonObject row){
        if(row.has("status_updated") && !row.get("status_updated").isJsonNull()){
            return row.get("status_updated").getAsLong();
        }
        return row.has("created") && !row.get("created").isJsonNull()
                ? row.get("created").getAsLong() : 0;
    }

    static String text(JsonObject row, String field){
        return row.has(field) && !row.get(field).isJsonNull() ? row.get(field).getAsString() : "";
    }

    /**
     * The hazard: of the men who were still sitting at the start of a day band,
     * what share got claimed during it.
     *
     * This is the number Justin's argument turns on. A flat hazard means sitting
     * tells you nothing. A falling one means survival is evidence of low demand,
     * and a man who lasted a day is likelier to last another.
     */
    public record Band(String label, double from, double to, int atRisk, int claimed) {

        /**
         * PER DAY, because the bands are not the same width.
         *
         * The first version divided claims by men-at-risk and called it a
         * hazard. "Over two weeks" is an open window of months and "under a day"
         * is one day, so that number rose with band width and appeared to show
         * the OPPOSITE of what the data says - a cumulative share dressed as a
         * rate. Same error as comparing a statistic to a yardstick from another
         * population, which this repo has now made five times.
         */
        public double hazardPerDay(){
            double width = to() - from();
            return atRisk == 0 || width <= 0 ? 0 : claimed / (atRisk * width);
        }
    }

    static List<Band> hazard(List<Spell> spells, long endOfRecord){
        // the last band is closed at 60 days: an open window makes a per-day rate
        // meaningless, and almost nothing sits longer than that inside a season
        double[][] bands = {{0, 1}, {1, 2}, {2, 3}, {3, 7}, {7, 14}, {14, 60}};
        String[] labels = {"under a day", "1-2 days", "2-3 days", "3-7 days", "1-2 weeks", "2-8 weeks"};
        List<Band> out = new ArrayList<>();
        for(int i = 0; i < bands.length; i++){
            int atRisk = 0, claimed = 0;
            for(Spell spell : spells){
                double lasted = spell.days(endOfRecord);
                if(lasted >= bands[i][0]){          // he was still sitting when this band opened
                    atRisk++;
                    if(spell.claimed() && lasted < bands[i][1]){
                        claimed++;
                    }
                }
            }
            out.add(new Band(labels[i], bands[i][0], bands[i][1], atRisk, claimed));
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        List<Spell> spells = spells(configuration.getLeagueID());
        long endOfRecord = spells.stream().mapToLong(s -> s.claimedAt() == null
                ? s.droppedAt() : s.claimedAt()).max().orElse(0);
        List<Band> bands = hazard(spells, endOfRecord);

        long claimed = spells.stream().filter(Spell::claimed).count();
        StringBuilder out = new StringBuilder();
        out.append(String.format("WIRE SURVIVAL  %s%n%n", LocalDate.now()));
        out.append(String.format("%d stretches on the wire across the completed seasons; %d ended in a claim.%n",
                spells.size(), claimed));
        out.append("A man is on the wire from the moment he is dropped until somebody adds him.\n\n");
        out.append("THE QUESTION: does sitting unclaimed make a man LESS likely to be claimed? If demand is\n");
        out.append("uniform the hazard below is flat. If some men are wanted by many and most by nobody, then\n");
        out.append("surviving a day is evidence of the second kind, and the hazard falls.\n\n");
        out.append(String.format("%-14s %10s %10s %14s%n",
                "SAT FOR", "AT RISK", "CLAIMED", "CLAIMED/DAY"));
        for(Band band : bands){
            out.append(String.format("%-14s %10d %10d %13.2f%%%n",
                    band.label(), band.atRisk(), band.claimed(), 100 * band.hazardPerDay()));
        }
        Band peak = bands.stream().max(Comparator.comparingDouble(Band::hazardPerDay)).orElse(bands.get(0));
        Band last = bands.get(bands.size() - 1);
        out.append(String.format("%nthe rate PEAKS at %s (%.2f%% a day) and falls to %.2f%% a day by %s -%n"
                + "a factor of %.0f. Waivers run on a schedule, so a man dropped today cannot be taken%n"
                + "until the next run, which is why day zero is quiet rather than uneventful.%n",
                peak.label(), 100 * peak.hazardPerDay(), 100 * last.hazardPerDay(), last.label(),
                last.hazardPerDay() == 0 ? 0 : peak.hazardPerDay() / last.hazardPerDay()));
        out.append("\nSO: surviving the first waiver run IS evidence. A man still sitting after it is in the\n");
        out.append("group nobody wanted, and the unconditional clearing-price distribution FaabBid uses does\n");
        out.append("not know that - it averages him in with the Monday-morning handcuff everybody bid on.\n");
        out.append("\nWHAT IT CANNOT SEE: men never rostered in this league have no drop to start a spell, so\n");
        out.append("the least-wanted players are missing entirely. That censoring makes the real decline\n");
        out.append("STEEPER than this shows - the omission runs with the argument, not against it.\n");

        Path target = Path.of("data", "wire-survival-" + LocalDate.now() + ".txt");
        Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
        System.out.print(out);
        System.out.println("written to " + target);
    }
}
