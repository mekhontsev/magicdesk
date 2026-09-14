package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

/** SystemUI grants foreground launch to this Activity; placement stays in ToolApplications. */
public final class TerminalNotificationActivity extends Activity {
    private static final String ACTION_RESUME = BuildConfig.APPLICATION_ID + ".RESUME_TERMINAL";

    static Intent resumeIntent(final Context context) {
        return new Intent(context, TerminalNotificationActivity.class)
                .setAction(ACTION_RESUME)
                .setData(Uri.parse("magicdesk-terminal:" + TerminalNotifications.PROCESS + "/resume"))
                .putExtra("process", TerminalNotifications.PROCESS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Override protected void onCreate(final Bundle state) {
        super.onCreate(state);
        final String id = getIntent().getStringExtra("terminalId");
        // Resolve the current MRU at click time, not when SystemUI renders the notification.
        final var session = TerminalNotifications.PROCESS.equals(getIntent().getStringExtra("process"))
                ? ACTION_RESUME.equals(getIntent().getAction())
                        ? ConsoleTerminalRegistry.mostRecent() : ConsoleTerminalRegistry.status(id)
                : null;
        if (session == null) {
            Toast.makeText(this, R.string.console_session_expired, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (id != null) { TerminalNotifications.cancel(id); }
        final int display = Math.max(0, session.displayId);
        TerminalSessions.open(this, CommandConsoleActivity.attachIntent(this, session),
                ToolLaunchTarget.resolve("auto", display, DesktopRuntimeBridge.workspaceDisplayIds()),
                null, error -> {
                    if (error != null) { Toast.makeText(this, ShellAccess.usefulMessage(error), Toast.LENGTH_LONG).show(); }
                    finish();
                });
    }
}
