package io.github.mekhontsev.magicdesk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONException;
import org.json.JSONObject;

/** App-owned interaction lifetime, independent of the command transport and Desktop. */
final class UserInteractions implements AutoCloseable {
    static final String CHANNEL = "script_interactions";
    private static final String TAG_PREFIX = "script-interaction/";
    static final String EXTRA_ID = "requestId";
    static final String EXTRA_ACTION = "actionId";
    static final String REPLY = "reply";
    private static UserInteractions sCurrent;
    final UserInteractionRegistry registry = new UserInteractionRegistry();
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    private UserInteractions(Context context) {
        this.context = context.getApplicationContext();
        // Android retains notifications across process death; their result owner does not survive it.
        final NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            for (var notification : manager.getActiveNotifications()) {
                if (notification.getTag() != null && notification.getTag().startsWith(TAG_PREFIX)) {
                    manager.cancel(notification.getTag(), notification.getId());
                }
            }
        }
    }

    static synchronized UserInteractions get(Context context) {
        if (sCurrent == null) sCurrent = new UserInteractions(context);
        return sCurrent;
    }

    static synchronized UserInteractions current() { return sCurrent; }

    JSONObject execute(String name, JSONObject args) throws JSONException {
        if (name.equals("interaction.result")) {
            return registry.result(args.getString("requestId"), args.optLong("waitMillis", 0));
        }
        if (name.equals("interaction.close")) {
            registry.complete(args.getString("requestId"), "cancelled", null, "");
            return registry.result(args.getString("requestId"), 0);
        }
        final String kind = switch (name) {
            case "dialog.show" -> "dialog";
            case "notification.post" -> "notification";
            default -> throw new IllegalArgumentException("unknown interaction command");
        };
        final UserInteractionRequest request = UserInteractionRequest.parse(kind, args);
        final String id = registry.begin(request);
        main.post(() -> {
            if (registry.pending(id) == null) return;
            // Register the deadline before cancellation can remove it on this same UI queue.
            final Runnable expire = () -> registry.complete(id, "expired", null, "");
            main.postDelayed(expire, request.lifetimeMillis());
            registry.whenFinished(id, () -> main.post(() -> {
                main.removeCallbacks(expire);
                final NotificationManager manager = context.getSystemService(NotificationManager.class);
                if (manager != null && kind.equals("notification")) manager.cancel(TAG_PREFIX + id, 1);
            }));
            try {
                if (kind.equals("dialog")) {
                    final ToolLaunchTarget target = ToolLaunchTarget.resolve("auto", request.displayId(),
                            DesktopRuntimeBridge.workspaceDisplayIds());
                    ToolApplications.open(context, new Intent(context, UserPromptActivity.class)
                                    .setData(Uri.parse("magicdesk-prompt:" + id)).putExtra(EXTRA_ID, id),
                            target, null, error -> { if (error != null) fail(id, error); });
                } else {
                    postNotification(id, request);
                }
            } catch (RuntimeException error) { fail(id, error); }
        });
        return registry.result(id, 0);
    }

    private void fail(String id, Throwable error) {
        registry.complete(id, "failed", null, ShellAccess.usefulMessage(error));
    }

    private void postNotification(String id, UserInteractionRequest request) {
        final NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) {
            throw new IllegalStateException("Allow MagicDesk notifications in Android settings");
        }
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                context.getString(R.string.script_notifications), NotificationManager.IMPORTANCE_DEFAULT));
        if (manager.getNotificationChannel(CHANNEL).getImportance() == NotificationManager.IMPORTANCE_NONE) {
            throw new IllegalStateException("The Script notifications channel is disabled in Android settings");
        }
        final Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_file_console).setContentTitle(request.title())
                .setContentText(request.message()).setStyle(new Notification.BigTextStyle().bigText(request.message()))
                .setContentIntent(intent(id, "open", false, false))
                .setDeleteIntent(intent(id, "", false, true)).setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE);
        for (UserInteractionRequest.Item item : request.items()) {
            final Notification.Action.Builder action = new Notification.Action.Builder(null, item.label(),
                    intent(id, item.id(), item.reply(), false));
            if (item.reply()) action.addRemoteInput(new RemoteInput.Builder(REPLY).setLabel(item.label()).build());
            builder.addAction(action.build());
        }
        manager.notify(TAG_PREFIX + id, 1, builder.build());
        registry.presented(id); // Submitted to Android; DND and lock-screen policy still apply.
    }

    private PendingIntent intent(String id, String action, boolean reply, boolean dismiss) {
        final Intent intent = new Intent(context, UserNotificationReceiver.class)
                .setData(new Uri.Builder().scheme("magicdesk-interaction").authority(id)
                        .appendPath(dismiss ? "dismiss" : "action").appendPath(action).build())
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_ACTION, action).putExtra("dismiss", dismiss);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT
                | (reply ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_IMMUTABLE));
    }

    @Override public void close() {
        synchronized (UserInteractions.class) { if (sCurrent == this) sCurrent = null; }
        registry.close();
    }
}
