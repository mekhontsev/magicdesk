package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.os.Handler;

import java.util.LinkedHashMap;
import java.util.Map;

/** Coalesces task callbacks into stable, low-frequency state writes. */
final class AppWindowStateTracker {
    private static final String MAGICDESK_PACKAGE =
            "io.github.mekhontsev.magicdesk";
    private static final long SAVE_DELAY_MILLIS = 500L;

    private final Handler mHandler;
    private final Runnable mFlush = this::flush;
    private final Map<String, RelativeWindowBounds> mLastObserved =
            new LinkedHashMap<>();
    private final Map<String, RelativeWindowBounds> mPending =
            new LinkedHashMap<>();

    AppWindowStateTracker(final Handler handler) {
        mHandler = handler;
    }

    void observe(
            final String packageName,
            final int displayId,
            final Rect bounds,
            final Rect workArea,
            final Rect fullscreenBounds) {
        if (!PackageNameValidator.isSafe(packageName)
                || MAGICDESK_PACKAGE.equals(packageName)
                || displayId < 0
                || bounds == null || bounds.isEmpty()
                || bounds.equals(fullscreenBounds)
                || workArea == null || workArea.isEmpty()) {
            return;
        }
        final RelativeWindowBounds relative =
                RelativeWindowBounds.from(bounds, workArea);
        if (relative == null
                || relative.equals(mLastObserved.get(packageName))) {
            return;
        }
        mLastObserved.put(packageName, relative);
        mPending.put(packageName, relative);
        mHandler.removeCallbacks(mFlush);
        mHandler.postDelayed(mFlush, SAVE_DELAY_MILLIS);
    }

    void stop() {
        mHandler.removeCallbacks(mFlush);
        flush();
        mLastObserved.clear();
    }

    private void flush() {
        if (mPending.isEmpty()) {
            return;
        }
        final Map<String, RelativeWindowBounds> pending =
                new LinkedHashMap<>(mPending);
        mPending.clear();
        if (AppWindowStateStore.rememberWindowBounds(pending)) {
            return;
        }
        for (final String packageName : pending.keySet()) {
            mLastObserved.remove(packageName);
        }
    }
}
