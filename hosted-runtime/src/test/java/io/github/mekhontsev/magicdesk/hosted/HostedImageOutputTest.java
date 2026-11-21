package io.github.mekhontsev.magicdesk.hosted;

import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import org.junit.Test;

public final class HostedImageOutputTest {
    @Test public void paddedRowsAndCropsRetainExactRgbaChannels() throws Exception {
        var pixels = ByteBuffer.allocate(32 + 3 * 8);
        pixels.put(32 + 8, (byte) 0x12).put(32 + 9, (byte) 0x34).put(32 + 10, (byte) 0x56).put(32 + 11, (byte) 0x78);
        pixels.put(32 + 16, (byte) 0xff).put(32 + 17, (byte) 0x80).put(32 + 18, (byte) 0x01).put(32 + 19, (byte) 0xff);
        pixels.limit(52);
        assertArrayEquals(new int[]{0x78123456, 0xffff8001}, HostedImageOutput.colors(pixels, 32, 8, 1, 1, 2, 1));
        assertEquals(0, pixels.position());
        assertThrows(java.io.IOException.class, () -> HostedImageOutput.colors(pixels, 32, 3, 0, 0, 1, 1));
    }
    @Test public void outputBudgetIsValidatedBeforeAllocating() {
        HostedImageOutput.validateSize(3840, 2160);
        assertThrows(IllegalArgumentException.class, () -> HostedImageOutput.validateSize(0, 1));
        assertThrows(IllegalArgumentException.class, () -> HostedImageOutput.validateSize(8193, 1));
        assertThrows(IllegalArgumentException.class, () -> HostedImageOutput.validateSize(8192, 8192));
    }
}
