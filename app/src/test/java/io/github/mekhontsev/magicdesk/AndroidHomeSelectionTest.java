package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AndroidHomeSelectionTest {
    private static final String PACKAGE = "com.example.launcher";
    private static final String COMPONENT = PACKAGE + "/.Launcher";

    @Test
    public void restoresDeclaredSelection() {
        final AndroidHomeSelection selection = AndroidHomeSelection.restore(
                PACKAGE,
                COMPONENT,
                42,
                AndroidHomeSelection.Availability.DECLARED.name());

        assertEquals(PACKAGE, selection.packageName);
        assertEquals(COMPONENT, selection.componentName);
        assertEquals(42, selection.packageVersionCode);
        assertEquals(
                AndroidHomeSelection.Availability.DECLARED,
                selection.availability);
    }

    @Test
    public void invalidComponentDegradesToUnresolvedSelection() {
        final AndroidHomeSelection selection = AndroidHomeSelection.restore(
                PACKAGE,
                "com.example.other/.Launcher",
                42,
                AndroidHomeSelection.Availability.DECLARED.name());

        assertEquals(PACKAGE, selection.packageName);
        assertEquals("", selection.componentName);
        assertEquals(-1, selection.packageVersionCode);
        assertEquals(
                AndroidHomeSelection.Availability.UNRESOLVED,
                selection.availability);
    }

    @Test
    public void missingMetadataDegradesToUnresolvedSelection() {
        final AndroidHomeSelection selection = AndroidHomeSelection.restore(
                PACKAGE, "", -1, "");

        assertEquals(PACKAGE, selection.packageName);
        assertEquals(
                AndroidHomeSelection.Availability.UNRESOLVED,
                selection.availability);
    }

    @Test
    public void emptyRoleHolderRestoresAsNone() {
        final AndroidHomeSelection selection = AndroidHomeSelection.restore(
                "", "", -1,
                AndroidHomeSelection.Availability.DECLARED.name());

        assertEquals(AndroidHomeSelection.Availability.NONE,
                selection.availability);
    }
}
