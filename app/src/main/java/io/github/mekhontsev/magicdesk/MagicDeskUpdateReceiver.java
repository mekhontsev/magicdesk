package io.github.mekhontsev.magicdesk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Android restarts enabled automation after replacement; never reopens desktop or takes HOME. */
public final class MagicDeskUpdateReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        try {
            if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
                MagicDeskRuntime.startAutomation(context);
            }
        } catch (Exception error) {
            CompatibilityDiagnostics.record("MCP-UPDATE-001", "Could not resume automation after update",
                    ShellAccess.usefulMessage(error), error);
        }
    }
}
