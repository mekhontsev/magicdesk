package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AndroidHomeSelectionTest {
    private static final String PACKAGE = "com.example.launcher";
    private static final String COMPONENT = PACKAGE + "/.Launcher";

    @Test
    public void restoresDeclaredSelection() {
        final AndroidHomeSelection selection =
                AndroidHomeSelection.fromPersisted(
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

    @Test(expected = IllegalArgumentException.class)
    public void invalidPersistedComponentIsRejected() {
        AndroidHomeSelection.fromPersisted(
                PACKAGE,
                "com.example.other/.Launcher",
                42,
                AndroidHomeSelection.Availability.DECLARED.name());
    }

    @Test(expected = IllegalArgumentException.class)
    public void incompletePersistedMetadataIsRejected() {
        AndroidHomeSelection.fromPersisted(
                PACKAGE, "", -1, "");
    }

    @Test
    public void emptyRoleHolderRestoresAsNone() {
        final AndroidHomeSelection selection =
                AndroidHomeSelection.fromPersisted(
                        "", "", -1,
                        AndroidHomeSelection.Availability.NONE.name());

        assertEquals(AndroidHomeSelection.Availability.NONE,
                selection.availability);
    }
}
