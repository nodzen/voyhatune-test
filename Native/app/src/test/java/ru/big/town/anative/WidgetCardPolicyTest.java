package ru.big.town.anative;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class WidgetCardPolicyTest {
    @Test public void parsesOrderAndAppDpi() {
        List<WidgetCardPolicy.Card> cards = WidgetCardPolicy.parse(
                "widgets-v1|trip|music|app:app.organicmaps:240|car");
        assertEquals(4, cards.size());
        assertEquals("app.organicmaps", cards.get(2).packageName);
        assertEquals(240, cards.get(2).dpi);
    }

    @Test public void invalidSchemaFallsBackToOemMusic() {
        assertEquals(WidgetCardPolicy.MUSIC, WidgetCardPolicy.parse("broken").get(0).kind);
    }

    @Test public void navigationWraps() {
        assertEquals(0, WidgetCardPolicy.nextIndex(2, 1, 3));
        assertEquals(2, WidgetCardPolicy.nextIndex(0, -1, 3));
    }
}
