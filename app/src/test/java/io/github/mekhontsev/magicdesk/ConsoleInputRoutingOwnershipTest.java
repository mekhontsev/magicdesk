package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Test;

public final class ConsoleInputRoutingOwnershipTest {
    @Test
    public void parsesOnlyRuntimeAssociations() throws Exception {
        final String dump =
                "Input Manager State:\n"
                        + "  Runtime Associations:\n"
                        + "    port: keyboard-port  display: 21\n"
                        + "    port: mouse port  display: 21\n"
                        + "  Gesture Monitors (implemented as spy windows):\n"
                        + "    port: unrelated  display: 0\n";

        assertEquals(
                new LinkedHashSet<>(Arrays.asList(
                        "keyboard-port", "mouse port")),
                ConsoleInputRoutingOwnership
                        .findRuntimeAssociations(dump));
    }

    @Test
    public void legacyCleanupRequiresMagicDeskMarker()
            throws Exception {
        final String dump = inputDump(
                "    port: dc:f0:90:67:42:3d  display: 21\n");

        assertTrue(ConsoleInputRoutingOwnership
                .findLegacyOwnedPorts(dump).isEmpty());
    }

    @Test
    public void legacyCleanupFindsMagicDeskPhysicalPorts()
            throws Exception {
        final String dump = inputDump(
                "    port: dc:f0:90:67:42:3d  display: 21\n"
                        + "    port: magicdesk-shizuku-keyboard"
                        + "  display: 21\n");

        final Set<String> expected = new LinkedHashSet<>(
                Arrays.asList(
                        "magicdesk-shizuku-keyboard",
                        "dc:f0:90:67:42:3d"));
        assertEquals(
                expected,
                ConsoleInputRoutingOwnership
                        .findLegacyOwnedPorts(dump));
    }

    @Test
    public void legacyCleanupRecognizesIndexedVirtualKeyboardPorts()
            throws Exception {
        final String dump = inputDump(
                "    port: dc:f0:90:67:42:3d  display: 21\n"
                        + "    port: magicdesk-shizuku-keyboard-0"
                        + "  display: 21\n"
                        + "    port: magicdesk-shizuku-keyboard-1"
                        + "  display: 21\n");

        final Set<String> expected = new LinkedHashSet<>(
                Arrays.asList(
                        "dc:f0:90:67:42:3d",
                        "magicdesk-shizuku-keyboard-0",
                        "magicdesk-shizuku-keyboard-1"));
        assertEquals(
                expected,
                ConsoleInputRoutingOwnership
                        .findLegacyOwnedPorts(dump));
    }

    private static String inputDump(
            final String runtimeAssociations) {
        return "Input Manager State:\n"
                + "Event Hub State:\n"
                + "  Devices:\n"
                + "    18: MagicDesk Shizuku Keyboard\n"
                + "      Classes: KEYBOARD | ALPHAKEY | EXTERNAL\n"
                + "      Path: /dev/input/event18\n"
                + "      Location: magicdesk-shizuku-keyboard\n"
                + "      Identifier: bus=0x0005, vendor=0x3554, "
                + "product=0xf603, version=0x0101\n"
                + "    13: ProtoArc Mouse\n"
                + "      Classes: CURSOR | EXTERNAL\n"
                + "      Path: /dev/input/event12\n"
                + "      Location: dc:f0:90:67:42:3d\n"
                + "      Identifier: bus=0x0005, vendor=0x3554, "
                + "product=0xf605, version=0x0101\n"
                + "    11: ProtoArc Keyboard\n"
                + "      Classes: KEYBOARD | ALPHAKEY | EXTERNAL\n"
                + "      Path: /dev/input/event10\n"
                + "      Location: dc:f0:90:67:42:3d\n"
                + "      Identifier: bus=0x0005, vendor=0x3554, "
                + "product=0xf603, version=0x0101\n"
                + "Input Reader State (Nums of device: 3):\n"
                + "  Runtime Associations:\n"
                + runtimeAssociations
                + "  Gesture Monitors (implemented as spy windows):\n";
    }
}
