package io.github.mekhontsev.magicdesk;

/** Stable action names shared by automation transports. */
enum DesktopAutomationAction {
    START_DESKTOP("start_desktop", false),
    CLOSE_DESKTOP("close_desktop", false),
    LAUNCH_APP("launch_app", false),
    QUERY_INTENT_HANDLERS("query_intent_handlers", false),
    LAUNCH_INTENT("launch_intent", false),
    OPEN_URI("open_uri", false),
    OPEN_FILE("open_file", false),
    SHARE("share", false),
    LIST_ANDROID_ACTIONS("list_android_actions", false),
    INVOKE_ANDROID_ACTION("invoke_android_action", false),
    GET_ACTIVITY_HISTORY("get_activity_history", false),
    SEND_BROADCAST("send_broadcast", true),
    START_SERVICE("start_service", true),
    LIST_APP_ACTIONS("list_app_actions", false),
    INVOKE_APP_ACTION("invoke_app_action", false),
    LIST_NOTIFICATIONS("list_notifications", false),
    INVOKE_NOTIFICATION("invoke_notification", false),
    GET_INTENT_RESULT("get_intent_result", false),
    SEARCH_APP_FUNCTIONS("search_app_functions", false),
    EXECUTE_APP_FUNCTION("execute_app_function", false),
    READ_CLIPBOARD_TEXT("clipboard.read_text", true),
    WRITE_CLIPBOARD_TEXT("clipboard.write_text", true),
    OPEN_CLIPBOARD_CONTENT("clipboard.open", true),
    SHARE_CLIPBOARD_CONTENT("clipboard.share", true),
    CLEAR_CLIPBOARD("clipboard.clear", true),
    LAUNCH_DESKTOP_ENTRY("launch_desktop_entry", false),
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
    RECONNECT_TERMUX_X11("reconnect_termux_x11", false),
    CAPTURE_SCREENSHOT("capture_screenshot", false),
    SAMPLE_PIXELS("sample_pixels", false),
    GET_RECORDING_STATUS("get_recording_status", false),
    START_RECORDING("start_recording", false),
    STOP_RECORDING("stop_recording", false),
    RUN_SELF_TEST("run_self_test", true),
    CANCEL_SELF_TEST("cancel_self_test", true),
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
