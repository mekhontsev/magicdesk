package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;

import java.io.Closeable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Routes a firmware Recents request into the active MagicDesk phone HOME. */
final class ShellPhoneOverviewRouter implements
        Closeable, ShellActivityStartController.Listener {
    interface Listener {
        void onError(String message);
    }

    private static final String TAG = "MagicDeskTasks";
    private static final String RECENTS_RESOURCE =
            "config_recentsComponentName";

    private final boolean mRequired;
    private final Object mTaskService;
    private final IActivityLaunchCallback mActivityLauncher;
    private final Listener mListener;
    private final ComponentName mSystemRecents;
    private final ExecutorService mLaunchExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(
                        runnable, "MagicDesk-phone-overview");
                thread.setDaemon(true);
                return thread;
            });

    private boolean mEnabled;
    private boolean mLaunchScheduled;
    private boolean mClosed;

    ShellPhoneOverviewRouter(
            final Context context,
            final Object taskService,
            final boolean required,
            final IActivityLaunchCallback activityLauncher,
            final Listener listener) {
        mRequired = required;
        mTaskService = taskService;
        mActivityLauncher = activityLauncher;
        mListener = listener;
        mSystemRecents = required
                ? resolveSystemRecentsComponent(context) : null;
    }

    synchronized void start() throws ReflectiveOperationException {
        if (mClosed) {
            throw new IllegalStateException("phone Overview router is closed");
        }
        if (mEnabled || !mRequired) {
            return;
        }
        if (mActivityLauncher == null || mSystemRecents == null) {
            throw new IllegalStateException(
                    "system Overview routing is unavailable");
        }
        mEnabled = true;
        try {
            removeExistingSystemOverviewTasks();
        } catch (ReflectiveOperationException | RuntimeException error) {
            mEnabled = false;
            throw error;
        }
    }

    synchronized void stop() {
        mEnabled = false;
    }

    @Override
    public boolean onActivityStarting(
            final Intent intent,
            final String packageName) {
        final ComponentName component = intent == null
                ? null : intent.getComponent();
        synchronized (this) {
            if (!shouldRoute(mEnabled, mSystemRecents, component)) {
                return true;
            }
        }
        // The observer can outlive the HOME lease while desktop teardown is
        // still running. Consult the lease owner before cancelling Recents so
        // every release path immediately restores the system gesture.
        final boolean homeSessionActive;
        try {
            homeSessionActive =
                    mActivityLauncher.isPhoneOverviewRoutingActive();
        } catch (RemoteException | RuntimeException error) {
            report("could not verify phone Overview routing: "
                    + usefulMessage(error));
            return true;
        }
        synchronized (this) {
            if (!shouldRoute(
                    mEnabled,
                    homeSessionActive,
                    flatten(mSystemRecents),
                    flatten(component))) {
                return true;
            }
            if (mLaunchScheduled) {
                return false;
            }
            mLaunchScheduled = true;
        }
        try {
            mLaunchExecutor.execute(this::presentHome);
            Log.i(TAG, "routed system Overview to MagicDesk HOME component="
                    + component.flattenToShortString());
            return false;
        } catch (RejectedExecutionException error) {
            synchronized (this) {
                mLaunchScheduled = false;
            }
            report("could not schedule phone Overview: "
                    + usefulMessage(error));
            return true;
        }
    }

    private void presentHome() {
        try {
            synchronized (this) {
                if (!mEnabled || mClosed) {
                    return;
                }
            }
            mActivityLauncher.presentHomeFromRecents();
        } catch (RemoteException | RuntimeException error) {
            report("could not present HOME from Recents: "
                    + usefulMessage(error));
        } finally {
            synchronized (this) {
                mLaunchScheduled = false;
            }
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            mClosed = true;
            mEnabled = false;
        }
        mLaunchExecutor.shutdownNow();
    }

    static boolean shouldRoute(
            final boolean enabled,
            final ComponentName systemRecents,
            final ComponentName requested) {
        return shouldRoute(
                enabled,
                flatten(systemRecents),
                flatten(requested));
    }

    static boolean shouldRoute(
            final boolean enabled,
            final String systemRecents,
            final String requested) {
        return shouldRoute(enabled, true, systemRecents, requested);
    }

    static boolean shouldRoute(
            final boolean enabled,
            final boolean homeSessionActive,
            final String systemRecents,
            final String requested) {
        return enabled
                && homeSessionActive
                && systemRecents != null
                && systemRecents.equals(requested);
    }

    static boolean isSystemOverviewTask(
            final String systemRecents,
            final String topActivity,
            final String baseActivity,
            final String baseIntentComponent) {
        return systemRecents != null
                && (systemRecents.equals(topActivity)
                || systemRecents.equals(baseActivity)
                || systemRecents.equals(baseIntentComponent));
    }

    private void removeExistingSystemOverviewTasks()
            throws ReflectiveOperationException {
        final String systemRecents = flatten(mSystemRecents);
        for (final Object task : HiddenTaskApi.getTasks(
                mTaskService, Display.DEFAULT_DISPLAY)) {
            final Intent baseIntent = HiddenTaskApi.getTaskBaseIntent(task);
            if (!isSystemOverviewTask(
                    systemRecents,
                    flatten(HiddenTaskApi.getTaskTopActivity(task)),
                    flatten(HiddenTaskApi.getTaskBaseActivity(task)),
                    flatten(baseIntent == null
                            ? null : baseIntent.getComponent()))) {
                continue;
            }
            final int taskId = HiddenTaskApi.getTaskId(task);
            if (!HiddenTaskApi.removeTask(mTaskService, taskId)) {
                throw new IllegalStateException(
                        "could not remove existing system Overview task "
                                + taskId);
            }
            Log.i(TAG, "removed existing system Overview task=" + taskId);
        }
    }

    private static String flatten(final ComponentName component) {
        return component == null ? null : component.flattenToString();
    }

    static ComponentName resolveSystemRecentsComponent(
            final Context context) {
        if (context == null) {
            return null;
        }
        try {
            final Resources resources = context.getResources();
            final int resourceId = resources.getIdentifier(
                    RECENTS_RESOURCE, "string", "android");
            if (resourceId == 0) {
                return null;
            }
            final String flattened = resources.getString(resourceId).trim();
            return flattened.isEmpty()
                    ? null : ComponentName.unflattenFromString(flattened);
        } catch (RuntimeException error) {
            Log.w(TAG, "system Overview component is unavailable", error);
            return null;
        }
    }

    private void report(final String message) {
        Log.w(TAG, message);
        if (mListener != null) {
            mListener.onError(message);
        }
    }

    private static String usefulMessage(final Throwable error) {
        final String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error == null ? "unknown error"
                        : error.getClass().getSimpleName()
                : message;
    }
}
