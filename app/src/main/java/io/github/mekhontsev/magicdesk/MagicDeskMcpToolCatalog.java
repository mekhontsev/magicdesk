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
                                        .put("displayId", integerProperty(
                                                "Active desktop display id."))
                                        .put("bounds", boundsProperty(
                                                "Initial bounds for a newly launched windowed task.")),
                                "package")))
                .put(actionTool(
                        "focus_task",
                        "Focus task",
                        "Focus a task through MagicDesk's ordered task focus path.",
                        taskIdSchema()))
                .put(destructiveTool(
                        "close_task",
                        "Close task",
                        "Close a managed application task without force-stopping its package.",
                        taskIdSchema()))
                .put(actionTool(
                        "set_window_mode",
                        "Set window mode",
                        "Change a task between native windowed and fullscreen modes.",
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
                        "Toggle between the desktop and the current application workspace.",
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
                        "Open a MagicDesk Files, Console, Task Manager, or Settings window.",
                        objectSchema(new JSONObject().put(
                                "builtin", enumProperty(
                                        "Built-in window.",
                                        "files", "console",
                                        "task_manager", "settings")),
                                "builtin")))
                .put(actionTool(
                        "arrange_task",
                        "Arrange task",
                        "Apply the same native task transition used by MagicDesk window shortcuts.",
                        objectSchema(new JSONObject()
                                        .put("taskId", integerProperty(
                                                "Android task id."))
                                        .put("arrangement", enumProperty(
                                                "Target arrangement.",
                                                "left", "right",
                                                "maximize", "restore")),
                                "taskId", "arrangement")))
                .put(readTool(
                        "list_app_actions",
                        "List application actions",
                        "List supported manifest shortcuts for an application.",
                        appTargetSchema(false)))
                .put(actionTool(
                        "invoke_app_action",
                        "Invoke application action",
                        "Launch one manifest shortcut through the desktop window pipeline.",
                        appTargetSchema(true)))
                .put(actionTool(
                        "launch_spec",
                        "Launch desktop specification",
                        "Launch a .desktop file or an Android launch specification through the shared coordinator.",
                        launchSpecSchema()))
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
                        "run_self_test",
                        "Run desktop self-test",
                        "Launch the built-in UI self-test on an exact display target.",
                        objectSchema(new JSONObject().put(
                                "target", enumProperty(
                                        "Self-test display target.",
                                        "phone", "simulated",
                                        "wired", "wireless")))))
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
                        .put("startedAfterMillis", integerProperty(
                                "For self_test_finished, require a result written after this timestamp.")),
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
                        terminalSchema()));
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

    private static JSONObject launchSpecSchema() throws JSONException {
        final JSONObject android = objectSchema(new JSONObject()
                .put("name", stringProperty("Display name."))
                .put("package", stringProperty("Android package."))
                .put("component", stringProperty(
                        "Flattened Android component."))
                .put("action", stringProperty("Intent action."))
                .put("intentUri", stringProperty("Android Intent URI."))
                .put("mode", enumProperty(
                        "Launch mode.", "auto", "windowed", "fullscreen")));
        return objectSchema(new JSONObject()
                .put("displayId", integerProperty(
                        "Optional active desktop display id."))
                .put("desktopPath", stringProperty(
                        "Absolute .desktop file path."))
                .put("android", android)
                .put("files", arrayProperty(
                        "File arguments for Desktop Entry field codes.",
                        stringProperty("Absolute file path."))));
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
                properties.put("running", booleanProperty("Test is running."))
                        .put("report", stringProperty("Latest test report."));
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
