package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

public final class InputRelayReportSnapshotTest {
    @Test
    public void ownedPhysicalAndVirtualPortsUseFullAssociationSet() {
        final LinkedHashSet<String> owned = new LinkedHashSet<>(Arrays.asList(
                "usb-keyboard",
                "magicdesk-mouse",
                "missing-keyboard"));
        final Map<String, String> associations = new LinkedHashMap<>();
        associations.put("usb-keyboard", "display:21");
        associations.put("magicdesk-mouse", "display:21");
        associations.put("magicdesk-keyboard-9", "display:21");
        associations.put("unrelated-port", "display:4");

        final InputRelayReportSnapshot.AssociationState state =
                InputRelayReportSnapshot.classifyAssociations(
                        owned, associations);

        assertEquals(
                new LinkedHashSet<>(Arrays.asList(
                        "usb-keyboard", "magicdesk-mouse")),
                state.active.keySet());
        assertEquals(
                new LinkedHashSet<>(Arrays.asList("missing-keyboard")),
                state.missing);
        assertEquals(
                new LinkedHashSet<>(Arrays.asList("magicdesk-keyboard-9")),
                state.unexpected);
    }
}
