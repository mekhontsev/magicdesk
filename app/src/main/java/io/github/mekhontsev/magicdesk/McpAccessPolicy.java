package io.github.mekhontsev.magicdesk;

import org.json.JSONArray;
import java.util.EnumSet;
import java.util.Set;

/** Explicit capabilities; newly introduced tools are denied until classified. */
final class McpAccessPolicy {
    enum Permission {
        CONTROL("control", R.string.mcp_permission_control),
        INPUT_TESTS("input_tests", R.string.mcp_permission_input),
        CONTENT("content", R.string.mcp_permission_content),
        FILES_READ("files_read", R.string.mcp_permission_files_read),
        FILES_WRITE("files_write", R.string.mcp_permission_files_write),
        SHELL("shell", R.string.mcp_permission_shell),
        UPDATE("update", R.string.mcp_permission_update);

        final String id;
        final int label;
        Permission(String id, int label) { this.id = id; this.label = label; }
    }

    private static final Set<String> OBSERVATIONS = Set.of(
            "get_state", "get_pointer_state", "list_displays", "list_tasks", "list_apps",
            "get_app_presentation", "list_ui_elements", "get_events", "get_diagnostics",
            "get_self_test", "wait_for_state",
            "query_intent_handlers", "list_android_actions", "list_app_actions",
            "search_app_functions", "get_recording_status", "begin_trace", "end_trace",
            "app.update_status", "graphics.list", "inspect_workspace");
    private final Set<Permission> mPermissions;

    McpAccessPolicy(final Set<String> names) {
        final EnumSet<Permission> allowed = EnumSet.noneOf(Permission.class);
        for (Permission permission : Permission.values()) {
            if (names.contains(permission.id)) allowed.add(permission);
        }
        mPermissions = Set.copyOf(allowed);
    }

    boolean has(final Permission permission) { return mPermissions.contains(permission); }

    boolean allows(final String name) {
        if (OBSERVATIONS.contains(name)) return true;
        final Permission permission = required(name);
        return permission != null && has(permission);
    }

    static Permission required(final String name) {
        return switch (name) {
            case "graphics.start", "graphics.execute", "graphics.stop",
                    "console.open", "console.execute", "console.status", "console.close",
                    "terminal.open", "terminal.list", "terminal.status", "terminal.read",
                    "terminal.write", "terminal.send_key", "terminal.close", "terminal.attach",
                    "terminal.detach", "terminal.emit", "tmux.list", "tmux.open", "tmux.panes", "tmux.emit"
                    -> Permission.SHELL;
            case "files.list", "files.stat", "files.download_begin", "files.download_chunk",
                    "files.download_finish" -> Permission.FILES_READ;
            case "files.create", "files.rename", "files.upload_begin", "files.upload_chunk",
                    "files.upload_status", "files.upload_commit", "files.upload_abort" -> Permission.FILES_WRITE;
            case "app.update" -> Permission.UPDATE;
            case "send_key", "move_pointer", "click_pointer", "run_self_test", "cancel_self_test",
                    "ui.perform", "ui.release", "input.gesture", "input.key_chord",
                    "device.keep_awake", "device.release_awake",
                    "force_stop_app" -> Permission.INPUT_TESTS;
            case "send_broadcast", "start_service", "launch_desktop_entry", "list_desktop_entries" -> Permission.SHELL;
            case "capture_screenshot", "sample_pixels", "start_recording", "stop_recording",
                    "dialog.show", "notification.post", "interaction.result", "interaction.close",
                    "clipboard.read_text", "clipboard.write_text", "clipboard.clear",
                    "clipboard.open", "clipboard.share", "list_notifications",
                    "get_intent_result", "get_activity_history" -> Permission.CONTENT;
            case "ui.inspect", "ui.wait", "ui.read_text", "graphics.inspect_window" -> Permission.CONTENT;
            case "graphics.open_window", "graphics.set_workspace", "graphics.set_scale", "start_desktop", "close_desktop", "create_display", "remove_display",
                    "graphics.close_window", "graphics.detach_viewer", "set_task_state",
                    "select_display_viewer",
                    "control_display", "move_task",
                    "launch_app", "set_app_presentation", "reset_app_presentation",
                    "launch_intent", "open_uri", "open_file", "share", "invoke_android_action",
                    "invoke_app_action", "invoke_notification", "execute_app_function", "focus_task",
                    "close_task", "set_window_mode", "set_window_bounds", "arrange_task", "show_start",
                    "show_desktop", "open_settings", "open_builtin",
                    "invoke_ui_action" -> Permission.CONTROL;
            default -> null;
        };
    }

    static String permissionName(final String name) {
        if (OBSERVATIONS.contains(name)) return "observe";
        final Permission permission = required(name);
        if (permission == null) throw new IllegalArgumentException("Unclassified MCP tool: " + name);
        return permission.id;
    }

    Set<String> names() {
        final Set<String> names = new java.util.HashSet<>();
        for (Permission permission : mPermissions) names.add(permission.id);
        return Set.copyOf(names);
    }

    JSONArray toJson() {
        final JSONArray values = new JSONArray().put("observe");
        for (Permission permission : Permission.values()) {
            if (has(permission)) values.put(permission.id);
        }
        return values;
    }
}
