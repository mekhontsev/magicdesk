package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

public final class DesktopInputReportSnapshotTest {
    @Test
    public void ownedPhysicalAndVirtualPortsUseFullAssociationSet() {
        final LinkedHashSet<String> owned = new LinkedHashSet<>(Arrays.asList(
                "usb-keyboard",
                "magicdesk-mouse",
                "missing-keyboard"));
        final Map<String, String> associations = new LinkedHashMap<>();
        associations.put("usb-keyboard", "display:21");
        associations.put("magicdesk-mouse", "display:21");
        associations.put("unrelated-port", "display:4");

        final DesktopInputReportSnapshot.AssociationState state =
                DesktopInputReportSnapshot.classifyAssociations(
                        owned, associations);

        assertEquals(
                new LinkedHashSet<>(Arrays.asList(
                        "usb-keyboard", "magicdesk-mouse")),
                state.active.keySet());
        assertEquals(
                new LinkedHashSet<>(Arrays.asList("missing-keyboard")),
                state.missing);
        assertEquals(
                new LinkedHashSet<>(),
                state.unexpected);
    }

    @Test
    public void detectsUnownedPhonePointerRoute() {
        assertEquals(java.util.Set.of("magicdesk-mouse"),
                DesktopInputReportSnapshot.classifyAssociations(java.util.Set.of(),
                        java.util.Map.of("magicdesk-mouse", "display:21")).unexpected);
    }
}
