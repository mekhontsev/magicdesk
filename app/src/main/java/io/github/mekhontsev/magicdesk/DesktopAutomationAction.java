package io.github.mekhontsev.magicdesk;

/** Stable action names shared by automation transports. */
enum DesktopAutomationAction {
    START_DESKTOP("start_desktop", false),
    CLOSE_DESKTOP("close_desktop", false),
    LAUNCH_APP("launch_app", false),
    LIST_APP_ACTIONS("list_app_actions", false),
    INVOKE_APP_ACTION("invoke_app_action", false),
    LAUNCH_SPEC("launch_spec", false),
    FOCUS_TASK("focus_task", false),
    CLOSE_TASK("close_task", false),
    FORCE_STOP_APP("force_stop_app", true),
    SET_WINDOW_MODE("set_window_mode", false),
    SET_WINDOW_BOUNDS("set_window_bounds", false),
    ARRANGE_TASK("arrange_task", false),
    SHOW_START("show_start", false),
    SHOW_DESKTOP("show_desktop", false),
    OPEN_SETTINGS("open_settings", false),
    OPEN_BUILTIN("open_builtin", false),
    CAPTURE_SCREENSHOT("capture_screenshot", false),
    SAMPLE_PIXELS("sample_pixels", false),
    GET_RECORDING_STATUS("get_recording_status", false),
    START_RECORDING("start_recording", false),
    STOP_RECORDING("stop_recording", false),
    RUN_SELF_TEST("run_self_test", true),
    SEND_KEY("send_key", true),
    MOVE_POINTER("move_pointer", true),
    CLICK_POINTER("click_pointer", true),
    INVOKE_UI_ACTION("invoke_ui_action", false),
    BEGIN_TRACE("begin_trace", false),
    END_TRACE("end_trace", false);

    final String wireName;
    final boolean developerOnly;

    DesktopAutomationAction(
            final String wireName, final boolean developerOnly) {
        this.wireName = wireName;
        this.developerOnly = developerOnly;
    }

    static DesktopAutomationAction parse(final String value) {
        if (value != null) {
            for (final DesktopAutomationAction action : values()) {
                if (action.wireName.equals(value)) {
                    return action;
                }
            }
        }
        return null;
    }
}
