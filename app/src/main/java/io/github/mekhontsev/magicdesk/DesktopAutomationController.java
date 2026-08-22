package io.github.mekhontsev.magicdesk;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.MotionEvent;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Shared typed command boundary for UI-independent desktop automation. */
final class DesktopAutomationController {
    private static final long ACTION_TIMEOUT_MILLIS = 20_000L;
    private static final long LAUNCH_OBSERVE_TIMEOUT_MILLIS = 10_000L;
    private static final long MAX_WAIT_MILLIS = 60_000L;

    private final Context mContext;
    private final DesktopAutomationStateReader mState;
    private final DesktopAutomationCapture mCapture;
    private final Object mPointerLock = new Object();

    private int mPointerDisplayId = Display.INVALID_DISPLAY;
    private Point mPointerPosition;

    DesktopAutomationController(final Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        mContext = context.getApplicationContext();
        mState = new DesktopAutomationStateReader(mContext);
        mCapture = new DesktopAutomationCapture(mContext);
    }

    DesktopAutomationStateReader stateReader() {
        return mState;
    }

    DesktopAutomationResult execute(
            final String actionName,
            final JSONObject arguments,
            final boolean developerToolsEnabled) {
        final DesktopAutomationAction action =
                DesktopAutomationAction.parse(actionName);
        if (action == null) {
            return record(actionName, DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.UNKNOWN_ACTION,
                    "unknown automation action", false));
        }
        if (action.developerOnly && !developerToolsEnabled) {
            return record(action.wireName, DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.TOOL_DISABLED,
                    "developer automation tools are disabled", false));
        }
        try {
            final JSONObject args = arguments == null
                    ? new JSONObject() : arguments;
            final DesktopAutomationResult result;
            switch (action) {
                case START_DESKTOP:
                    result = startDesktop(optionalString(
                            args, "target", "auto"));
                    break;
                case CLOSE_DESKTOP:
                    result = closeDesktop();
                    break;
                case LAUNCH_APP:
                    result = launchApp(args);
                    break;
                case LIST_APP_ACTIONS:
                    result = listAppActions(args);
                    break;
                case INVOKE_APP_ACTION:
                    result = invokeAppAction(args);
                    break;
                case LAUNCH_SPEC:
                    result = launchSpec(args);
                    break;
                case FOCUS_TASK:
                    result = focusTask(requiredInt(args, "taskId"));
                    break;
                case CLOSE_TASK:
                    result = closeTask(requiredInt(args, "taskId"));
                    break;
                case FORCE_STOP_APP:
                    result = forceStopApp(requiredString(args, "package"));
                    break;
                case SET_WINDOW_MODE:
                    result = setWindowMode(args);
                    break;
                case SET_WINDOW_BOUNDS:
                    result = setWindowBounds(args);
                    break;
                case ARRANGE_TASK:
                    result = arrangeTask(args);
                    break;
                case SHOW_START:
                    result = simpleRuntimeAction(
                            MagicDeskRuntime.showStart(), "Start menu shown");
                    break;
                case SHOW_DESKTOP:
                    result = simpleRuntimeAction(
                            MagicDeskRuntime.toggleDesktopWorkspace(),
                            "desktop visibility toggled");
                    break;
                case OPEN_SETTINGS:
                    result = openSettings();
                    break;
                case OPEN_BUILTIN:
                    result = openBuiltin(args);
                    break;
                case CAPTURE_SCREENSHOT:
                    result = captureScreenshot(args);
                    break;
                case SAMPLE_PIXELS:
                    result = mCapture.samplePixels(args);
                    break;
                case GET_RECORDING_STATUS:
                    result = recordingStatus("screen recording status");
                    break;
                case START_RECORDING:
                    result = startRecording();
                    break;
                case STOP_RECORDING:
                    result = stopRecording();
                    break;
                case RUN_SELF_TEST:
                    result = runSelfTest(args);
                    break;
                case SEND_KEY:
                    result = sendKey(args);
                    break;
                case MOVE_POINTER:
                    result = movePointer(args);
                    break;
                case CLICK_POINTER:
                    result = clickPointer(args);
                    break;
                default:
                    result = DesktopAutomationResult.failure(
                            DesktopAutomationErrorCode.UNKNOWN_ACTION,
                            "unsupported automation action", false);
                    break;
            }
            return record(action.wireName, result);
        } catch (IllegalArgumentException error) {
            return record(action.wireName,
                    DesktopAutomationResult.failure(
                            DesktopAutomationErrorCode.INVALID_ARGUMENT,
                            error.getMessage(), false));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return record(action.wireName,
                    DesktopAutomationResult.failure(
                            DesktopAutomationErrorCode.ACTION_FAILED,
                            "automation action was interrupted", true));
        } catch (IOException | JSONException | RuntimeException error) {
            return record(action.wireName,
                    DesktopAutomationResult.failure(
                            DesktopAutomationErrorCode.ACTION_FAILED,
                            ShellAccess.usefulMessage(error), false));
        }
    }

    DesktopAutomationResult waitFor(
            final JSONObject arguments) {
        final JSONObject args = arguments == null
                ? new JSONObject() : arguments;
        final String condition = requiredString(args, "condition");
        final long timeoutMillis = Math.max(
                1L,
                Math.min(
                        MAX_WAIT_MILLIS,
                        args.optLong("timeoutMillis", 10_000L)));
        final long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        long observedEventId = DesktopAutomationEventJournal.latestId();
        JSONObject observation = new JSONObject();
        while (true) {
            try {
                observation = observeCondition(condition, args);
                if (observation.optBoolean("matched", false)) {
                    return record("wait_for_state",
                            DesktopAutomationResult.success(
                                    "condition matched", observation));
                }
            } catch (JSONException error) {
                return record("wait_for_state",
                        DesktopAutomationResult.failure(
                                DesktopAutomationErrorCode.INVALID_ARGUMENT,
                                error.getMessage(), false));
            }
            final long remaining = deadline - SystemClock.uptimeMillis();
            if (remaining <= 0L) {
                break;
            }
            try {
                observedEventId = DesktopAutomationEventJournal.awaitChange(
                        observedEventId, remaining);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return record("wait_for_state",
                        DesktopAutomationResult.failure(
                                DesktopAutomationErrorCode.ACTION_FAILED,
                                "wait was interrupted", true, observation));
            }
        }
        try {
            observation.put("timeoutMillis", timeoutMillis);
        } catch (JSONException ignored) {
        }
        return record("wait_for_state", DesktopAutomationResult.failure(
                DesktopAutomationErrorCode.TIMEOUT,
                "condition timed out", true, observation));
    }

    private DesktopAutomationResult startDesktop(final String rawTarget)
            throws JSONException {
        if (!ShellAccess.isReady()) {
            return DesktopAutomationResult.failure(
                    "shell command service is unavailable");
        }
        final String target = rawTarget.toLowerCase(Locale.ROOT);
        switch (target) {
            case "auto":
                ConsoleModeSwitcher.showMagicDesk();
                break;
            case "phone":
                mContext.startActivity(
                        ControlActivity.createOpenDesktopIntent(mContext));
                break;
            case "simulated":
                SimulatedDesktopDisplayController.show();
                break;
            case "wired":
                DesktopDisplayDrivers
                        .forKind(DesktopDisplayTarget.Kind.WIRED)
                        .show(null,
                                ConsoleDisplayController
                                        .findExternalDisplayId());
                break;
            case "wireless":
                final int wirelessDisplayId =
                        ConsoleDisplayController.findWirelessDisplayId();
                if (wirelessDisplayId <= Display.DEFAULT_DISPLAY) {
                    return DesktopAutomationResult.failure(
                            "no connected wireless display");
                }
                ConsoleModeSwitcher.showDesktop(
                        DesktopDisplayTarget.wireless(wirelessDisplayId));
                break;
            default:
                throw new IllegalArgumentException(
                        "target must be auto, phone, simulated, wired, or wireless");
        }
        return DesktopAutomationResult.success(
                "desktop start accepted",
                new JSONObject().put("target", target));
    }

    private DesktopAutomationResult closeDesktop()
            throws InterruptedException {
        final DesktopDisplayTarget target =
                DesktopRuntimeBridge.getActiveDesktopTarget();
        if (target == null) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.DESKTOP_NOT_ACTIVE,
                    "no active desktop session", true);
        }
        final CountDownLatch completed = new CountDownLatch(1);
        final boolean[] success = new boolean[1];
        ConsoleModeSwitcher.closeDesktop(target, true, value -> {
            success[0] = value;
            completed.countDown();
        });
        if (!completed.await(ACTION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.TIMEOUT,
                    "desktop close timed out", true);
        }
        return success[0]
                ? DesktopAutomationResult.success(
                        "desktop closed", new JSONObject())
                : DesktopAutomationResult.failure(
                        DesktopAutomationErrorCode.ACTION_FAILED,
                        "desktop close failed", true);
    }

    private DesktopAutomationResult launchApp(final JSONObject args)
            throws JSONException, InterruptedException {
        final String packageName = requiredString(args, "package");
        final AppLaunchTarget target = appTarget(args);
        final DesktopLaunchMode mode = parseLaunchMode(
                optionalString(args, "mode", "auto"));
        final int activeDisplayId =
                DesktopRuntimeBridge.getActiveDesktopDisplayId();
        final int displayId = args.has("displayId")
                ? requiredInt(args, "displayId") : activeDisplayId;
        if (displayId < Display.DEFAULT_DISPLAY
                || displayId != activeDisplayId) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.DISPLAY_NOT_AVAILABLE,
                    "the requested display has no active desktop host", true);
        }
        if (!DesktopRuntimeBridge.launchApplication(
                target, mode, displayId)) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.HOST_UNAVAILABLE,
                    "desktop host is unavailable", true);
        }
        final TaskRepository.TaskEntry launchedTask =
                waitForLaunchedTask(target, displayId);
        final JSONObject data = new JSONObject()
                .put("package", packageName)
                .put("displayId", displayId)
                .put("mode", mode.wireName)
                .put("taskObserved", launchedTask != null);
        if (launchedTask != null) {
            data.put("taskId", launchedTask.taskId)
                    .put("observedMode", DesktopLaunchMode
                            .semanticWindowingMode(
                                    launchedTask.windowingMode))
                    .put("nativeWindowingMode",
                            launchedTask.windowingMode);
            data.put("health", DesktopWindowObservation.capture()
                    .health(launchedTask)
                    .toJson());
        }
        return DesktopAutomationResult.success(
                "application launch accepted",
                data);
    }

    private DesktopAutomationResult listAppActions(final JSONObject args)
            throws JSONException {
        final AppLaunchTarget target = appTarget(args);
        final org.json.JSONArray actions = new org.json.JSONArray();
        for (final AppShortcutAction action
                : new AppShortcutRepository(mContext).load(target)) {
            actions.put(new JSONObject()
                    .put("id", action.id)
                    .put("label", action.label));
        }
        return DesktopAutomationResult.success(
                "application actions listed",
                new JSONObject()
                        .put("package", target.packageName)
                        .put("actions", actions));
    }

    private DesktopAutomationResult invokeAppAction(final JSONObject args)
            throws JSONException {
        final AppLaunchTarget target = appTarget(args);
        final String actionId = requiredString(args, "actionId");
        if (!DesktopRuntimeBridge.invokeAppAction(target, actionId)) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.ACTION_FAILED,
                    "application action was not found or could not launch",
                    false,
                    new JSONObject()
                            .put("package", target.packageName)
                            .put("actionId", actionId));
        }
        return DesktopAutomationResult.success(
                "application action launch accepted",
                new JSONObject()
                        .put("package", target.packageName)
                        .put("actionId", actionId));
    }

    private DesktopAutomationResult launchSpec(final JSONObject args)
            throws IOException, JSONException {
        final String desktopPath = optionalString(args, "desktopPath", "");
        final JSONObject android = args.optJSONObject("android");
        if (desktopPath.isEmpty() == (android == null)) {
            throw new IllegalArgumentException(
                    "provide exactly one of desktopPath or android");
        }
        final int displayId = optionalDisplayId(args);
        final boolean launched;
        final String kind;
        if (!desktopPath.isEmpty()) {
            if (!ShellAccess.isReady()) {
                return DesktopAutomationResult.failure(
                        DesktopAutomationErrorCode.SHELL_UNAVAILABLE,
                        "shell command service is unavailable", true);
            }
            final ShellFileInfo file = ShellAccess.getShellFileInfo(
                    desktopPath);
            final DesktopEntry entry = DesktopEntryFile.read(file);
            if (entry == null) {
                throw new IllegalArgumentException(
                        "unsupported or invalid .desktop file");
            }
            if (entry instanceof DesktopFolderShortcut) {
                launched = DesktopRuntimeBridge.openFilesAt(
                        ((DesktopFolderShortcut) entry).targetPath,
                        displayId);
                kind = "folder";
            } else if (entry instanceof DesktopWebShortcut) {
                launched = DesktopRuntimeBridge.launchDesktopWebShortcut(
                        (DesktopWebShortcut) entry, displayId);
                kind = "web";
            } else if (entry instanceof DesktopApplicationShortcut) {
                final DesktopLaunchRequest request = DesktopLaunchRequest.from(
                        (DesktopApplicationShortcut) entry,
                        launchArguments(args),
                        desktopPath);
                launched = DesktopRuntimeBridge.launchAutomationRequest(
                        request, displayId);
                kind = "application";
            } else {
                throw new IllegalArgumentException(
                        "unsupported .desktop entry type");
            }
        } else {
            final String packageName = optionalString(
                    android, "package", "");
            final String componentValue = optionalString(
                    android, "component", "");
            final String action = optionalString(
                    android, "action", Intent.ACTION_MAIN);
            final String intentUri = optionalString(
                    android, "intentUri", "");
            AppLaunchTarget target = null;
            if (!packageName.isEmpty()) {
                if (componentValue.isEmpty()) {
                    target = AppLaunchTarget.packageDefault(packageName);
                } else {
                    final ComponentName component =
                            ComponentName.unflattenFromString(componentValue);
                    if (component == null || !packageName.equals(
                            component.getPackageName())) {
                        throw new IllegalArgumentException(
                                "component must belong to package");
                    }
                    target = AppLaunchTarget.explicit(
                            packageName, component.getClassName(), action);
                }
            }
            final AndroidLaunchSpec launch;
            if (!intentUri.isEmpty()) {
                launch = AndroidLaunchSpec.intent(target, intentUri);
            } else if (target != null) {
                launch = AndroidLaunchSpec.defaultLaunch(target);
            } else {
                throw new IllegalArgumentException(
                        "android package or intentUri is required");
            }
            final DesktopLaunchRequest request = new DesktopLaunchRequest(
                    optionalString(android, "name",
                            packageName.isEmpty() ? "Android app" : packageName),
                    "",
                    launch,
                    null,
                    parseLaunchMode(optionalString(
                            android, "mode", "auto")));
            launched = DesktopRuntimeBridge.launchAutomationRequest(
                    request, displayId);
            kind = "android";
        }
        if (!launched) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.HOST_UNAVAILABLE,
                    "desktop launch request was not accepted", true);
        }
        return DesktopAutomationResult.success(
                "desktop launch request accepted",
                new JSONObject()
                        .put("kind", kind)
                        .put("displayId", displayId));
    }

    private static DesktopLaunchArguments launchArguments(
            final JSONObject args) throws JSONException {
        final org.json.JSONArray files = args.optJSONArray("files");
        if (files == null || files.length() == 0) {
            return DesktopLaunchArguments.empty();
        }
        final List<String> paths = new ArrayList<>();
        for (int index = 0; index < files.length(); index++) {
            paths.add(files.getString(index));
        }
        return DesktopLaunchArguments.files(paths);
    }

    private DesktopAutomationResult focusTask(final int taskId)
            throws InterruptedException {
        final TaskRepository.TaskEntry task = findTask(taskId);
        if (task == null) {
            return taskNotFound(taskId);
        }
        return awaitTaskAction(callback -> MagicDeskRuntime.focusDesktopTask(
                task.displayId, task.taskId, callback));
    }

    private DesktopAutomationResult closeTask(final int taskId)
            throws InterruptedException {
        final TaskRepository.TaskEntry task = findTask(taskId);
        if (task == null) {
            return taskNotFound(taskId);
        }
        return awaitTaskAction(callback ->
                MagicDeskRuntime.closeTask(task, callback));
    }

    private DesktopAutomationResult forceStopApp(final String packageName)
            throws InterruptedException {
        if (!PackageNameValidator.isSafe(packageName)) {
            throw new IllegalArgumentException("invalid package");
        }
        return awaitTaskAction(callback ->
                MagicDeskRuntime.forceStopPackage(packageName, callback));
    }

    private DesktopAutomationResult setWindowMode(final JSONObject args)
            throws IOException, JSONException, InterruptedException {
        final int taskId = requiredInt(args, "taskId");
        final TaskRepository.TaskEntry task = findTask(taskId);
        if (task == null) {
            return taskNotFound(taskId);
        }
        final String mode = requiredString(args, "mode")
                .toLowerCase(Locale.ROOT);
        if ("fullscreen".equals(mode)) {
            return awaitTaskAction(callback ->
                    TaskRepository.setFullscreen(task, callback));
        }
        if (!"windowed".equals(mode) && !"freeform".equals(mode)) {
            throw new IllegalArgumentException(
                    "mode must be windowed or fullscreen");
        }
        final Rect bounds = readBounds(
                args.optJSONObject("bounds"), task.displayId);
        return awaitTaskAction(callback ->
                TaskRepository.setFreeform(task, bounds, callback));
    }

    private DesktopAutomationResult setWindowBounds(final JSONObject args)
            throws IOException, JSONException, InterruptedException {
        final int taskId = requiredInt(args, "taskId");
        final TaskRepository.TaskEntry task = findTask(taskId);
        if (task == null) {
            return taskNotFound(taskId);
        }
        final Rect bounds = readBounds(
                requiredObject(args, "bounds"), task.displayId);
        return awaitTaskAction(callback ->
                TaskRepository.resizeTaskBounds(task, bounds, callback));
    }

    private DesktopAutomationResult openSettings() {
        if (MagicDeskRuntime.openSettings()) {
            return DesktopAutomationResult.success(
                    "settings opened", new JSONObject());
        }
        mContext.startActivity(SettingsActivity.createIntent(mContext)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return DesktopAutomationResult.success(
                "settings launch accepted", new JSONObject());
    }

    private DesktopAutomationResult openBuiltin(final JSONObject args)
            throws JSONException {
        final String builtin = requiredString(args, "builtin")
                .toLowerCase(Locale.ROOT);
        if (!DesktopRuntimeBridge.openBuiltin(builtin)) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.HOST_UNAVAILABLE,
                    "desktop host or built-in window is unavailable", true,
                    new JSONObject().put("builtin", builtin));
        }
        return DesktopAutomationResult.success(
                "built-in window launch accepted",
                new JSONObject().put("builtin", builtin));
    }

    private DesktopAutomationResult arrangeTask(final JSONObject args)
            throws JSONException {
        final int taskId = requiredInt(args, "taskId");
        final TaskRepository.TaskEntry task = findTask(taskId);
        if (task == null) {
            return taskNotFound(taskId);
        }
        final String arrangement = requiredString(args, "arrangement")
                .toLowerCase(Locale.ROOT);
        final int shortcut;
        switch (arrangement) {
            case "left":
                shortcut = DesktopTaskController.SHORTCUT_SNAP_LEFT;
                break;
            case "right":
                shortcut = DesktopTaskController.SHORTCUT_SNAP_RIGHT;
                break;
            case "maximize":
                shortcut = DesktopTaskController.SHORTCUT_FULLSCREEN;
                break;
            case "restore":
                shortcut = DesktopTaskController.SHORTCUT_RESTORE;
                break;
            default:
                throw new IllegalArgumentException(
                        "arrangement must be left, right, maximize, or restore");
        }
        if (!MagicDeskRuntime.arrangeTask(taskId, shortcut)) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.HOST_UNAVAILABLE,
                    "desktop task runtime is unavailable", true);
        }
        return DesktopAutomationResult.success(
                "task arrangement accepted",
                new JSONObject()
                        .put("taskId", taskId)
                        .put("arrangement", arrangement));
    }

    private DesktopAutomationResult captureScreenshot(
            final JSONObject args) {
        return mCapture.screenshot(args.has("displayId")
                ? Integer.valueOf(requiredInt(args, "displayId")) : null);
    }

    private DesktopAutomationResult startRecording()
            throws JSONException {
        final DisplayRecordingController controller =
                DisplayRecordingController.get();
        if (!controller.requestStart()) {
            return recordingStateFailure(
                    "screen recording is not idle", controller.snapshot());
        }
        return recordingStatus("screen recording start accepted");
    }

    private DesktopAutomationResult stopRecording()
            throws JSONException {
        final DisplayRecordingController controller =
                DisplayRecordingController.get();
        if (!controller.requestStop()) {
            return recordingStateFailure(
                    "screen recording is not active", controller.snapshot());
        }
        return recordingStatus("screen recording stop accepted");
    }

    private DesktopAutomationResult recordingStatus(final String message)
            throws JSONException {
        return DesktopAutomationResult.success(
                message,
                recordingJson(DisplayRecordingController.get().snapshot()));
    }

    private static DesktopAutomationResult recordingStateFailure(
            final String message,
            final DisplayRecordingController.Snapshot snapshot)
            throws JSONException {
        return DesktopAutomationResult.failure(
                DesktopAutomationErrorCode.ACTION_FAILED,
                message,
                true,
                recordingJson(snapshot));
    }

    private static JSONObject recordingJson(
            final DisplayRecordingController.Snapshot snapshot)
            throws JSONException {
        return new JSONObject()
                .put("state", snapshot.state.name()
                        .toLowerCase(Locale.ROOT))
                .put("message", snapshot.message);
    }

    private DesktopAutomationResult runSelfTest(final JSONObject args)
            throws JSONException {
        final KeyguardManager keyguard =
                mContext.getSystemService(KeyguardManager.class);
        final PowerManager power =
                mContext.getSystemService(PowerManager.class);
        if ((keyguard != null && keyguard.isDeviceLocked())
                || (power != null && !power.isInteractive())) {
            return DesktopAutomationResult.failure(
                    "unlock the phone before starting the self-test");
        }
        final String rawTarget = optionalString(args, "target", "simulated")
                .toLowerCase(Locale.ROOT);
        final DesktopSelfTestTarget target;
        DesktopDisplayTarget.Kind displayKind = null;
        switch (rawTarget) {
            case "phone":
                target = DesktopSelfTestTarget.PHONE;
                break;
            case "simulated":
                target = DesktopSelfTestTarget.SIMULATED;
                break;
            case "wired":
                target = DesktopSelfTestTarget.EXTERNAL;
                displayKind = DesktopDisplayTarget.Kind.WIRED;
                break;
            case "wireless":
                target = DesktopSelfTestTarget.EXTERNAL;
                displayKind = DesktopDisplayTarget.Kind.WIRELESS;
                break;
            default:
                throw new IllegalArgumentException(
                        "target must be phone, simulated, wired, or wireless");
        }
        final Intent intent = DiagnosticsActivity.createIntent(mContext)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(
                        DiagnosticsActivity.EXTRA_SELF_TEST_TARGET,
                        target.name());
        if (displayKind != null) {
            intent.putExtra(
                    DiagnosticsActivity.EXTRA_SELF_TEST_DISPLAY_KIND,
                    displayKind.name());
        }
        final long requestedAtMillis = System.currentTimeMillis();
        mContext.startActivity(intent);
        return DesktopAutomationResult.success(
                "self-test launch accepted",
                new JSONObject()
                        .put("target", rawTarget)
                        .put("requestedAtMillis", requestedAtMillis));
    }

    private DesktopAutomationResult sendKey(final JSONObject args)
            throws IOException, JSONException {
        final int displayId = optionalDisplayId(args);
        String keyCode = requiredString(args, "keyCode")
                .toUpperCase(Locale.ROOT);
        if (!keyCode.startsWith("KEYCODE_")) {
            keyCode = "KEYCODE_" + keyCode;
        }
        if (!keyCode.matches("KEYCODE_[A-Z0-9_]+")) {
            throw new IllegalArgumentException("invalid keyCode");
        }
        ShellAccess.run("/system/bin/input -d " + displayId
                + " keyevent " + keyCode);
        return DesktopAutomationResult.success(
                "key sent",
                new JSONObject()
                        .put("displayId", displayId)
                        .put("keyCode", keyCode));
    }

    private DesktopAutomationResult movePointer(final JSONObject args) {
        final int displayId = optionalDisplayId(args);
        final int x = requiredInt(args, "x");
        final int y = requiredInt(args, "y");
        final boolean success = ShellAccess.injectPointerHoverAt(
                displayId, x, y);
        if (success) {
            synchronized (mPointerLock) {
                mPointerDisplayId = displayId;
                mPointerPosition = new Point(x, y);
            }
        }
        return simpleRuntimeAction(success, "pointer moved");
    }

    private DesktopAutomationResult clickPointer(final JSONObject args) {
        final int displayId = optionalDisplayId(args);
        final String value = optionalString(args, "button", "primary");
        final int button;
        if ("primary".equalsIgnoreCase(value)
                || "left".equalsIgnoreCase(value)) {
            button = MotionEvent.BUTTON_PRIMARY;
        } else if ("secondary".equalsIgnoreCase(value)
                || "right".equalsIgnoreCase(value)) {
            button = MotionEvent.BUTTON_SECONDARY;
        } else {
            throw new IllegalArgumentException(
                    "button must be primary or secondary");
        }
        final Point positionedClick;
        synchronized (mPointerLock) {
            positionedClick = mPointerDisplayId == displayId
                    && mPointerPosition != null
                            ? new Point(mPointerPosition) : null;
            mPointerDisplayId = Display.INVALID_DISPLAY;
            mPointerPosition = null;
        }
        if (positionedClick != null) {
            return simpleRuntimeAction(
                    ShellAccess.injectPointerClickAt(
                            displayId,
                            positionedClick.x,
                            positionedClick.y,
                            button),
                    "pointer clicked");
        }
        MagicDeskRuntime.activateDesktopPointer(displayId);
        return simpleRuntimeAction(
                MagicDeskRuntime.clickDesktopPointer(displayId, button),
                "pointer clicked");
    }

    private JSONObject observeCondition(
            final String condition,
            final JSONObject args) throws JSONException {
        final JSONObject observation = new JSONObject()
                .put("condition", condition);
        switch (condition) {
            case "desktop_active": {
                final DesktopSessionSnapshot session =
                        DesktopRuntimeBridge.getSessionSnapshot();
                return observation
                        .put("matched", session.hasHost())
                        .put("displayId", session.activeDisplayId());
            }
            case "desktop_inactive": {
                final DesktopSessionSnapshot session =
                        DesktopRuntimeBridge.getSessionSnapshot();
                return observation.put("matched",
                        session.target() == null && !session.hasHost());
            }
            case "task_present":
            case "task_absent":
            case "task_windowing_mode":
            case "task_focused":
            case "task_bounds": {
                final TaskRepository.TaskEntry task = findTask(
                        requiredInt(args, "taskId"));
                boolean matched = task != null;
                if ("task_absent".equals(condition)) {
                    matched = task == null;
                } else if ("task_windowing_mode".equals(condition)) {
                    matched = task != null
                            && DesktopLaunchMode.matchesWindowingMode(
                                    requiredString(args, "mode"),
                                    task.windowingMode);
                } else if ("task_focused".equals(condition)) {
                    matched = task != null && task.active;
                } else if ("task_bounds".equals(condition)) {
                    final Rect expected = readWaitBounds(
                            requiredObject(args, "bounds"));
                    final int tolerance = Math.max(
                            0, args.optInt("tolerance", 0));
                    matched = task != null
                            && boundsMatch(task.bounds, expected, tolerance);
                    observation.put("expectedBounds", rectJson(expected))
                            .put("tolerance", tolerance);
                }
                observation.put("matched", matched);
                if (task != null) {
                    observation.put("displayId", task.displayId)
                            .put("windowingMode", task.windowingMode)
                            .put("mode", DesktopLaunchMode
                                    .semanticWindowingMode(
                                            task.windowingMode))
                            .put("bounds", rectJson(task.bounds))
                            .put("visible", task.visible);
                }
                return observation;
            }
            case "app_ready":
            case "app_crashed":
            case "app_not_responding": {
                final int taskId = requiredInt(args, "taskId");
                final TaskRepository.Snapshot snapshot =
                        TaskRepository.loadAllNow();
                final TaskRepository.TaskEntry task =
                        findTask(snapshot, taskId);
                final DesktopWindowObservation windows =
                        DesktopWindowObservation.capture();
                final DesktopWindowObservation.TaskHealth health =
                        windows.health(task);
                final DesktopProcessHealthRegistry.Failure failure =
                        task == null
                                ? DesktopProcessHealthRegistry.find(taskId)
                                : health.failure;
                final boolean matched;
                if ("app_ready".equals(condition)) {
                    matched = windows.available() && health.ready;
                } else if ("app_crashed".equals(condition)) {
                    matched = health.crashed
                            || (failure != null && failure.crashed());
                } else {
                    matched = health.notResponding
                            || (failure != null
                                    && failure.notResponding());
                }
                observation.put("matched", matched)
                        .put("taskId", taskId)
                        .put("windowStateAvailable", windows.available())
                        .put("health", health.toJson());
                if (failure != null && health.failure == null) {
                    observation.put("lastFailure", failure.toJson());
                }
                return observation;
            }
            case "system_dialog_visible": {
                final int displayId = optionalDisplayId(args);
                final Integer taskId = args.has("taskId")
                        ? Integer.valueOf(requiredInt(args, "taskId")) : null;
                final String packageName = optionalString(
                        args, "package", "");
                final TaskRepository.Snapshot snapshot =
                        TaskRepository.loadAllNow();
                final DesktopWindowObservation windows =
                        DesktopWindowObservation.capture();
                return observation
                        .put("matched", windows.available()
                                && windows.hasBlockingSystemDialog(
                                        Integer.valueOf(displayId),
                                        taskId,
                                        packageName,
                                        snapshot))
                        .put("displayId", displayId)
                        .put("windowState", windows.toJson());
            }
            case "pointer_ready":
                return observation.put(
                        "matched", MagicDeskRuntime.isDesktopMouseBridgeReady());
            case "ui_visible": {
                final String element = requiredString(args, "element")
                        .toLowerCase(Locale.ROOT);
                final int displayId = optionalDisplayId(args);
                final DesktopUiSnapshot ui = DesktopRuntimeBridge
                        .getAutomationUiSnapshot(displayId);
                final boolean visible;
                switch (element) {
                    case "taskbar":
                        visible = ui.taskbarVisible;
                        break;
                    case "start":
                        visible = ui.startVisible;
                        break;
                    case "popup":
                        visible = ui.popupVisible;
                        break;
                    case "wallpaper":
                        visible = ui.wallpaperRendered;
                        break;
                    case "touchpad":
                        visible = ConsoleModeSwitcher.isTouchpadVisible();
                        break;
                    case "control_panel":
                        visible = ControlActivity.isControlPanelVisible();
                        break;
                    default:
                        throw new IllegalArgumentException(
                                "unknown UI element");
                }
                return observation.put("matched", visible)
                        .put("displayId", displayId)
                        .put("element", element)
                        .put("uiAvailable", ui.available);
            }
            case "taskbar_visible": {
                final int displayId = optionalDisplayId(args);
                return observation
                        .put("matched", DesktopRuntimeBridge
                                .isTaskbarVisibleOnDisplay(displayId))
                        .put("displayId", displayId);
            }
            case "wallpaper_rendered": {
                final int displayId = optionalDisplayId(args);
                return observation
                        .put("matched", DesktopRuntimeBridge
                                .isDesktopWallpaperRendered(displayId))
                        .put("displayId", displayId);
            }
            case "self_test_finished":
                final long startedAfterMillis = Math.max(
                        0L, args.optLong("startedAfterMillis", 0L));
                final long resultModifiedAtMillis =
                        DesktopSelfTestResult.lastModifiedMillis(mContext);
                return observation
                        .put("matched",
                                !DesktopSelfTestController.isRunning()
                                        && resultModifiedAtMillis
                                                >= startedAfterMillis)
                        .put("resultModifiedAtMillis",
                                resultModifiedAtMillis);
            default:
                throw new IllegalArgumentException("unknown wait condition");
        }
    }

    private TaskRepository.TaskEntry findTask(final int taskId) {
        final TaskRepository.Snapshot snapshot = TaskRepository.loadAllNow();
        return findTask(snapshot, taskId);
    }

    private static TaskRepository.TaskEntry findTask(
            final TaskRepository.Snapshot snapshot, final int taskId) {
        if (!snapshot.available) {
            return null;
        }
        for (final TaskRepository.TaskEntry task : snapshot.tasks) {
            if (task.taskId == taskId) {
                return task;
            }
        }
        return null;
    }

    private static Rect readWaitBounds(final JSONObject json) {
        final Rect bounds = new Rect(
                requiredInt(json, "left"),
                requiredInt(json, "top"),
                requiredInt(json, "right"),
                requiredInt(json, "bottom"));
        if (!TaskRepository.hasExplicitBounds(bounds)) {
            throw new IllegalArgumentException("invalid bounds");
        }
        return bounds;
    }

    private static boolean boundsMatch(
            final Rect actual,
            final Rect expected,
            final int tolerance) {
        return actual != null
                && Math.abs(actual.left - expected.left) <= tolerance
                && Math.abs(actual.top - expected.top) <= tolerance
                && Math.abs(actual.right - expected.right) <= tolerance
                && Math.abs(actual.bottom - expected.bottom) <= tolerance;
    }

    private static JSONObject rectJson(final Rect bounds)
            throws JSONException {
        return new JSONObject()
                .put("left", bounds.left)
                .put("top", bounds.top)
                .put("right", bounds.right)
                .put("bottom", bounds.bottom);
    }

    private TaskRepository.TaskEntry waitForLaunchedTask(
            final AppLaunchTarget target,
            final int displayId) throws InterruptedException {
        final long deadline = SystemClock.uptimeMillis()
                + LAUNCH_OBSERVE_TIMEOUT_MILLIS;
        long observedEventId = DesktopAutomationEventJournal.latestId();
        TaskRepository.TaskEntry candidate = null;
        while (true) {
            candidate = null;
            final TaskRepository.Snapshot snapshot =
                    TaskRepository.loadAllNow();
            if (snapshot.available) {
                for (final TaskRepository.TaskEntry task : snapshot.tasks) {
                    if (task.displayId != displayId
                            || !target.matchesTask(task)) {
                        continue;
                    }
                    if (candidate == null || task.active
                            || (!candidate.visible && task.visible)) {
                        candidate = task;
                    }
                    if (task.active) {
                        return task;
                    }
                }
                if (candidate != null && candidate.visible) {
                    return candidate;
                }
            }
            final long remaining = deadline - SystemClock.uptimeMillis();
            if (remaining <= 0L) {
                break;
            }
            observedEventId = DesktopAutomationEventJournal.awaitChange(
                    observedEventId, remaining);
        }
        return candidate;
    }

    private DesktopAutomationResult awaitTaskAction(
            final TaskAction action) throws InterruptedException {
        final CountDownLatch completed = new CountDownLatch(1);
        final TaskRepository.ActionResult[] result =
                new TaskRepository.ActionResult[1];
        action.run(value -> {
            result[0] = value;
            completed.countDown();
        });
        if (!completed.await(ACTION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.TIMEOUT,
                    "task action timed out", true);
        }
        if (result[0] == null) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.ACTION_FAILED,
                    "task action returned no result", true);
        }
        return result[0].success
                ? DesktopAutomationResult.success(
                        result[0].message, new JSONObject())
                : DesktopAutomationResult.failure(result[0].message);
    }

    private static DesktopAutomationResult taskNotFound(final int taskId) {
        try {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.TASK_NOT_FOUND,
                    "task not found", false,
                    new JSONObject().put("taskId", taskId));
        } catch (JSONException ignored) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.TASK_NOT_FOUND,
                    "task not found", false);
        }
    }

    private DesktopAutomationResult simpleRuntimeAction(
            final boolean success, final String message) {
        return success
                ? DesktopAutomationResult.success(message, new JSONObject())
                : DesktopAutomationResult.failure(
                        "desktop runtime is unavailable");
    }

    private DesktopAutomationResult record(
            final String operation,
            final DesktopAutomationResult result) {
        DesktopAutomationEventJournal.record(
                "action", operation, result.success, result.message);
        return result;
    }

    private int optionalDisplayId(final JSONObject args) {
        final int active = DesktopRuntimeBridge.getActiveDesktopDisplayId();
        final int displayId = args.has("displayId")
                ? requiredInt(args, "displayId") : active;
        if (displayId < Display.DEFAULT_DISPLAY) {
            throw new IllegalArgumentException(
                    "no active desktop display");
        }
        return displayId;
    }

    private static Rect readBounds(
            final JSONObject json,
            final int displayId) throws IOException {
        if (json == null) {
            return FloatingWindowController.getDefaultWindowBounds(displayId);
        }
        final Rect bounds = new Rect(
                requiredInt(json, "left"),
                requiredInt(json, "top"),
                requiredInt(json, "right"),
                requiredInt(json, "bottom"));
        if (!TaskRepository.hasExplicitBounds(bounds)) {
            throw new IllegalArgumentException("invalid bounds");
        }
        return bounds;
    }

    private static DesktopLaunchMode parseLaunchMode(final String value) {
        for (final DesktopLaunchMode mode : DesktopLaunchMode.values()) {
            if (mode.wireName.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(
                "mode must be auto, windowed, or fullscreen");
    }

    private static AppLaunchTarget appTarget(final JSONObject args) {
        final String packageName = requiredString(args, "package");
        final String componentValue = optionalString(args, "component", "");
        if (componentValue.isEmpty()) {
            return AppLaunchTarget.packageDefault(packageName);
        }
        final ComponentName component = ComponentName.unflattenFromString(
                componentValue);
        if (component == null
                || !packageName.equals(component.getPackageName())) {
            throw new IllegalArgumentException(
                    "component must belong to package");
        }
        return AppLaunchTarget.explicit(
                packageName, component.getClassName(), Intent.ACTION_MAIN);
    }

    private static String requiredString(
            final JSONObject object, final String key) {
        final String value = object == null
                ? "" : object.optString(key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static String optionalString(
            final JSONObject object,
            final String key,
            final String defaultValue) {
        if (object == null || !object.has(key)) {
            return defaultValue;
        }
        final String value = object.optString(key, "").trim();
        return value.isEmpty() ? defaultValue : value;
    }

    private static int requiredInt(
            final JSONObject object, final String key) {
        if (object == null || !object.has(key)) {
            throw new IllegalArgumentException(key + " is required");
        }
        final Object value = object.opt(key);
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        final long number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " is out of range");
        }
        return (int) number;
    }

    private static JSONObject requiredObject(
            final JSONObject object, final String key) {
        final JSONObject value = object == null
                ? null : object.optJSONObject(key);
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private interface TaskAction {
        void run(TaskRepository.ActionCallback callback);
    }
}
