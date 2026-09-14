package ru.big.town.anative;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LightSensorParkingPolicyTest {

    @Test
    public void enabledParkingSwitchAlwaysCommandsOffOnParkingTransition() {
        // The rule intentionally has no "last automatic target" input. The BCM or a manual
        // command can have turned lights on after the previous automatic OFF command.
        assertTrue(ParkingHeadlightPolicy.shouldTurnOff(true, 0));
    }

    @Test
    public void parkingSwitchDoesNotAffectOtherGearsOrDisabledState() {
        assertFalse(ParkingHeadlightPolicy.shouldTurnOff(false, 0));
        assertFalse(ParkingHeadlightPolicy.shouldTurnOff(true, 3));
    }
}
