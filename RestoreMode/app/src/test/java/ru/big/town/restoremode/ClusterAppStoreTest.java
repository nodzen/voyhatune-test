package ru.big.town.restoremode;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class ClusterAppStoreTest {
    @Test public void codecKeepsValidUniqueOrder() {
        assertEquals("app.organicmaps,ru.yandex.yandexnavi", ClusterAppStore.encode(Arrays.asList(
                "app.organicmaps", "bad package", "app.organicmaps", "ru.yandex.yandexnavi")));
    }
}
