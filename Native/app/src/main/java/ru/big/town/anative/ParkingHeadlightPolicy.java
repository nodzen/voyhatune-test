package ru.big.town.anative;

/**
 * Parking-light rule is deliberately independent of auto-light state: a manual or BCM change
 * after the previous automatic decision must not suppress the next explicit parking command.
 */
final class ParkingHeadlightPolicy {
    static final int GEAR_PARKING = 0;

    private ParkingHeadlightPolicy() {}

    static boolean shouldTurnOff(boolean enabled, int gearValue) {
        return enabled && gearValue == GEAR_PARKING;
    }
}
