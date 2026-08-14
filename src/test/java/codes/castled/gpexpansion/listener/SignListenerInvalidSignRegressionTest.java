package codes.castled.gpexpansion.listener;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class SignListenerInvalidSignRegressionTest {

    @Test
    void validationFailuresNeverReplacePlayerSignText() throws IOException {
        String source = Files.readString(Path.of(
            "src/main/java/codes/castled/gpexpansion/listener/SignListener.java"));

        assertFalse(source.contains("formatInvalidSign(event"),
            "Validation failures must be reported in chat without rewriting the sign");
        assertFalse(source.contains("[Invalid Rental]"));
        assertFalse(source.contains("[Invalid Listing]"));
    }
}
