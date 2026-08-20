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
                                        "Optional Android display id.")))))
                .put(readTool(
                        "magicdesk.list_apps",
                        "List applications",
                        "List launchable Android application activities.",
                        emptySchema()))
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
                .put(actionTool(
                        "magicdesk.capture_screenshot",
                        "Capture screenshot",
                        "Request a screenshot of the active desktop display.",
                        emptySchema()))
                .put(readTool(
                        "magicdesk.wait_for_state",
                        "Wait for state",
                        "Wait for an observable desktop, task, taskbar, wallpaper, or self-test condition.",
                        waitSchema()));
        if (!includeDeveloperTools) {
            return tools;
        }
        return tools
                .put(destructiveTool(
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

    private static JSONObject waitSchema() throws JSONException {
        return objectSchema(new JSONObject()
                        .put("condition", enumProperty(
                                "Condition to observe.",
                                "desktop_active", "desktop_inactive",
                                "task_present", "task_absent",
                                "task_windowing_mode", "taskbar_visible",
                                "wallpaper_rendered", "self_test_finished"))
                        .put("taskId", integerProperty(
                                "Task id for task conditions."))
                        .put("mode", enumProperty(
                                "Expected task mode.",
                                "windowed", "freeform", "fullscreen"))
                        .put("displayId", integerProperty(
                                "Display id for display conditions."))
                        .put("timeoutMillis", integerProperty(
                                "Timeout from 200 to 60000 milliseconds."))
                        .put("startedAfterMillis", integerProperty(
                                "For self_test_finished, require a result written after this timestamp.")),
                "condition");
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
                .put("outputSchema", resultSchema())
                .put("annotations", new JSONObject()
                        .put("readOnlyHint", readOnly)
                        .put("destructiveHint", destructive)
                        .put("idempotentHint", idempotent)
                        .put("openWorldHint", false));
    }

    private static JSONObject resultSchema() throws JSONException {
        return objectSchema(new JSONObject()
                        .put("success", new JSONObject()
                                .put("type", "boolean"))
                        .put("message", stringProperty("Result message."))
                        .put("data", new JSONObject().put("type", "object")),
                "success", "message", "data");
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
