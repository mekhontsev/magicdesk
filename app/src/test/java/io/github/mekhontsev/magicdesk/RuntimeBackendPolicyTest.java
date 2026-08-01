package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class RuntimeBackendPolicyTest {
    @Test
    public void shellUidSelectsShizuku() {
        assertEquals(
                RuntimeAccess.Backend.SHIZUKU,
                RuntimeBackendPolicy.select(true, 2000));
    }

    @Test
    public void unavailableServerSelectsUnavailable() {
        assertEquals(
                RuntimeAccess.Backend.UNAVAILABLE,
                RuntimeBackendPolicy.select(false, -1));
    }

    @Test
    public void nonShellShizukuIsRejected() {
        assertEquals(
                RuntimeAccess.Backend.UNAVAILABLE,
                RuntimeBackendPolicy.select(true, 0));
    }
}
