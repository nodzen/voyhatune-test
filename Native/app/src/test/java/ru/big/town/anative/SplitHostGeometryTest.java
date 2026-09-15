package ru.big.town.anative;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SplitHostGeometryTest {
    @Test public void presetFractionsMatchSavedRatioContract() {
        assertEquals(3f / 7f, SplitHostGeometry.presetFraction(0), 0.0001f);
        assertEquals(0.5f, SplitHostGeometry.presetFraction(1), 0.0001f);
        assertEquals(4f / 7f, SplitHostGeometry.presetFraction(2), 0.0001f);
        assertEquals(5f / 7f, SplitHostGeometry.presetFraction(3), 0.0001f);
        assertEquals(2f / 7f, SplitHostGeometry.presetFraction(4), 0.0001f);
    }

    @Test public void clampUsesEachVirtualDisplayDpi() {
        assertEquals(0.325f,
                SplitHostGeometry.clampFraction(0.1f, 1_000, 200, 320, 260f), 0.0001f);
        assertEquals(0.48f,
                SplitHostGeometry.clampFraction(0.9f, 1_000, 200, 320, 260f), 0.0001f);
    }

    @Test public void impossibleMinimumsStillLeaveTenPercentMovement() {
        assertEquals(0.45f,
                SplitHostGeometry.clampFraction(0f, 500, 320, 320, 260f), 0.0001f);
        assertEquals(0.55f,
                SplitHostGeometry.clampFraction(1f, 500, 320, 320, 260f), 0.0001f);
    }

    @Test public void invalidInputsAreSafe() {
        assertEquals(0.5f,
                SplitHostGeometry.clampFraction(Float.NaN, 0, 213, 213, 260f), 0.0001f);
    }
}
