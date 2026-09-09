package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public final class IntegrationPackageTest {
    @Test
    public void defaultsAreTheOriginalPackages() {
        final var values = IntegrationPackage.snapshot(Map.of());
        assertEquals("com.termux", values.get(IntegrationPackage.TERMUX));
        assertEquals("moe.shizuku.privileged.api", values.get(IntegrationPackage.SHIZUKU));
    }

    @Test
    public void arbitraryPackagesAreFrozenForTheProcessWithoutAForkCatalog() {
        final var configured = new HashMap<>(Map.of(
                IntegrationPackage.TERMUX, "example.compat.terminal",
                IntegrationPackage.SHIZUKU, "example.compat.manager"));
        final var active = IntegrationPackage.snapshot(configured);
        configured.put(IntegrationPackage.TERMUX, "other.terminal");
        assertEquals("example.compat.terminal", active.get(IntegrationPackage.TERMUX));
        assertEquals("example.compat.manager", active.get(IntegrationPackage.SHIZUKU));
        assertEquals("other.terminal", IntegrationPackage.snapshot(configured).get(IntegrationPackage.TERMUX));
        assertThrows(UnsupportedOperationException.class,
                () -> active.put(IntegrationPackage.TERMUX, "other.terminal"));
    }

    @Test
    public void packageInputRejectsIntentsPathsAndEmptyValues() {
        assertEquals("org.example.Terminal_2", IntegrationPackage.normalize(" org.example.Terminal_2 "));
        for (final String value : new String[]{"", "com", "com..termux", "com.2termux", "com.termux/.Main",
                "com.termux;id", "com.termux\nother.app", "com." + "a".repeat(256)}) {
            assertThrows(value, IllegalArgumentException.class, () -> IntegrationPackage.normalize(value));
        }
    }

    @Test
    public void incompatibleSavedPackageIsNotSilentlyReplacedByOriginal() {
        assertThrows(IllegalArgumentException.class, () -> IntegrationPackage.snapshot(
                Map.of(IntegrationPackage.TERMUX, "not a package")));
    }

    @Test
    public void serviceCompatibilityDoesNotDependOnJavaClassOrApplicationName() {
        assertEquals("", TermuxIntegration.serviceError(true, true, TermuxIntegration.RUN_COMMAND_PERMISSION));
        assertEquals("", TermuxIntegration.serviceError(true, true, null));
        assertTrue(TermuxIntegration.serviceError(true, true, "example.permission.COMMAND")
                .contains("Unsupported RUN_COMMAND permission"));
        assertTrue(!TermuxIntegration.serviceError(false, true, null).isEmpty());
        assertTrue(!TermuxIntegration.serviceError(true, false, null).isEmpty());
    }
}
