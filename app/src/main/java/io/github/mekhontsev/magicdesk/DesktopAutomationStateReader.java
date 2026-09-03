package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Process;
import android.util.DisplayMetrics;
import android.view.Display;
import android.hardware.display.DisplayManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Reads structured desktop state without mutating the active session. */
final class DesktopAutomationStateReader {
    private final Context mContext;

    DesktopAutomationStateReader(final Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        mContext = context.getApplicationContext();
    }

    JSONObject state() throws JSONException {
        final DesktopSessionSnapshot session =
                DesktopRuntimeBridge.getSessionSnapshot();
        final DesktopDisplayTarget target = session.target();
        final ShellAccess.Snapshot shell = ShellAccess.currentSnapshot();
        final PlatformDriver platform = PlatformDrivers.current();
        final int activeDisplayId = session.activeDisplayId();
        final DesktopUiSnapshot ui = activeDisplayId >= Display.DEFAULT_DISPLAY
                ? DesktopRuntimeBridge.getAutomationUiSnapshot(activeDisplayId)
                : DesktopUiSnapshot.UNAVAILABLE;
        final DesktopWindowObservation windows =
                DesktopWindowObservation.capture();
        final JSONObject result = new JSONObject()
                .put("generatedAtMillis", System.currentTimeMillis())
                .put("app", new JSONObject()
                        .put("package", BuildConfig.APPLICATION_ID)
                        .put("versionName", BuildConfig.VERSION_NAME)
                        .put("versionCode", BuildConfig.VERSION_CODE))
                .put("shell", new JSONObject()
                        .put("ready", shell.isReady())
                        .put("installed", shell.installed)
                        .put("running", shell.running)
                        .put("permissionGranted", shell.permissionGranted)
                        .put("uid", shell.uid)
                        .put("apiVersion", shell.version)
                        .put("error", shell.error))
                .put("platform", new JSONObject()
                        .put("id", platform.id())
                        .put("name", platform.name())
                        .put("selection", PlatformDrivers.selectionDetail()))
                .put("session", sessionJson(session, target))
                .put("ui", uiJson(ui))
                .put("runtime", new JSONObject()
                        .put("mouseBridgeReady",
                                MagicDeskRuntime.isDesktopMouseBridgeReady())
                        .put("touchpadVisible",
                                DesktopOperations.isTouchpadVisible())
                        .put("controlPanelVisible",
                                ControlActivity.isControlPanelVisible())
                        .put("wakeLockHeld",
                                MagicDeskRuntime.isSessionWakeLockHeld())
                        .put("selfTestRunning",
                                DesktopSelfTestController.isRunning())
                        .put("terminalWindows",
                                ConsoleTerminalRegistry.registeredCount())
                        .put("termuxX11",
                                TermuxX11Integration.cachedStatusJson(
                                        mContext, null)))
                .put("windows", windows.toJson())
                .put("mcp", MagicDeskMcpRuntime.snapshotJson())
                .put("eventSequence",
                        DesktopAutomationEventJournal.latestId());
        return result;
    }

    JSONObject termuxX11Status() throws JSONException {
        final TaskRepository.Snapshot tasks = TaskRepository.loadAllNow();
        return new JSONObject()
                .put("generatedAtMillis", System.currentTimeMillis())
                .put("termuxX11",
                        TermuxX11Integration.refreshedStatusJson(
                                mContext, tasks));
    }

    JSONObject pointerState(final JSONObject arguments) throws JSONException {
        final DesktopSessionSnapshot session =
                DesktopRuntimeBridge.getSessionSnapshot();
        final Integer requestedDisplayId = optionalInteger(
                arguments == null ? new JSONObject() : arguments,
                "displayId");
        final int displayId = requestedDisplayId == null
                ? session.activeDisplayId() : requestedDisplayId.intValue();
        final PlatformSelection.Provider provider = PlatformDrivers.current()
                .selection().provider(PlatformComponent.POINTER);
        final DesktopPointerState state = displayId >= Display.DEFAULT_DISPLAY
                ? MagicDeskRuntime.getDesktopPointerState(displayId) : null;
        final Point position = state == null ? null : state.position;
        return new JSONObject()
                .put("generatedAtMillis", System.currentTimeMillis())
                .put("displayId", displayId)
                .put("active", session.hasHost()
                        && displayId == session.activeDisplayId())
                .put("provider", state != null
                        ? state.provider
                        : provider == null ? "android" : provider.id)
                .put("relayRequired", state != null
                        && state.relayRequired)
                .put("relayReady", state != null
                        && state.relayReady)
                .put("routingReady", state != null
                        && state.routingReady)
                .put("positionAvailable", position != null)
                .put("x", position == null ? JSONObject.NULL : position.x)
                .put("y", position == null ? JSONObject.NULL : position.y);
    }

    private static JSONObject uiJson(final DesktopUiSnapshot ui)
            throws JSONException {
        return new JSONObject()
                .put("available", ui.available)
                .put("displayId", ui.displayId)
                .put("taskbar", new JSONObject()
                        .put("visible", ui.taskbarVisible)
                        .put("bounds", rectJson(ui.taskbarBounds)))
                .put("startVisible", ui.startVisible)
                .put("popup", new JSONObject()
                        .put("visible", ui.popupVisible)
                        .put("title", ui.popupTitle)
                        .put("bounds", rectJson(ui.popupBounds)))
                .put("wallpaper", new JSONObject()
                        .put("rendered", ui.wallpaperRendered)
                        .put("fallback", ui.fallbackWallpaper));
    }

    JSONObject displays() throws JSONException {
        final JSONArray displays = new JSONArray();
        final DisplayManager manager =
                mContext.getSystemService(DisplayManager.class);
        if (manager != null) {
            for (final Display display : manager.getDisplays()) {
                displays.put(displayJson(display));
            }
        }
        return new JSONObject()
                .put("generatedAtMillis", System.currentTimeMillis())
                .put("displays", displays);
    }

    JSONObject tasks(final Integer displayFilter) throws JSONException {
        final JSONObject arguments = new JSONObject();
        if (displayFilter != null) {
            arguments.put("displayId", displayFilter.intValue());
        }
        return tasks(arguments);
    }

    JSONObject tasks(final JSONObject arguments) throws JSONException {
        final TaskRepository.Snapshot snapshot =
                TaskRepository.loadAllNow();
        final DesktopWindowObservation windows =
                DesktopWindowObservation.capture();
        final JSONObject args = arguments == null
                ? new JSONObject() : arguments;
        final Integer displayFilter = optionalInteger(args, "displayId");
        final String packageFilter = normalized(args, "package");
        final String modeFilter = normalized(args, "mode");
        final String query = normalized(args, "query").toLowerCase(Locale.ROOT);
        final int limit = pageLimit(args);
        final int offset = pageOffset(args);
        final List<TaskRepository.TaskEntry> filtered = new ArrayList<>();
        if (snapshot.available) {
            for (final TaskRepository.TaskEntry task : snapshot.tasks) {
                if (displayFilter != null
                        && task.displayId != displayFilter.intValue()) {
                    continue;
                }
                if (!packageFilter.isEmpty()
                        && !packageFilter.equals(task.packageName)) {
                    continue;
                }
                if (!modeFilter.isEmpty()
                        && !DesktopLaunchMode.matchesWindowingMode(
                                modeFilter, task.windowingMode)) {
                    continue;
                }
                if (!query.isEmpty()
                        && !task.packageName.toLowerCase(Locale.ROOT)
                                .contains(query)
                        && !task.componentName.toLowerCase(Locale.ROOT)
                                .contains(query)) {
                    continue;
                }
                filtered.add(task);
            }
        }
        final JSONArray tasks = new JSONArray();
        final int end = Math.min(filtered.size(), offset + limit);
        for (int index = Math.min(offset, filtered.size());
                index < end; index++) {
            final TaskRepository.TaskEntry task = filtered.get(index);
            tasks.put(taskJson(task, windows.health(task)));
        }
        return new JSONObject()
                .put("generatedAtMillis", System.currentTimeMillis())
                .put("available", snapshot.available)
                .put("error", snapshot.error)
                .put("windows", windows.toJson())
                .put("tasks", tasks)
                .put("items", tasks)
                .put("count", tasks.length())
                .put("total", filtered.size())
                .put("nextCursor", end < filtered.size()
                        ? Integer.toString(end) : JSONObject.NULL);
    }

    JSONObject apps() throws JSONException {
        return apps(new JSONObject());
    }

    JSONObject apps(final JSONObject arguments) throws JSONException {
        final JSONObject args = arguments == null
                ? new JSONObject() : arguments;
        final String packageFilter = normalized(args, "package");
        final String query = normalized(args, "query").toLowerCase(Locale.ROOT);
        final int limit = pageLimit(args);
        final int offset = pageOffset(args);
        final List<AppRow> rows = new ArrayList<>();
        final LauncherApps launcherApps =
                mContext.getSystemService(LauncherApps.class);
        if (launcherApps != null) {
            final List<LauncherActivityInfo> activities =
                    launcherApps.getActivityList(null, Process.myUserHandle());
            final Set<String> seen = new HashSet<>();
            for (final LauncherActivityInfo activity : activities) {
                final String component = activity.getComponentName()
                        .flattenToShortString();
                if (!seen.add(component)) {
                    continue;
                }
                final CharSequence label = activity.getLabel();
                final String packageName = activity.getComponentName()
                        .getPackageName();
                final String resolvedLabel = label == null
                        ? packageName : label.toString();
                if (!packageFilter.isEmpty()
                        && !packageFilter.equals(packageName)) {
                    continue;
                }
                if (!query.isEmpty()
                        && !packageName.toLowerCase(Locale.ROOT).contains(query)
                        && !component.toLowerCase(Locale.ROOT).contains(query)
                        && !resolvedLabel.toLowerCase(Locale.ROOT)
                                .contains(query)) {
                    continue;
                }
                rows.add(new AppRow(packageName, component, resolvedLabel));
            }
        }
        rows.sort(Comparator
                .comparing((AppRow row) -> row.label.toLowerCase(Locale.ROOT))
                .thenComparing(row -> row.component));
        final JSONArray result = new JSONArray();
        final int end = Math.min(rows.size(), offset + limit);
        for (int index = Math.min(offset, rows.size()); index < end; index++) {
            final AppRow row = rows.get(index);
            result.put(new JSONObject()
                    .put("package", row.packageName)
                    .put("component", row.component)
                    .put("label", row.label));
        }
        return new JSONObject()
                .put("generatedAtMillis", System.currentTimeMillis())
                .put("apps", result)
                .put("items", result)
                .put("count", result.length())
                .put("total", rows.size())
                .put("nextCursor", end < rows.size()
                        ? Integer.toString(end) : JSONObject.NULL);
    }

    JSONObject events(final long afterId, final int limit)
            throws JSONException {
        return new JSONObject()
                .put("latestId", DesktopAutomationEventJournal.latestId())
                .put("events", DesktopAutomationEventJournal.snapshot(
                        Math.max(0L, afterId), limit));
    }

    JSONObject uiElements(final JSONObject arguments) throws JSONException {
        final JSONObject args = arguments == null
                ? new JSONObject() : arguments;
        final int active = DesktopRuntimeBridge.getActiveDesktopDisplayId();
        final Integer requested = optionalInteger(args, "displayId");
        final int displayId = requested == null
                ? active : requested.intValue();
        if (displayId < Display.DEFAULT_DISPLAY) {
            throw new IllegalArgumentException("no active desktop display");
        }
        final DesktopAutomationUiRegistry.Snapshot snapshot =
                DesktopRuntimeBridge.getAutomationUiElements(
                        displayId,
                        normalized(args, "query"),
                        args.optBoolean("includeHidden", false));
        return snapshot.toJson()
                .put("generatedAtMillis", System.currentTimeMillis());
    }

    JSONObject diagnostics() throws JSONException {
        return new JSONObject().put(
                "report",
                CompatibilityDiagnostics.buildReport(mContext));
    }

    JSONObject selfTest() throws JSONException {
        final DesktopSelfTestRunState.Snapshot snapshot =
                DesktopSelfTestRunState.snapshot();
        return snapshot.toJson()
                .put("running", snapshot.active())
                .put("resultModifiedAtMillis",
                        DesktopSelfTestResult.lastModifiedMillis(mContext))
                .put("report", DesktopSelfTestResult.readLastResult(mContext));
    }

    private JSONObject sessionJson(
            final DesktopSessionSnapshot session,
            final DesktopDisplayTarget target) throws JSONException {
        final int displayId = session.activeDisplayId();
        final JSONObject result = new JSONObject()
                .put("active", session.hasHost())
                .put("starting", target != null && !session.hasHost())
                .put("displayId", displayId)
                .put("hostTaskId", session.hostTaskId());
        if (target == null) {
            return result.put("target", JSONObject.NULL);
        }
        result.put("target", new JSONObject()
                .put("kind", target.kind.name()
                        .toLowerCase(Locale.ROOT))
                .put("displayId", target.displayId)
                .put("profileDisplayId", target.profileDisplayId)
                .put("profileKey", target.profileKey)
                .put("activationSource",
                        target.activationSource.diagnosticLabel));
        if (displayId >= Display.DEFAULT_DISPLAY) {
            result.put("wallpaperRendered",
                            DesktopRuntimeBridge
                                    .isDesktopWallpaperRendered(displayId))
                    .put("fallbackWallpaper",
                            DesktopRuntimeBridge
                                    .isUsingFallbackDesktopWallpaper(displayId))
                    .put("taskbarVisible",
                            DesktopRuntimeBridge
                                    .isTaskbarVisibleOnDisplay(displayId))
                    .put("desktopWindowFocused",
                            DesktopRuntimeBridge
                                    .isDesktopWindowFocused(displayId));
        }
        return result;
    }

    private JSONObject displayJson(final Display display)
            throws JSONException {
        final Point realSize = new Point();
        display.getRealSize(realSize);
        final DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        final Display.Mode mode = display.getMode();
        final JSONArray modes = new JSONArray();
        for (final Display.Mode supported : display.getSupportedModes()) {
            modes.put(new JSONObject()
                    .put("modeId", supported.getModeId())
                    .put("width", supported.getPhysicalWidth())
                    .put("height", supported.getPhysicalHeight())
                    .put("refreshRate", supported.getRefreshRate()));
        }
        final Rect workArea = DesktopRuntimeBridge
                .getDesktopWorkAreaBounds(display.getDisplayId());
        return new JSONObject()
                .put("id", display.getDisplayId())
                .put("name", display.getName())
                .put("state", display.getState())
                .put("flags", String.format(Locale.ROOT,
                        "0x%x", display.getFlags()))
                .put("rotation", display.getRotation())
                .put("width", realSize.x)
                .put("height", realSize.y)
                .put("workArea", workArea == null
                        ? JSONObject.NULL : rectJson(workArea))
                .put("densityDpi", metrics.densityDpi)
                .put("mode", new JSONObject()
                        .put("modeId", mode.getModeId())
                        .put("width", mode.getPhysicalWidth())
                        .put("height", mode.getPhysicalHeight())
                        .put("refreshRate", mode.getRefreshRate()))
                .put("supportedModes", modes);
    }

    private static JSONObject taskJson(
            final TaskRepository.TaskEntry task,
            final DesktopWindowObservation.TaskHealth health)
            throws JSONException {
        return new JSONObject()
                .put("rootTaskId", task.rootTaskId)
                .put("taskId", task.taskId)
                .put("displayId", task.displayId)
                .put("package", task.packageName)
                .put("component", task.componentName)
                .put("topActivity", task.topActivityName)
                .put("windowingMode", task.windowingMode)
                .put("mode", DesktopLaunchMode.semanticWindowingMode(
                        task.windowingMode))
                .put("bounds", rectJson(task.bounds))
                .put("home", task.home)
                .put("visible", task.visible)
                .put("active", task.active)
                .put("health", health.toJson());
    }

    private static JSONObject rectJson(final Rect rect)
            throws JSONException {
        return new JSONObject()
                .put("left", rect.left)
                .put("top", rect.top)
                .put("right", rect.right)
                .put("bottom", rect.bottom);
    }

    private static int pageLimit(final JSONObject arguments) {
        final int limit = arguments.optInt("limit", 100);
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and 200");
        }
        return limit;
    }

    private static int pageOffset(final JSONObject arguments) {
        final String cursor = normalized(arguments, "cursor");
        if (cursor.isEmpty()) {
            return 0;
        }
        try {
            final int offset = Integer.parseInt(cursor);
            if (offset < 0) {
                throw new NumberFormatException();
            }
            return offset;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("invalid cursor");
        }
    }

    private static Integer optionalInteger(
            final JSONObject arguments,
            final String name) {
        if (!arguments.has(name)) {
            return null;
        }
        final Object value = arguments.opt(name);
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return Integer.valueOf(((Number) value).intValue());
    }

    private static String normalized(
            final JSONObject arguments,
            final String name) {
        return arguments.optString(name, "").trim();
    }

    private static final class AppRow {
        final String packageName;
        final String component;
        final String label;

        AppRow(
                final String packageName,
                final String component,
                final String label) {
            this.packageName = packageName;
            this.component = component;
            this.label = label;
        }
    }
}
