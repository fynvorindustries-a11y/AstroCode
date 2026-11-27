package com.averolauncher.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** JavaFX CSS rejects CSS3 angle gradients, which silently drops the whole declaration (panels lose their background). */
class StylesheetSanityTest {

    private static final Pattern ANGLE = Pattern.compile("(linear|radial)-gradient\\(\\s*-?[0-9.]+deg");

    @Test
    void noStylesheetUsesUnsupportedAngleGradients() throws IOException {
        Path dir = Path.of("src/main/resources/css");
        assertTrue(Files.isDirectory(dir), "run tests from the project root");
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".css")).toList()) {
                String css = Files.readString(f);
                assertFalse(ANGLE.matcher(css).find(), f.getFileName() + " uses an angle gradient JavaFX cannot parse");
            }
        }
    }
}
