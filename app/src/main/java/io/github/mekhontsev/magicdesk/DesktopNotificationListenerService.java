package io.github.mekhontsev.magicdesk;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public final class DesktopNotificationListenerService extends NotificationListenerService {
    private static final String TAG = "MagicDeskNotifications";
    private static final int MAX_NOTIFICATIONS = 100;
    private static final long REBIND_RECOVERY_DELAY_MS = 2_000L;
    private static final long REBIND_RETRY_DELAY_MS = 1_000L;
    private static final long REBIND_VERIFY_DELAY_MS = 2_000L;
    private static final long REBIND_RECOVERY_COOLDOWN_MS = 30_000L;
    private static final Object LOCK = new Object();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private static final Set<String> UNREAD_KEYS = new LinkedHashSet<>();
    private static final Set<Listener> LISTENERS = new CopyOnWriteArraySet<>();
    private static Snapshot sPendingSnapshot;
    private static final Runnable SNAPSHOT_DISPATCH = new Runnable() {
        @Override
        public void run() {
            final Snapshot snapshot;
            synchronized (LOCK) {
                snapshot = sPendingSnapshot;
                sPendingSnapshot = null;
            }
            if (snapshot == null) {
                return;
            }
            for (final Listener listener : LISTENERS) {
                listener.onNotificationsChanged(snapshot);
            }
        }
    };

    private static WeakReference<DesktopNotificationListenerService> sInstance =
            new WeakReference<>(null);
    private static boolean sConnected;
    private static boolean sRebindRecoveryScheduled;
    private static long sRebindRecoveryGeneration;
    private static long sLastRebindRecoveryTime;
    private static String sConnectionIssueCode = "";

    interface Listener {
        void onNotificationsChanged(Snapshot snapshot);

        void onNotificationPopup(Entry entry);
    }

    static final class ActionEntry {
        final int index;
        final String title;

        ActionEntry(final int index, final String title) {
            this.index = index;
            this.title = title;
        }
    }

    static final class Entry {
        final String key;
        final String packageName;
        final String appName;
        final String title;
        final String text;
        final long postTime;
        final int importance;
        final int notificationFlags;
        final boolean hasContentIntent;
        final boolean clearable;
        final boolean ongoing;
        final boolean groupSummary;
        final boolean progress;
        final boolean matchesInterruptionFilter;
        final Icon icon;
        final List<ActionEntry> actions;

        Entry(final String key, final String packageName, final String appName,
                final String title, final String text, final long postTime,
                final int importance, final int notificationFlags,
                final boolean hasContentIntent, final boolean clearable, final boolean ongoing,
                final boolean groupSummary, final boolean progress,
                final boolean matchesInterruptionFilter, final Icon icon,
                final List<ActionEntry> actions) {
            this.key = key;
            this.packageName = packageName;
            this.appName = appName;
            this.title = title;
            this.text = text;
            this.postTime = postTime;
            this.importance = importance;
            this.notificationFlags = notificationFlags;
            this.hasContentIntent = hasContentIntent;
            this.clearable = clearable;
            this.ongoing = ongoing;
            this.groupSummary = groupSummary;
            this.progress = progress;
            this.matchesInterruptionFilter = matchesInterruptionFilter;
            this.icon = icon;
            this.actions = actions;
        }

        boolean sameVisibleContent(final Entry other) {
            return other != null
                    && TextUtils.equals(title, other.title)
                    && TextUtils.equals(text, other.text)
                    && importance == other.importance
                    && notificationFlags == other.notificationFlags
                    && hasContentIntent == other.hasContentIntent
                    && clearable == other.clearable
                    && ongoing == other.ongoing
                    && groupSummary == other.groupSummary
                    && progress == other.progress
                    && sameActions(actions, other.actions);
        }

        private static boolean sameActions(final List<ActionEntry> left,
                final List<ActionEntry> right) {
            if (left.size() != right.size()) {
                return false;
            }
            for (int index = 0; index < left.size(); index++) {
                final ActionEntry leftAction = left.get(index);
                final ActionEntry rightAction = right.get(index);
                if (leftAction.index != rightAction.index
                        || !TextUtils.equals(leftAction.title, rightAction.title)) {
                    return false;
                }
            }
            return true;
        }
    }

    static final class Snapshot {
        final List<Entry> entries;
        final int unreadCount;
        final boolean connected;
        final String connectionIssueCode;

        Snapshot(final List<Entry> entries, final int unreadCount,
                final boolean connected, final String connectionIssueCode) {
            this.entries = entries;
            this.unreadCount = unreadCount;
            this.connected = connected;
            this.connectionIssueCode = connectionIssueCode;
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        synchronized (LOCK) {
            sInstance = new WeakReference<>(this);
            sConnected = true;
            sRebindRecoveryScheduled = false;
            sRebindRecoveryGeneration++;
            sConnectionIssueCode = "";
        }
        refreshActiveNotifications(getCurrentRanking());
        Log.i(TAG, "notification listener connected");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        clearConnection();
        Log.w(TAG, "notification listener disconnected");
    }

    @Override
    public void onDestroy() {
        clearConnection();
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(final StatusBarNotification notification,
            final RankingMap rankingMap) {
        final Entry entry = DesktopNotificationMapper.fromSystemNotification(
                this, notification, rankingMap);
        if (entry == null) {
            return;
        }

        final Entry previous;
        final boolean popup;
        final Snapshot snapshot;
        synchronized (LOCK) {
            previous = ENTRIES.put(entry.key, entry);
            final boolean visibleContentChanged = !entry.sameVisibleContent(previous);
            if (!visibleContentChanged && previous != null) {
                return;
            }
            if (visibleContentChanged && !entry.ongoing
                    && !entry.groupSummary && !entry.progress) {
                UNREAD_KEYS.add(entry.key);
            }
            popup = shouldPopup(entry, previous, visibleContentChanged);
            trimEntriesLocked();
            snapshot = createSnapshotLocked();
        }
        dispatchSnapshot(snapshot);
        if (popup) {
            dispatchPopup(entry);
        }
    }

    @Override
    public void onNotificationRemoved(final StatusBarNotification notification,
            final RankingMap rankingMap, final int reason) {
        if (notification == null) {
            return;
        }
        final Snapshot snapshot;
        synchronized (LOCK) {
            ENTRIES.remove(notification.getKey());
            UNREAD_KEYS.remove(notification.getKey());
            snapshot = createSnapshotLocked();
        }
        dispatchSnapshot(snapshot);
    }

    @Override
    public void onNotificationRankingUpdate(final RankingMap rankingMap) {
        refreshActiveNotifications(rankingMap);
    }

    static ComponentName getComponentName(final Context context) {
        return new ComponentName(context, DesktopNotificationListenerService.class);
    }

    static boolean isAccessGranted(final Context context) {
        final NotificationManager manager =
                context.getSystemService(NotificationManager.class);
        return manager != null
                && manager.isNotificationListenerAccessGranted(getComponentName(context));
    }

    static void requestRebindIfGranted(final Context context) {
        if (!isAccessGranted(context)) {
            return;
        }
        final boolean connected;
        final boolean scheduleRecovery;
        final long recoveryGeneration;
        synchronized (LOCK) {
            connected = sConnected;
            final long now = SystemClock.elapsedRealtime();
            scheduleRecovery = !connected
                    && !sRebindRecoveryScheduled
                    && (sLastRebindRecoveryTime == 0L
                            || now - sLastRebindRecoveryTime
                                    >= REBIND_RECOVERY_COOLDOWN_MS);
            if (scheduleRecovery) {
                sRebindRecoveryScheduled = true;
                sRebindRecoveryGeneration++;
                sLastRebindRecoveryTime = now;
                sConnectionIssueCode = "";
            }
            recoveryGeneration = sRebindRecoveryGeneration;
        }
        if (!connected) {
            try {
                requestRebind(getComponentName(context));
            } catch (RuntimeException e) {
                Log.w(TAG, "public notification-listener rebind failed", e);
            }
            if (scheduleRecovery) {
                final Context applicationContext = context.getApplicationContext();
                MAIN_HANDLER.postDelayed(
                        () -> recoverNotificationListenerBinding(
                                applicationContext, recoveryGeneration),
                        REBIND_RECOVERY_DELAY_MS);
            }
        }
    }

    static void addListener(final Listener listener) {
        if (listener == null) {
            return;
        }
        LISTENERS.add(listener);
        final Snapshot snapshot = getSnapshot();
        MAIN_HANDLER.post(() -> {
            if (LISTENERS.contains(listener)) {
                listener.onNotificationsChanged(snapshot);
            }
        });
    }

    static void removeListener(final Listener listener) {
        LISTENERS.remove(listener);
    }

    static Snapshot getSnapshot() {
        synchronized (LOCK) {
            return createSnapshotLocked();
        }
    }

    static void markAllRead() {
        final DesktopNotificationListenerService service;
        final String[] keys;
        final Snapshot snapshot;
        synchronized (LOCK) {
            UNREAD_KEYS.clear();
            service = sInstance.get();
            keys = ENTRIES.keySet().toArray(new String[0]);
            snapshot = createSnapshotLocked();
        }
        if (service != null && keys.length > 0) {
            try {
                service.setNotificationsShown(keys);
            } catch (RuntimeException e) {
                Log.w(TAG, "failed to mark notifications shown", e);
            }
        }
        dispatchSnapshot(snapshot);
    }

    static void markRead(final String key) {
        if (key == null) {
            return;
        }
        final DesktopNotificationListenerService service;
        final Snapshot snapshot;
        synchronized (LOCK) {
            if (!UNREAD_KEYS.remove(key)) {
                return;
            }
            service = sInstance.get();
            snapshot = createSnapshotLocked();
        }
        if (service != null) {
            try {
                service.setNotificationsShown(new String[] {key});
            } catch (RuntimeException e) {
                Log.w(TAG, "failed to mark notification shown key=" + key, e);
            }
        }
        dispatchSnapshot(snapshot);
    }

    static boolean openNotification(final Context context, final String key,
            final int displayId) {
        final Notification notification = findEntryNotification(key);
        if (notification == null || notification.contentIntent == null) {
            return false;
        }
        final boolean sent = sendPendingIntent(
                context, notification.contentIntent, displayId);
        if (sent) {
            markRead(key);
            if ((notification.flags & Notification.FLAG_AUTO_CANCEL) != 0) {
                dismissNotification(key);
            }
        }
        return sent;
    }

    static boolean invokeAction(final Context context, final String key,
            final int actionIndex, final int displayId) {
        final Notification notification = findEntryNotification(key);
        if (notification == null || notification.actions == null
                || actionIndex < 0 || actionIndex >= notification.actions.length) {
            return false;
        }
        final Notification.Action action = notification.actions[actionIndex];
        if (action == null || action.actionIntent == null) {
            return false;
        }
        final boolean sent = sendPendingIntent(context, action.actionIntent, displayId);
        if (sent) {
            markRead(key);
        }
        return sent;
    }

    static boolean dismissNotification(final String key) {
        final DesktopNotificationListenerService service;
        final Entry entry;
        synchronized (LOCK) {
            service = sInstance.get();
            entry = ENTRIES.get(key);
        }
        if (service == null || entry == null || !entry.clearable) {
            return false;
        }
        try {
            service.cancelNotification(key);
        } catch (RuntimeException e) {
            Log.w(TAG, "failed to dismiss notification key=" + key, e);
            return false;
        }
        final Snapshot snapshot;
        synchronized (LOCK) {
            ENTRIES.remove(key);
            UNREAD_KEYS.remove(key);
            snapshot = createSnapshotLocked();
        }
        dispatchSnapshot(snapshot);
        return true;
    }

    static boolean clearAllNotifications() {
        final DesktopNotificationListenerService service;
        synchronized (LOCK) {
            service = sInstance.get();
        }
        if (service == null) {
            return false;
        }
        try {
            service.cancelAllNotifications();
        } catch (RuntimeException e) {
            Log.w(TAG, "failed to clear notifications", e);
            return false;
        }
        final Snapshot snapshot;
        synchronized (LOCK) {
            ENTRIES.values().removeIf(entry -> entry.clearable);
            UNREAD_KEYS.retainAll(ENTRIES.keySet());
            snapshot = createSnapshotLocked();
        }
        dispatchSnapshot(snapshot);
        return true;
    }

    private void refreshActiveNotifications(final RankingMap rankingMap) {
        final StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (RuntimeException e) {
            Log.w(TAG, "failed to load active notifications", e);
            CompatibilityDiagnostics.record(
                    "NOTIFICATIONS-006",
                    "Active Android notifications could not be loaded",
                    "",
                    e);
            return;
        }

        final Map<String, Entry> refreshed = new LinkedHashMap<>();
        if (active != null) {
            for (final StatusBarNotification notification : active) {
                final Entry entry =
                        DesktopNotificationMapper.fromSystemNotification(
                                this, notification, rankingMap);
                if (entry != null) {
                    refreshed.put(entry.key, entry);
                }
            }
        }

        final Snapshot snapshot;
        synchronized (LOCK) {
            ENTRIES.clear();
            ENTRIES.putAll(refreshed);
            UNREAD_KEYS.retainAll(ENTRIES.keySet());
            trimEntriesLocked();
            snapshot = createSnapshotLocked();
        }
        dispatchSnapshot(snapshot);
    }

    private static boolean shouldPopup(final Entry entry, final Entry previous,
            final boolean visibleContentChanged) {
        return visibleContentChanged
                && entry.importance >= NotificationManager.IMPORTANCE_HIGH
                && entry.matchesInterruptionFilter
                && !entry.ongoing
                && !entry.groupSummary
                && !entry.progress
                && (previous == null
                        || (entry.notificationFlags
                                & Notification.FLAG_ONLY_ALERT_ONCE) == 0);
    }

    private static boolean sendPendingIntent(final Context context,
            final PendingIntent pendingIntent, final int displayId) {
        final ActivityOptions options = ActivityOptions.makeBasic();
        if (displayId >= 0) {
            options.setLaunchDisplayId(displayId);
        }
        options.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        try {
            pendingIntent.send(context, 0, null, null, null, null, options.toBundle());
            return true;
        } catch (PendingIntent.CanceledException | RuntimeException e) {
            Log.w(TAG, "notification pending intent failed", e);
            return false;
        }
    }

    private static Notification findEntryNotification(final String key) {
        final DesktopNotificationListenerService service;
        synchronized (LOCK) {
            service = sInstance.get();
        }
        if (service == null || key == null) {
            return null;
        }
        try {
            final StatusBarNotification[] notifications =
                    service.getActiveNotifications(new String[] {key});
            return notifications != null && notifications.length > 0
                    ? notifications[0].getNotification() : null;
        } catch (RuntimeException e) {
            Log.w(TAG, "failed to resolve notification key=" + key, e);
            return null;
        }
    }

    private void clearConnection() {
        final Snapshot snapshot;
        synchronized (LOCK) {
            if (sInstance.get() != this) {
                return;
            }
            sInstance.clear();
            sConnected = false;
            ENTRIES.clear();
            UNREAD_KEYS.clear();
            snapshot = createSnapshotLocked();
        }
        dispatchSnapshot(snapshot);
    }

    private static void trimEntriesLocked() {
        if (ENTRIES.size() <= MAX_NOTIFICATIONS) {
            return;
        }
        final List<Entry> sorted = sortedEntriesLocked();
        ENTRIES.clear();
        for (int index = 0; index < Math.min(MAX_NOTIFICATIONS, sorted.size()); index++) {
            final Entry entry = sorted.get(index);
            ENTRIES.put(entry.key, entry);
        }
        UNREAD_KEYS.retainAll(ENTRIES.keySet());
    }

    private static Snapshot createSnapshotLocked() {
        return new Snapshot(
                Collections.unmodifiableList(sortedEntriesLocked()),
                UNREAD_KEYS.size(),
                sConnected,
                sConnectionIssueCode);
    }

    private static void recoverNotificationListenerBinding(
            final Context context,
            final long generation) {
        synchronized (LOCK) {
            if (generation != sRebindRecoveryGeneration) {
                return;
            }
            if (sConnected || !sRebindRecoveryScheduled) {
                sRebindRecoveryScheduled = false;
                return;
            }
        }
        if (!isAccessGranted(context)) {
            synchronized (LOCK) {
                if (generation == sRebindRecoveryGeneration) {
                    sRebindRecoveryScheduled = false;
                }
            }
            return;
        }

        try {
            requestUnbind(getComponentName(context));
            Log.i(TAG, "notification listener recovery unbind requested");
        } catch (RuntimeException e) {
            Log.w(TAG, "public notification-listener unbind failed", e);
            finishNotificationListenerRecovery(
                    generation,
                    "Public notification-listener unbind failed: "
                            + describeFailure(e));
            return;
        }

        MAIN_HANDLER.postDelayed(
                () -> requestNotificationListenerRebind(
                        context, generation),
                REBIND_RETRY_DELAY_MS);
    }

    private static void requestNotificationListenerRebind(
            final Context context,
            final long generation) {
        synchronized (LOCK) {
            if (generation != sRebindRecoveryGeneration) {
                return;
            }
        }
        if (!isAccessGranted(context)) {
            synchronized (LOCK) {
                if (generation == sRebindRecoveryGeneration) {
                    sRebindRecoveryScheduled = false;
                }
            }
            return;
        }

        String failure = "";
        try {
            requestRebind(getComponentName(context));
            Log.i(TAG, "notification listener recovery rebind requested");
        } catch (RuntimeException e) {
            failure = "Public notification-listener rebind failed: "
                    + describeFailure(e);
            Log.w(TAG, "public notification-listener recovery rebind failed", e);
        }
        synchronized (LOCK) {
            if (generation != sRebindRecoveryGeneration) {
                return;
            }
            if (sConnected) {
                sRebindRecoveryScheduled = false;
                return;
            }
        }
        final String finalFailure = failure;
        MAIN_HANDLER.postDelayed(
                () -> finishNotificationListenerRecovery(
                        generation, finalFailure),
                REBIND_VERIFY_DELAY_MS);
    }

    private static void finishNotificationListenerRecovery(
            final long generation,
            final String failureDetail) {
        final Snapshot snapshot;
        synchronized (LOCK) {
            if (generation != sRebindRecoveryGeneration) {
                return;
            }
            sRebindRecoveryScheduled = false;
            if (sConnected) {
                return;
            }
            sConnectionIssueCode = "NOTIFICATIONS-005";
            snapshot = createSnapshotLocked();
        }
        final String detail = TextUtils.isEmpty(failureDetail)
                ? "The listener remained disconnected after the public unbind/rebind recovery"
                : failureDetail;
        CompatibilityDiagnostics.record(
                "NOTIFICATIONS-005",
                "The Android notification listener could not reconnect",
                detail);
        dispatchSnapshot(snapshot);
    }

    private static String describeFailure(final RuntimeException exception) {
        return TextUtils.isEmpty(exception.getMessage())
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    private static List<Entry> sortedEntriesLocked() {
        final List<Entry> entries = new ArrayList<>(ENTRIES.values());
        entries.sort(new Comparator<Entry>() {
            @Override
            public int compare(final Entry left, final Entry right) {
                return Long.compare(right.postTime, left.postTime);
            }
        });
        return entries;
    }

    private static void dispatchSnapshot(final Snapshot snapshot) {
        synchronized (LOCK) {
            sPendingSnapshot = snapshot;
        }
        MAIN_HANDLER.removeCallbacks(SNAPSHOT_DISPATCH);
        MAIN_HANDLER.postDelayed(SNAPSHOT_DISPATCH, 100L);
    }

    private static void dispatchPopup(final Entry entry) {
        MAIN_HANDLER.post(() -> {
            for (final Listener listener : LISTENERS) {
                listener.onNotificationPopup(entry);
            }
        });
    }
}
