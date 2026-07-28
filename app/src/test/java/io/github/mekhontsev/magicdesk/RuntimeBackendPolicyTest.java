package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RuntimeBackendPolicyTest {
    @Test
    public void onlyAutoAndExplicitRootProbeRoot() {
        assertTrue(RuntimeBackendPolicy.shouldProbeRoot(
                SessionProfile.PrivilegeMode.AUTO));
        assertTrue(RuntimeBackendPolicy.shouldProbeRoot(
                SessionProfile.PrivilegeMode.ROOT));
        assertFalse(RuntimeBackendPolicy.shouldProbeRoot(
                SessionProfile.PrivilegeMode.BASIC));
        assertFalse(RuntimeBackendPolicy.shouldProbeRoot(
                SessionProfile.PrivilegeMode.SHIZUKU));
    }

    @Test
    public void onlyExplicitShizukuProbesShizuku() {
        assertTrue(RuntimeBackendPolicy.shouldProbeShizuku(
                SessionProfile.PrivilegeMode.SHIZUKU));
        assertFalse(RuntimeBackendPolicy.shouldProbeShizuku(
                SessionProfile.PrivilegeMode.AUTO));
        assertFalse(RuntimeBackendPolicy.shouldProbeShizuku(
                SessionProfile.PrivilegeMode.BASIC));
        assertFalse(RuntimeBackendPolicy.shouldProbeShizuku(
                SessionProfile.PrivilegeMode.ROOT));
    }

    @Test
    public void basicNeverEscalatesEvenWhenRootIsAvailable() {
        assertEquals(
                RuntimeAccess.Backend.BASIC,
                RuntimeBackendPolicy.select(
                        SessionProfile.PrivilegeMode.BASIC,
                        true,
                        true,
                        0));
    }

    @Test
    public void shizukuNeverFallsBackToRoot() {
        assertEquals(
                RuntimeAccess.Backend.SHIZUKU_SHELL,
                RuntimeBackendPolicy.select(
                        SessionProfile.PrivilegeMode.SHIZUKU,
                        true,
                        true,
                        2000));
        assertEquals(
                RuntimeAccess.Backend.BASIC,
                RuntimeBackendPolicy.select(
                        SessionProfile.PrivilegeMode.SHIZUKU,
                        true,
                        false,
                        -1));
    }

    @Test
    public void rootStartedShizukuRemainsABoundedShizukuBackend() {
        assertEquals(
                RuntimeAccess.Backend.SHIZUKU_ROOT,
                RuntimeBackendPolicy.select(
                        SessionProfile.PrivilegeMode.SHIZUKU,
                        true,
                        true,
                        0));
    }

    @Test
    public void autoUsesRootWhenAvailableAndBasicOtherwise() {
        assertEquals(
                RuntimeAccess.Backend.ROOT,
                RuntimeBackendPolicy.select(
                        SessionProfile.PrivilegeMode.AUTO,
                        true,
                        false,
                        -1));
        assertEquals(
                RuntimeAccess.Backend.BASIC,
                RuntimeBackendPolicy.select(
                        SessionProfile.PrivilegeMode.AUTO,
                        false,
                        false,
                        -1));
    }

    @Test
    public void explicitRootDoesNotSilentlyBecomeAnotherPrivilegedMode() {
        assertEquals(
                RuntimeAccess.Backend.ROOT,
                RuntimeBackendPolicy.select(
                        SessionProfile.PrivilegeMode.ROOT,
                        true,
                        true,
                        2000));
        assertEquals(
                RuntimeAccess.Backend.BASIC,
                RuntimeBackendPolicy.select(
                        SessionProfile.PrivilegeMode.ROOT,
                        false,
                        true,
                        2000));
    }
}
