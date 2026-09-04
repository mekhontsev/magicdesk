package io.github.mekhontsev.magicdesk;

/** Stable identifiers exposed through Android App Functions. */
final class MagicDeskAppFunctionCatalog {
    private static final String PREFIX =
            "io.github.mekhontsev.magicdesk.MagicDeskAppFunctionService#";

    static final String GET_DESKTOP_STATE = PREFIX + "getDesktopState";
    static final String START_DESKTOP = PREFIX + "startDesktop";
    static final String CLOSE_DESKTOP = PREFIX + "closeDesktop";
    static final String LAUNCH_APP = PREFIX + "launchApp";
    static final String OPEN_SETTINGS = PREFIX + "openSettings";
    static final String INVOKE_ANDROID_ACTION =
            PREFIX + "invokeAndroidAction";
    static final String LIST_ANDROID_ACTIONS =
            PREFIX + "listAndroidActions";
    static final String GET_ANDROID_ACTIVITY_RESULT =
            PREFIX + "getAndroidActivityResult";

    static String[] all() {
        return new String[] {
            GET_DESKTOP_STATE,
            START_DESKTOP,
            CLOSE_DESKTOP,
            LAUNCH_APP,
            OPEN_SETTINGS,
            INVOKE_ANDROID_ACTION,
            LIST_ANDROID_ACTIONS,
            GET_ANDROID_ACTIVITY_RESULT
        };
    }

    private MagicDeskAppFunctionCatalog() {
    }
}
