package ru.big.town.anative;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ClusterSurfaceGeometryTest {
    @Test public void h97cUsesOnlyTheVerifiedOemMediaCardBounds() {
        ClusterSurfaceGeometry.Bounds bounds = ClusterSurfaceGeometry.forDisplay(1920, 720);
        assertEquals(66, bounds.left);
        assertEquals(200, bounds.top);
        assertEquals(574, bounds.width);
        assertEquals(464, bounds.height);
        assertEquals(160, bounds.dpi);
    }

    @Test public void unknownGeometryFailsClosedInsteadOfCoveringTheCluster() {
        assertNull(ClusterSurfaceGeometry.forDisplay(1280, 480));
        assertNull(ClusterSurfaceGeometry.forDisplay(0, 0));
    }
}
