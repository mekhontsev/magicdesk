package io.github.mekhontsev.magicdesk;

import org.junit.Test;

/** Runs production policy boundaries without Android services or input devices. */
public final class DesktopInputPolicyLifecycleTest {
    @Test public void touchpadEligibilityFollowsSharedInputNotDesktopOrVendor() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Display { static final int DEFAULT_DISPLAY = 0; }
                static class MagicDeskRuntime {
                    static int target = 7;
                    static int inputDisplayId() { return target; }
                }
                public static void verify() {
                    check(isSupported(7), "independent input cannot open touchpad");
                    check(!isSupported(0) && !isSupported(-1), "accepted a non-external display");
                    check(!isSupported(8), "accepted another display");
                    MagicDeskRuntime.target = -1;
                    check(!isSupported(7), "released route still accepts touchpad");
                }
                """ + RuntimeSourceFixture.methods("PhoneTouchpadController", "isSupported"));
    }
}
