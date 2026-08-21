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
                                ConsoleModeSwitcher.isTouchpadVisible())
                        .put("controlPanelVisible",
                                ControlActivity.isControlPanelVisible())
                        .put("wakeLockHeld",
                                MagicDeskRuntime.isSessionWakeLockHeld())
                        .put("selfTestRunning",
                                DesktopSelfTestController.isRunning()))
                .put("mcp", MagicDeskMcpRuntime.snapshotJson())
                .put("eventSequence",
                        DesktopAutomationEventJournal.latestId());
        return result;
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
                        .put("fallback", ui.fallbackWallpaper))
                .put("desktopPlaneForeground", ui.desktopPlaneForeground);
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
        final TaskRepository.Snapshot snapshot =
                TaskRepository.loadAllNow();
        final JSONArray tasks = new JSONArray();
        if (snapshot.available) {
            for (final TaskRepository.TaskEntry task : snapshot.tasks) {
                if (displayFilter == null
                        || task.displayId == displayFilter.intValue()) {
                    tasks.put(taskJson(task));
                }
            }
        }
        return new JSONObject()
                .put("generatedAtMillis", System.currentTimeMillis())
                .put("available", snapshot.available)
                .put("error", snapshot.error)
                .put("tasks", tasks);
    }

    JSONObject apps() throws JSONException {
        final JSONArray result = new JSONArray();
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
                result.put(new JSONObject()
                        .put("package", activity.getComponentName()
                                .getPackageName())
                        .put("component", component)
                        .put("label", label == null
                                ? activity.getComponentName().getPackageName()
                                : label.toString()));
            }
        }
        return new JSONObject()
                .put("generatedAtMillis", System.currentTimeMillis())
                .put("apps", result);
    }

    JSONObject events(final long afterId, final int limit)
            throws JSONException {
        return new JSONObject()
                .put("latestId", DesktopAutomationEventJournal.latestId())
                .put("events", DesktopAutomationEventJournal.snapshot(
                        Math.max(0L, afterId), limit));
    }

    JSONObject diagnostics() throws JSONException {
        return new JSONObject().put(
                "report",
                CompatibilityDiagnostics.buildReport(mContext));
    }

    JSONObject selfTest() throws JSONException {
        return new JSONObject()
                .put("running", DesktopSelfTestController.isRunning())
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
            final TaskRepository.TaskEntry task) throws JSONException {
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
                .put("active", task.active);
    }

    private static JSONObject rectJson(final Rect rect)
            throws JSONException {
        return new JSONObject()
                .put("left", rect.left)
                .put("top", rect.top)
                .put("right", rect.right)
                .put("bottom", rect.bottom);
    }
}
