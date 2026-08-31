package ru.big.town.anative;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DoorMediaPolicyTest {
    @Test
    public void driverModeOnlyUsesFrontLeftDoor() {
        int previous = DoorMediaPolicy.openMask(0, 0, 0, 0);
        int passengerOpen = DoorMediaPolicy.openMask(0, 1, 0, 0);

        assertFalse(DoorMediaPolicy.triggerOnOpen(previous, passengerOpen, false));
        assertTrue(DoorMediaPolicy.triggerOnOpen(previous, passengerOpen, true));
    }

    @Test
    public void anyDoorResumeWaitsUntilAllDoorsAreKnownClosed() {
        int open = DoorMediaPolicy.openMask(0, 1, 0, 0);
        int closed = DoorMediaPolicy.openMask(0, 0, 0, 0);
        int allKnown = DoorMediaPolicy.knownMask(0, 0, 0, 0);
        int rearUnknown = DoorMediaPolicy.knownMask(0, 0, -1, 0);

        assertFalse(DoorMediaPolicy.readyToResume(open, allKnown, true));
        assertTrue(DoorMediaPolicy.readyToResume(closed, allKnown, true));
        assertFalse(DoorMediaPolicy.readyToResume(closed, rearUnknown, true));
    }

    @Test
    public void unknownPreviousStateStillTriggersAnOpenEdge() {
        int driverOpen = DoorMediaPolicy.openMask(1, -1, -1, -1);
        assertTrue(DoorMediaPolicy.triggerOnOpen(0, driverOpen, false));
    }
}
