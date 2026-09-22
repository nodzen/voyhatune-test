package ru.big.town.restoremode;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class CustomWidgetStoreTest {
    @Test public void cardConfigurationRoundTripsInOrder() {
        String value = CustomWidgetStore.encode(Arrays.asList(
                new CustomWidgetStore.Card("trip"),
                new CustomWidgetStore.Card("app", "app.organicmaps", 240),
                new CustomWidgetStore.Card("music")));
        assertEquals("widgets-v1|trip|app:app.organicmaps:240|music", value);
        assertEquals("app.organicmaps", CustomWidgetStore.decode(value).get(1).packageName);
    }

    @Test public void invalidConfigurationFallsBackToMusic() {
        assertEquals("music", CustomWidgetStore.decode("unknown").get(0).kind);
    }
}
