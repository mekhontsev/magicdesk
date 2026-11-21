package io.github.mekhontsev.magicdesk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import java.util.HashMap;
import java.util.Map;

/** Debug-only Zygote launch: no instrumentation force-stop and no shell execution. */
public final class GuestFixtureService extends Service {
    private static final String CHANNEL = "shroot-fixtures";
    private static final int NOTIFICATION = 7314;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Thread> runs = new HashMap<>();
    private int latestStartId;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Shroot tests", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION, new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Shroot tests").setOngoing(true).build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        String id = intent == null ? null : intent.getStringExtra("runId");
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) {
            if (runs.isEmpty()) stopSelfResult(latestStartId);
            return START_NOT_STICKY;
        }
        if ("cancel".equals(intent.getStringExtra("operation"))) {
            Thread run = runs.get(id);
            if (run != null) run.interrupt();
            if (runs.isEmpty()) stopSelfResult(latestStartId);
            return START_NOT_STICKY;
        }
        if (runs.containsKey(id)) return START_NOT_STICKY;
        final Intent request = new Intent(intent);
        Thread run = new Thread(() -> {
            try { new GuestFixtureRunner(this).execute(request); }
            catch (Exception error) { android.util.Log.e("GuestFixture", "Fixture failed", error); }
            finally {
                main.post(() -> {
                    runs.remove(id);
                    if (runs.isEmpty()) stopSelfResult(latestStartId);
                });
            }
        }, "guest-fixture-" + id);
        runs.put(id, run);
        run.start();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        for (Thread run : runs.values()) run.interrupt();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
