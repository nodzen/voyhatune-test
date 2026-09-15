package ru.big.town.anative;

/** Minimal, name-stable JNI boundary for the vendor CAN HAL. */
final class NativeCanBridge {
    static {
        System.loadLibrary("anative");
    }

    private NativeCanBridge() {}

    static native int send(int commandNumber, byte[] frame);
}
