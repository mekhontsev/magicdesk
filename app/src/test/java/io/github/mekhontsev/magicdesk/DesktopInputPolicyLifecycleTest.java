package io.github.mekhontsev.magicdesk;

import org.junit.Test;

/** Runs production policy boundaries without Android services or input devices. */
public final class DesktopInputPolicyLifecycleTest {
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
