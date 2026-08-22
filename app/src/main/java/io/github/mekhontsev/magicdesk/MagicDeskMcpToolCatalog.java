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
                        "magicdesk.get_state",
                        "Get desktop state",
                        "Read current MagicDesk runtime and desktop session state.",
                        emptySchema()))
                .put(readTool(
                        "magicdesk.list_displays",
                        "List displays",
                        "List connected displays, current modes and supported modes.",
                        emptySchema()))
                .put(readTool(
                        "magicdesk.list_tasks",
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
                        "magicdesk.list_apps",
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
                        "magicdesk.get_events",
                        "Get automation events",
                        "Read the bounded structured automation event history.",
                        objectSchema(new JSONObject()
                                .put("afterId", integerProperty(
                                        "Return events newer than this id."))
                                .put("limit", integerProperty(
                                        "Maximum number of events, up to 256.")))))
                .put(readTool(
                        "magicdesk.get_diagnostics",
                        "Get diagnostics",
                        "Build the full MagicDesk compatibility report.",
                        emptySchema()))
                .put(readTool(
                        "magicdesk.get_self_test",
                        "Get self-test result",
                        "Read the current status and latest desktop self-test report.",
                        emptySchema()))
                .put(actionTool(
                        "magicdesk.start_desktop",
                        "Start desktop",
                        "Start MagicDesk on the requested available display target.",
                        objectSchema(new JSONObject().put(
                                "target", enumProperty(
                                        "Target display environment.",
                                        "auto", "phone", "simulated",
                                        "wired", "wireless")))))
                .put(actionTool(
                        "magicdesk.close_desktop",
                        "Close desktop",
                        "Run the normal Close Desktop procedure for the active session.",
                        emptySchema()))
                .put(actionTool(
                        "magicdesk.launch_app",
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
                                                "Active desktop display id.")),
                                "package")))
                .put(actionTool(
                        "magicdesk.focus_task",
                        "Focus task",
                        "Focus a task through MagicDesk's ordered task focus path.",
                        taskIdSchema()))
                .put(destructiveTool(
                        "magicdesk.close_task",
                        "Close task",
                        "Close a managed application task without force-stopping its package.",
                        taskIdSchema()))
                .put(actionTool(
                        "magicdesk.set_window_mode",
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
                        "magicdesk.set_window_bounds",
                        "Set window bounds",
                        "Resize a native freeform task to explicit display coordinates.",
                        objectSchema(new JSONObject()
                                        .put("taskId", integerProperty(
                                                "Android task id."))
                                        .put("bounds", boundsProperty(
                                                "Required task bounds.")),
                                "taskId", "bounds")))
                .put(actionTool(
                        "magicdesk.show_start",
                        "Show Start",
                        "Open the MagicDesk Start menu on the active desktop.",
                        emptySchema()))
                .put(actionTool(
                        "magicdesk.show_desktop",
                        "Toggle desktop",
                        "Toggle between the desktop and the current application workspace.",
                        emptySchema()))
                .put(actionTool(
                        "magicdesk.open_settings",
                        "Open settings",
                        "Open MagicDesk settings on the active desktop or phone.",
                        emptySchema()))
                .put(readTool(
                        "magicdesk.capture_screenshot",
                        "Capture screenshot",
                        "Capture the active desktop as an in-memory PNG image.",
                        objectSchema(new JSONObject().put(
                                "displayId", integerProperty(
                                        "Optional active desktop display id.")))))
                .put(readTool(
                        "magicdesk.wait_for_state",
                        "Wait for state",
                        "Wait for an observable desktop, task, taskbar, wallpaper, or self-test condition.",
                        waitSchema()));
        tools.put(readTool(
                        "magicdesk.sample_pixels",
                        "Sample display pixels",
                        "Read up to 64 exact pixels from the active desktop without creating a file.",
                        pixelSampleSchema()))
                .put(actionTool(
                        "magicdesk.open_builtin",
                        "Open built-in window",
                        "Open a MagicDesk Files, Console, Task Manager, or Settings window.",
                        objectSchema(new JSONObject().put(
                                "builtin", enumProperty(
                                        "Built-in window.",
                                        "files", "console",
                                        "task_manager", "settings")),
                                "builtin")))
                .put(actionTool(
                        "magicdesk.arrange_task",
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
                        "magicdesk.list_app_actions",
                        "List application actions",
                        "List supported manifest shortcuts for an application.",
                        appTargetSchema(false)))
                .put(actionTool(
                        "magicdesk.invoke_app_action",
                        "Invoke application action",
                        "Launch one manifest shortcut through the desktop window pipeline.",
                        appTargetSchema(true)))
                .put(actionTool(
                        "magicdesk.launch_spec",
                        "Launch desktop specification",
                        "Launch a .desktop file or an Android launch specification through the shared coordinator.",
                        launchSpecSchema()))
                .put(readTool(
                        "magicdesk.get_recording_status",
                        "Get recording status",
                        "Read the current desktop screen recording state.",
                        emptySchema()))
                .put(actionTool(
                        "magicdesk.start_recording",
                        "Start screen recording",
                        "Start recording the active desktop with the configured audio mode.",
                        emptySchema()))
                .put(actionTool(
                        "magicdesk.stop_recording",
                        "Stop screen recording",
                        "Finalize and save the active desktop recording.",
                        emptySchema()));
        if (includeDeveloperTools) {
            tools.put(destructiveTool(
                        "magicdesk.force_stop_app",
                        "Force stop application",
                        "Force-stop an Android package. Developer automation only.",
                        objectSchema(new JSONObject().put(
                                "package", stringProperty(
                                        "Android package name.")),
                                "package")))
                .put(actionTool(
                        "magicdesk.run_self_test",
                        "Run desktop self-test",
                        "Launch the built-in UI self-test on an exact display target.",
                        objectSchema(new JSONObject().put(
                                "target", enumProperty(
                                        "Self-test display target.",
                                        "phone", "simulated",
                                        "wired", "wireless")))))
                .put(actionTool(
                        "magicdesk.send_key",
                        "Send key",
                        "Inject one Android key event on a desktop display. Developer automation only.",
                        objectSchema(new JSONObject()
                                        .put("displayId", integerProperty(
                                                "Optional active display id."))
                                        .put("keyCode", stringProperty(
                                                "Android KEYCODE name.")),
                                "keyCode")))
                .put(actionTool(
                        "magicdesk.move_pointer",
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
                        "magicdesk.click_pointer",
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
                                "task_bounds", "pointer_ready", "ui_visible",
                                "taskbar_visible",
                                "wallpaper_rendered", "self_test_finished"))
                        .put("taskId", integerProperty(
                                "Task id for task conditions."))
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
                        .put("timeoutMillis", integerProperty(
                                "Timeout from 1 to 60000 milliseconds."))
                        .put("startedAfterMillis", integerProperty(
                                "For self_test_finished, require a result written after this timestamp.")),
                "condition");
    }

    private static void addShellTools(final JSONArray tools)
            throws JSONException {
        tools.put(readTool(
                        "magicdesk.files.list",
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
                        "magicdesk.files.stat",
                        "Read file information",
                        "Read metadata for one shell-visible file or directory.",
                        pathSchema()))
                .put(actionTool(
                        "magicdesk.files.create",
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
                        "magicdesk.files.rename",
                        "Rename file entry",
                        "Rename a file or directory through the shared Files backend.",
                        objectSchema(new JSONObject()
                                        .put("path", stringProperty(
                                                "Absolute source path."))
                                        .put("newName", stringProperty(
                                                "New entry name.")),
                                "path", "newName")))
                .put(actionTool(
                        "magicdesk.console.open",
                        "Open console session",
                        "Open a bounded persistent Android shell session.",
                        objectSchema(new JSONObject().put(
                                "directory", stringProperty(
                                        "Initial absolute working directory.")))))
                .put(actionTool(
                        "magicdesk.console.execute",
                        "Execute console command",
                        "Execute a command in a persistent gated console session.",
                        objectSchema(new JSONObject()
                                        .put("sessionId", stringProperty(
                                                "Console session id."))
                                        .put("command", stringProperty(
                                                "Shell command.")),
                                "sessionId", "command")))
                .put(readTool(
                        "magicdesk.console.status",
                        "Get console session",
                        "Read a console session's current working directory.",
                        sessionSchema()))
                .put(destructiveTool(
                        "magicdesk.console.close",
                        "Close console session",
                        "Close one persistent MCP console session.",
                        sessionSchema()));
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
            case "magicdesk.get_state":
                properties.put("generatedAtMillis", integerProperty("Timestamp."))
                        .put("session", openObjectProperty("Desktop session."))
                        .put("ui", openObjectProperty("Desktop UI state."))
                        .put("runtime", openObjectProperty("Runtime state."));
                break;
            case "magicdesk.list_displays":
                properties.put("displays", arrayProperty(
                        "Connected displays.", openObjectProperty("Display.")));
                break;
            case "magicdesk.list_tasks":
                properties.put("tasks", arrayProperty(
                                "Task page.", openObjectProperty("Task.")))
                        .put("count", integerProperty("Returned count."))
                        .put("total", integerProperty("Matching count."))
                        .put("nextCursor", nullableStringProperty(
                                "Next page cursor."));
                break;
            case "magicdesk.list_apps":
                properties.put("apps", arrayProperty(
                                "Application page.", openObjectProperty("App.")))
                        .put("count", integerProperty("Returned count."))
                        .put("total", integerProperty("Matching count."))
                        .put("nextCursor", nullableStringProperty(
                                "Next page cursor."));
                break;
            case "magicdesk.get_events":
                properties.put("latestId", integerProperty("Latest event id."))
                        .put("events", arrayProperty(
                                "Structured events.", openObjectProperty("Event.")));
                break;
            case "magicdesk.get_diagnostics":
                properties.put("report", stringProperty(
                        "Compatibility report."));
                break;
            case "magicdesk.get_self_test":
                properties.put("running", booleanProperty("Test is running."))
                        .put("report", stringProperty("Latest test report."));
                break;
            case "magicdesk.capture_screenshot":
                properties.put("displayId", integerProperty("Display id."))
                        .put("width", integerProperty("Image width."))
                        .put("height", integerProperty("Image height."))
                        .put("mimeType", stringProperty("Image MIME type."))
                        .put("captureSource", stringProperty("Capture source."));
                break;
            case "magicdesk.sample_pixels":
                properties.put("displayId", integerProperty("Display id."))
                        .put("samples", arrayProperty(
                                "Pixel values.", openObjectProperty("Pixel.")));
                break;
            case "magicdesk.get_recording_status":
            case "magicdesk.start_recording":
            case "magicdesk.stop_recording":
                properties.put("state", stringProperty("Recording state."))
                        .put("message", stringProperty("Recording detail."));
                break;
            case "magicdesk.list_app_actions":
                properties.put("package", stringProperty("Package."))
                        .put("actions", arrayProperty(
                                "Available actions.",
                                openObjectProperty("Application action.")));
                break;
            case "magicdesk.files.list":
                properties.put("path", stringProperty("Directory path."))
                        .put("entries", arrayProperty(
                                "File entries.", openObjectProperty("File.")))
                        .put("nextCursor", nullableStringProperty(
                                "Next page cursor."));
                break;
            case "magicdesk.files.stat":
            case "magicdesk.files.create":
            case "magicdesk.files.rename":
                properties.put("file", openObjectProperty("File metadata."));
                break;
            case "magicdesk.console.open":
            case "magicdesk.console.status":
            case "magicdesk.console.close":
                properties.put("sessionId", stringProperty("Session id."))
                        .put("workingDirectory", stringProperty(
                                "Current directory."));
                break;
            case "magicdesk.console.execute":
                properties.put("sessionId", stringProperty("Session id."))
                        .put("exitCode", integerProperty("Command exit code."))
                        .put("output", stringProperty("Combined output."))
                        .put("workingDirectory", stringProperty(
                                "Current directory."));
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
