package dev.phoneforai.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import org.junit.Test;

public final class ScreenshotOptionsTest {
    @Test public void usesProtocolDefaults() {
        ScreenshotOptions capture = ScreenshotOptions.forCommand("screen.capture", null, null);
        ScreenshotOptions action = ScreenshotOptions.forCommand("ui.global.back", null, null);
        assertEquals(1080, capture.maxWidth); assertEquals(72, capture.quality);
        assertEquals(720, action.maxWidth); assertEquals(60, action.quality);
    }
    @Test public void rejectsOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class,
                () -> ScreenshotOptions.forCommand("screen.capture", 539, 72));
        assertThrows(IllegalArgumentException.class,
                () -> ScreenshotOptions.forCommand("screen.capture", 1080, 91));
    }
}
