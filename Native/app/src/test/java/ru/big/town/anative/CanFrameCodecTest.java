package ru.big.town.anative;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CanFrameCodecTest {
    @Test public void parsesWhitespaceAndMixedCase() {
        assertArrayEquals(new byte[]{0x1f, 0x08, (byte) 0xFF},
                CanFrameCodec.parse("1f 08 Ff"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOddLength() {
        CanFrameCodec.parse("123");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonHex() {
        CanFrameCodec.parse("0x");
    }

    @Test public void formatsUnsignedBytes() {
        assertEquals("00 FF 10 ", CanFrameCodec.toHex(new byte[]{0, -1, 16}));
    }
}
