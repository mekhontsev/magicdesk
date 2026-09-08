package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.ref.WeakReference;

/** Lifecycle barrier for diagnostics acting as the non-phone test input guard. */
final class DesktopSelfTestGuardWindow {
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<DiagnosticsActivity> sActivity = new WeakReference<>(null);
    private static long sRequestedRunId;
    private static boolean sVisible;
    private static String sError = "";

    private DesktopSelfTestGuardWindow() { }

    static boolean accepts(final long runId) {
        synchronized (LOCK) {
            return runId > 0L && sRequestedRunId == runId
                    && DesktopSelfTestRunState.isActive(runId);
        }
    }

    static boolean showAndWait(final Context context, final long runId,
            final long timeoutMillis) {
        synchronized (LOCK) {
            if (sRequestedRunId == runId && sVisible) {
                return true;
            }
            sRequestedRunId = runId;
            sError = "";
        }
        MAIN.post(() -> {
            if (!accepts(runId)) {
                fail("stale phone guard launch");
                return;
            }
            try {
                DiagnosticsActivity.showGuard(context, runId);
            } catch (RuntimeException error) {
                fail(DesktopSelfTestSteps.usefulMessage(error));
            }
        });
        return awaitVisibility(true, runId, timeoutMillis);
    }

    static boolean hideAndWait(final long timeoutMillis) {
        final DiagnosticsActivity activity;
        synchronized (LOCK) {
            sRequestedRunId = 0L;
            sError = "";
            activity = sActivity.get();
            if (activity == null) {
                sVisible = false;
                return true;
            }
        }
        MAIN.post(() -> activity.releaseSelfTestGuard());
        return awaitVisibility(false, 0L, timeoutMillis);
    }

    static boolean isVisible() {
        synchronized (LOCK) {
            return sVisible && sActivity.get() != null;
        }
    }

    static String lastError() {
        synchronized (LOCK) {
            return sError;
        }
    }

    static void resumed(final DiagnosticsActivity activity, final long runId) {
        synchronized (LOCK) {
            if (!accepts(runId)) {
                return;
            }
            sActivity = new WeakReference<>(activity);
            sVisible = true;
            LOCK.notifyAll();
        }
        DesktopSelfTestPhoneInputGuard.noteWindowShown();
    }

    static void stopped(final DiagnosticsActivity activity, final boolean recreating) {
        final boolean wasVisible;
        synchronized (LOCK) {
            wasVisible = sActivity.get() == activity && sVisible;
            if (sActivity.get() == activity) {
                sVisible = false;
                LOCK.notifyAll();
            }
        }
        if (wasVisible && !recreating) {
            DesktopSelfTestPhoneInputGuard.noteWindowHidden();
        }
    }

    static void destroyed(final DiagnosticsActivity activity) {
        stopped(activity, activity.isChangingConfigurations());
        synchronized (LOCK) {
            if (sActivity.get() == activity) {
                sActivity.clear();
                LOCK.notifyAll();
            }
        }
    }

    private static void fail(final String detail) {
        synchronized (LOCK) {
            sError = detail;
            LOCK.notifyAll();
        }
    }

    private static boolean awaitVisibility(final boolean visible,
            final long runId, final long timeoutMillis) {
        final long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        synchronized (LOCK) {
            while (sVisible != visible && sError.isEmpty()
                    && sRequestedRunId == runId) {
                final long remaining = deadline - SystemClock.uptimeMillis();
                if (remaining <= 0L) {
                    sError = visible ? "diagnostics resume timed out"
                            : "diagnostics guard hide timed out";
                    break;
                }
                try {
                    EventDrivenWaits.await(LOCK,
                            EventDrivenWaits.Reason.SELF_TEST_GUARD_WINDOW, remaining);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    sError = "diagnostics guard wait interrupted";
                    break;
                }
            }
            return sVisible == visible && sError.isEmpty() && sRequestedRunId == runId;
        }
    }
}
