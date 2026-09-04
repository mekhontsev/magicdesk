package io.github.mekhontsev.magicdesk;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Declarative MCP tool schemas kept separate from action execution. */
final class MagicDeskMcpToolCatalog {
    private MagicDeskMcpToolCatalog() {
    }

    static JSONArray create(final boolean includeDeveloperTools)
            throws JSONException {
        return create(includeDeveloperTools, false);
    }

    static JSONArray create(
            final boolean includeDeveloperTools,
            final boolean includeShellTools) throws JSONException {
        final JSONArray tools = new JSONArray()
                .put(readTool(
                        "get_state",
                        "Get desktop state",
                        "Read current MagicDesk runtime and desktop session state.",
                        emptySchema()))
                .put(readTool(
                        "get_pointer_state",
                        "Get pointer state",
                        "Read the active pointer relay, routing, and optional platform cursor position.",
                        objectSchema(new JSONObject().put(
                                "displayId", integerProperty(
                                        "Optional active desktop display id.")))))
                .put(readTool(
                        "list_displays",
                        "List displays",
                        "List connected displays, current modes and supported modes.",
                        emptySchema()))
                .put(readTool(
                        "list_tasks",
                        "List tasks",
                        "List Android tasks and native window state on all or one display.",
                        objectSchema(new JSONObject()
                                .put("displayId", integerProperty(
                                        "Optional Android display id."))
                                .put("package", stringProperty(
                                        "Exact package filter."))
                                .put("mode", enumProperty(
                                        "Window mode filter.",
                                        "windowed", "freeform", "fullscreen"))
                                .put("query", stringProperty(
                                        "Package or component substring."))
                                .put("limit", integerProperty(
                                        "Page size from 1 to 200."))
                                .put("cursor", stringProperty(
                                        "Opaque cursor from the previous page.")))))
                .put(readTool(
                        "list_apps",
                        "List applications",
                        "List launchable Android application activities.",
                        objectSchema(new JSONObject()
                                .put("package", stringProperty(
                                        "Exact package filter."))
                                .put("query", stringProperty(
                                        "Label, package, or component substring."))
                                .put("limit", integerProperty(
                                        "Page size from 1 to 200."))
                                .put("cursor", stringProperty(
                                        "Opaque cursor from the previous page.")))))
                .put(readTool(
                        "list_ui_elements",
                        "List desktop UI elements",
                        "List semantic controls from the live MagicDesk desktop UI with stable ids, state, actions, and display bounds.",
                        objectSchema(new JSONObject()
                                .put("displayId", integerProperty(
                                        "Optional active desktop display id."))
                                .put("query", stringProperty(
                                        "Optional id, role, label, or package substring."))
                                .put("includeHidden", booleanProperty(
                                        "Include registered controls that are currently hidden.")))))
                .put(readTool(
                        "get_events",
                        "Get automation events",
                        "Read the bounded structured automation event history.",
                        objectSchema(new JSONObject()
                                .put("afterId", integerProperty(
                                        "Return events newer than this id."))
                                .put("limit", integerProperty(
                                        "Maximum number of events, up to 256.")))))
                .put(readTool(
                        "get_diagnostics",
                        "Get diagnostics",
                        "Build the full MagicDesk compatibility report.",
                        emptySchema()))
                .put(readTool(
                        "get_self_test",
                        "Get self-test result",
                        "Read the current status and latest desktop self-test report.",
                        emptySchema()))
                .put(readTool(
                        "get_termux_x11_status",
                        "Get Termux:X11 status",
                        "Probe the configured Termux:X11 display, reconnect listener, and Android viewer task.",
                        emptySchema()))
                .put(actionTool(
                        "start_desktop",
                        "Start desktop",
                        "Start MagicDesk on the requested available display target.",
                        objectSchema(new JSONObject().put(
                                "target", enumProperty(
                                        "Target display environment.",
                                        "auto", "phone", "simulated",
                                        "wired", "wireless")))))
                .put(actionTool(
                        "close_desktop",
                        "Close desktop",
                        "Run the normal Close Desktop procedure for the active session.",
                        emptySchema()))
                .put(actionTool(
                        "launch_app",
                        "Launch application",
                        "Launch an Android application through the native MagicDesk window pipeline.",
                        objectSchema(new JSONObject()
                                        .put("package", stringProperty(
                                                "Android package name."))
                                        .put("component", stringProperty(
                                                "Optional flattened activity component."))
                                        .put("mode", enumProperty(
                                                "Launch mode.", "auto",
                                                "windowed", "fullscreen"))
                                        .put("instance", enumProperty(
                                                "Task instance policy.",
                                                "reuse", "new"))
                                        .put("preferredTaskId", integerProperty(
                                                "Existing task to activate when instance is reuse."))
                                        .put("displayId", integerProperty(
                                                "Active desktop display id."))
                                        .put("bounds", relativeBoundsProperty(
                                                "Initial bounds within the desktop work area.")),
                                "package")))
                .put(actionTool(
                        "focus_task",
                        "Focus task",
                        "Activate a task through MagicDesk's managed workspace path and return only after hierarchy and input focus converge.",
                        taskIdSchema()))
                .put(destructiveTool(
                        "close_task",
                        "Close task",
                        "Close a managed application task without force-stopping its package.",
                        taskIdSchema()))
                .put(actionTool(
                        "set_window_mode",
                        "Set raw window mode",
                        "Directly change a task's native windowing mode without MagicDesk fullscreen-plane ownership. This diagnostic path can expose firmware behavior; use arrange_task maximize or restore for normal desktop behavior.",
                        objectSchema(new JSONObject()
                                        .put("taskId", integerProperty(
                                                "Android task id."))
                                        .put("mode", enumProperty(
                                                "Target window mode.",
                                                "windowed", "fullscreen"))
                                        .put("bounds", boundsProperty(
                                                "Optional bounds for windowed mode.")),
                                "taskId", "mode")))
                .put(actionTool(
                        "set_window_bounds",
                        "Set window bounds",
                        "Resize a native freeform task to explicit display coordinates.",
                        objectSchema(new JSONObject()
                                        .put("taskId", integerProperty(
                                                "Android task id."))
                                        .put("bounds", boundsProperty(
                                                "Required task bounds.")),
                                "taskId", "bounds")))
                .put(actionTool(
                        "show_start",
                        "Show Start",
                        "Open the MagicDesk Start menu on the active desktop.",
                        emptySchema()))
                .put(actionTool(
                        "show_desktop",
                        "Toggle desktop",
                        "Toggle between the desktop and the current application workspace, waiting for the managed workspace command to complete.",
                        emptySchema()))
                .put(actionTool(
                        "invoke_ui_action",
                        "Invoke desktop UI action",
                        "Invoke an action through the live control's existing click or context-menu listener.",
                        objectSchema(new JSONObject()
                                        .put("displayId", integerProperty(
                                                "Optional active desktop display id."))
                                        .put("elementId", stringProperty(
                                                "Stable id returned by list_ui_elements."))
                                        .put("action", enumProperty(
                                                "Semantic action supported by the element.",
                                                "click",
                                                "secondary_click")),
                                "elementId", "action")))
                .put(actionTool(
                        "begin_trace",
                        "Begin operation trace",
                        "Mark the start of a bounded trace in MagicDesk's shared structured event journal.",
                        objectSchema(new JSONObject()
                                .put("displayId", integerProperty(
                                        "Optional display used to filter the final task snapshot."))
                                .put("label", stringProperty(
                                        "Optional caller label.")))))
                .put(actionTool(
                        "end_trace",
                        "End operation trace",
                        "Return events since begin_trace plus failures and final runtime and task snapshots.",
                        objectSchema(new JSONObject().put(
                                "traceId", stringProperty(
                                        "Trace id returned by begin_trace.")),
                                "traceId")))
                .put(actionTool(
                        "open_settings",
                        "Open settings",
                        "Open MagicDesk settings on the active desktop or phone.",
                        emptySchema()))
                .put(actionTool(
                        "reconnect_termux_x11",
                        "Reconnect Termux:X11",
                        "Reconnect the Android viewer to the running configured Termux:X11 display.",
                        emptySchema()))
                .put(readTool(
                        "capture_screenshot",
                        "Capture screenshot",
                        "Capture the active desktop as an in-memory PNG image.",
                        objectSchema(new JSONObject().put(
                                "displayId", integerProperty(
                                        "Optional active desktop display id.")))))
                .put(readTool(
                        "wait_for_state",
                        "Wait for state",
                        "Wait for an observable desktop, task, application health, system dialog, UI, or self-test condition.",
                        waitSchema()));
        tools.put(readTool(
                        "sample_pixels",
                        "Sample display pixels",
                        "Read up to 64 exact pixels from the active desktop without creating a file.",
                        pixelSampleSchema()))
                .put(actionTool(
                        "open_builtin",
                        "Open built-in window",
                        "Open a MagicDesk Files, Console, Task Manager, Settings, Diagnostics, or Activity Explorer window.",
                        objectSchema(new JSONObject().put(
                                "builtin", enumProperty(
                                        "Built-in window.",
                                        "files", "console",
                                        "task_manager", "settings",
                                        "diagnostics", "activity_explorer")),
                                "builtin")))
                .put(actionTool(
                        "arrange_task",
                        "Arrange task",
                        "Apply the same managed task transition used by MagicDesk window shortcuts. Maximize and restore preserve fullscreen-plane ownership.",
                        objectSchema(new JSONObject()
                                        .put("taskId", integerProperty(
                                                "Android task id."))
                                        .put("arrangement", enumProperty(
                                                "Target arrangement.",
                                                "left", "right",
                                                "maximize", "restore")),
                                "taskId", "arrangement")))
                .put(readTool(
                        "query_intent_handlers",
                        "Query Android handlers",
                        "Resolve visible Android activities, broadcast receivers, or services for a typed or raw Intent without executing it.",
                        intentSchema(true)))
                .put(actionTool(
                        "launch_intent",
                        "Launch Android Intent",
                        "Launch a typed or raw Android Activity Intent through the managed desktop window pipeline.",
                        intentSchema(false)))
                .put(actionTool(
                        "open_uri",
                        "Open URI",
                        "Open a URI through the managed Android Activity and desktop window pipeline.",
                        openUriSchema()))
                .put(actionTool(
                        "open_file",
                        "Open file",
                        "Grant and open one shell path or content URI through an Android Activity.",
                        openFileSchema()))
                .put(actionTool(
                        "share",
                        "Share content",
                        "Share text and shell files through Android with bounded temporary URI grants.",
                        shareSchema()))
                .put(readTool(
                        "list_android_actions",
                        "List Android actions",
                        "List stable semantic Android actions shared by MagicDesk UI, MCP, and App Functions.",
                        emptySchema()))
                .put(actionTool(
                        "invoke_android_action",
                        "Invoke Android action",
                        "Invoke a stable semantic Android action through the managed desktop Activity pipeline.",
                        androidActionSchema()))
                .put(readTool(
                        "get_activity_history",
                        "Get Activity history",
                        "Read bounded compatibility evidence from Activity launches that already occurred.",
                        objectSchema(new JSONObject().put(
                                "limit", integerProperty(
                                        "Maximum launches, up to 64.")))))
                .put(readTool(
                        "list_app_actions",
                        "List application actions",
                        "List dynamic, pinned, cached, and manifest application shortcuts visible to MagicDesk.",
                        appTargetSchema(false)))
                .put(actionTool(
                        "invoke_app_action",
                        "Invoke application action",
                        "Launch one published application shortcut through the desktop window pipeline.",
                        appTargetSchema(true)))
                .put(readTool(
                        "list_notifications",
                        "List notifications",
                        "List active notifications and their opaque PendingIntent actions from the connected Android notification listener.",
                        objectSchema(new JSONObject().put(
                                "package", stringProperty(
                                        "Optional exact package filter.")))))
                .put(actionTool(
                        "invoke_notification",
                        "Invoke notification",
                        "Open, invoke an action on, or dismiss an active Android notification by its opaque key.",
                        notificationActionSchema()))
                .put(readTool(
                        "get_intent_result",
                        "Get Activity result",
                        "Read an event-driven Activity result requested by launch_intent or open_file.",
                        objectSchema(new JSONObject()
                                .put("requestId", stringProperty(
                                        "Result request id returned by the launch tool."))
                                .put("waitMillis", integerProperty(
                                        "Optional event-driven wait, up to 60000 ms."))
                                .put("consume", booleanProperty(
                                        "Remove a terminal result and release its persisted URI grants after reading it.")),
                                "requestId")))
                .put(readTool(
                        "search_app_functions",
                        "Search App Functions",
                        "Discover Android App Functions through the shell-authorized framework service. Discovery requires API 37.",
                        appFunctionSearchSchema()))
                .put(actionTool(
                        "execute_app_function",
                        "Execute App Function",
                        "Execute an Android App Function through the shell-authorized framework service.",
                        appFunctionExecuteSchema()))
                .put(actionTool(
                        "launch_desktop_entry",
                        "Launch desktop entry",
                        "Launch one .desktop file through the shared coordinator.",
                        desktopEntrySchema()))
                .put(readTool(
                        "get_recording_status",
                        "Get recording status",
                        "Read the current desktop screen recording state.",
                        emptySchema()))
                .put(actionTool(
                        "start_recording",
                        "Start screen recording",
                        "Start recording the active desktop with the configured audio mode.",
                        emptySchema()))
                .put(actionTool(
                        "stop_recording",
                        "Stop screen recording",
                        "Finalize and save the active desktop recording.",
                        emptySchema()));
        if (includeDeveloperTools) {
            tools.put(destructiveTool(
                        "force_stop_app",
                        "Force stop application",
                        "Force-stop an Android package. Developer automation only.",
                        objectSchema(new JSONObject().put(
                                "package", stringProperty(
                                        "Android package name.")),
                                "package")))
                .put(actionTool(
                        "send_broadcast",
                        "Send Android broadcast",
                        "Send a typed or raw broadcast Intent. Developer automation only because it can trigger background state changes.",
                        intentSchema(true)))
                .put(actionTool(
                        "start_service",
                        "Start Android service",
                        "Start a typed or raw Android service Intent. Developer automation only because it is an invisible background operation.",
                        intentSchema(true)))
                .put(readTool(
                        "clipboard.read_text",
                        "Read clipboard text",
                        "Read text from Android's system clipboard. Developer automation only because clipboard contents may contain secrets; Android may require a focused MagicDesk window.",
                        emptySchema()))
                .put(actionTool(
                        "clipboard.write_text",
                        "Write clipboard text",
                        "Write bounded plain text to Android's system clipboard. Developer automation only.",
                        objectSchema(new JSONObject()
                                        .put("text", stringProperty(
                                                "Text to place on the clipboard."))
                                        .put("label", stringProperty(
                                                "Optional clipboard label."))
                                        .put("sensitive", booleanProperty(
                                                "Mark clipboard previews as sensitive.")),
                                "text")))
                .put(actionTool(
                        "clipboard.open",
                        "Open clipboard link or file",
                        "Open the current clipboard file or web link through the production desktop Intent launcher. Developer automation only.",
                        objectSchema(new JSONObject().put(
                                "displayId", integerProperty(
                                        "Optional active desktop display id.")))))
                .put(actionTool(
                        "clipboard.share",
                        "Share clipboard content",
                        "Open Android's share chooser for the current clipboard content through the production desktop Intent launcher. Developer automation only.",
                        objectSchema(new JSONObject().put(
                                "displayId", integerProperty(
                                        "Optional active desktop display id.")))))
                .put(destructiveTool(
                        "clipboard.clear",
                        "Clear clipboard",
                        "Clear Android's system clipboard and a matching published MagicDesk file operation. Developer automation only.",
                        emptySchema()))
                .put(actionTool(
                        "run_self_test",
                        "Run desktop self-test",
                        "Launch the built-in UI self-test on an exact display target.",
                        objectSchema(new JSONObject()
                                .put("target", enumProperty(
                                        "Self-test display target.",
                                        "phone", "simulated",
                                        "wired", "wireless"))
                                .put("mode", enumProperty(
                                        "Execution mode. fail_fast stops after the first FAIL but still runs cleanup.",
                                        "full", "fail_fast")))))
                .put(actionTool(
                        "cancel_self_test",
                        "Cancel desktop self-test",
                        "Cancel one exact active self-test run and allow its cleanup to finish.",
                        objectSchema(new JSONObject().put(
                                "runId", integerProperty(
                                        "Exact run id returned by run_self_test.")),
                                "runId")))
                .put(actionTool(
                        "send_key",
                        "Send key",
                        "Inject one Android key event on a desktop display. Developer automation only.",
                        objectSchema(new JSONObject()
                                        .put("displayId", integerProperty(
                                                "Optional active display id."))
                                        .put("keyCode", stringProperty(
                                                "Android KEYCODE name.")),
                                "keyCode")))
                .put(actionTool(
                        "move_pointer",
                        "Move pointer",
                        "Move the MagicDesk pointer to absolute display coordinates.",
                        objectSchema(new JSONObject()
                                        .put("displayId", integerProperty(
                                                "Optional active display id."))
                                        .put("x", integerProperty(
                                                "Horizontal display coordinate."))
                                        .put("y", integerProperty(
                                                "Vertical display coordinate.")),
                                "x", "y")))
                .put(actionTool(
                        "click_pointer",
                        "Click pointer",
                        "Inject a primary or secondary pointer click at the current position.",
                        objectSchema(new JSONObject()
                                .put("displayId", integerProperty(
                                        "Optional active display id."))
                                .put("button", enumProperty(
                                        "Pointer button.",
                                        "primary", "secondary")))));
        }
        if (includeShellTools) {
            addShellTools(tools);
        }
        return tools;
    }

    private static JSONObject waitSchema() throws JSONException {
        return objectSchema(new JSONObject()
                        .put("condition", enumProperty(
                                "Condition to observe.",
                                "desktop_active", "desktop_inactive",
                                "task_present", "task_absent",
                                "task_windowing_mode", "task_focused",
                                "task_bounds", "app_ready", "app_crashed",
                                "app_not_responding",
                                "system_dialog_visible",
                                "pointer_ready", "ui_visible",
                                "ui_element_state", "popup_state",
                                "taskbar_visible",
                                "wallpaper_rendered", "self_test_finished"))
                        .put("taskId", integerProperty(
                                "Task id for task or application conditions."))
                        .put("package", stringProperty(
                                "Optional package filter for a system dialog."))
                        .put("mode", enumProperty(
                                "Expected task mode.",
                                "windowed", "freeform", "fullscreen"))
                        .put("displayId", integerProperty(
                                "Display id for display conditions."))
                        .put("bounds", boundsProperty(
                                "Expected task bounds."))
                        .put("tolerance", integerProperty(
                                "Allowed coordinate difference in pixels."))
                        .put("element", enumProperty(
                                "UI element for ui_visible.",
                                "taskbar", "start", "popup", "wallpaper",
                                "touchpad", "control_panel"))
                        .put("elementId", stringProperty(
                                "Stable id for ui_element_state."))
                        .put("visible", booleanProperty(
                                "Expected visibility; defaults to true."))
                        .put("enabled", booleanProperty(
                                "Optional expected enabled state."))
                        .put("focused", booleanProperty(
                                "Optional expected focused state."))
                        .put("selected", booleanProperty(
                                "Optional expected selected state."))
                        .put("popupTitle", stringProperty(
                                "Exact title required for a visible popup."))
                        .put("timeoutMillis", integerProperty(
                                "Timeout from 1 to 60000 milliseconds."))
                        .put("runId", integerProperty(
                                "Exact run id required for self_test_finished.")),
                "condition");
    }

    private static void addShellTools(final JSONArray tools)
            throws JSONException {
        tools.put(readTool(
                        "files.list",
                        "List files",
                        "List a directory through the same shell file service used by Files.",
                        objectSchema(new JSONObject()
                                        .put("path", stringProperty(
                                                "Absolute directory path."))
                                        .put("cursor", stringProperty(
                                                "Cursor from the previous page."))
                                        .put("limit", integerProperty(
                                                "Page size from 1 to 200."))
                                        .put("showHidden", booleanProperty(
                                                "Include hidden entries."))
                                        .put("sort", enumProperty(
                                                "Sort field.", "name",
                                                "modified", "size"))
                                        .put("order", enumProperty(
                                                "Sort order.", "ascending",
                                                "descending")),
                                "path")))
                .put(readTool(
                        "files.stat",
                        "Read file information",
                        "Read metadata for one shell-visible file or directory.",
                        pathSchema()))
                .put(actionTool(
                        "files.create",
                        "Create file entry",
                        "Create a file or directory through the shared Files backend.",
                        objectSchema(new JSONObject()
                                        .put("parent", stringProperty(
                                                "Absolute parent directory."))
                                        .put("name", stringProperty(
                                                "New entry name."))
                                        .put("directory", booleanProperty(
                                                "Create a directory.")),
                                "parent", "name")))
                .put(actionTool(
                        "files.rename",
                        "Rename file entry",
                        "Rename a file or directory through the shared Files backend.",
                        objectSchema(new JSONObject()
                                        .put("path", stringProperty(
                                                "Absolute source path."))
                                        .put("newName", stringProperty(
                                                "New entry name.")),
                                "path", "newName")))
                .put(actionTool(
                        "console.open",
                        "Open headless shell session",
                        "Open a bounded persistent headless Android shell session for deterministic commands.",
                        objectSchema(new JSONObject().put(
                                "directory", stringProperty(
                                        "Initial absolute working directory.")))))
                .put(actionTool(
                        "console.execute",
                        "Execute headless shell command",
                        "Execute a command in a persistent gated headless shell session.",
                        objectSchema(new JSONObject()
                                        .put("sessionId", stringProperty(
                                                "Console session id."))
                                        .put("command", stringProperty(
                                                "Shell command.")),
                                "sessionId", "command")))
                .put(readTool(
                        "console.status",
                        "Get console session",
                        "Read a console session's current working directory.",
                        sessionSchema()))
                .put(destructiveTool(
                        "console.close",
                        "Close console session",
                        "Close one persistent MCP console session.",
                        sessionSchema()))
                .put(actionTool(
                        "terminal.open",
                        "Open terminal window",
                        "Open a visible interactive MagicDesk PTY terminal on the active desktop.",
                        objectSchema(new JSONObject()
                                .put("directory", stringProperty(
                                        "Initial absolute working directory."))
                                .put("command", stringProperty(
                                        "Optional command to run after the terminal is ready."))
                                .put("backend", enumProperty(
                                        "PTY execution environment.",
                                        "shell", "termux")))))
                .put(readTool(
                        "terminal.list",
                        "List terminal windows",
                        "List live interactive MagicDesk terminal windows using cached process and terminal metadata; terminal.status refreshes live metadata.",
                        emptySchema()))
                .put(readTool(
                        "terminal.status",
                        "Get terminal status",
                        "Read task, display, shell and foreground process, dimensions, title, and working directory.",
                        terminalSchema()))
                .put(readTool(
                        "terminal.read",
                        "Read terminal screen",
                        "Read the textual viewport or bounded transcript of an interactive terminal.",
                        objectSchema(new JSONObject()
                                        .put("terminalId", stringProperty(
                                                "Interactive terminal id."))
                                        .put("scope", enumProperty(
                                                "Text region to read.",
                                                "viewport", "transcript"))
                                        .put("maxChars", integerProperty(
                                                "Maximum returned characters, up to 65536.")),
                                "terminalId")))
                .put(actionTool(
                        "terminal.write",
                        "Write terminal input",
                        "Write text directly to an interactive terminal PTY.",
                        objectSchema(new JSONObject()
                                        .put("terminalId", stringProperty(
                                                "Interactive terminal id."))
                                        .put("text", stringProperty(
                                                "Text or terminal control sequence.")),
                                "terminalId", "text")))
                .put(actionTool(
                        "terminal.send_key",
                        "Send terminal key",
                        "Send a semantic keyboard key with optional modifiers to an interactive terminal.",
                        objectSchema(new JSONObject()
                                        .put("terminalId", stringProperty(
                                                "Interactive terminal id."))
                                        .put("key", stringProperty(
                                                "Android key name such as ENTER, C, ESC, or UP."))
                                        .put("ctrl", booleanProperty(
                                                "Hold Control."))
                                        .put("alt", booleanProperty(
                                                "Hold Alt."))
                                        .put("shift", booleanProperty(
                                                "Hold Shift.")),
                                "terminalId", "key")))
                .put(destructiveTool(
                        "terminal.close",
                        "Close terminal window",
                        "Close one visible interactive terminal and its PTY process group.",
                        terminalSchema()))
                .put(readTool(
                        "tmux.list",
                        "List tmux sessions",
                        "List persistent tmux sessions inside Termux on demand; tmux may be unavailable.",
                        emptySchema()))
                .put(actionTool(
                        "tmux.open",
                        "Open tmux session",
                        "Open an existing tmux session by id, or open/create one by name, in a visible Termux Console.",
                        objectSchema(new JSONObject()
                                .put("sessionId", stringProperty(
                                        "Existing tmux session id from tmux.list."))
                                .put("name", stringProperty(
                                        "Persistent tmux session name to open or create.")))));
    }

    private static JSONObject pathSchema() throws JSONException {
        return objectSchema(new JSONObject().put(
                "path", stringProperty("Absolute shell path.")), "path");
    }

    private static JSONObject sessionSchema() throws JSONException {
        return objectSchema(new JSONObject().put(
                "sessionId", stringProperty("Console session id.")),
                "sessionId");
    }

    private static JSONObject terminalSchema() throws JSONException {
        return objectSchema(new JSONObject().put(
                "terminalId", stringProperty("Interactive terminal id.")),
                "terminalId");
    }

    private static JSONObject pixelSampleSchema() throws JSONException {
        final JSONObject point = objectSchema(new JSONObject()
                        .put("x", integerProperty("Horizontal coordinate."))
                        .put("y", integerProperty("Vertical coordinate.")),
                "x", "y");
        return objectSchema(new JSONObject()
                        .put("displayId", integerProperty(
                                "Optional active desktop display id."))
                        .put("points", arrayProperty(
                                "Coordinates to sample.", point)),
                "points");
    }

    private static JSONObject appTargetSchema(final boolean actionId)
            throws JSONException {
        final JSONObject properties = new JSONObject()
                .put("package", stringProperty("Android package name."))
                .put("component", stringProperty(
                        "Optional flattened activity component."));
        if (actionId) {
            properties.put("actionId", stringProperty(
                    "Action id returned by list_app_actions."));
            return objectSchema(properties, "package", "actionId");
        }
        return objectSchema(properties, "package");
    }

    private static JSONObject intentSchema(final boolean includeKind)
            throws JSONException {
        final JSONObject properties = new JSONObject()
                .put("name", stringProperty("Display name for Activity launches."))
                .put("intentUri", stringProperty(
                        "Raw Android Intent URI used as the base request."))
                .put("action", stringProperty("Android Intent action."))
                .put("dataUri", stringProperty("Android Intent data URI."))
                .put("mimeType", stringProperty("Android MIME type."))
                .put("package", stringProperty("Optional target package."))
                .put("component", stringProperty(
                        "Optional flattened target component."))
                .put("categories", arrayProperty(
                        "Intent categories.", stringProperty("Category name.")))
                .put("extras", openObjectProperty(
                        "Scalar, array, or nested scalar Intent extras."))
                .put("flags", integerProperty(
                        "Complete numeric Intent flags value."))
                .put("flagNames", arrayProperty(
                        "Portable symbolic Intent flags.",
                        stringProperty("Symbolic flag name.")))
                .put("mode", enumProperty(
                        "Activity launch mode.",
                        "auto", "windowed", "fullscreen"))
                .put("instance", enumProperty(
                        "Task instance policy.", "reuse", "new"))
                .put("preferredTaskId", integerProperty(
                        "Existing task to receive the action when instance is reuse."))
                .put("bounds", relativeBoundsProperty(
                        "Initial bounds within the desktop work area."))
                .put("displayId", integerProperty(
                        "Optional active desktop display id."))
                .put("chooser", booleanProperty(
                        "Wrap an Activity request in the Android chooser."))
                .put("chooserTitle", stringProperty(
                        "Optional Android chooser title."))
                .put("expectResult", booleanProperty(
                        "Create an asynchronous Activity result request."))
                .put("foreground", booleanProperty(
                        "Use startForegroundService for service requests."))
                .put("limit", integerProperty(
                        "Maximum handlers returned by discovery."));
        if (includeKind) {
            properties.put("kind", enumProperty(
                    "Android component kind.",
                    "activity", "broadcast", "service"));
        }
        return objectSchema(properties);
    }

    private static JSONObject openUriSchema() throws JSONException {
        return objectSchema(new JSONObject()
                        .put("uri", stringProperty("URI to open."))
                        .put("mimeType", stringProperty("Optional MIME type."))
                        .put("package", stringProperty("Optional target package."))
                        .put("component", stringProperty(
                                "Optional flattened Activity component."))
                        .put("mode", enumProperty(
                                "Launch mode.", "auto", "windowed", "fullscreen"))
                        .put("instance", enumProperty(
                                "Task instance policy.", "reuse", "new"))
                        .put("preferredTaskId", integerProperty(
                                "Existing task to receive the action."))
                        .put("bounds", relativeBoundsProperty(
                                "Initial bounds within the desktop work area."))
                        .put("displayId", integerProperty(
                                "Optional active desktop display id."))
                        .put("chooser", booleanProperty("Show Android chooser."))
                        .put("chooserTitle", stringProperty("Chooser title."))
                        .put("expectResult", booleanProperty(
                                "Create an asynchronous Activity result request.")),
                "uri");
    }

    private static JSONObject openFileSchema() throws JSONException {
        final JSONObject schema = objectSchema(new JSONObject()
                .put("displayId", integerProperty(
                        "Optional active desktop display id."))
                .put("path", stringProperty("Absolute shell file path."))
                .put("uri", stringProperty("Existing content URI."))
                .put("mimeType", stringProperty("Optional MIME type."))
                .put("operation", enumProperty(
                        "File operation.", "view", "edit"))
                .put("writable", booleanProperty(
                        "Grant write access when available."))
                .put("package", stringProperty("Optional target package."))
                .put("component", stringProperty(
                        "Optional flattened Activity component."))
                .put("mode", enumProperty(
                        "Launch mode.", "auto", "windowed", "fullscreen"))
                .put("instance", enumProperty(
                        "Task instance policy.", "reuse", "new"))
                .put("preferredTaskId", integerProperty(
                        "Existing task to receive the action."))
                .put("bounds", relativeBoundsProperty(
                        "Initial bounds within the desktop work area."))
                .put("chooser", booleanProperty("Show Android chooser."))
                .put("chooserTitle", stringProperty("Chooser title."))
                .put("expectResult", booleanProperty(
                        "Create an asynchronous Activity result request.")));
        return schema.put("oneOf", new JSONArray()
                .put(requiredOnly("path"))
                .put(requiredOnly("uri")));
    }

    private static JSONObject shareSchema() throws JSONException {
        final JSONObject schema = objectSchema(new JSONObject()
                .put("text", stringProperty("Optional shared text."))
                .put("subject", stringProperty("Optional shared subject."))
                .put("files", arrayProperty(
                        "Absolute shell paths or content URIs.",
                        stringProperty("File path or content URI.")))
                .put("mimeType", stringProperty("Optional shared MIME type."))
                .put("package", stringProperty("Optional target package."))
                .put("component", stringProperty(
                        "Optional flattened Activity component."))
                .put("mode", enumProperty(
                        "Launch mode.", "auto", "windowed", "fullscreen"))
                .put("instance", enumProperty(
                        "Task instance policy.", "reuse", "new"))
                .put("preferredTaskId", integerProperty(
                        "Existing task to receive the action."))
                .put("bounds", relativeBoundsProperty(
                        "Initial bounds within the desktop work area."))
                .put("displayId", integerProperty(
                        "Optional active desktop display id."))
                .put("chooser", booleanProperty("Show Android chooser."))
                .put("chooserTitle", stringProperty("Chooser title.")));
        return schema.put("anyOf", new JSONArray()
                .put(requiredOnly("text"))
                .put(requiredOnly("files")));
    }

    private static JSONObject androidActionSchema() throws JSONException {
        return objectSchema(new JSONObject()
                        .put("actionId", enumProperty(
                                "Stable Android action id.",
                                AndroidDesktopActionCatalog.ids()))
                        .put("displayId", integerProperty(
                                "Optional active desktop display id."))
                        .put("mimeType", stringProperty(
                                "Document MIME type."))
                        .put("multiple", booleanProperty(
                                "Allow multiple documents."))
                        .put("suggestedName", stringProperty(
                                "Suggested created document name."))
                        .put("package", stringProperty(
                                "Package for app-details."))
                        .put("listenerComponent", stringProperty(
                                "Notification listener component."))
                        .put("mode", enumProperty(
                                "Launch mode.", "auto", "windowed", "fullscreen"))
                        .put("instance", enumProperty(
                                "Task instance policy.", "reuse", "new"))
                        .put("preferredTaskId", integerProperty(
                                "Existing task to receive the action."))
                        .put("bounds", relativeBoundsProperty(
                                "Initial bounds within the desktop work area.")),
                "actionId");
    }

    private static JSONObject relativeBoundsProperty(
            final String description) throws JSONException {
        return objectSchema(new JSONObject()
                        .put("x", integerProperty(
                                "Horizontal position on a 0..10000 scale."))
                        .put("y", integerProperty(
                                "Vertical position on a 0..10000 scale."))
                        .put("width", integerProperty(
                                "Width on a 1..10000 scale."))
                        .put("height", integerProperty(
                                "Height on a 1..10000 scale.")),
                "x", "y", "width", "height")
                .put("description", description);
    }

    private static JSONObject notificationActionSchema() throws JSONException {
        return objectSchema(new JSONObject()
                        .put("key", stringProperty(
                                "Opaque notification key from list_notifications."))
                        .put("operation", enumProperty(
                                "Notification operation.",
                                "open", "action", "dismiss"))
                        .put("actionIndex", integerProperty(
                                "Action index from list_notifications."))
                        .put("displayId", integerProperty(
                                "Optional active desktop display id.")),
                "key");
    }

    private static JSONObject desktopEntrySchema() throws JSONException {
        return objectSchema(new JSONObject()
                .put("displayId", integerProperty(
                        "Optional active desktop display id."))
                .put("desktopPath", stringProperty(
                        "Absolute .desktop file path."))
                .put("files", arrayProperty(
                        "File arguments for Desktop Entry field codes.",
                        stringProperty("Absolute file path."))),
                "desktopPath");
    }

    private static JSONObject appFunctionSearchSchema() throws JSONException {
        return objectSchema(new JSONObject()
                .put("package", stringProperty("Optional target package."))
                .put("functionId", stringProperty(
                        "Optional function id; package is also required."))
                .put("schemaCategory", stringProperty(
                        "Optional App Function schema category."))
                .put("schemaName", stringProperty(
                        "Optional App Function schema name."))
                .put("minSchemaVersion", integerProperty(
                        "Optional minimum schema version."))
                .put("timeoutMillis", integerProperty(
                        "Bounded operation timeout, up to 60000 ms.")));
    }

    private static JSONObject appFunctionExecuteSchema() throws JSONException {
        return objectSchema(new JSONObject()
                        .put("package", stringProperty("Target package."))
                        .put("functionId", stringProperty(
                                "Published App Function identifier."))
                        .put("parameters", openObjectProperty(
                                "GenericDocument properties, optionally with namespace, id, schemaType, and a properties object."))
                        .put("timeoutMillis", integerProperty(
                                "Bounded operation timeout, up to 60000 ms.")),
                "package", "functionId");
    }

    private static JSONObject taskIdSchema() throws JSONException {
        return objectSchema(new JSONObject().put(
                "taskId", integerProperty("Android task id.")), "taskId");
    }

    private static JSONObject readTool(
            final String name,
            final String title,
            final String description,
            final JSONObject schema) throws JSONException {
        return tool(name, title, description, schema,
                true, false, true);
    }

    private static JSONObject actionTool(
            final String name,
            final String title,
            final String description,
            final JSONObject schema) throws JSONException {
        return tool(name, title, description, schema,
                false, false, false);
    }

    private static JSONObject destructiveTool(
            final String name,
            final String title,
            final String description,
            final JSONObject schema) throws JSONException {
        return tool(name, title, description, schema,
                false, true, false);
    }

    private static JSONObject tool(
            final String name,
            final String title,
            final String description,
            final JSONObject schema,
            final boolean readOnly,
            final boolean destructive,
            final boolean idempotent) throws JSONException {
        return new JSONObject()
                .put("name", name)
                .put("title", title)
                .put("description", description)
                .put("inputSchema", schema)
                .put("outputSchema", resultSchema(name))
                .put("annotations", new JSONObject()
                        .put("readOnlyHint", readOnly)
                        .put("destructiveHint", destructive)
                        .put("idempotentHint", idempotent)
                        .put("openWorldHint", false));
    }

    private static JSONObject resultSchema(final String toolName)
            throws JSONException {
        final JSONObject error = new JSONObject()
                .put("type", new JSONArray().put("object").put("null"))
                .put("properties", new JSONObject()
                        .put("code", stringProperty(
                                "Stable machine-readable error code."))
                        .put("retryable", booleanProperty(
                                "Whether retrying after state changes can help."))
                        .put("observation", new JSONObject()
                                .put("type", "object")
                                .put("additionalProperties", true)));
        return objectSchema(new JSONObject()
                        .put("success", new JSONObject()
                                .put("type", "boolean"))
                        .put("message", stringProperty("Result message."))
                        .put("data", dataSchema(toolName))
                        .put("error", error),
                "success", "message", "data", "error");
    }

    private static JSONObject dataSchema(final String toolName)
            throws JSONException {
        final JSONObject properties = new JSONObject();
        switch (toolName) {
            case "get_state":
                properties.put("generatedAtMillis", integerProperty("Timestamp."))
                        .put("session", openObjectProperty("Desktop session."))
                        .put("ui", openObjectProperty("Desktop UI state."))
                        .put("runtime", openObjectProperty("Runtime state."));
                break;
            case "get_pointer_state":
                properties.put("generatedAtMillis", integerProperty("Timestamp."))
                        .put("displayId", integerProperty("Display id."))
                        .put("active", booleanProperty(
                                "Whether this is the active desktop display."))
                        .put("provider", stringProperty(
                                "Selected platform pointer provider."))
                        .put("relayRequired", booleanProperty(
                                "Whether this platform requires the mouse relay."))
                        .put("relayReady", booleanProperty(
                                "Whether the required mouse relay is ready."))
                        .put("routingReady", booleanProperty(
                                "Whether input routing is ready."))
                        .put("positionAvailable", booleanProperty(
                                "Whether the platform exposes cursor coordinates."))
                        .put("x", nullableIntegerProperty(
                                "Observed cursor x coordinate."))
                        .put("y", nullableIntegerProperty(
                                "Observed cursor y coordinate."));
                break;
            case "list_displays":
                properties.put("displays", arrayProperty(
                        "Connected displays.", openObjectProperty("Display.")));
                break;
            case "list_tasks":
                properties.put("tasks", arrayProperty(
                                "Task page.", openObjectProperty("Task.")))
                        .put("count", integerProperty("Returned count."))
                        .put("total", integerProperty("Matching count."))
                        .put("nextCursor", nullableStringProperty(
                                "Next page cursor."));
                break;
            case "list_apps":
                properties.put("apps", arrayProperty(
                                "Application page.", openObjectProperty("App.")))
                        .put("count", integerProperty("Returned count."))
                        .put("total", integerProperty("Matching count."))
                        .put("nextCursor", nullableStringProperty(
                                "Next page cursor."));
                break;
            case "list_ui_elements":
                properties.put("available", booleanProperty(
                                "Live desktop UI is available."))
                        .put("displayId", integerProperty("Display id."))
                        .put("elements", arrayProperty(
                                "Semantic UI elements.",
                                openObjectProperty("UI element.")))
                        .put("count", integerProperty("Returned count."));
                break;
            case "get_events":
                properties.put("latestId", integerProperty("Latest event id."))
                        .put("events", arrayProperty(
                                "Structured events.", openObjectProperty("Event.")));
                break;
            case "get_diagnostics":
                properties.put("report", stringProperty(
                        "Compatibility report."));
                break;
            case "get_self_test":
                selfTestStateProperties(properties);
                properties.put("running", booleanProperty("Test is active."))
                        .put("resultModifiedAtMillis", integerProperty(
                                "Timestamp of the latest saved result."))
                        .put("report", stringProperty("Latest test report."));
                break;
            case "run_self_test":
                selfTestStateProperties(properties);
                break;
            case "cancel_self_test":
                selfTestStateProperties(properties);
                properties.put("cancellationStatus", enumProperty(
                        "Cancellation request outcome.",
                        "accepted", "already_requested", "not_active",
                        "run_mismatch", "cleanup_started"));
                break;
            case "get_termux_x11_status":
            case "reconnect_termux_x11":
                properties.put("termuxX11", openObjectProperty(
                        "Typed Termux:X11 runtime status."));
                break;
            case "capture_screenshot":
                properties.put("displayId", integerProperty("Display id."))
                        .put("width", integerProperty("Image width."))
                        .put("height", integerProperty("Image height."))
                        .put("mimeType", stringProperty("Image MIME type."))
                        .put("captureSource", stringProperty("Capture source."));
                break;
            case "sample_pixels":
                properties.put("displayId", integerProperty("Display id."))
                        .put("samples", arrayProperty(
                                "Pixel values.", openObjectProperty("Pixel.")));
                break;
            case "get_recording_status":
            case "start_recording":
            case "stop_recording":
                properties.put("state", stringProperty("Recording state."))
                        .put("message", stringProperty("Recording detail."));
                break;
            case "list_app_actions":
                properties.put("package", stringProperty("Package."))
                        .put("actions", arrayProperty(
                                "Available actions.",
                                openObjectProperty("Application action.")));
                break;
            case "query_intent_handlers":
                properties.put("kind", stringProperty("Component kind."))
                        .put("visibilityScope", stringProperty(
                                "Package visibility identity used by discovery."))
                        .put("count", integerProperty("Returned handler count."))
                        .put("truncated", booleanProperty(
                                "Whether more handlers were available."))
                        .put("handlers", arrayProperty(
                                "Resolved Android handlers.",
                                openObjectProperty("Handler.")));
                break;
            case "launch_app":
                properties.put("package", stringProperty("Target package."));
                taskLaunchResultProperties(properties);
                break;
            case "launch_intent":
            case "open_uri":
            case "open_file":
            case "share":
                activityLaunchResultProperties(properties);
                break;
            case "list_android_actions":
                properties.put("actions", arrayProperty(
                        "Stable semantic Android actions.",
                        openObjectProperty("Android action.")));
                break;
            case "invoke_android_action":
                activityLaunchResultProperties(properties);
                break;
            case "get_activity_history":
                properties.put("launches", arrayProperty(
                        "Bounded observed Activity launch history.",
                        openObjectProperty("Activity launch evidence.")));
                break;
            case "invoke_app_action":
                properties.put("package", stringProperty("Package."))
                        .put("actionId", stringProperty("Application action id."))
                        .put("instance", stringProperty(
                                "Requested task instance policy."));
                taskLaunchResultProperties(properties);
                break;
            case "list_notifications":
                properties.put("connected", booleanProperty(
                                "Notification listener connection state."))
                        .put("connectionIssue", stringProperty(
                                "Connection diagnostic code."))
                        .put("unreadCount", integerProperty(
                                "Active unread notification count."))
                        .put("notifications", arrayProperty(
                                "Active notifications.",
                                openObjectProperty("Notification.")));
                break;
            case "invoke_notification":
                properties.put("key", stringProperty(
                                "Opaque notification key."))
                        .put("operation", stringProperty(
                                "Accepted notification operation."));
                break;
            case "get_intent_result":
                properties.put("requestId", stringProperty(
                                "Activity result request id."))
                        .put("state", stringProperty(
                                "pending, completed, failed, or not_found."))
                        .put("timestampMillis", integerProperty(
                                "Last result-state timestamp."))
                        .put("resultCode", integerProperty(
                                "Android Activity result code when completed."))
                        .put("consumed", booleanProperty(
                                "Whether this read removed the terminal result."))
                        .put("releasedPersistedUris", arrayProperty(
                                "Persisted URI grants released by consume=true.",
                                stringProperty("Released content URI.")))
                        .put("data", openObjectProperty(
                                "Sanitized result Intent data."));
                break;
            case "search_app_functions":
                properties.put("count", integerProperty(
                                "Returned App Function count."))
                        .put("truncated", booleanProperty(
                                "Whether more functions were available."))
                        .put("functions", arrayProperty(
                                "Discovered App Functions.",
                                openObjectProperty("App Function.")));
                break;
            case "execute_app_function":
                properties.put("package", stringProperty("Target package."))
                        .put("functionId", stringProperty("Function id."))
                        .put("result", openObjectProperty(
                                "Returned GenericDocument."));
                break;
            case "clipboard.read_text":
            case "clipboard.write_text":
            case "clipboard.clear":
                properties.put("access", stringProperty(
                                "Android clipboard access state."))
                        .put("itemCount", integerProperty(
                                "Clipboard item count, or -1 for metadata-only state."))
                        .put("mimeTypes", arrayProperty(
                                "Declared clipboard MIME types.",
                                stringProperty("MIME type.")))
                        .put("sensitive", booleanProperty(
                                "Whether the clipboard is marked sensitive."))
                        .put("magicDeskFileClip", booleanProperty(
                                "Whether this is a MagicDesk file-operation clip."))
                        .put("textLength", integerProperty(
                                "Text length when text was read or written."));
                if ("clipboard.read_text".equals(toolName)) {
                    properties.put("text", stringProperty(
                                    "Bounded clipboard text returned by the explicit read."))
                            .put("truncated", booleanProperty(
                                    "Whether clipboard text exceeded the returned limit."));
                }
                break;
            case "clipboard.open":
            case "clipboard.share":
                activityLaunchResultProperties(properties);
                break;
            case "send_broadcast":
            case "start_service":
                properties.put("kind", stringProperty("Component kind."))
                        .put("action", stringProperty("Intent action."))
                        .put("component", stringProperty("Target component."));
                break;
            case "launch_desktop_entry":
                properties.put("kind", stringProperty("Desktop entry kind."))
                        .put("displayId", integerProperty("Display id."));
                break;
            case "begin_trace":
                properties.put("traceId", stringProperty("Trace id."))
                        .put("startedAtMillis", integerProperty(
                                "Trace start timestamp."))
                        .put("afterEventId", integerProperty(
                                "Event sequence baseline."));
                break;
            case "end_trace":
                properties.put("traceId", stringProperty("Trace id."))
                        .put("truncated", booleanProperty(
                                "Older trace events were evicted."))
                        .put("eventCount", integerProperty(
                                "Returned event count."))
                        .put("failureCount", integerProperty(
                                "Returned failure count."))
                        .put("events", arrayProperty(
                                "Events recorded during the trace.",
                                openObjectProperty("Event.")))
                        .put("failures", arrayProperty(
                                "Failed operations, crashes, and ANRs.",
                                openObjectProperty("Failure event.")))
                        .put("state", openObjectProperty(
                                "Final desktop state."))
                        .put("tasks", openObjectProperty(
                                "Final task snapshot."));
                break;
            case "files.list":
                properties.put("path", stringProperty("Directory path."))
                        .put("entries", arrayProperty(
                                "File entries.", openObjectProperty("File.")))
                        .put("nextCursor", nullableStringProperty(
                                "Next page cursor."));
                break;
            case "files.stat":
            case "files.create":
            case "files.rename":
                properties.put("file", openObjectProperty("File metadata."));
                break;
            case "console.open":
            case "console.status":
            case "console.close":
                properties.put("sessionId", stringProperty("Session id."))
                        .put("workingDirectory", stringProperty(
                                "Current directory."));
                break;
            case "console.execute":
                properties.put("sessionId", stringProperty("Session id."))
                        .put("exitCode", integerProperty("Command exit code."))
                        .put("output", stringProperty("Combined output."))
                        .put("workingDirectory", stringProperty(
                                "Current directory."));
                break;
            case "terminal.open":
                properties.put("accepted", booleanProperty(
                                "Whether the launch was accepted."))
                        .put("terminalId", stringProperty(
                                "Reserved interactive terminal id."))
                        .put("backend", stringProperty(
                                "Selected terminal backend."))
                        .put("observed", booleanProperty(
                                "Whether the terminal registered before the response."))
                        .put("workingDirectory", stringProperty(
                                "Requested initial directory."))
                        .put("commandProvided", booleanProperty(
                                "Whether an initial command was supplied."));
                break;
            case "terminal.list":
                properties.put("count", integerProperty(
                                "Number of live terminal windows."))
                        .put("terminals", arrayProperty(
                                "Live terminal windows.",
                                openObjectProperty("Terminal.")));
                break;
            case "terminal.status":
                terminalResultProperties(properties);
                break;
            case "terminal.read":
                properties.put("terminalId", stringProperty(
                                "Interactive terminal id."))
                        .put("scope", stringProperty("Returned text region."))
                        .put("text", stringProperty("Terminal text."))
                        .put("truncated", booleanProperty(
                                "Whether older text was omitted."));
                break;
            case "terminal.write":
                properties.put("terminalId", stringProperty(
                                "Interactive terminal id."))
                        .put("characters", integerProperty(
                                "Number of accepted characters."));
                break;
            case "terminal.send_key":
                properties.put("terminalId", stringProperty(
                                "Interactive terminal id."))
                        .put("keyCode", integerProperty(
                                "Resolved Android key code."))
                        .put("metaState", integerProperty(
                                "Resolved Android modifier state."));
                break;
            case "terminal.close":
                properties.put("terminalId", stringProperty(
                        "Interactive terminal id."));
                break;
            case "tmux.list":
                properties.put("available", booleanProperty(
                                "Whether tmux is installed inside Termux."))
                        .put("detail", stringProperty(
                                "Availability detail when tmux is absent."))
                        .put("count", integerProperty(
                                "Number of persistent tmux sessions."))
                        .put("sessions", arrayProperty(
                                "Persistent tmux sessions.",
                                openObjectProperty("tmux session.")));
                break;
            case "tmux.open":
                properties.put("accepted", booleanProperty(
                                "Whether the terminal launch was accepted."))
                        .put("terminalId", stringProperty(
                                "Reserved interactive terminal id."))
                        .put("backend", stringProperty(
                                "Selected terminal backend."))
                        .put("observed", booleanProperty(
                                "Whether the terminal registered before the response."))
                        .put("workingDirectory", stringProperty(
                                "Requested initial directory."))
                        .put("commandProvided", booleanProperty(
                                "Whether an initial command was supplied."))
                        .put("tmuxSessionId", stringProperty(
                                "Known tmux session id, empty for a new name."))
                        .put("tmuxSessionName", stringProperty(
                                "Requested tmux session name."));
                break;
            default:
                properties.put("accepted", booleanProperty(
                        "Operation was accepted when present."));
                break;
        }
        return new JSONObject()
                .put("type", "object")
                .put("title", toolName + " data")
                .put("properties", properties)
                .put("additionalProperties", true);
    }

    private static void activityLaunchResultProperties(
            final JSONObject properties) throws JSONException {
        properties.put("kind", stringProperty(
                        "Android component kind."))
                .put("action", stringProperty("Android Intent action."))
                .put("dataUri", stringProperty("Android Intent data URI."))
                .put("mimeType", stringProperty("Android Intent MIME type."))
                .put("package", stringProperty("Requested target package."))
                .put("component", stringProperty(
                        "Requested target component."))
                .put("resolvedComponent", stringProperty(
                        "Resolved target Activity."))
                .put("resolution", enumProperty(
                        "Activity resolution state.",
                        "concrete", "resolver", "none"))
                .put("handlerCount", integerProperty(
                        "Number of matching Activity handlers."))
                .put("authorization", openObjectProperty(
                        "App-identity Activity authorization decision."))
                .put("launchIdentity", enumProperty(
                        "Identity authorized to initiate the Activity.",
                        "application", "shell"))
                .put("delivery", enumProperty(
                        "Activity delivery mechanism.",
                        "direct-intent",
                        "pending-intent",
                        "activity-result-relay"))
                .put("relay", booleanProperty(
                        "Whether an Activity-result relay owns the launch."))
                .put("resultExpected", booleanProperty(
                        "Whether an Activity result is pending."))
                .put("requestId", stringProperty(
                        "Optional Activity result request id."));
        taskLaunchResultProperties(properties);
    }

    private static void taskLaunchResultProperties(
            final JSONObject properties) throws JSONException {
        properties.put("displayId", integerProperty("Display id."))
                .put("mode", stringProperty("Requested launch mode."))
                .put("taskObserved", booleanProperty(
                        "Whether the production launch identified a task."))
                .put("taskId", integerProperty("Observed final task id."))
                .put("transportTaskId", integerProperty(
                        "Task id first identified by the launch transport."))
                .put("reused", booleanProperty(
                        "Whether an existing task was reused."))
                .put("observedComponent", stringProperty(
                        "Observed root Activity component."))
                .put("observedTopActivity", stringProperty(
                        "Observed top Activity component."))
                .put("observedActivityType", integerProperty(
                        "Observed Android activity type."))
                .put("observedMode", stringProperty(
                        "Observed semantic windowing mode."))
                .put("nativeWindowingMode", stringProperty(
                        "Observed framework windowing mode."))
                .put("bounds", openObjectProperty(
                        "Observed task bounds."));
    }

    private static void selfTestStateProperties(
            final JSONObject properties) throws JSONException {
        properties.put("runId", nullableIntegerProperty(
                        "Current or most recently completed run id."))
                .put("state", enumProperty(
                        "Self-test lifecycle state.",
                        "idle", "starting", "running", "cleanup",
                        "completed", "cancelled"))
                .put("active", booleanProperty(
                        "Whether the run is starting, running, or cleaning up."))
                .put("target", nullableStringProperty(
                        "Selected phone, simulated, wired, or wireless target."))
                .put("mode", nullableStringProperty(
                        "Selected full or fail_fast execution mode."))
                .put("stage", nullableStringProperty(
                        "Existing code of the currently executing stage."))
                .put("lastCompletedStage", nullableStringProperty(
                        "Existing code of the last result-recorded stage."))
                .put("cancelRequested", booleanProperty(
                        "Whether cancellation has been requested."))
                .put("requestedAtMillis", nullableIntegerProperty(
                        "Request timestamp."))
                .put("startedAtMillis", nullableIntegerProperty(
                        "Execution start timestamp."))
                .put("completedAtMillis", nullableIntegerProperty(
                        "Terminal-state timestamp."))
                .put("detail", stringProperty("Lifecycle detail."));
    }

    private static void terminalResultProperties(
            final JSONObject properties) throws JSONException {
        properties.put("terminalId", stringProperty(
                        "Interactive terminal id."))
                .put("taskId", integerProperty("Android task id."))
                .put("displayId", integerProperty("Android display id."))
                .put("focused", booleanProperty("Window focus state."))
                .put("ready", booleanProperty("PTY readiness."))
                .put("processId", integerProperty("Interactive shell PID."))
                .put("columns", integerProperty("Terminal columns."))
                .put("rows", integerProperty("Terminal rows."))
                .put("workingDirectory", stringProperty(
                        "Current shell directory."))
                .put("title", stringProperty("Terminal OSC title."))
                .put("taskLabel", stringProperty(
                        "Current label derived from process metadata and OSC title."))
                .put("foregroundProcess", openObjectProperty(
                        "Foreground PTY process metadata."));
    }

    private static JSONObject emptySchema() throws JSONException {
        return objectSchema(new JSONObject());
    }

    private static JSONObject objectSchema(
            final JSONObject properties,
            final String... required) throws JSONException {
        final JSONObject schema = new JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("additionalProperties", false);
        if (required != null && required.length > 0) {
            final JSONArray names = new JSONArray();
            for (final String name : required) {
                names.put(name);
            }
            schema.put("required", names);
        }
        return schema;
    }

    private static JSONObject requiredOnly(final String property)
            throws JSONException {
        return new JSONObject().put(
                "required", new JSONArray().put(property));
    }

    private static JSONObject stringProperty(final String description)
            throws JSONException {
        return new JSONObject()
                .put("type", "string")
                .put("description", description);
    }

    private static JSONObject integerProperty(final String description)
            throws JSONException {
        return new JSONObject()
                .put("type", "integer")
                .put("description", description);
    }

    private static JSONObject booleanProperty(final String description)
            throws JSONException {
        return new JSONObject()
                .put("type", "boolean")
                .put("description", description);
    }

    private static JSONObject nullableStringProperty(
            final String description) throws JSONException {
        return new JSONObject()
                .put("type", new JSONArray().put("string").put("null"))
                .put("description", description);
    }

    private static JSONObject nullableIntegerProperty(
            final String description) throws JSONException {
        return new JSONObject()
                .put("type", new JSONArray().put("integer").put("null"))
                .put("description", description);
    }

    private static JSONObject openObjectProperty(final String description)
            throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("description", description)
                .put("additionalProperties", true);
    }

    private static JSONObject arrayProperty(
            final String description,
            final JSONObject items) throws JSONException {
        return new JSONObject()
                .put("type", "array")
                .put("description", description)
                .put("items", items);
    }

    private static JSONObject enumProperty(
            final String description,
            final String... values) throws JSONException {
        final JSONArray choices = new JSONArray();
        for (final String value : values) {
            choices.put(value);
        }
        return stringProperty(description).put("enum", choices);
    }

    private static JSONObject boundsProperty(final String description)
            throws JSONException {
        return objectSchema(new JSONObject()
                        .put("left", integerProperty("Left coordinate."))
                        .put("top", integerProperty("Top coordinate."))
                        .put("right", integerProperty("Right coordinate."))
                        .put("bottom", integerProperty("Bottom coordinate.")),
                "left", "top", "right", "bottom")
                .put("description", description);
    }
}
