package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import org.junit.Test;

public final class DesktopInputRoutingOwnershipTest {
    @Test
    public void parsesRuntimeAndUniqueIdAssociations() throws Exception {
        final String dump =
                "Input Manager State:\n"
                        + "  Runtime Associations:\n"
                        + "    port: keyboard-port  display: 21\n"
                        + "    port: mouse port  display: 21\n"
                        + "  Unique Id Associations:\n"
                        + "    port: wifi keyboard  uniqueId: wifi:01:02\n"
                        + "  Type Associations:\n"
                        + "    port: unrelated  type: touchNavigation\n"
                        + "  Gesture Monitors (implemented as spy windows):\n"
                        + "    port: unrelated  display: 0\n"
                        + "Event Hub State:\n";

        assertEquals(
                new LinkedHashSet<>(Arrays.asList(
                        "keyboard-port", "mouse port", "wifi keyboard")),
                FrameworkInputRoutingSnapshot.parse(dump).labels().keySet());

        final Map<String, String> expected = new LinkedHashMap<>();
        expected.put("keyboard-port", "display:21");
        expected.put("mouse port", "display:21");
        expected.put("wifi keyboard", "uniqueId:wifi:01:02");
        assertEquals(
                expected,
                FrameworkInputRoutingSnapshot.parse(dump).labels());
    }

    @Test public void journalRestoresBothMapsOnlyWithinTheSameBoot() throws Exception {
        final var original = new InputRoutingLease.Entry("virtual:7", "local:0", 21);
        final String encoded = DesktopInputRoutingOwnership.encode(Map.of("usb", original), "boot-a");
        final var restored = DesktopInputRoutingOwnership.decode(encoded, "boot-a").get("usb");
        assertEquals(original.target, restored.target);
        assertEquals(original.previousUniqueId, restored.previousUniqueId);
        assertEquals(original.previousDisplayPort, restored.previousDisplayPort);
        assertEquals(Map.of(), DesktopInputRoutingOwnership.decode(encoded, "boot-b"));
    }

    @Test(expected = java.io.IOException.class)
    public void absentInputSnapshotIsUnknownNotEmptyRoutes() throws Exception {
        FrameworkInputRoutingSnapshot.parse("Permission denied");
    }

    @Test(expected = java.io.IOException.class)
    public void incompleteManagerSnapshotIsUnknown() throws Exception {
        FrameworkInputRoutingSnapshot.parse("Input Manager State:\n");
    }

    @Test(expected = java.io.IOException.class)
    public void malformedExistingPortCannotBeOverwrittenAsAnEmptyRoute() throws Exception {
        FrameworkInputRoutingSnapshot.parse("Input Manager State:\nRuntime Associations:\n"
                + "port: keyboard display: unknown\nEvent Hub State:\n");
    }
}
