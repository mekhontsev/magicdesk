package io.github.mekhontsev.magicdesk;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Command schemas shared by the local CLI and the MCP adapter. */
final class AutomationCommandCatalog {
    private AutomationCommandCatalog() {
    }

    static JSONArray create() throws JSONException {
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
                        "get_app_presentation",
                        "Get application presentation",
                        "Read the saved interface scale and resolved task density for an Android application.",
                        objectSchema(new JSONObject().put(
                                "appIdentity", stringProperty(
                                        "Profile-scoped identity returned by list_apps.")),
                                "appIdentity")))
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
                        "Read current-run checks separately from the last saved result; each carries its run id.",
                        objectSchema(new JSONObject().put("includeReport",
                                booleanProperty("Include bounded text in the saved result; default false.")))))
                .put(readTool(
                        "get_termux_x11_status",
                        "Get Termux:X11 status",
                        "Probe the configured Termux:X11 display, reconnect listener, and Android viewer task.",
                        emptySchema()))
                .put(actionTool(
                        "start_desktop",
                        "Start desktop",
                        "Start MagicDesk on the requested available display target.",
                        objectSchema(new JSONObject()
                                .put("displayId", integerProperty("Exact display ID returned by list_displays."))
                                .put("uniqueId", stringProperty("Optional exact display identity."))
                                .put("target", enumProperty(
                                        "Target display environment.",
                                        "auto", "phone", "simulated",
                                        "wired", "wireless")))))
                .put(actionTool(
                        "create_display", "Create display",
                        "Create an owned display without starting a desktop or acquiring HOME.",
                        objectSchema(new JSONObject()
                                .put("type", enumProperty("Display creation mechanism.", "virtual", "overlay"))
                                .put("width", integerProperty("Display width in pixels."))
                                .put("height", integerProperty("Display height in pixels."))
                                .put("densityDpi", integerProperty("Display density; default 160.")),
                                "width", "height")))
                .put(actionTool(
                        "remove_display", "Remove display",
                        "Close any desktop on this display, then remove only the selected MagicDesk-owned display.",
                        objectSchema(new JSONObject()
                                .put("displayId", integerProperty("Owned display ID."))
                                .put("uniqueId", stringProperty("Exact identity returned by list_displays.")),
                                "displayId", "uniqueId")))
                .put(actionTool("control_display", "Control display",
                        "Route phone-attached mice and keyboards to a display without starting Desktop. Use -1 to restore prior routes. Acceptance is separate from input readiness.",
                        objectSchema(new JSONObject().put("displayId", integerProperty(
                                "Destination display ID, or -1 to release input.")), "displayId")))
                .put(actionTool("move_task", "Move task",
                        "Move an existing task to a display: ordinary fullscreen or managed Desktop window. Same-display selection only activates the task. Does not route input.",
                        objectSchema(new JSONObject().put("taskId", integerProperty("Existing task ID."))
                                .put("displayId", integerProperty("Destination display ID.")), "taskId", "displayId")))
                .put(actionTool(
                        "close_desktop",
                        "Close desktop",
                        "Close the active desktop session without removing or disconnecting its display.",
                        emptySchema()))
                .put(actionTool(
                        "launch_app",
                        "Launch application",
                        "Launch an Android application on an ordinary display or through the active Desktop. Ordinary launches return acceptance; use ui.wait to verify the interface.",
                        objectSchema(activityPlacementProperties()
                                        .put("appIdentity", stringProperty(
                                                "Profile-scoped identity returned by list_apps."))
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
                                        .put("bounds", relativeBoundsProperty(
                                                "Initial bounds within the desktop work area.")),
                                "appIdentity")))
                .put(actionTool(
                        "set_app_presentation",
                        "Set application presentation",
                        "Set an application interface scale and apply it to its live desktop tasks in one window transaction.",
                        objectSchema(new JSONObject()
                                        .put("appIdentity", stringProperty(
                                                "Profile-scoped identity returned by list_apps."))
                                        .put("scalePercent", integerRangeProperty(
                                                "Interface scale from 50 to 200 percent.",
                                                AppPresentationProfile
                                                        .MIN_SCALE_PERCENT,
                                                AppPresentationProfile
                                                        .MAX_SCALE_PERCENT)),
                                "appIdentity", "scalePercent")))
                .put(actionTool(
                        "reset_app_presentation",
                        "Reset application presentation",
                        "Restore Android's inherited display density for an application and its live desktop tasks.",
                        objectSchema(new JSONObject().put(
                                "appIdentity", stringProperty(
                                        "Profile-scoped identity returned by list_apps.")),
                                "appIdentity")))
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
                        "Capture a display or a pixel region as an in-memory PNG, independently of Desktop. region uses display coordinates (left/top inclusive, right/bottom exclusive) and must fit entirely inside the display. No scaling or implicit clipping. The image includes whatever is visibly composed there; it does not isolate an occluded window or element. Returns sourceBounds and original display dimensions for coordinate mapping.",
                        objectSchema(new JSONObject().put(
                                "displayId", integerProperty(
                                        "Display id; defaults to active Desktop or display 0."))
                                .put("region", objectSchema(new JSONObject()
                                        .put("left", integerProperty("Inclusive left display pixel."))
                                        .put("top", integerProperty("Inclusive top display pixel."))
                                        .put("right", integerProperty("Exclusive right display pixel."))
                                        .put("bottom", integerProperty("Exclusive bottom display pixel.")),
                                        "left", "top", "right", "bottom")))))
                .put(readTool(
                        "wait_for_state",
                        "Wait for state",
                        "Wait for an observable desktop, task, application health, system dialog, UI, or self-test condition.",
                        waitSchema()));
        tools.put(readTool(
                        "sample_pixels",
                        "Sample display pixels",
                        "Read up to 64 exact pixels from a display without creating a file or starting Desktop.",
                        pixelSampleSchema()))
                .put(actionTool(
                        "open_builtin",
                        "Open built-in window",
                        "Open a MagicDesk Files, Console, Task Manager, Settings, Application Profiles, Diagnostics, or Activity Explorer window.",
                        objectSchema(toolPlacementProperties().put(
                                "builtin", enumProperty(
                                        "Built-in window.",
                                        "files", "console", "termux",
                                        "task_manager", "settings",
                                        "app_profiles",
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
                        "Launch a typed or raw Activity Intent without requiring Desktop. Ordinary launches return acceptance; use ui.wait to verify the interface. An active Desktop destination retains managed placement.",
                        intentSchema(false)))
                .put(actionTool(
                        "open_uri",
                        "Open URI",
                        "Open a URI on an ordinary display or inside the active Desktop, preserving Android authorization.",
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
                        "Invoke a stable semantic Android action without requiring Desktop; an active Desktop destination retains managed placement.",
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
                        "Launch a published application shortcut on an ordinary display or inside the active Desktop.",
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
                .put(destructiveTool(
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
            tools.put(destructiveTool(
                        "force_stop_app",
                        "Force stop application",
                        "Force-stop an Android package.",
                        objectSchema(new JSONObject().put(
                                "appIdentity", stringProperty(
                                        "Profile-scoped identity returned by list_apps.")),
                                "appIdentity")))
                .put(actionTool(
                        "send_broadcast",
                        "Send Android broadcast",
                        "Send a typed or raw broadcast Intent. Sensitive operation because it can trigger background state changes.",
                        intentSchema(true)))
                .put(actionTool(
                        "start_service",
                        "Start Android service",
                        "Start a typed or raw Android service Intent. Sensitive operation because it is an invisible background operation.",
                        intentSchema(true)))
                .put(readTool(
                        "clipboard.read_text",
                        "Read clipboard text",
                        "Read text from Android's system clipboard. Sensitive operation because clipboard contents may contain secrets; Android may require a focused MagicDesk window.",
                        emptySchema()))
                .put(actionTool(
                        "clipboard.write_text",
                        "Write clipboard text",
                        "Write bounded plain text to Android's system clipboard.",
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
                        "Open the current clipboard file or web link through the shared Android launcher, without requiring Desktop.",
                        objectSchema(activityPlacementProperties())))
                .put(actionTool(
                        "clipboard.share",
                        "Share clipboard content",
                        "Open Android's share chooser for clipboard content without requiring Desktop.",
                        objectSchema(activityPlacementProperties())))
                .put(destructiveTool(
                        "clipboard.clear",
                        "Clear clipboard",
                        "Clear Android's system clipboard and a matching published MagicDesk file operation.",
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
                        "Inject one Android key event on a desktop display.",
                        objectSchema(new JSONObject()
                                        .put("displayId", integerProperty(
                                                "Optional active display id."))
                                        .put("keyCode", stringProperty(
                                                "Android KEYCODE name.")),
                                "keyCode")))
                .put(actionTool(
                        "move_pointer",
                        "Move pointer",
                        "Inject mouse hover at absolute display coordinates. Does not reposition the hardware cursor. The next click_pointer uses these coordinates.",
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
                        "Click atomically at explicit x/y, otherwise consume the last move_pointer coordinates once, or use the current virtual mouse position. Explicit coordinates work without Desktop.",
                        objectSchema(new JSONObject()
                                .put("displayId", integerProperty(
                                        "Optional active display id."))
                                .put("button", enumProperty(
                                        "Pointer button.",
                                        "primary", "secondary"))
                                .put("x", integerProperty("Optional x; requires y."))
                                .put("y", integerProperty("Optional y; requires x.")))));
        addAndroidUiTools(tools);
        addShellTools(tools);
        addTransferTools(tools);
        tools.put(destructiveTool("app.update", "Update MagicDesk",
                "Install a same-package, same-signer APK with no downgrade or data reset. Requires an inactive desktop. A one-operation privileged worker restores enabled MCP after installation. Reconnect and query app.update_status with the same updateId; do not retry using a new id after a lost response.",
                objectSchema(new JSONObject().put("updateId", stringProperty("Unique client operation id, 16-64 letters, digits, underscores or hyphens."))
                        .put("path", stringProperty("Absolute shell-readable APK path, typically from files.upload_commit."))
                        .put("sha256", stringProperty("Expected APK SHA-256 digest.")), "updateId", "path", "sha256")))
                .put(readTool("app.update_status", "MagicDesk update status", "Read the durable installer result for an exact update operation.",
                        objectSchema(new JSONObject().put("updateId", stringProperty("Id passed to app.update.")), "updateId")));
        return tools;
    }

    private static void addAndroidUiTools(final JSONArray tools) throws JSONException {
        final JSONObject display = integerProperty("Exact Android display id from list_displays, including 0. No Desktop required.");
        final JSONObject selector = new JSONObject();
        for (final String key : java.util.List.of("package", "resourceId", "className", "text", "description")) {
            selector.put(key, stringProperty("Exact full " + key + " match, up to 32768 UTF-16 units; all criteria must match the same node."));
        }
        for (final String key : java.util.List.of("enabled", "visible", "focused", "selected", "checked", "editable", "scrollable")) {
            selector.put(key, booleanProperty("Expected " + key + " state."));
        }
        tools.put(readTool("ui.inspect", "Inspect Android UI",
                "Read fresh Android accessibility windows/nodes, optionally scoped to windowId or rootElementId. Optional selector searches up to 4096 nodes and returns only exact matches. Previews are bounded; use ui.read_text for full text. complete describes stable traversal, not rendering; stable=false means events changed during capture or cache invalidation failed. Password text is redacted. Handles expire after 60 seconds or four newer snapshots. Prefer MagicDesk semantic controls for its own UI.",
                objectSchema(androidUiScopeProperties().put("selector", objectSchema(selector).put("minProperties", 1)), "displayId")))
                .put(readTool("ui.read_text", "Read Android UI text",
                        "Read text or description from one retained snapshot element without preview truncation. Pages use UTF-16 offsets in that same immutable revision; nextOffset=null ends it. No live refresh: inspect/wait again for current text. Password values and lengths stay redacted. Handles have the same expiry as ui.inspect.",
                        objectSchema(new JSONObject().put("elementId", stringProperty("Handle from ui.inspect/ui.wait."))
                                .put("field", enumProperty("Default text.", "text", "description"))
                                .put("offset", integerProperty("UTF-16 offset, default 0."))
                                .put("limit", integerProperty("Page length 1-32768 UTF-16 units, default 32768. Unicode-safe boundaries; limit=1 may return a two-unit character.")), "elementId")))
                .put(actionTool("ui.perform", "Act on Android UI element",
                        "Perform an advertised accessibility action on an elementId from ui.inspect/ui.wait. Rejects expired or changed identities, with no coordinate fallback. set_text preserves Unicode and line breaks. accepted is not proof of visual completion; verify using ui.wait.",
                        objectSchema(new JSONObject().put("elementId", stringProperty("Short-lived opaque node handle."))
                                .put("action", enumProperty("Use an action from the node's actions list.", "click", "long_click", "focus", "clear_focus",
                                        "set_text", "select_text", "scroll_forward", "scroll_backward", "scroll_up", "scroll_down",
                                        "scroll_left", "scroll_right", "show_on_screen"))
                                .put("text", stringProperty("Required for set_text, up to 32768 characters; empty clears the field."))
                                .put("start", integerProperty("Selection start for select_text."))
                                .put("end", integerProperty("Selection end for select_text.")), "elementId", "action")))
                .put(readTool("ui.wait", "Wait for Android UI",
                        "Wait on accessibility events for exact full-text/state matches in a display, window or subtree. Searches up to 4096 nodes per observation. Returns matched/timedOut, not a claimed action result. Unstable observations never satisfy a wait; incomplete or redacted observations never prove absence. No periodic UI polling.",
                        objectSchema(androidUiScopeProperties()
                                .put("selector", objectSchema(selector).put("minProperties", 1))
                                .put("condition", enumProperty("Default present.", "present", "absent"))
                                .put("timeoutMillis", integerProperty("0-60000 ms, default 5000.")), "displayId", "selector")))
                .put(actionTool("ui.release", "Release Android UI connection",
                        "Release UI node handles and Android UiAutomation, cancelling outstanding UI waits. Also released after 60 idle seconds or client process death. Does not disable existing accessibility services.", emptySchema()))
                .put(actionTool("input.gesture", "Inject touch gesture",
                        "Inject a bounded touch gesture on an explicit display without Desktop. Uses screen pixels; prefer ui.perform when the app exposes an action. A drag holds first, then moves. Does not reposition the hardware mouse cursor.",
                        objectSchema(new JSONObject().put("displayId", display)
                                .put("type", enumProperty("Gesture type.", "tap", "long_press", "swipe", "drag"))
                                .put("points", arrayProperty("One point for tap/long_press; 2-32 ordered points for swipe/drag.",
                                        objectSchema(new JSONObject().put("x", integerProperty("Screen x, 0-32768."))
                                                .put("y", integerProperty("Screen y, 0-32768.")), "x", "y")))
                                .put("durationMillis", integerProperty("Movement/press duration 0-5000 ms; defaults tap=0, long_press=600, swipe/drag=400."))
                                .put("holdMillis", integerProperty("Drag's initial hold, 0-2000 ms, default 600.")), "displayId", "type", "points")))
                .put(actionTool("input.key_chord", "Inject key chord",
                        "Press 1-8 distinct Android key codes in order, then release in reverse order, including on failure. Example [CTRL_LEFT,A]. This is key input, not Unicode text entry; use ui.perform set_text for text.",
                        objectSchema(new JSONObject().put("displayId", display)
                                .put("keys", arrayProperty("Modifiers first, then the key; KEYCODE_ prefix is optional.", stringProperty("Android key name."))),
                                "displayId", "keys")))
                .put(actionTool("device.keep_awake", "Keep phone awake temporarily",
                        "Keep an already awake, unlocked phone's display on for 1 second to 30 minutes. No Desktop or privileged service required. Does not change screen timeout or bypass the lock screen. Returns a leaseId; pass it to renew an active lease. Expires automatically and is released when the runtime stops.",
                        objectSchema(new JSONObject().put("durationMillis", integerProperty("1000-1800000 ms, default 300000."))
                                .put("leaseId", stringProperty("Required only to renew the currently held lease.")))))
                .put(actionTool("device.release_awake", "Release awake lease",
                        "Release the exact awake lease; a stale leaseId cannot release a newer lease.",
                        objectSchema(new JSONObject().put("leaseId", stringProperty("Id returned by device.keep_awake.")), "leaseId")));
    }

    private static JSONObject androidUiScopeProperties() throws JSONException {
        return new JSONObject().put("displayId", integerProperty("Exact Android display id, including 0. No Desktop required."))
                .put("windowId", integerProperty("Optional Android accessibility window id from ui.inspect. Excludes rootElementId."))
                .put("rootElementId", stringProperty("Optional retained handle: refresh and inspect only its subtree. Excludes windowId; must belong to displayId."))
                .put("maxNodes", integerProperty("Maximum returned nodes/matches, 1-256, default 200. Traversal is separately bounded to 4096 nodes, depth 40, 3 seconds."));
    }

    private static void addTransferTools(JSONArray tools) throws JSONException {
        final JSONObject id = stringProperty("Client-generated unique id, 16-64 letters, digits, hyphens or underscores. Reuse only for retries of the same transfer.");
        tools.put(actionTool("files.upload_begin", "Begin upload",
                "Create a resumable upload for an ordinary shell-writable file. No destination is published until commit; idempotent for this transferId.",
                objectSchema(new JSONObject().put("transferId", id).put("path", stringProperty("Absolute destination path."))
                        .put("size", integerProperty("Exact file length in bytes."))
                        .put("sha256", stringProperty("Expected SHA-256 digest."))
                        .put("overwrite", booleanProperty("Explicitly replace an existing ordinary file on commit; default false.")),
                        "transferId", "path", "size", "sha256")))
                .put(actionTool("files.upload_chunk", "Upload chunk", "Write up to 128 KiB. Repeating acknowledged bytes is safe; different bytes or gaps are rejected.",
                        objectSchema(new JSONObject().put("transferId", id).put("offset", integerProperty("Acknowledged byte offset."))
                                .put("data", stringProperty("Base64-encoded binary chunk.")), "transferId", "offset", "data")))
                .put(readTool("files.upload_status", "Upload status", "Read acknowledged upload position after reconnecting.",
                        objectSchema(new JSONObject().put("transferId", id), "transferId")))
                .put(actionTool("files.upload_commit", "Commit upload", "Verify size and SHA-256, then publish the file. Retrying a completed commit does not rewrite the destination.",
                        objectSchema(new JSONObject().put("transferId", id), "transferId")))
                .put(destructiveTool("files.upload_abort", "Abort upload", "Delete only the verified incomplete upload file. A committed destination is never deleted.",
                        objectSchema(new JSONObject().put("transferId", id), "transferId")))
                .put(readTool("files.download_begin", "Begin download", "Snapshot an ordinary shell-readable file and its SHA-256. Download sessions survive reconnection and app restart.",
                        objectSchema(new JSONObject().put("transferId", id).put("path", stringProperty("Absolute source path.")), "transferId", "path")))
                .put(readTool("files.download_chunk", "Download chunk", "Read a bounded file range. Rejects changed files; the client must verify the final SHA-256.",
                        objectSchema(new JSONObject().put("transferId", id).put("offset", integerProperty("Byte offset."))
                                .put("length", integerRangeProperty("Chunk bytes; default 131072.", 1, 131072)), "transferId", "offset")))
                .put(actionTool("files.download_finish", "Finish download", "Release a download session after verifying the local file; never modifies the source.",
                        objectSchema(new JSONObject().put("transferId", id), "transferId")));
    }

    private static JSONObject waitSchema() throws JSONException {
        return objectSchema(new JSONObject()
                        .put("condition", enumProperty(
                                "Condition to observe.",
                                "desktop_active", "desktop_inactive",
                                "display_present", "display_absent",
                                "task_present", "task_absent",
                                "task_windowing_mode", "task_focused",
                                "task_bounds", "app_ready", "app_crashed",
                                "app_not_responding",
                                "system_dialog_visible",
                                "input_ready", "pointer_ready", "ui_visible",
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
                                "Display id for display conditions and optional task scope. "
                                        + "task_absent without a display id checks global absence; "
                                        + "with a display id it checks absence on that display."))
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
                        "Close one persistent automation console session.",
                        sessionSchema()))
                .put(actionTool(
                        "terminal.open",
                        "Open terminal window",
                        "Create a terminal session and show it on the phone, a selected display, or the active desktop.",
                        objectSchema(toolPlacementProperties()
                                .put("directory", stringProperty(
                                        "Initial absolute working directory."))
                                .put("command", stringProperty(
                                        "Optional command to run after the terminal is ready."))
                                .put("backend", enumProperty(
                                        "PTY execution environment.",
                                        "shell", "termux")))))
                .put(readTool(
                        "terminal.list",
                        "List terminal sessions",
                        "List retained terminal sessions, including detached sessions. A taskId of -1 means no attached window.",
                        emptySchema()))
                .put(actionTool("terminal.attach", "Show terminal session",
                        "Attach an existing terminal session to a window without restarting its process.",
                        objectSchema(toolPlacementProperties().put("terminalId",
                                stringProperty("Existing terminal session id.")), "terminalId")))
                .put(actionTool("terminal.detach", "Detach terminal window",
                        "Detach a window: ordinary PTYs and transcripts remain; a managed tmux client disconnects while its server session remains.", terminalSchema()))
                .put(readTool(
                        "terminal.status",
                        "Get terminal status",
                        "Read task, process, title, directory, shell command marks, progress, notifications and visible-screen hyperlinks.",
                        terminalSchema()))
                .put(readTool(
                        "terminal.read",
                        "Read terminal screen",
                        "Read the viewport, bounded transcript or shell-marked command output. Missing or expired command output returns available=false.",
                        objectSchema(new JSONObject()
                                        .put("terminalId", stringProperty(
                                                "Interactive terminal id."))
                                        .put("scope", enumProperty(
                                                "Text region to read.",
                                                "viewport", "transcript", "command"))
                                        .put("commandId", integerProperty("Command id from terminal.status; required only with scope=command."))
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
                        "End terminal session",
                        "End one terminal session and close its attached window, if any.",
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
                        objectSchema(toolPlacementProperties()
                                .put("sessionId", stringProperty(
                                        "Existing tmux session id from tmux.list."))
                                .put("name", stringProperty(
                                        "Persistent tmux session name to open or create.")))));
    }

    private static JSONObject pathSchema() throws JSONException {
        return objectSchema(new JSONObject().put(
                "path", stringProperty("Absolute shell path.")), "path");
    }

    private static JSONObject toolPlacementProperties() throws JSONException {
        return activityPlacementProperties()
                .put("uniqueId", stringProperty("Optional stable display identity to reject a stale selection."));
    }

    private static JSONObject activityPlacementProperties() throws JSONException {
        return new JSONObject().put("placement", enumProperty(
                        "auto selects Desktop when present, otherwise phone; display and phone are ordinary fullscreen launches.",
                        "auto", "phone", "display", "desktop"))
                .put("displayId", integerProperty("Destination display, including 0; defaults to active Desktop or phone. Required for display placement."));
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
                                "Optional live display id; defaults to the desktop display, or the phone when inactive."))
                        .put("points", arrayProperty(
                                "Coordinates to sample.", point)),
                "points");
    }

    private static JSONObject appTargetSchema(final boolean actionId)
            throws JSONException {
        final JSONObject properties = new JSONObject()
                .put("appIdentity", stringProperty("Profile-scoped identity returned by list_apps."))
                .put("component", stringProperty(
                        "Optional flattened activity component."));
        if (actionId) {
            final JSONObject placement = activityPlacementProperties();
            properties.put("placement", placement.get("placement"))
                    .put("displayId", placement.get("displayId"))
                    .put("mode", enumProperty("Launch mode; windowed requires Desktop.",
                            "auto", "windowed", "fullscreen"));
            properties.put("actionId", stringProperty(
                    "Action id returned by list_app_actions."));
            return objectSchema(properties, "appIdentity", "actionId");
        }
        return objectSchema(properties, "appIdentity");
    }

    private static JSONObject intentSchema(final boolean includeKind)
            throws JSONException {
        final JSONObject properties = activityPlacementProperties()
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
        return objectSchema(activityPlacementProperties()
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
                        .put("chooser", booleanProperty("Show Android chooser."))
                        .put("chooserTitle", stringProperty("Chooser title."))
                        .put("expectResult", booleanProperty(
                                "Create an asynchronous Activity result request.")),
                "uri");
    }

    private static JSONObject openFileSchema() throws JSONException {
        final JSONObject schema = objectSchema(activityPlacementProperties()
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
        final JSONObject schema = objectSchema(activityPlacementProperties()
                .put("text", stringProperty("Optional shared text."))
                .put("subject", stringProperty("Optional shared subject."))
                .put("files", arrayProperty(
                        "Absolute shell paths or content URIs.",
                        stringProperty("File path or content URI."))
                        .put("maxItems", AndroidContentPayload.MAX_URI_ITEMS))
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
                .put("chooser", booleanProperty("Show Android chooser."))
                .put("chooserTitle", stringProperty("Chooser title.")));
        return schema.put("anyOf", new JSONArray()
                .put(requiredOnly("text"))
                .put(requiredOnly("files")));
    }

    private static JSONObject androidActionSchema() throws JSONException {
        return objectSchema(activityPlacementProperties()
                        .put("actionId", enumProperty(
                                "Stable Android action id.",
                                AndroidDesktopActionCatalog.ids()))
                        .put("mimeType", stringProperty(
                                "Document MIME type."))
                        .put("multiple", booleanProperty(
                                "Allow multiple documents."))
                        .put("suggestedName", stringProperty(
                                "Suggested created document name."))
                        .put("appIdentity", stringProperty(
                                "Profile-scoped application identity for app-details."))
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
        return objectSchema(activityPlacementProperties()
                        .put("key", stringProperty(
                                "Opaque notification key from list_notifications."))
                        .put("operation", enumProperty(
                                "Notification operation.",
                                "open", "action", "dismiss"))
                        .put("actionIndex", integerProperty(
                                "Action index from list_notifications.")),
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
                                "GenericDocument properties, optionally with namespace, id, schemaType, and a properties object. An explicit properties field must be an object; names inside it are properties, not metadata. Arrays must contain one value type; integers and floating-point numbers cannot be mixed. Null array elements are unsupported."))
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
            case "ui.inspect":
                properties.put("snapshotId", stringProperty("Short-lived snapshot identity."))
                        .put("complete", booleanProperty("Stable, accessible, complete traversal; separate from text preview truncation."))
                        .put("stable", booleanProperty("Cache invalidated and no accessibility event observed during capture; not a rendering guarantee."))
                        .put("textTruncated", booleanProperty("Preview text was shortened; use ui.read_text."))
                        .put("nodes", arrayProperty("Nodes with opaque handles, parent ids and advertised actions.", openObjectProperty("UI node.")))
                        .put("windows", arrayProperty("Accessibility windows.", openObjectProperty("Window.")));
                break;
            case "ui.wait":
                properties.put("matched", booleanProperty("The requested condition was observed."))
                        .put("timedOut", booleanProperty("Wait ended without observing the condition."))
                        .put("complete", booleanProperty("Completeness of the final observation."))
                        .put("stable", booleanProperty("Final observation had no concurrent accessibility events and its cache was cleared."))
                        .put("matches", arrayProperty("Matching nodes with action handles.", openObjectProperty("UI node.")));
                break;
            case "ui.read_text":
                properties.put("snapshotId", stringProperty("Source snapshot identity."))
                        .put("elementId", stringProperty("Retained element handle."))
                        .put("field", enumProperty("Selected field.", "text", "description"))
                        .put("text", nullableStringProperty("Snapshot text page; null for passwords."))
                        .put("totalLength", nullableIntegerProperty("Full UTF-16 length; null for passwords."))
                        .put("offset", integerProperty("Returned page's UTF-16 start offset."))
                        .put("nextOffset", nullableIntegerProperty("Next page offset, or null when complete/redacted."))
                        .put("redacted", booleanProperty("Password text cannot be returned."));
                break;
            case "device.keep_awake":
                properties.put("held", booleanProperty("The wake lock is currently held."))
                        .put("leaseId", stringProperty("Token for renewal or release."))
                        .put("remainingMillis", integerProperty("Remaining lease lifetime."));
                break;
            case "get_state":
                properties.put("generatedAtMillis", integerProperty("Timestamp."))
                        .put("app", openObjectProperty("Build identity, process instance and install time."))
                        .put("device", openObjectProperty("Device model and Android release."))
                        .put("readiness", openObjectProperty("Awake/lock state and required prerequisite actions."))
                        .put("connection", openObjectProperty("Listener scope and current granted permissions."))
                        .put("session", openObjectProperty("Desktop session."))
                        .put("inputControl", openObjectProperty("Independent input target: requestedDisplayId, readyDisplayId, transitioning and error."))
                        .put("services", openObjectProperty("Service prerequisites, independent of MCP grants."))
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
                                "Whether observed coordinates belong to the requested display."))
                        .put("observation", objectSchema(new JSONObject()
                                .put("displayId", nullableIntegerProperty(
                                        "Observed display, or null when the source cannot identify it."))
                                .put("x", integerProperty("Observed x coordinate."))
                                .put("y", integerProperty("Observed y coordinate.")),
                                "displayId", "x", "y")
                                .put("type", new JSONArray().put("object").put("null"))
                                .put("description", "Raw observation, not necessarily on the requested display."))
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
            case "get_app_presentation":
            case "set_app_presentation":
            case "reset_app_presentation":
                properties.put("package", stringProperty(
                                "Android package name."))
                        .put("appIdentity", stringProperty("Profile-scoped application identity."))
                        .put("mode", enumProperty(
                                "Presentation profile mode.",
                                "system", "custom"))
                        .put("scalePercent", nullableIntegerProperty(
                                "Saved custom interface scale."))
                        .put("activeDisplayId", nullableIntegerProperty(
                                "Active desktop display id."))
                        .put("displayDensityDpi", nullableIntegerProperty(
                                "Active display density."))
                        .put("expectedDensityDpi", nullableIntegerProperty(
                                "Expected effective task density."))
                        .put("overrideDensityDpi", nullableIntegerProperty(
                                "Density override sent to Android; zero inherits."));
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
                final JSONObject runProperties = new JSONObject();
                selfTestStateProperties(runProperties);
                runProperties.put("buildId", stringProperty("Source build identity."))
                        .put("checks", arrayProperty("Bounded current-run checks.", selfTestCheckSchema()))
                        .put("failures", arrayProperty("Failures within the retained checks.", selfTestCheckSchema()))
                        .put("checksTruncated", booleanProperty("Older checks were evicted."))
                        .put("firstFailure", selfTestCheckSchema()
                                .put("type", new JSONArray().put("object").put("null")));
                properties.put("currentRun", objectSchema(runProperties))
                        .put("lastCompletedResult", openObjectProperty(
                                "Saved result with runId, target, buildId, checks and failures; never attributed to currentRun.")
                                .put("type", new JSONArray().put("object").put("null")));
                break;
            case "wait_for_state":
                properties.put("matched", booleanProperty("The observed condition was satisfied."))
                        .put("waitExpired", booleanProperty("Only this observation wait expired; the operation was not cancelled."))
                        .put("condition", stringProperty("Requested condition."))
                        .put("runKnown", booleanProperty("For self_test_finished: the exact requested run is known."))
                        .put("source", stringProperty("Source of a self-test result: current_run or saved_result."));
                break;
            case "files.upload_begin": case "files.upload_chunk": case "files.upload_status":
            case "files.upload_commit": case "files.upload_abort": case "files.download_begin":
            case "files.download_chunk": case "files.download_finish":
                properties.put("transferId", stringProperty("Transfer identity."))
                        .put("state", stringProperty("active, completed or aborted."))
                        .put("path", stringProperty("Destination or source path."))
                        .put("size", integerProperty("File bytes."))
                        .put("sha256", stringProperty("Complete file digest."))
                        .put("offset", integerProperty("Acknowledged upload position or requested download offset."))
                        .put("chunkBytes", integerProperty("Maximum decoded chunk bytes."))
                        .put("nextOffset", integerProperty("Next download offset."))
                        .put("data", stringProperty("Base64 download chunk."))
                        .put("eof", booleanProperty("End of download."));
                break;
            case "app.update": case "app.update_status":
                properties.put("updateId", stringProperty("Exact operation id."))
                        .put("state", stringProperty("unknown, preparing, submitted, submission_unknown, installed, user_action_required or failed."))
                        .put("sessionId", integerProperty("Android PackageInstaller session id."))
                        .put("sha256", stringProperty("Verified APK digest."))
                        .put("versionCode", integerProperty("Expected installed version code."))
                        .put("versionName", stringProperty("Expected installed version name."))
                        .put("installerStatus", integerProperty("Android PackageInstaller result."))
                        .put("detail", nullableStringProperty("Installer detail."));
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
                        .put("displayWidth", integerProperty("Full display width in pixels."))
                        .put("displayHeight", integerProperty("Full display height in pixels."))
                        .put("rotation", integerProperty("Android Surface rotation: 0, 1, 2 or 3."))
                        .put("sourceBounds", objectSchema(new JSONObject()
                                .put("left", integerProperty("Image origin x on the display."))
                                .put("top", integerProperty("Image origin y on the display."))
                                .put("right", integerProperty("Exclusive right edge."))
                                .put("bottom", integerProperty("Exclusive bottom edge.")), "left", "top", "right", "bottom"))
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
                        .put("appIdentity", stringProperty("Profile-scoped application identity."))
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
                properties.put("package", stringProperty("Target package."))
                        .put("appIdentity", stringProperty("Profile-scoped application identity."));
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
            case "terminal.attach":
                properties.put("terminalId", stringProperty("Attached terminal session."))
                        .put("observed", booleanProperty("An attached terminal window was observed."));
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
                        .put("available", booleanProperty("Whether the requested output remains available."))
                        .put("commandId", nullableIntegerProperty("Command id, or null outside command scope."))
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
            case "terminal.detach":
                properties.put("terminalId", stringProperty("Interactive terminal id."))
                        .put("ptyRetained", booleanProperty("False for a disconnected tmux client; its tmux session remains."));
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
                        .put("observed", booleanProperty(
                                "Whether the terminal registered before the response."))
                        .put("tmuxSessionId", stringProperty(
                                "Resolved tmux session id, including newly created sessions."))
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
                .put("placement", enumProperty("Resolved placement.", "display", "desktop"))
                .put("accepted", booleanProperty("Android accepted dispatch; not proof that a window appeared."))
                .put("nextAction", stringProperty("Required observation after an ordinary launch."))
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
                .put("progress", objectSchema(new JSONObject()
                        .put("stageLabel", stringProperty("Current check label, when available."))
                        .put("lastLabel", stringProperty("Last completed check label."))
                        .put("lastResult", stringProperty("Last check result, or empty before a check."))
                        .put("lastDetail", stringProperty("Bounded last check detail."))
                        .put("passed", integerProperty("Completed PASS count."))
                        .put("warnings", integerProperty("Completed WARN count."))
                        .put("failed", integerProperty("Completed FAIL count."))
                        .put("notTested", integerProperty("Completed NOT_TESTED count."))))
                .put("cancelRequested", booleanProperty(
                        "Whether cancellation has been requested."))
                .put("cancellationReason", nullableStringProperty("user or session_closed, otherwise null."))
                .put("outcome", enumProperty("Run outcome, independent of wait expiration.",
                        "none", "pending", "passed", "warnings", "failed", "cancelled"))
                .put("requestedAtMillis", nullableIntegerProperty(
                        "Request timestamp."))
                .put("startedAtMillis", nullableIntegerProperty(
                        "Execution start timestamp."))
                .put("completedAtMillis", nullableIntegerProperty(
                        "Terminal-state timestamp."))
                .put("detail", stringProperty("Lifecycle detail."));
    }

    private static JSONObject selfTestCheckSchema() throws JSONException {
        return objectSchema(new JSONObject()
                .put("code", stringProperty("Existing self-test check code."))
                .put("state", enumProperty("Check result.", "PASS", "WARN", "FAIL", "NOT_TESTED"))
                .put("label", stringProperty("Check label."))
                .put("detail", stringProperty("Bounded check detail.")));
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
                        "Foreground PTY process metadata."))
                .put("semantics", openObjectProperty("Status-only OSC metadata: shellState, up to 128 commands, progress, last notification and up to 256 visible-screen link spans. Positions are zero-based buffer cells; negative rows are scrollback; endColumn is exclusive. Unknown exit codes are null."));
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

    private static JSONObject integerRangeProperty(
            final String description,
            final int minimum,
            final int maximum) throws JSONException {
        return integerProperty(description)
                .put("minimum", minimum)
                .put("maximum", maximum);
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
