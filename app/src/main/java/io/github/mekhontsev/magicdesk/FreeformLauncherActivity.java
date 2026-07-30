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
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class FreeformLauncherActivity extends Activity {
    public static final String ACTION_LAUNCH =
            "io.github.mekhontsev.magicdesk.action.LAUNCH_FREEFORM";
    public static final String EXTRA_PACKAGE_NAME =
            "io.github.mekhontsev.magicdesk.extra.PACKAGE_NAME";
    public static final String EXTRA_PRESERVED_TASK_IDS =
            "io.github.mekhontsev.magicdesk.extra.PRESERVED_TASK_IDS";
    private static final String EXTRA_ROOT_COLD_LAUNCH =
            "io.github.mekhontsev.magicdesk.extra.ROOT_COLD_LAUNCH";

    private static final String TAG = "MagicDeskFreeform";
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

    static Intent createIntent(final Activity activity, final String packageName,
            final int[] preservedTaskIds) {
        return createIntent(activity, packageName, preservedTaskIds, false);
    }

    static Intent createIntent(final Activity activity, final String packageName,
            final int[] preservedTaskIds, final boolean rootColdLaunch) {
        return new Intent(activity, FreeformLauncherActivity.class)
                .setAction(ACTION_LAUNCH)
                .putExtra(EXTRA_PACKAGE_NAME, packageName)
                .putExtra(EXTRA_PRESERVED_TASK_IDS, preservedTaskIds)
                .putExtra(EXTRA_ROOT_COLD_LAUNCH, rootColdLaunch);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        launchFromIntent(getIntent());
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        launchFromIntent(intent);
    }

    private void launchFromIntent(final Intent intent) {
        final String packageName = intent == null
                ? null : intent.getStringExtra(EXTRA_PACKAGE_NAME);
        final int[] preservedTaskIds = intent == null
                ? null : intent.getIntArrayExtra(EXTRA_PRESERVED_TASK_IDS);
        final boolean rootColdLaunch = intent != null
                && intent.getBooleanExtra(EXTRA_ROOT_COLD_LAUNCH, false);
        if (!PackageNameValidator.isSafe(packageName)) {
            toastAndFinish("Bad package name");
            return;
        }
        final Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launchIntent == null) {
            toastAndFinish("No launcher activity for " + packageName);
            return;
        }

        final int displayId = getCurrentDisplayId();
        launchIntent.addFlags(getLaunchFlags());
        launchIntent.putExtra("start_from_heartservice_app_lock", true);
        final Context appContext = getApplicationContext();

        try {
            if (!RuntimeAccess.has(RuntimeAccess.Capability.TASK_CONTROL)) {
                launchBasicFreeform(launchIntent, displayId);
                finish();
                overridePendingTransition(0, 0);
                return;
            }
            final boolean existingTask =
                    ExistingTaskController.taskExists(packageName, displayId);
            if (!existingTask) {
                if (rootColdLaunch) {
                    finish();
                    overridePendingTransition(0, 0);
                    LAUNCH_EXECUTOR.execute(() -> launchAsRootAndConvert(
                            appContext, launchIntent, packageName, displayId,
                            preservedTaskIds));
                    return;
                }
                final ActivityOptions options = ActivityOptions.makeBasic();
                invokeIntOption(options, "setLaunchDisplayId", displayId);
                Log.i(TAG, "launch package=" + packageName + " display=" + displayId);
                startActivity(launchIntent, options.toBundle());
            }
            finish();
            overridePendingTransition(0, 0);
            LAUNCH_EXECUTOR.execute(() -> convertToNativeDesktop(appContext,
                    packageName, displayId, preservedTaskIds, !existingTask));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "cannot prepare native desktop launch package=" + packageName, e);
            toastAndFinish("Window launch failed: " + usefulMessage(e));
        }
    }

    private void launchBasicFreeform(
            final Intent launchIntent, final int displayId) {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        options.setLaunchBounds(defaultLaunchBounds());
        Log.i(TAG, "basic freeform launch display=" + displayId
                + " component=" + launchIntent.getComponent());
        startActivity(launchIntent, options.toBundle());
    }

    private Rect defaultLaunchBounds() {
        final Display display = getDisplay();
        final Point size = new Point();
        if (display != null) {
            display.getRealSize(size);
        }
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

    private static void launchAsRootAndConvert(final Context context,
            final Intent launchIntent, final String packageName, final int displayId,
            final int[] preservedTaskIds) {
        try {
            ExistingTaskController.startActivityAsRoot(
                    launchIntent.getComponent(), displayId);
            convertToNativeDesktop(
                    context, packageName, displayId, preservedTaskIds, true);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "root cold launch failed package=" + packageName, e);
            showToast(context, "Window launch failed: " + usefulMessage(e));
        }
    }

    private static void convertToNativeDesktop(final Context context,
            final String packageName, final int displayId, final int[] preservedTaskIds,
            final boolean waitForVisibleTask) {
        try {
            final ExistingTaskController.ReuseResult reuseResult =
                    ExistingTaskController.reuseNativeDesktopIfExists(
                            packageName, displayId, preservedTaskIds,
                            waitForVisibleTask);
            if (!reuseResult.found) {
                throw new IOException("task not found");
            }
            Log.i(TAG, "native desktop ready package=" + packageName
                    + " display=" + displayId);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "native desktop launch failed package=" + packageName, e);
            showToast(context, "Window launch failed: " + usefulMessage(e));
        }
    }

    private int getCurrentDisplayId() {
        final Display display = getWindowManager().getDefaultDisplay();
        return display == null ? 0 : display.getDisplayId();
    }

    private static void invokeIntOption(final ActivityOptions options, final String methodName,
            final int value) {
        try {
            final Method method = ActivityOptions.class.getMethod(methodName, Integer.TYPE);
            method.invoke(options, Integer.valueOf(value));
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, methodName + " unavailable", e);
        } catch (RuntimeException e) {
            Log.w(TAG, methodName + " failed", e);
        }
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

    private void toastAndFinish(final String message) {
        Log.w(TAG, message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
        overridePendingTransition(0, 0);
    }

}
