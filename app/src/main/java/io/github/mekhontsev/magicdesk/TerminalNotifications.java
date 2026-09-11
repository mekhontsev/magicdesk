package io.github.mekhontsev.magicdesk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** OSC requests use ordinary, user-controlled Android notifications, not Desktop popups. */
final class TerminalNotifications {
    static final String CHANNEL = "terminal_messages";
    // Process-local sessions must not be confused with a new process's reused session IDs.
    static final String PROCESS = java.util.UUID.randomUUID().toString();

    private TerminalNotifications() { }

    static void show(final ConsoleTerminalRegistry.Snapshot session, final String message) {
        final Context context = MagicDeskApplication.applicationContext();
        final NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) { return; }
        ensureChannel(context);
        final Intent intent = new Intent(context, TerminalNotificationActivity.class)
                .setData(Uri.parse("magicdesk-terminal:" + PROCESS + "/" + session.id))
                .putExtra("terminalId", session.id).putExtra("process", PROCESS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final PendingIntent open = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        final Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_file_console)
                .setContentTitle(session.taskLabel(context.getString("termux".equals(session.backend)
                        ? R.string.console_termux_title : R.string.console_title)))
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).build();
        try { manager.notify(tag(session.id), 1, notification); }
        catch (SecurityException ignored) { /* Revocation never disrupts PTY output. */ }
    }

    static void ensureChannel(final Context context) {
        final NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) { manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, context.getString(R.string.console_notifications), NotificationManager.IMPORTANCE_DEFAULT)); }
    }

    static void cancel(final String id) {
        final NotificationManager manager = MagicDeskApplication.applicationContext()
                .getSystemService(NotificationManager.class);
        if (manager != null) { manager.cancel(tag(id), 1); }
    }

    private static String tag(final String id) { return "terminal/" + PROCESS + "/" + id; }
}
