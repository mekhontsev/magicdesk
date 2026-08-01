package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.LinkedHashSet;

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

}
