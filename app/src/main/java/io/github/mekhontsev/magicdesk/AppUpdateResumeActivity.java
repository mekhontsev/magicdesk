package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/** Installer-only process entry. Theme.NoDisplay creates no window or launcher surface.
 * Do not use noHistory: loss of visibility during cold startup can remove this
 * Activity before onCreate. This entry, not visibility, owns its completion. */
public final class AppUpdateResumeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            MagicDeskRuntime.startAutomation(this);
            var resume = getIntent().getBundleExtra("updateResume");
            if (resume != null && resume.getBinder("worker") != null) {
                var context = getApplicationContext();
                var worker = IAppUpdateWorker.Stub.asInterface(resume.getBinder("worker"));
                String updateId = resume.getString("updateId");
                new Thread(() -> restorePower(context, worker, updateId), "MagicDeskUpdateResume").start();
            }
        } catch (RuntimeException error) {
            Log.e("MagicDeskUpdate", "Could not resume automation", error);
        } finally {
            finish();
        }
    }

    private static void restorePower(android.content.Context context, IAppUpdateWorker worker, String updateId) {
        var restored = new java.util.concurrent.CompletableFuture<Boolean>();
        try {
            var bundle = worker.claimPowerState(updateId);
            var power = AppUpdatePowerState.fromBundle(bundle, android.os.SystemClock.elapsedRealtime());
            long remaining = bundle.getLong("resumeDeadline") - android.os.SystemClock.uptimeMillis();
            if (remaining <= 0) throw new IllegalStateException("update resume expired");
            // EVENT_WAIT: focused panel's drawn window; expiry cancels a not-yet-applied request.
            restored.orTimeout(remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
            AutomationMainThread.submit(() -> {
                if (restored.isDone()) return null;
                AutomationCommandRuntime.get(context).commands.restoreUpdatePower(power);
                if (power.blackScreen()) ControlActivity.requestBlackScreen(true, restored);
                else restored.complete(true);
                return null;
            }).whenComplete((unused, error) -> {
                if (error != null) restored.completeExceptionally(error);
            });
            restored.whenComplete((unused, error) -> {
                try { worker.completeResume(updateId, error == null ? "" : ShellAccess.usefulMessage(error)); }
                catch (Exception lost) { Log.w("MagicDeskUpdate", "Resume acknowledgement unavailable", lost); }
            });
        } catch (Exception error) {
            restored.cancel(false);
            try { worker.completeResume(updateId, ShellAccess.usefulMessage(error)); }
            catch (Exception lost) { error.addSuppressed(lost); }
            Log.w("MagicDeskUpdate", "Could not restore phone power state", error);
        }
    }
}
