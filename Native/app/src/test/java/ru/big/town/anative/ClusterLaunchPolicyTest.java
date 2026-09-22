package ru.big.town.anative;

import org.junit.Test;
import static org.junit.Assert.*;

public class ClusterLaunchPolicyTest {
    @Test public void allowlistIsNormalizedAndInvalidEntriesAreDropped() {
        assertEquals(2, ClusterLaunchPolicy.parseAllowlist(
                "ru.yandex.yandexnavi, BAD PACKAGE,ru.yandex.yandexnavi,app.organicmaps").size());
    }

    @Test public void typedActionIsStrict() {
        assertEquals("app.organicmaps", ClusterLaunchPolicy.packageFromAction(
                "cluster_app:app.organicmaps"));
        assertNull(ClusterLaunchPolicy.packageFromAction("app:app.organicmaps"));
        assertNull(ClusterLaunchPolicy.packageFromAction("cluster_app:Bad Package"));
    }

    @Test public void gestureRequiresEverySafetyGate() {
        assertTrue(ClusterLaunchPolicy.shouldInterceptGesture(true, 0, true,
                "app.organicmaps", "app.organicmaps"));
        assertFalse(ClusterLaunchPolicy.shouldInterceptGesture(true, 1, true,
                "app.organicmaps", "app.organicmaps"));
        assertFalse(ClusterLaunchPolicy.shouldInterceptGesture(true, 0, false,
                "app.organicmaps", "app.organicmaps"));
    }
}
