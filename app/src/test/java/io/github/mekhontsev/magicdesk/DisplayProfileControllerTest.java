package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public final class DisplayProfileControllerTest {
    @After
    public void restoreStorage() {
        DesktopStateStore.useStorageForTests(null);
    }

    @Test
    public void simulatedTestDoesNotReadOrReplaceTheSavedProfile() {
        final String[] encoded = {""};
        DesktopStateStore.useStorageForTests(new DesktopStateStore.Storage() {
            public String read() { return encoded[0]; }
            public void write(final String value) { encoded[0] = value; }
        });
        final DesktopDisplayOutput output = DesktopDisplayTarget.simulated(7)
                .withProfile("display:simulated:overlay:1").output;
        final DisplayProfileStore.Profile saved = new DisplayProfileStore.Profile(output.profileKey);
        saved.dpi = 520;
        saved.dpiExplicit = true;
        saved.originProfileKey = "display:local:phone";
        assertTrue(DisplayProfileStore.save(saved));
        final String before = encoded[0];

        final DisplayProfileStore.Profile isolated = DisplayProfileController.loadProfile(
                output.profileKey, 213, output, DesktopSessionPolicy.ISOLATED_SELF_TEST);
        assertEquals(160, isolated.dpi);
        assertEquals("", isolated.originProfileKey);
        isolated.dpi = 240;
        assertEquals(160, DisplayProfileController.loadProfile(
                output.profileKey, 213, output, DesktopSessionPolicy.ISOLATED_SELF_TEST).dpi);
        assertEquals(before, encoded[0]);
        assertEquals(520, DisplayProfileController.loadProfile(
                output.profileKey, 213, output, DesktopSessionPolicy.USER).dpi);
    }

    @Test
    public void onlySimulatedSelfTestsIsolateDisplayPreferences() {
        for (final DesktopDisplayOutput.Kind kind : DesktopDisplayOutput.Kind.values()) {
            final DesktopDisplayOutput output = new DesktopDisplayOutput(kind,
                    kind == DesktopDisplayOutput.Kind.BUILT_IN ? 0 : 7,
                    "profile", DesktopDisplayOutput.ActivationSource.MAGICDESK_REQUESTED);
            assertTrue(DesktopSessionPolicy.USER.usesSavedDisplayProfile(output));
            assertEquals(kind != DesktopDisplayOutput.Kind.SIMULATED,
                    DesktopSessionPolicy.ISOLATED_SELF_TEST.usesSavedDisplayProfile(output));
        }
    }

    @Test
    public void isolatedProfileChangesAreNotPersisted() throws Exception {
        RuntimeSourceFixture.verify("""
                static class DisplayProfileStore {
                    static int writes;
                    static void save(Object profile) { writes++; }
                }
                static class Policy {
                    boolean saved;
                    boolean usesSavedDisplayProfile(Object output) { return saved; }
                }
                static class Host {
                    Policy policy = new Policy();
                    Policy getSessionPolicy() { return policy; }
                    Object getDesktopOutput() { return null; }
                }
                Host mHost = new Host();
                Object getProfile() { return new Object(); }
                public static void verify() {
                    Fixture f = new Fixture();
                    f.save();
                    check(DisplayProfileStore.writes == 0, "isolated profile persisted");
                    f.mHost.policy.saved = true;
                    f.save();
                    check(DisplayProfileStore.writes == 1, "user profile not saved");
                }
                """ + RuntimeSourceFixture.methods("DisplayProfileController", "save"));
    }

    @Test
    public void stableProfilePrefersDisplayUniqueId() {
        assertEquals(
                "display:wireless:wifi:aa:bb:cc",
                DisplayProfileController.stableProfileKey(
                        DesktopDisplayOutput.Kind.WIRELESS,
                        "wifi:aa:bb:cc",
                        "Living room",
                        null));
    }

    @Test
    public void stableProfileFallbackDoesNotUseLogicalDisplayId() {
        assertEquals(
                "display:simulated:MagicDesk test|unknown",
                DisplayProfileController.stableProfileKey(
                        DesktopDisplayOutput.Kind.SIMULATED,
                        "",
                        "MagicDesk test",
                        null));
    }
}
