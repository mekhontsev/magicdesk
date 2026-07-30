package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

public final class ConsoleInputDeviceDiscoveryTest {
    @Test
    public void findsOnlyExternalCursorEventNodes() throws Exception {
        final String dump =
                "Input Manager State:\n"
                        + "Event Hub State:\n"
                        + "  Devices:\n"
                        + "    15: MagicDesk Shizuku Mouse\n"
                        + "      Classes: CURSOR\n"
                        + "      Path: /dev/input/event13\n"
                        + "      Location: virtual\n"
                        + "      Identifier: bus=0x0006, vendor=0x4d44, "
                        + "product=0x0001, version=0x0001\n"
                        + "    13: ProtoArc Mouse\n"
                        + "      Classes: CURSOR | EXTERNAL\n"
                        + "      Path: /dev/input/event12\n"
                        + "      Location: dc:f0:90:67:42:3d\n"
                        + "      Identifier: bus=0x0005, vendor=0x3554, "
                        + "product=0xf605, version=0x0101\n"
                        + "Input Reader State (Nums of device: 2):\n";

        final List<ConsoleMouseDevice> mice =
                ConsoleInputDeviceDiscovery.findMice(dump);

        assertEquals(1, mice.size());
        assertEquals("/dev/input/event12", mice.get(0).path);
        assertEquals("dc:f0:90:67:42:3d", mice.get(0).location);
        assertEquals(0x3554, mice.get(0).vendorId);
        assertEquals(0xf605, mice.get(0).productId);
    }
}
