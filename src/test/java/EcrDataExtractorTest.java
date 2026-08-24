import PlayerImportAndSetup.EcrDataExtractor;
import PlayerImportAndSetup.Position;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Every FantasyPros ranking page is scraped by pulling a `var ecrData = {...}`
 * blob out of the HTML. The previous version sliced it with
 * split("\"players\":") and split("var sosData"), which returns a truncated
 * array the moment FantasyPros reorders their JSON keys - and truncated means
 * players silently vanish from the rankings rather than anything failing.
 */
class EcrDataExtractorTest {

    private static String page(String ecrData){
        return "<html><head><script>\n"
                + "var somethingElse = {\"decoy\": 1};\n"
                + "var ecrData = " + ecrData + ";\n"
                + "var sosData = {\"other\": true};\n"
                + "</script></html>";
    }

    @Test
    void pullsTheWholeObjectOutOfASurroundingPage(){
        JsonObject ecrData = EcrDataExtractor.extract(page(
                "{\"year\":\"2026\",\"players\":[{\"player_id\":1},{\"player_id\":2}]}"));

        Assertions.assertEquals("2026", ecrData.get("year").getAsString());
        Assertions.assertEquals(2, ecrData.getAsJsonArray("players").size());
    }

    @Test
    void keysAfterThePlayersArrayAreStillRead(){
        // The old slicing cut everything after "players", so any key ordered
        // behind it was lost.
        JsonObject ecrData = EcrDataExtractor.extract(page(
                "{\"players\":[{\"player_id\":1}],\"total_experts\":42}"));

        Assertions.assertEquals(42, ecrData.get("total_experts").getAsInt());
    }

    @Test
    void aBraceInsideAStringDoesNotEndTheObject(){
        JsonObject ecrData = EcrDataExtractor.extract(page(
                "{\"note\":\"a } brace and a { brace\",\"players\":[{\"player_id\":1}]}"));

        Assertions.assertEquals(1, ecrData.getAsJsonArray("players").size());
        Assertions.assertEquals("a } brace and a { brace", ecrData.get("note").getAsString());
    }

    @Test
    void anEscapedQuoteDoesNotEndTheString(){
        JsonObject ecrData = EcrDataExtractor.extract(page(
                "{\"note\":\"he said \\\"} \\\" loudly\",\"players\":[{\"player_id\":1}]}"));

        Assertions.assertEquals(1, ecrData.getAsJsonArray("players").size());
    }

    @Test
    void nestedObjectsAreKeptWhole(){
        JsonObject ecrData = EcrDataExtractor.extract(page(
                "{\"players\":[{\"player_id\":1,\"meta\":{\"deep\":{\"deeper\":true}}}]}"));

        Assertions.assertTrue(ecrData.getAsJsonArray("players").get(0).getAsJsonObject()
                .getAsJsonObject("meta").getAsJsonObject("deep").get("deeper").getAsBoolean());
    }

    @Test
    void aPageWithoutEcrDataSaysSoRatherThanThrowingAnIndexError(){
        RuntimeException thrown = Assertions.assertThrows(RuntimeException.class,
                () -> EcrDataExtractor.extract("<html>they redesigned the site</html>"));

        Assertions.assertTrue(thrown.getMessage().toLowerCase().contains("ecrdata"), thrown.getMessage());
    }

    @Test
    void aTruncatedBlobIsRejectedRatherThanHalfParsed(){
        Assertions.assertThrows(RuntimeException.class,
                () -> EcrDataExtractor.extract("var ecrData = {\"players\":[{\"player_id\":1}"));
    }

    @Test
    void parsesTheFieldsFantasyProsStillPublishes(){
        List<FantasyProsEcrData.Entry> entries = FantasyProsEcrData.parse(page(
                "{\"players\":["
                + "{\"player_id\":22968,\"player_name\":\"Jahmyr Gibbs\",\"player_team_id\":\"DET\","
                + "\"player_position_id\":\"RB\",\"rank_ecr\":1,\"rank_ave\":\"1.69\"},"
                + "{\"player_id\":8120,\"player_name\":\"Houston Texans\",\"player_team_id\":\"HOU\","
                + "\"player_position_id\":\"DST\",\"rank_ecr\":2,\"rank_ave\":\"\"}"
                + "]}"));

        Assertions.assertEquals(2, entries.size());
        Assertions.assertEquals("Jahmyr Gibbs", entries.get(0).playerName);
        Assertions.assertEquals(Position.RB, entries.get(0).position);
        Assertions.assertEquals(1, entries.get(0).rankEcr);
        Assertions.assertEquals(1.69, entries.get(0).rankAverage, 0.0001);

        // FantasyPros says DST where Sleeper says DEF.
        Assertions.assertEquals(Position.DEF, entries.get(1).position);
        // An unranked average is written as "", not omitted.
        Assertions.assertNull(entries.get(1).rankAverage);
    }

    @Test
    void aRowWithNoRankIsSkippedRatherThanScoredAsZero(){
        List<FantasyProsEcrData.Entry> entries = FantasyProsEcrData.parse(page(
                "{\"players\":["
                + "{\"player_id\":1,\"player_name\":\"Ranked Guy\",\"player_team_id\":\"DET\","
                + "\"player_position_id\":\"RB\",\"rank_ecr\":1},"
                + "{\"player_id\":2,\"player_name\":\"Unranked Guy\",\"player_team_id\":\"DET\","
                + "\"player_position_id\":\"RB\",\"rank_ecr\":null}"
                + "]}"));

        Assertions.assertEquals(1, entries.size());
        Assertions.assertEquals("Ranked Guy", entries.get(0).playerName);
    }

    @Test
    void aPageWithNoRankedPlayersFailsLoudly(){
        // Better than handing back an empty ranking that scores every roster 0.
        Assertions.assertThrows(RuntimeException.class,
                () -> FantasyProsEcrData.parse(page("{\"players\":[]}")));
    }
}
