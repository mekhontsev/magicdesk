package io.github.mekhontsev.magicdesk;

import android.app.ActivityOptions;
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
    private static final long WAIT_RECHECK_MILLIS = 200L;

    private final Context mContext;
    private final DesktopAutomationStateReader mState;
    private final DesktopAutomationCapture mCapture;
    private final DesktopAutomationTraceManager mTraces;
    private final AndroidIntegrationGateway mAndroid;
    private final ClipboardAutomationGateway mClipboard;
    private final AndroidContentActionGateway mClipboardActions;
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
        mTraces = new DesktopAutomationTraceManager(mState);
        mAndroid = new AndroidIntegrationGateway(mContext);
        mClipboard = new ClipboardAutomationGateway(mContext);
        mClipboardActions = new AndroidContentActionGateway(
                mContext, mAndroid);
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
                case QUERY_INTENT_HANDLERS:
                    result = mAndroid.queryIntentHandlers(args);
                    break;
                case LAUNCH_INTENT:
                    result = mAndroid.launchIntent(args);
                    break;
                case OPEN_URI:
                    result = mAndroid.openUri(args);
                    break;
                case OPEN_FILE:
                    result = mAndroid.openFile(args);
                    break;
                case SHARE:
                    result = mAndroid.share(args);
                    break;
                case SEND_BROADCAST:
                    result = mAndroid.sendBroadcast(args);
                    break;
                case START_SERVICE:
                    result = mAndroid.startService(args);
                    break;
                case LIST_APP_ACTIONS:
                    result = mAndroid.listAppActions(args);
                    break;
                case INVOKE_APP_ACTION:
                    result = mAndroid.invokeAppAction(args);
                    break;
                case LIST_NOTIFICATIONS:
                    result = mAndroid.listNotifications(args);
                    break;
                case INVOKE_NOTIFICATION:
                    result = mAndroid.invokeNotification(args);
                    break;
                case GET_INTENT_RESULT:
                    result = mAndroid.getActivityResult(args);
                    break;
                case SEARCH_APP_FUNCTIONS:
                    result = mAndroid.searchAppFunctions(args);
                    break;
                case EXECUTE_APP_FUNCTION:
                    result = mAndroid.executeAppFunction(args);
                    break;
                case READ_CLIPBOARD_TEXT:
                    result = mClipboard.readText();
                    break;
                case WRITE_CLIPBOARD_TEXT:
                    result = mClipboard.writeText(args);
                    break;
                case OPEN_CLIPBOARD_CONTENT:
                    result = mClipboardActions.openClipboard(
                            optionalDisplayId(args));
                    break;
                case SHARE_CLIPBOARD_CONTENT:
                    result = mClipboardActions.shareClipboard(
                            optionalDisplayId(args));
                    break;
                case CLEAR_CLIPBOARD:
                    result = mClipboard.clear();
                    break;
                case LAUNCH_DESKTOP_ENTRY:
                    result = launchDesktopEntry(args);
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
                    result = setRawWindowMode(args);
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
                    result = toggleDesktopWorkspace();
                    break;
                case OPEN_SETTINGS:
                    result = openSettings();
                    break;
                case OPEN_BUILTIN:
                    result = openBuiltin(args);
                    break;
                case RECONNECT_TERMUX_X11:
                    result = reconnectTermuxX11();
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
                case CANCEL_SELF_TEST:
                    result = cancelSelfTest(args);
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
                case INVOKE_UI_ACTION:
                    result = invokeUiAction(args);
                    break;
                case BEGIN_TRACE:
                    result = mTraces.begin(args);
                    break;
                case END_TRACE:
                    result = mTraces.end(args);
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

    private DesktopAutomationResult reconnectTermuxX11()
            throws JSONException {
        final TermuxX11RuntimeStatus.OperationResult operation =
                TermuxX11RuntimeStatus.reconnectBlocking(mContext);
        final TaskRepository.Snapshot tasks = TaskRepository.loadAllNow();
        final JSONObject data = new JSONObject().put(
                "termuxX11",
                TermuxX11RuntimeStatus.refreshBlocking(
                        mContext, tasks).toJson());
        return operation.success
                ? DesktopAutomationResult.success(operation.message, data)
                : DesktopAutomationResult.failure(operation.message, data);
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
                        observedEventId,
                        Math.min(remaining, WAIT_RECHECK_MILLIS));
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
                DesktopOperations.showMagicDesk();
                break;
            case "phone":
                mContext.startActivity(
                        ControlActivity.createOpenDesktopIntent(mContext));
                break;
            case "simulated":
                SimulatedDesktopDisplayController.show();
                break;
            case "wired":
                if (ExternalDisplayController.findExternalDisplayId()
                                <= Display.DEFAULT_DISPLAY) {
                    return DesktopAutomationResult.failure(
                            "no connected wired display");
                }
                DesktopOperations.showWiredDesktop();
                break;
            case "wireless":
                final int wirelessDisplayId =
                        ExternalDisplayController.findWirelessDisplayId();
                if (wirelessDisplayId <= Display.DEFAULT_DISPLAY) {
                    return DesktopAutomationResult.failure(
                            "no connected wireless display");
                }
                DesktopOperations.showDesktop(
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
        DesktopOperations.closeDesktop(target, true, value -> {
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
            throws IOException, JSONException, InterruptedException {
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
        final RelativeWindowBounds preferredBounds = readLaunchBounds(
                args, mode, displayId);
        if (!DesktopRuntimeBridge.launchApplication(
                target, mode, preferredBounds, displayId)) {
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
        if (args.has("bounds")) {
            data.put("requestedBounds", args.getJSONObject("bounds"));
        }
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

    private DesktopAutomationResult launchDesktopEntry(final JSONObject args)
            throws IOException, JSONException {
        final String desktopPath = requiredString(args, "desktopPath");
        if (!ShellAccess.isReady()) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.SHELL_UNAVAILABLE,
                    "shell command service is unavailable", true);
        }
        final int displayId = optionalDisplayId(args);
        final boolean launched;
        final String kind;
        final ShellFileInfo file = ShellAccess.getShellFileInfo(desktopPath);
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
        final DesktopAutomationResult result = awaitTaskAction(
                callback -> MagicDeskRuntime.focusDesktopTask(
                        task.displayId, task.taskId, callback));
        if (!result.success) {
            return result;
        }
        try {
            return DesktopAutomationResult.success(
                    result.message,
                    new JSONObject()
                            .put("taskId", taskId)
                            .put("workspaceOperation", "activate")
                            .put("transitionPath", "managed-workspace")
                            .put("focusConverged", true));
        } catch (JSONException error) {
            return result;
        }
    }

    private DesktopAutomationResult toggleDesktopWorkspace()
            throws InterruptedException {
        final DesktopAutomationResult result = awaitTaskAction(callback -> {
            if (!MagicDeskRuntime.toggleDesktopWorkspace(callback)) {
                callback.onComplete(new TaskRepository.ActionResult(
                        false, "desktop UI is unavailable"));
            }
        });
        if (!result.success) {
            return result;
        }
        try {
            return DesktopAutomationResult.success(
                    result.message,
                    new JSONObject()
                            .put("workspaceOperation", "toggle-desktop")
                            .put("transitionPath", "managed-workspace")
                            .put("focusConverged", true));
        } catch (JSONException error) {
            return result;
        }
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

    private DesktopAutomationResult setRawWindowMode(final JSONObject args)
            throws IOException, JSONException, InterruptedException {
        final int taskId = requiredInt(args, "taskId");
        final TaskRepository.TaskEntry task = findTask(taskId);
        if (task == null) {
            return taskNotFound(taskId);
        }
        final String mode = requiredString(args, "mode")
                .toLowerCase(Locale.ROOT);
        final DesktopAutomationResult result;
        if ("fullscreen".equals(mode)) {
            result = awaitTaskAction(callback ->
                    TaskRepository.setFullscreen(task, callback));
        } else if ("windowed".equals(mode) || "freeform".equals(mode)) {
            final Rect bounds = readBounds(
                    args.optJSONObject("bounds"), task.displayId);
            result = awaitTaskAction(callback ->
                    TaskRepository.setFreeform(task, bounds, callback));
        } else {
            throw new IllegalArgumentException(
                    "mode must be windowed or fullscreen");
        }
        if (!result.success) {
            return result;
        }
        return DesktopAutomationResult.success(
                result.message,
                new JSONObject()
                        .put("taskId", taskId)
                        .put("mode", mode)
                        .put("transitionPath", "raw"));
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
        final DesktopSelfTestExecutionPolicy executionPolicy =
                DesktopSelfTestExecutionPolicy.parse(optionalString(
                        args, "mode", "full"));
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
                        target.name())
                .putExtra(
                        DiagnosticsActivity.EXTRA_SELF_TEST_EXECUTION_POLICY,
                        executionPolicy.name());
        if (displayKind != null) {
            intent.putExtra(
                    DiagnosticsActivity.EXTRA_SELF_TEST_DISPLAY_KIND,
                    displayKind.name());
        }
        final long requestedAtMillis = System.currentTimeMillis();
        final long runId = DesktopSelfTestRunState.beginRequest(
                rawTarget, executionPolicy, requestedAtMillis);
        if (runId <= 0L) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.ACTION_FAILED,
                    "another desktop self-test is already active",
                    true,
                    DesktopSelfTestRunState.snapshot().toJson());
        }
        intent.putExtra(DiagnosticsActivity.EXTRA_SELF_TEST_RUN_ID, runId);
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
        try {
            mContext.startActivity(intent, options.toBundle());
        } catch (RuntimeException error) {
            DesktopSelfTestRunState.complete(
                    runId,
                    false,
                    false,
                    System.currentTimeMillis(),
                    "could not launch diagnostics: "
                            + ShellAccess.usefulMessage(error),
                    DesktopSelfTestResult.lastModifiedMillis(mContext));
            throw error;
        }
        return DesktopAutomationResult.success(
                "self-test launch accepted",
                DesktopSelfTestRunState.snapshot().toJson());
    }

    private DesktopAutomationResult cancelSelfTest(final JSONObject args)
            throws JSONException {
        final long runId = requiredLong(args, "runId");
        if (runId <= 0L) {
            throw new IllegalArgumentException("runId must be positive");
        }
        final DesktopSelfTestRunState.CancellationStatus status =
                DesktopSelfTestRunState.requestCancellation(runId);
        final JSONObject state = DesktopSelfTestRunState.snapshot().toJson()
                .put("cancellationStatus", status.name()
                        .toLowerCase(Locale.ROOT));
        switch (status) {
            case ACCEPTED:
                return DesktopAutomationResult.success(
                        "self-test cancellation requested", state);
            case ALREADY_REQUESTED:
                return DesktopAutomationResult.success(
                        "self-test cancellation was already requested", state);
            case RUN_MISMATCH:
                return DesktopAutomationResult.failure(
                        DesktopAutomationErrorCode.INVALID_ARGUMENT,
                        "runId does not identify the active self-test",
                        false,
                        state);
            case CLEANUP_STARTED:
                return DesktopAutomationResult.failure(
                        DesktopAutomationErrorCode.ACTION_FAILED,
                        "self-test cleanup has already started",
                        false,
                        state);
            case NOT_ACTIVE:
            default:
                return DesktopAutomationResult.failure(
                        DesktopAutomationErrorCode.ACTION_FAILED,
                        "no self-test is active",
                        false,
                        state);
        }
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
        return simpleRuntimeAction(
                MagicDeskRuntime.clickDesktopPointer(displayId, button),
                "pointer clicked");
    }

    private DesktopAutomationResult invokeUiAction(final JSONObject args)
            throws JSONException {
        final int displayId = optionalDisplayId(args);
        final String elementId = requiredString(args, "elementId");
        final String action = requiredString(args, "action")
                .toLowerCase(Locale.ROOT);
        final DesktopAutomationUiRegistry.ActionResult result =
                DesktopRuntimeBridge.invokeAutomationUiAction(
                        displayId, elementId, action);
        final JSONObject data = new JSONObject()
                .put("displayId", displayId)
                .put("elementId", elementId)
                .put("action", action)
                .put("accepted", result.accepted)
                .put("element", result.element);
        DesktopAutomationEventJournal.record(
                "ui", "element_invoked", result.accepted,
                result.message, data);
        return result.accepted
                ? DesktopAutomationResult.success(result.message, data)
                : DesktopAutomationResult.failure(
                        DesktopAutomationErrorCode.ACTION_FAILED,
                        result.message, true, data);
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
                        visible = DesktopOperations.isTouchpadVisible();
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
            case "ui_element_state": {
                final int displayId = optionalDisplayId(args);
                final String elementId = requiredString(args, "elementId");
                final DesktopAutomationUiRegistry.Snapshot ui =
                        DesktopRuntimeBridge.getAutomationUiElements(
                                displayId, elementId, true);
                final JSONObject element = findUiElement(
                        ui.elements, elementId);
                final boolean expectedVisible =
                        args.optBoolean("visible", true);
                final boolean actualVisible = element != null
                        && element.optBoolean("visible", false);
                boolean matched = ui.available
                        && actualVisible == expectedVisible;
                if (args.has("enabled")) {
                    matched = matched && element != null
                            && element.optBoolean("enabled")
                                    == args.optBoolean("enabled");
                }
                if (args.has("focused")) {
                    matched = matched && element != null
                            && element.optBoolean("focused")
                                    == args.optBoolean("focused");
                }
                if (args.has("selected")) {
                    matched = matched && element != null
                            && element.optBoolean("selected")
                                    == args.optBoolean("selected");
                }
                return observation
                        .put("matched", matched)
                        .put("displayId", displayId)
                        .put("elementId", elementId)
                        .put("uiAvailable", ui.available)
                        .put("found", element != null)
                        .put("expectedVisible", expectedVisible)
                        .put("element", element == null
                                ? JSONObject.NULL : element);
            }
            case "popup_state": {
                final int displayId = optionalDisplayId(args);
                final DesktopUiSnapshot ui = DesktopRuntimeBridge
                        .getAutomationUiSnapshot(displayId);
                final boolean expectedVisible =
                        args.optBoolean("visible", true);
                final String expectedTitle = optionalString(
                        args, "popupTitle", "");
                boolean matched = ui.available
                        && ui.popupVisible == expectedVisible;
                if (expectedVisible && !expectedTitle.isEmpty()) {
                    matched = matched && expectedTitle.equals(ui.popupTitle);
                }
                return observation
                        .put("matched", matched)
                        .put("displayId", displayId)
                        .put("uiAvailable", ui.available)
                        .put("expectedVisible", expectedVisible)
                        .put("expectedTitle", expectedTitle)
                        .put("visible", ui.popupVisible)
                        .put("title", ui.popupTitle)
                        .put("bounds", rectJson(ui.popupBounds));
            }
            case "taskbar_visible": {
                final int displayId = optionalDisplayId(args);
                final boolean expectedVisible =
                        args.optBoolean("visible", true);
                final boolean visible = DesktopRuntimeBridge
                        .isTaskbarVisibleOnDisplay(displayId);
                return observation
                        .put("matched", visible == expectedVisible)
                        .put("displayId", displayId)
                        .put("expectedVisible", expectedVisible)
                        .put("visible", visible);
            }
            case "wallpaper_rendered": {
                final int displayId = optionalDisplayId(args);
                return observation
                        .put("matched", DesktopRuntimeBridge
                                .isDesktopWallpaperRendered(displayId))
                        .put("displayId", displayId);
            }
            case "self_test_finished": {
                final long runId = requiredLong(args, "runId");
                if (runId <= 0L) {
                    throw new IllegalArgumentException(
                            "runId must be positive");
                }
                final DesktopSelfTestRunState.Snapshot snapshot =
                        DesktopSelfTestRunState.snapshot();
                final long resultModifiedAtMillis =
                        DesktopSelfTestResult.lastModifiedMillis(mContext);
                final JSONObject state = snapshot.toJson();
                final java.util.Iterator<String> keys = state.keys();
                while (keys.hasNext()) {
                    final String key = keys.next();
                    observation.put(key, state.get(key));
                }
                return observation.put("matched",
                                snapshot.runId == runId
                                        && snapshot.terminal())
                        .put("expectedRunId", runId)
                        .put("resultModifiedAtMillis",
                                resultModifiedAtMillis);
            }
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

    private static JSONObject findUiElement(
            final org.json.JSONArray elements,
            final String elementId) {
        if (elements == null) {
            return null;
        }
        for (int index = 0; index < elements.length(); index++) {
            final JSONObject element = elements.optJSONObject(index);
            if (element != null
                    && elementId.equals(element.optString("id"))) {
                return element;
            }
        }
        return null;
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

    private static RelativeWindowBounds readLaunchBounds(
            final JSONObject args,
            final DesktopLaunchMode mode,
            final int displayId) throws IOException {
        if (!args.has("bounds")) {
            return null;
        }
        if (mode != DesktopLaunchMode.WINDOWED) {
            throw new IllegalArgumentException(
                    "bounds require mode=windowed");
        }
        final Rect bounds = readBounds(
                requiredObject(args, "bounds"), displayId);
        final Rect workArea = FloatingWindowController.getWorkAreaBounds(
                displayId);
        if (bounds.left < workArea.left
                || bounds.top < workArea.top
                || bounds.right > workArea.right
                || bounds.bottom > workArea.bottom) {
            throw new IllegalArgumentException(
                    "bounds must be inside the desktop work area");
        }
        final RelativeWindowBounds relative = RelativeWindowBounds.from(
                bounds, workArea);
        if (relative == null) {
            throw new IllegalArgumentException("invalid bounds");
        }
        return relative;
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

    private static long requiredLong(
            final JSONObject object, final String key) {
        if (object == null || !object.has(key)) {
            throw new IllegalArgumentException(key + " is required");
        }
        final Object value = object.opt(key);
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        return ((Number) value).longValue();
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
