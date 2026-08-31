package ru.big.town.anative;

/** Android-free door matching policy for the media reactor. */
final class DoorMediaPolicy {
    static final int DRIVER_DOOR = 1;
    static final int FRONT_PASSENGER_DOOR = 1 << 1;
    static final int REAR_LEFT_DOOR = 1 << 2;
    static final int REAR_RIGHT_DOOR = 1 << 3;
    static final int ALL_DOORS = DRIVER_DOOR | FRONT_PASSENGER_DOOR
            | REAR_LEFT_DOOR | REAR_RIGHT_DOOR;

    private DoorMediaPolicy() {}

    static int openMask(int frontLeft, int frontRight, int rearLeft, int rearRight) {
        int mask = 0;
        if (frontLeft == 1) mask |= DRIVER_DOOR;
        if (frontRight == 1) mask |= FRONT_PASSENGER_DOOR;
        if (rearLeft == 1) mask |= REAR_LEFT_DOOR;
        if (rearRight == 1) mask |= REAR_RIGHT_DOOR;
        return mask;
    }

    static int knownMask(int frontLeft, int frontRight, int rearLeft, int rearRight) {
        int mask = 0;
        if (frontLeft >= 0) mask |= DRIVER_DOOR;
        if (frontRight >= 0) mask |= FRONT_PASSENGER_DOOR;
        if (rearLeft >= 0) mask |= REAR_LEFT_DOOR;
        if (rearRight >= 0) mask |= REAR_RIGHT_DOOR;
        return mask;
    }

    static boolean triggerOnOpen(int previousOpenMask, int currentOpenMask,
                                 boolean anyDoor) {
        int scope = anyDoor ? ALL_DOORS : DRIVER_DOOR;
        return (currentOpenMask & scope) != 0 && (previousOpenMask & scope) == 0;
    }

    /**
     * For the any-door mode, wait until every reported door is known and closed. This prevents an
     * unknown rear-door value from causing a premature resume while another door is still open.
     */
    static boolean readyToResume(int currentOpenMask, int currentKnownMask, boolean anyDoor) {
        int scope = anyDoor ? ALL_DOORS : DRIVER_DOOR;
        return (currentKnownMask & scope) == scope && (currentOpenMask & scope) == 0;
    }
}
