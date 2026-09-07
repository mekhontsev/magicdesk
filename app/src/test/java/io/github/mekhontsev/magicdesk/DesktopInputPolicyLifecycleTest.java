package io.github.mekhontsev.magicdesk;

import org.junit.Test;

/** Runs production policy boundaries without Android services or input devices. */
public final class DesktopInputPolicyLifecycleTest {
    @Test
    public void settingsAreLatchedOnlyAtExternalSessionEntry() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static class Display { static final int DEFAULT_DISPLAY = 0; }
                static class MagicDeskSettings {
                    static int reads;
                    static DesktopInputRelayPolicy selected = DesktopInputRelayPolicy.KEYBOARD_AND_MOUSE;
                    static MagicDeskSettings load() { reads++; return new MagicDeskSettings(); }
                    DesktopInputRelayPolicy inputRelayPolicy(Object features) { return selected; }
                }
                static class Relay {
                    int stops;
                    void stop() { stops++; }
                }
                int mDesktopDisplayId = -1, mInputSourceRefreshGeneration, creates;
                Object mPlatformFeatures;
                DesktopInputRelayPolicy mInputRelay = DesktopInputRelayPolicy.NONE;
                Relay mRelaySession = new Relay();
                Relay createRelaySession() { creates++; return new Relay(); }
                public static void verify() {
                    Fixture f = new Fixture();
                    f.selectInputPolicyForNewSession(0);
                    check(MagicDeskSettings.reads == 0, "phone desktop read external policy");
                    Relay idle = f.mRelaySession;
                    f.selectInputPolicyForNewSession(7);
                    f.mDesktopDisplayId = 7;
                    check(f.mInputRelay.keyboard && f.mInputRelay.mouse, "default was not applied");
                    check(idle.stops == 1 && f.creates == 1, "previous owner was not released once");
                    Relay active = f.mRelaySession;
                    MagicDeskSettings.selected = DesktopInputRelayPolicy.NONE;
                    f.selectInputPolicyForNewSession(7);
                    f.selectInputPolicyForNewSession(8);
                    check(MagicDeskSettings.reads == 1 && active.stops == 0,
                            "live capture changed after editing settings or changing display");
                    f.mDesktopDisplayId = -1;
                    f.selectInputPolicyForNewSession(9);
                    check(f.mInputRelay == DesktopInputRelayPolicy.NONE, "next session ignored preference");
                    check(active.stops == 1 && f.creates == 2, "next session reused wrong capture policy");
                    f.selectInputPolicyForNewSession(9);
                    check(f.creates == 2, "unchanged policy recreated transport");
                }
                """ + RuntimeSourceFixture.methods("RuntimeDesktopInputCoordinator",
                        "selectInputPolicyForNewSession", "ownsExternalDesktop"),
                "DesktopInputRelayPolicy");
    }

    @Test
    public void touchpadEligibilityDoesNotRequireVendorPositioning() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Display { static final int DEFAULT_DISPLAY = 0; }
                static class DesktopDisplayTarget {}
                static class DesktopRuntimeBridge {
                    static boolean active = true;
                    static DesktopDisplayTarget getDesktopTarget(int id) {
                        return active ? new DesktopDisplayTarget() : null;
                    }
                }
                static class DesktopDisplayDrivers {
                    static boolean enabled = true;
                    boolean phoneTouchpad = enabled;
                    static DesktopDisplayDrivers forTarget(DesktopDisplayTarget target) {
                        return new DesktopDisplayDrivers();
                    }
                    DesktopDisplayDrivers features() { return this; }
                }
                public static void verify() {
                    check(isSupported(7), "shared touchpad requires a vendor provider");
                    check(!isSupported(0) && !isSupported(-1), "touchpad accepted a non-external display");
                    DesktopDisplayDrivers.enabled = false;
                    check(!isSupported(7), "display driver touchpad policy ignored");
                    DesktopDisplayDrivers.enabled = true;
                    DesktopRuntimeBridge.active = false;
                    check(!isSupported(7), "touchpad accepted an unowned display");
                }
                """ + RuntimeSourceFixture.methods("PhoneTouchpadController", "isSupported"));
    }
}
