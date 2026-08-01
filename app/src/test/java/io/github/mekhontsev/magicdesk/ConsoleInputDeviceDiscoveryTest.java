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
                        + "      Classes: CURSOR | EXTERNAL\n"
                        + "      Path: /dev/input/event13\n"
                        + "      Location: magicdesk-shizuku-mouse\n"
                        + "      Identifier: bus=0x0005, vendor=0x4d44, "
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
        final List<ConsoleMouseDevice> routable =
                ConsoleInputDeviceDiscovery.findRoutableMice(dump);

        assertEquals(1, mice.size());
        assertEquals("/dev/input/event12", mice.get(0).path);
        assertEquals("dc:f0:90:67:42:3d", mice.get(0).location);
        assertEquals(0x3554, mice.get(0).vendorId);
        assertEquals(0xf605, mice.get(0).productId);
        assertEquals(2, routable.size());
        assertEquals(
                "magicdesk-shizuku-mouse",
                routable.get(0).location);
    }

    @Test
    public void separatesPhysicalAndVirtualRoutableKeyboards()
            throws Exception {
        final String dump =
                "Input Manager State:\n"
                        + "Event Hub State:\n"
                        + "  Devices:\n"
                        + "    18: MagicDesk Shizuku Keyboard 0\n"
                        + "      Classes: KEYBOARD | ALPHAKEY | EXTERNAL\n"
                        + "      Path: /dev/input/event18\n"
                        + "      Location: magicdesk-shizuku-keyboard-0\n"
                        + "      Identifier: bus=0x0005, vendor=0x3554, "
                        + "product=0xf603, version=0x0101\n"
                        + "    11: ProtoArc Keyboard\n"
                        + "      Classes: KEYBOARD | ALPHAKEY | EXTERNAL\n"
                        + "      Path: /dev/input/event10\n"
                        + "      Location: dc:f0:90:67:42:3d\n"
                        + "      Identifier: bus=0x0005, vendor=0x3554, "
                        + "product=0xf603, version=0x0101\n"
                        + "Input Reader State (Nums of device: 2):\n";

        final List<ConsoleKeyboardDevice> physical =
                ConsoleInputDeviceDiscovery.findKeyboards(dump);
        final List<ConsoleKeyboardDevice> routable =
                ConsoleInputDeviceDiscovery.findRoutableKeyboards(dump);

        assertEquals(1, physical.size());
        assertEquals("/dev/input/event10", physical.get(0).path);
        assertEquals(2, routable.size());
        assertEquals(
                "magicdesk-shizuku-keyboard-0",
                routable.get(0).location);
    }
}
