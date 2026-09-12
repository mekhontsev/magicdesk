package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/** SystemUI grants foreground launch to this Activity; placement stays in ToolApplications. */
public final class TerminalNotificationActivity extends Activity {
    @Override protected void onCreate(final Bundle state) {
        super.onCreate(state);
        final String id = getIntent().getStringExtra("terminalId");
        final var session = TerminalNotifications.PROCESS.equals(getIntent().getStringExtra("process"))
                ? ConsoleTerminalRegistry.status(id) : null;
        if (session == null) {
            Toast.makeText(this, R.string.console_session_expired, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        TerminalNotifications.cancel(id);
        final int display = Math.max(0, session.displayId);
        ToolApplications.open(this, CommandConsoleActivity.attachIntent(this, session),
                ToolLaunchTarget.resolve("auto", display, DesktopRuntimeBridge.workspaceDisplayIds()),
                null, error -> {
                    if (error != null) { Toast.makeText(this, ShellAccess.usefulMessage(error), Toast.LENGTH_LONG).show(); }
                    finish();
                });
    }
}
