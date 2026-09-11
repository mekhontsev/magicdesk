package io.github.mekhontsev.magicdesk;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.LinkedHashMap;

/** One lazily connected, Binder-owned Android automation session, independent of Desktop. */
final class ShellUiAutomation implements AutoCloseable {
    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mIdleRelease = this::releaseIfIdle;
    private Session mSession;
    private IBinder mOwner;
    private IBinder.DeathRecipient mDeath;
    private int mCalls;

    ShellUiAutomation(final Context context) { mContext = context; }

    String execute(final IBinder owner, final String operation, final String arguments) {
        // These calls originate in the app's Binder transaction, but registration is owned by shell.
        final long identity = Binder.clearCallingIdentity();
        try {
            final JSONObject args = new JSONObject(arguments);
            if (operation.equals("ui.release")) {
                release(owner);
                return new JSONObject().put("released", true).toString();
            }
            if (!java.util.Set.of("ui.inspect", "ui.read_text", "ui.perform", "ui.wait", "input.gesture", "input.key_chord")
                    .contains(operation)) throw new IllegalArgumentException("unknown UI automation operation");
            final Session session = begin(owner);
            try {
                return session.execute(operation, args).toString();
            } finally {
                end();
            }
        } catch (JSONException error) {
            throw new IllegalArgumentException("invalid UI automation arguments", error);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private synchronized Session begin(final IBinder owner) {
        if (owner == null || !owner.isBinderAlive()) throw new IllegalArgumentException("missing UI automation owner");
        if (mOwner != null && !mOwner.equals(owner)) {
            throw new IllegalStateException("UI automation belongs to another owner");
        }
        if (mSession == null) {
            final IBinder.DeathRecipient death = () -> releaseQuietly(owner);
            try {
                owner.linkToDeath(death, 0);
            } catch (RemoteException error) {
                throw new IllegalStateException("UI automation owner disconnected", error);
            }
            try {
                mSession = new Session(FrameworkUiAutomationApi.connect(mContext));
                mOwner = owner;
                mDeath = death;
            } catch (RuntimeException error) {
                owner.unlinkToDeath(death, 0);
                throw error;
            }
        }
        mHandler.removeCallbacks(mIdleRelease);
        mCalls++;
        return mSession;
    }

    private synchronized void end() {
        mCalls--;
        if (mCalls == 0 && mSession != null) mHandler.postDelayed(mIdleRelease, 60000L);
    }

    private synchronized void releaseIfIdle() {
        if (mCalls == 0) releaseQuietly(mOwner);
    }

    private void releaseQuietly(final IBinder owner) {
        try { release(owner); }
        catch (RuntimeException error) { android.util.Log.w("MagicDeskUiAutomation", "UI connection cleanup failed", error); }
    }

    synchronized void release(final IBinder owner) {
        if (mOwner == null || !mOwner.equals(owner)) return;
        final Session session = mSession;
        mSession = null;
        mOwner.unlinkToDeath(mDeath, 0);
        mOwner = null;
        mDeath = null;
        mHandler.removeCallbacks(mIdleRelease);
        session.close();
    }

    @Override public synchronized void close() { release(mOwner); }

    private static final class Session implements AutoCloseable {
        private final UiAutomation mAutomation;
        private final Object mEvents = new Object();
        private final Object mOperations = new Object();
        private final LinkedHashMap<String, AndroidUiSnapshot> mSnapshots = new LinkedHashMap<>();
        private volatile boolean mClosed;
        private long mGeneration;

        Session(final UiAutomation automation) {
            mAutomation = automation;
            try {
                automation.setOnAccessibilityEventListener(event -> signal());
                final AccessibilityServiceInfo info = automation.getServiceInfo();
                if (info == null) throw new IllegalStateException("Android did not publish the automation service");
                info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                        | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                        | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
                info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
                info.notificationTimeout = 0;
                automation.setServiceInfo(info);
            } catch (RuntimeException error) {
                FrameworkUiAutomationApi.disconnect(automation);
                throw error;
            }
        }

        JSONObject execute(final String operation, final JSONObject args) throws JSONException {
            if (operation.equals("ui.wait")) return await(args);
            synchronized (mOperations) {
                checkOpen();
                prune();
                switch (operation) {
                    case "ui.inspect": {
                        return retain(capture(AndroidUiScope.parse(args))).toJson();
                    }
                    case "ui.perform":
                    case "ui.read_text": {
                        final String elementId = args.getString("elementId");
                        final AndroidUiSnapshot snapshot = snapshotFor(elementId);
                        return operation.equals("ui.read_text") ? snapshot.readText(elementId, args)
                                : snapshot.perform(elementId, args);
                    }
                    case "input.gesture": return AndroidAutomationInput.gesture(mAutomation, args);
                    case "input.key_chord": return AndroidAutomationInput.keyChord(mAutomation, args);
                    default: throw new IllegalArgumentException("unknown UI operation");
                }
            }
        }

        private JSONObject await(final JSONObject args) throws JSONException {
            final AndroidUiScope scope = AndroidUiScope.parse(args);
            if (scope.selector() == null) throw new IllegalArgumentException("selector is required");
            final String condition = args.optString("condition", "present");
            if (!condition.equals("present") && !condition.equals("absent")) {
                throw new IllegalArgumentException("condition must be present or absent");
            }
            final long deadline = SystemClock.uptimeMillis()
                    + AndroidUiSelector.integer(args, "timeoutMillis", 5000, 0, 60000);
            for (;;) {
                final long generation;
                synchronized (mEvents) { generation = mGeneration; }
                synchronized (mOperations) {
                    checkOpen();
                    final AndroidUiSnapshot snapshot = capture(scope);
                    final JSONArray matches = snapshot.nodes;
                    final boolean satisfied = AndroidUiSelector.satisfied(condition.equals("present"),
                            matches.length(), snapshot.complete(), snapshot.stable());
                    if (satisfied || SystemClock.uptimeMillis() >= deadline) {
                        retain(snapshot);
                        return snapshot.metadata().put("matched", satisfied).put("timedOut", !satisfied)
                                .put("matches", matches);
                    }
                    snapshot.close();
                }
                // Query between generation capture and wait: no lost notification, no idle polling.
                // Do not hold the operation lock here; another client may perform the awaited action.
                synchronized (mEvents) {
                    checkOpen();
                    final long remaining = deadline - SystemClock.uptimeMillis();
                    if (generation == mGeneration && remaining > 0) {
                        try {
                            EventDrivenWaits.await(mEvents, EventDrivenWaits.Reason.UI_AUTOMATION_CHANGE, remaining);
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("UI wait interrupted", error);
                        }
                    }
                }
            }
        }

        private AndroidUiSnapshot snapshotFor(final String elementId) {
            prune();
            final int separator = elementId.indexOf(':');
            final AndroidUiSnapshot snapshot = separator < 0 ? null
                    : mSnapshots.get(elementId.substring(0, separator));
            if (snapshot == null) throw new IllegalArgumentException("expired UI handle; inspect again");
            return snapshot;
        }

        private AndroidUiSnapshot capture(final AndroidUiScope scope) throws JSONException {
            final long start;
            synchronized (mEvents) { start = mGeneration; }
            final android.view.accessibility.AccessibilityNodeInfo subtree = scope.rootElementId() == null
                    ? null : snapshotFor(scope.rootElementId()).refreshedNode(scope.rootElementId(), scope.displayId());
            try {
                final AndroidUiSnapshot snapshot = AndroidUiSnapshot.capture(mAutomation, scope, subtree);
                synchronized (mEvents) { snapshot.observedBetween(start, mGeneration); }
                return snapshot;
            } finally {
                if (subtree != null) subtree.recycle();
            }
        }

        private AndroidUiSnapshot retain(final AndroidUiSnapshot snapshot) {
            prune();
            if (mSnapshots.size() == 4) mSnapshots.remove(mSnapshots.keySet().iterator().next()).close();
            mSnapshots.put(snapshot.id, snapshot);
            return snapshot;
        }

        private void prune() {
            final var iterator = mSnapshots.values().iterator();
            while (iterator.hasNext()) {
                final AndroidUiSnapshot snapshot = iterator.next();
                if (SystemClock.uptimeMillis() - snapshot.createdAt > 60000L) {
                    iterator.remove();
                    snapshot.close();
                }
            }
        }

        private void signal() { synchronized (mEvents) { mGeneration++; mEvents.notifyAll(); } }
        private void checkOpen() { if (mClosed) throw new IllegalStateException("UI automation session was released"); }

        @Override public void close() {
            mClosed = true;
            signal();
            synchronized (mOperations) {
                for (final AndroidUiSnapshot snapshot : mSnapshots.values()) snapshot.close();
                mSnapshots.clear();
                mAutomation.setOnAccessibilityEventListener(null);
                FrameworkUiAutomationApi.disconnect(mAutomation);
            }
        }
    }
}
