package ru.big.town.anative;

import android.util.Log;

/** Strict conversion between editable hexadecimal commands and binary CAN frames. */
final class CanFrameCodec {
    private CanFrameCodec() {}

    static String toHex(byte[] data) {
        StringBuilder result = new StringBuilder();
        if (data == null) return "";
        for (byte value : data) result.append(String.format("%02X ", value));
        return result.toString();
    }

    static byte[] parse(String value) {
        String compact = value == null ? "" : value.replace(" ", "");
        if ((compact.length() & 1) != 0) {
            throw new IllegalArgumentException("hexBinary needs to be even-length: " + compact);
        }
        byte[] result = new byte[compact.length() / 2];
        for (int i = 0; i < compact.length(); i += 2) {
            int high = Character.digit(compact.charAt(i), 16);
            int low = Character.digit(compact.charAt(i + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("contains illegal character for hexBinary: " + compact);
            }
            result[i / 2] = (byte) ((high << 4) | low);
        }
        return result;
    }

    static byte[][] parseAll(String[] commands) {
        if (commands == null) {
            Log.w("$$$ CanFrameCodec $$$", "commands=null; returning an empty frame set");
            return new byte[0][];
        }
        byte[][] result = new byte[commands.length][];
        for (int i = 0; i < commands.length; i++) result[i] = parse(commands[i]);
        return result;
    }
}
