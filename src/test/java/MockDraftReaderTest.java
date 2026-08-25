import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MockDraftReaderTest {

    @Test
    void acceptsABareId(){
        Assertions.assertEquals("1398034370945662976",
                MockDraftReader.extractDraftId("1398034370945662976"));
    }

    @Test
    void acceptsAPastedUrlWithQueryNoise(){
        Assertions.assertEquals("1398034370945662976",
                MockDraftReader.extractDraftId("https://sleeper.com/draft/nfl/1398034370945662976?ftue=commish"));
    }

    @Test
    void rejectsInputWithNoId(){
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> MockDraftReader.extractDraftId("https://sleeper.com/draft/nfl/"));
    }
}
