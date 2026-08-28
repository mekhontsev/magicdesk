package io.github.mekhontsev.magicdesk.platform.nubia;

/** Process-local state published by the Nubia phone-screen guard. */
final class NubiaPhoneScreenState {
    static final String SETTING = "nubia_screen_off_tp";

    private static volatile boolean sOff;

    private NubiaPhoneScreenState() {
    }

    static boolean isOff() {
        return sOff;
    }

    static boolean setOff(final boolean off) {
        if (sOff == off) {
            return false;
        }
        sOff = off;
        return true;
    }
}
