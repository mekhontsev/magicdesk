package io.github.mekhontsev.magicdesk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, final Intent intent) {
        final String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            return;
        }
        final PendingResult pendingResult = goAsync();
        final Context applicationContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                final DeviceSetupManager.Audit audit =
                        DeviceSetupManager.audit(applicationContext);
                if (audit.canEnterMagicDesk()
                        && audit.acknowledged
                        && audit.backend == RuntimeAccess.Backend.ROOT) {
                    DeviceSetupManager.authorizeRuntime();
                    KeyboardWatcherService.start(applicationContext);
                }
            } finally {
                pendingResult.finish();
            }
        }, "MagicDeskBootSetupCheck").start();
    }
}
