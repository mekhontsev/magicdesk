package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class FreeformLaunchAnchorActivity extends Activity {
    private static final String ACTION_PREPARE =
            "io.github.mekhontsev.magicdesk.action.PREPARE_FREEFORM_ANCHOR";
    private static final String ACTION_LAUNCH =
            "io.github.mekhontsev.magicdesk.action.LAUNCH_FREEFORM";
    private static final String EXTRA_PACKAGE_NAME =
            "io.github.mekhontsev.magicdesk.extra.PACKAGE_NAME";
    private static final String EXTRA_ACTIVITY_CLASS_NAME =
            "io.github.mekhontsev.magicdesk.extra.ACTIVITY_CLASS_NAME";
    private static final String EXTRA_LAUNCH_ACTION =
            "io.github.mekhontsev.magicdesk.extra.LAUNCH_ACTION";
    private static final String EXTRA_PRESERVED_TASK_IDS =
            "io.github.mekhontsev.magicdesk.extra.PRESERVED_TASK_IDS";
    private static final String EXTRA_DESKTOP_TASK_ID =
            "io.github.mekhontsev.magicdesk.extra.DESKTOP_TASK_ID";

    private static final String TAG = "MagicDeskLaunchAnchor";
    private static final ExecutorService LAUNCH_EXECUTOR = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(final Runnable runnable) {
                    final Thread thread = new Thread(runnable, "MagicDeskAppLauncher");
                    thread.setDaemon(true);
                    return thread;
                }
            });
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ArrayDeque<LaunchRequest> STARTUP_REQUESTS = new ArrayDeque<>();

    private static WeakReference<FreeformLaunchAnchorActivity> sAnchor =
            new WeakReference<>(null);
    private static int sStartingDisplayId = -1;
    private static int sStartingDesktopTaskId = -1;

    private final ArrayDeque<LaunchRequest> mRequests = new ArrayDeque<>();
    private int mDisplayId = -1;
    private int mDesktopTaskId = -1;
    private boolean mPreparing;
    private boolean mPrepared;
    private boolean mLaunching;
    private boolean mClosing;

    static void prepare(final Activity desktop) {
        requestAnchor(desktop, null);
    }

    static void launch(
            final Activity desktop,
            final AppLaunchTarget launchTarget,
            final int[] preservedTaskIds) {
        if (launchTarget == null) {
            throw new IllegalArgumentException("Missing launch target");
        }
        requestAnchor(desktop, new LaunchRequest(
                launchTarget, preservedTaskIds));
    }

    static void release() {
        final FreeformLaunchAnchorActivity anchor = sAnchor.get();
        sAnchor.clear();
        sStartingDisplayId = -1;
        sStartingDesktopTaskId = -1;
        STARTUP_REQUESTS.clear();
        if (anchor != null) {
            anchor.closeAnchor();
        }
    }

    private static void requestAnchor(
            final Activity desktop,
            final LaunchRequest request) {
        final int displayId = getDisplayId(desktop);
        final int desktopTaskId = desktop.getTaskId();
        final FreeformLaunchAnchorActivity anchor = sAnchor.get();
        if (anchor != null && anchor.canServe(displayId)) {
            anchor.mDesktopTaskId = desktopTaskId;
            if (request != null) {
                anchor.enqueue(request);
            }
            return;
        }

        if (anchor != null) {
            sAnchor.clear();
            anchor.closeAnchor();
        }
        if (sStartingDisplayId == displayId) {
            sStartingDesktopTaskId = desktopTaskId;
            if (request != null) {
                STARTUP_REQUESTS.addLast(request);
            }
            return;
        }

        sStartingDisplayId = displayId;
        sStartingDesktopTaskId = desktopTaskId;
        STARTUP_REQUESTS.clear();
        if (request != null) {
            STARTUP_REQUESTS.addLast(request);
        }
        final Intent intent = new Intent(desktop, FreeformLaunchAnchorActivity.class)
                .setAction(request == null ? ACTION_PREPARE : ACTION_LAUNCH)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_DESKTOP_TASK_ID, desktopTaskId);
        putRequest(intent, request);
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        try {
            desktop.startActivity(intent, options.toBundle());
        } catch (RuntimeException error) {
            sStartingDisplayId = -1;
            sStartingDesktopTaskId = -1;
            STARTUP_REQUESTS.clear();
            throw error;
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDisplayId = getDisplayId(this);
        final FreeformLaunchAnchorActivity previous = sAnchor.get();
        if (previous != null && previous != this) {
            previous.closeAnchor();
        }
        sAnchor = new WeakReference<>(this);

        if (sStartingDisplayId == mDisplayId) {
            mDesktopTaskId = sStartingDesktopTaskId;
            mRequests.addAll(STARTUP_REQUESTS);
            STARTUP_REQUESTS.clear();
            sStartingDisplayId = -1;
            sStartingDesktopTaskId = -1;
        } else {
            mDesktopTaskId = getIntent().getIntExtra(EXTRA_DESKTOP_TASK_ID, -1);
            enqueueIntentRequest(getIntent());
        }
        setIntent(new Intent(this, FreeformLaunchAnchorActivity.class)
                .setAction(ACTION_PREPARE));
        ensurePrepared();
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        enqueueIntentRequest(intent);
        setIntent(new Intent(this, FreeformLaunchAnchorActivity.class)
                .setAction(ACTION_PREPARE));
    }

    @Override
    protected void onDestroy() {
        if (sAnchor.get() == this) {
            sAnchor.clear();
        }
        mClosing = true;
        mRequests.clear();
        super.onDestroy();
    }

    private boolean canServe(final int displayId) {
        return !mClosing
                && !isFinishing()
                && !isDestroyed()
                && mDisplayId == displayId;
    }

    private void enqueueIntentRequest(final Intent intent) {
        if (intent == null || !ACTION_LAUNCH.equals(intent.getAction())) {
            return;
        }
        final String packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME);
        final String activityClassName =
                intent.getStringExtra(EXTRA_ACTIVITY_CLASS_NAME);
        final String launchAction = intent.getStringExtra(EXTRA_LAUNCH_ACTION);
        final AppLaunchTarget launchTarget;
        try {
            launchTarget = AppLaunchTarget.explicit(
                    packageName,
                    activityClassName == null ? "" : activityClassName,
                    launchAction);
        } catch (IllegalArgumentException error) {
            showToast(getApplicationContext(), "Bad launch target");
            return;
        }
        enqueue(new LaunchRequest(
                launchTarget,
                intent.getIntArrayExtra(EXTRA_PRESERVED_TASK_IDS)));
    }

    private void enqueue(final LaunchRequest request) {
        if (!canServe(mDisplayId)) {
            return;
        }
        mRequests.addLast(request);
        if (mPrepared) {
            launchNext();
        }
    }

    private void ensurePrepared() {
        if (mPrepared || mPreparing || mClosing) {
            return;
        }
        mPreparing = true;
        final int taskId = getTaskId();
        final int displayId = mDisplayId;
        final Rect bounds = tinyAnchorBounds();
        final boolean restoreTouchpad = ConsoleModeSwitcher.isTouchpadVisible();
        if (restoreTouchpad) {
            DesktopTaskController.expectTouchpadDisplacement();
        }
        Log.i(TAG, "prepare task=" + taskId + " display=" + displayId);
        LAUNCH_EXECUTOR.execute(() -> {
            try {
                ExistingTaskController.prepareFreeformLaunchSource(
                        taskId, displayId, bounds);
                MAIN_HANDLER.post(() -> {
                    finishTouchpadPreservation(restoreTouchpad);
                    if (!isCurrentAnchor(taskId, displayId)) {
                        return;
                    }
                    mPreparing = false;
                    mPrepared = true;
                    Log.i(TAG, "ready task=" + taskId + " display=" + displayId);
                    if (mRequests.isEmpty()) {
                        restoreDesktopFocus();
                    } else {
                        launchNext();
                    }
                });
            } catch (IOException | RuntimeException error) {
                MAIN_HANDLER.post(() -> {
                    finishTouchpadPreservation(restoreTouchpad);
                    if (!mClosing) {
                        showToast(getApplicationContext(),
                                "Window launch failed: " + usefulMessage(error));
                    }
                    closeAnchor();
                });
            }
        });
    }

    private void launchNext() {
        if (!mPrepared || mLaunching || mClosing) {
            return;
        }
        final LaunchRequest request = mRequests.pollFirst();
        if (request == null) {
            return;
        }
        final Intent launchIntent = request.launchTarget.resolve(
                getPackageManager());
        if (launchIntent == null) {
            showToast(getApplicationContext(),
                    "No launcher activity for "
                            + request.launchTarget.packageName);
            launchNext();
            return;
        }
        launchIntent.addFlags(getLaunchFlags());
        launchIntent.putExtra("start_from_heartservice_app_lock", true);
        mLaunching = true;
        final boolean restoreTouchpad = ConsoleModeSwitcher.isTouchpadVisible();
        if (restoreTouchpad) {
            DesktopTaskController.expectTouchpadDisplacement();
        }
        LAUNCH_EXECUTOR.execute(() -> inspectAndLaunch(
                request, launchIntent, restoreTouchpad));
    }

    private void inspectAndLaunch(
            final LaunchRequest request,
            final Intent launchIntent,
            final boolean restoreTouchpad) {
        try {
            final boolean existingTask = ExistingTaskController.taskExists(
                    request.launchTarget.packageName, mDisplayId);
            if (!existingTask) {
                ExistingTaskController.focusFreeformLaunchSource(
                        getTaskId(), mDisplayId);
            }
            MAIN_HANDLER.post(() -> {
                if (!canServe(mDisplayId)) {
                    finishLaunch(restoreTouchpad);
                    return;
                }
                try {
                    if (!existingTask) {
                        startTargetActivity(launchIntent);
                    }
                    LAUNCH_EXECUTOR.execute(() -> {
                        convertToDesktopWindow(
                                getApplicationContext(),
                                request.launchTarget.packageName,
                                mDisplayId,
                                request.preservedTaskIds,
                                !existingTask);
                        MAIN_HANDLER.post(() -> finishLaunch(restoreTouchpad));
                    });
                } catch (RuntimeException error) {
                    showToast(getApplicationContext(),
                            "Window launch failed: " + usefulMessage(error));
                    if (!existingTask) {
                        restoreDesktopFocus();
                    }
                    finishLaunch(restoreTouchpad);
                }
            });
        } catch (IOException | RuntimeException error) {
            MAIN_HANDLER.post(() -> {
                showToast(getApplicationContext(),
                        "Window launch failed: " + usefulMessage(error));
                restoreDesktopFocus();
                finishLaunch(restoreTouchpad);
            });
        }
    }

    private void finishLaunch(final boolean restoreTouchpad) {
        finishTouchpadPreservation(restoreTouchpad);
        mLaunching = false;
        if (!mClosing) {
            launchNext();
        }
    }

    private void startTargetActivity(final Intent launchIntent) {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(mDisplayId);
        options.setLaunchBounds(defaultLaunchBounds());
        Log.i(TAG, "launch package=" + launchIntent.getPackage()
                + " display=" + mDisplayId
                + " sourceTask=" + getTaskId());
        startActivity(launchIntent, options.toBundle());
    }

    private void restoreDesktopFocus() {
        final int desktopTaskId = mDesktopTaskId;
        if (desktopTaskId < 0) {
            Log.w(TAG, "desktop task unavailable after anchor preparation");
            return;
        }
        LAUNCH_EXECUTOR.execute(() -> {
            try {
                ShellAccess.run(TaskFocusCommands.createShellCommand(
                        Collections.singletonList(Integer.valueOf(desktopTaskId))));
            } catch (IOException | RuntimeException error) {
                Log.w(TAG, "cannot restore desktop focus task=" + desktopTaskId, error);
            }
        });
    }

    private Rect defaultLaunchBounds() {
        final Point size = displaySize();
        final int width = Math.max(1, size.x);
        final int height = Math.max(1, size.y);
        final int desiredWidth = Math.max(480, Math.round(width * 0.68f));
        final int desiredHeight = Math.max(520, Math.round(height * 0.72f));
        final int boundedWidth = Math.min(width, desiredWidth);
        final int boundedHeight = Math.min(height, desiredHeight);
        final int left = Math.max(0, (width - boundedWidth) / 2);
        final int top = Math.max(0, (height - boundedHeight) / 3);
        return new Rect(
                left,
                top,
                left + boundedWidth,
                top + boundedHeight);
    }

    private Rect tinyAnchorBounds() {
        final Point size = displaySize();
        final int right = Math.max(1, size.x);
        final int bottom = Math.max(1, size.y);
        return new Rect(right - 1, bottom - 1, right, bottom);
    }

    private Point displaySize() {
        final Point size = new Point();
        final Display display = getDisplay();
        if (display != null) {
            display.getRealSize(size);
        }
        return size;
    }

    private boolean isCurrentAnchor(final int taskId, final int displayId) {
        return canServe(displayId)
                && sAnchor.get() == this
                && getTaskId() == taskId;
    }

    private void closeAnchor() {
        if (mClosing) {
            return;
        }
        mClosing = true;
        mRequests.clear();
        if (sAnchor.get() == this) {
            sAnchor.clear();
        }
        finishAndRemoveTask();
        overridePendingTransition(0, 0);
    }

    private static void convertToDesktopWindow(
            final Context context,
            final String packageName,
            final int displayId,
            final int[] preservedTaskIds,
            final boolean waitForVisibleTask) {
        try {
            final boolean nativeDesktop = NativeDesktopController.shouldUse();
            final ExistingTaskController.ReuseResult reuseResult = nativeDesktop
                    ? ExistingTaskController.reuseNativeDesktopIfExists(
                            packageName, displayId, preservedTaskIds,
                            waitForVisibleTask)
                    : ExistingTaskController.reuseFreeformIfExists(
                            packageName, displayId, preservedTaskIds,
                            waitForVisibleTask);
            if (!reuseResult.found) {
                throw new IOException("task not found");
            }
            Log.i(TAG, (nativeDesktop ? "native desktop" : "freeform")
                    + " ready package=" + packageName
                    + " display=" + displayId);
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "desktop window launch failed package=" + packageName, error);
            showToast(context, "Window launch failed: " + usefulMessage(error));
        }
    }

    private static void finishTouchpadPreservation(final boolean restoreTouchpad) {
        if (!restoreTouchpad) {
            return;
        }
        DesktopTaskController.finishTouchpadPreservation();
        ConsoleModeSwitcher.restoreTouchpadIfMissing();
    }

    private static void putRequest(final Intent intent, final LaunchRequest request) {
        if (request == null) {
            return;
        }
        intent.putExtra(
                EXTRA_PACKAGE_NAME,
                request.launchTarget.packageName);
        intent.putExtra(
                EXTRA_ACTIVITY_CLASS_NAME,
                request.launchTarget.activityClassName);
        intent.putExtra(EXTRA_LAUNCH_ACTION, request.launchTarget.action);
        intent.putExtra(EXTRA_PRESERVED_TASK_IDS, request.preservedTaskIds);
    }

    private static int getDisplayId(final Activity activity) {
        final Display display = activity.getDisplay();
        return display == null ? Display.DEFAULT_DISPLAY : display.getDisplayId();
    }

    private static void showToast(final Context context, final String message) {
        MAIN_HANDLER.post(() ->
                Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }

    private static String usefulMessage(final Exception error) {
        final String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private static int getLaunchFlags() {
        return Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT;
    }

    private static final class LaunchRequest {
        final AppLaunchTarget launchTarget;
        final int[] preservedTaskIds;

        LaunchRequest(
                final AppLaunchTarget launchTarget,
                final int[] preservedTaskIds) {
            this.launchTarget = launchTarget;
            this.preservedTaskIds = preservedTaskIds == null
                    ? null : preservedTaskIds.clone();
        }
    }
}
