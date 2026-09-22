package ru.big.town.anative;

import org.junit.Test;
import static org.junit.Assert.*;

public class LaunchGenerationTest {
    @Test public void onlyLatestCommandCanCommit() {
        LaunchGeneration gate = new LaunchGeneration();
        long first = gate.next();
        long second = gate.next();
        assertFalse(gate.accepts(first));
        assertTrue(gate.accepts(second));
        gate.cancel();
        assertFalse(gate.accepts(second));
    }
}
