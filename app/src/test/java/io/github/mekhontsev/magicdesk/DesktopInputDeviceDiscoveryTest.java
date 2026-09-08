package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

public final class DesktopInputDeviceDiscoveryTest {
    @Test
    public void findsOnlyExternalCursorEventNodes() throws Exception {
        final String dump =
                "Input Manager State:\n"
                        + "Event Hub State:\n"
                        + "  Devices:\n"
                        + "    15: MagicDesk Mouse\n"
                        + "      Classes: CURSOR\n"
                        + "      Path: /dev/input/event13\n"
                        + "      Location: magicdesk-mouse\n"
                        + "      Identifier: bus=0x0005, vendor=0x4d44, "
                        + "product=0x0001, version=0x0001\n"
                        + "    13: ProtoArc Mouse\n"
                        + "      Classes: CURSOR | EXTERNAL\n"
                        + "      Path: /dev/input/event12\n"
                        + "      Location: dc:f0:90:67:42:3d\n"
                        + "      Identifier: bus=0x0005, vendor=0x3554, "
                        + "product=0xf605, version=0x0101\n"
                        + "Input Reader State (Nums of device: 2):\n";

        final List<DesktopMouseDevice> mice =
                DesktopInputDeviceDiscovery.findMice(dump);
        final List<DesktopMouseDevice> routable =
                DesktopInputDeviceDiscovery.findRoutableMice(dump);

        assertEquals(1, mice.size());
        assertEquals("/dev/input/event12", mice.get(0).path);
        assertEquals("dc:f0:90:67:42:3d", mice.get(0).location);
        assertEquals(0x3554, mice.get(0).vendorId);
        assertEquals(0xf605, mice.get(0).productId);
        assertEquals(2, routable.size());
        assertEquals(
                "magicdesk-mouse",
                routable.get(0).location);
    }

    @Test
    public void excludesInternalKeyboards()
            throws Exception {
        final String dump =
                "Input Manager State:\n"
                        + "Event Hub State:\n"
                        + "  Devices:\n"
                        + "    18: Internal Keyboard\n"
                        + "      Classes: KEYBOARD | ALPHAKEY\n"
                        + "      Path: /dev/input/event18\n"
                        + "      Location: internal-keyboard\n"
                        + "      Identifier: bus=0x0005, vendor=0x3554, "
                        + "product=0xf603, version=0x0101\n"
                        + "    11: ProtoArc Keyboard\n"
                        + "      Classes: KEYBOARD | ALPHAKEY | EXTERNAL\n"
                        + "      Path: /dev/input/event10\n"
                        + "      Location: dc:f0:90:67:42:3d\n"
                        + "      Identifier: bus=0x0005, vendor=0x3554, "
                        + "product=0xf603, version=0x0101\n"
                        + "Input Reader State (Nums of device: 2):\n";

        final List<DesktopKeyboardDevice> physical =
                DesktopInputDeviceDiscovery.findKeyboards(dump);
        assertEquals(1, physical.size());
        assertEquals("/dev/input/event10", physical.get(0).path);
    }

    @Test(expected = java.io.IOException.class)
    public void truncatedInventoryIsNotAnEmptyDeviceList() throws Exception {
        DesktopInputDeviceDiscovery.findKeyboards("Event Hub State:\n  Devices:\n");
    }
}
