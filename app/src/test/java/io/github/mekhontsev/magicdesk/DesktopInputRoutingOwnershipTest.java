package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.LinkedHashSet;

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
                        + "    port: unrelated  display: 0\n";

        assertEquals(
                new LinkedHashSet<>(Arrays.asList(
                        "keyboard-port", "mouse port", "wifi keyboard")),
                DesktopInputRoutingOwnership
                        .findActiveAssociations(dump));
    }

}
