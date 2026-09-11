package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.widget.Toast;

import java.util.List;

/** Session selection does not require a desktop host or an attached terminal View. */
final class TerminalSessionsDialog {
    private TerminalSessionsDialog() { }

    static void show(final Activity activity) {
        show(activity, ToolLaunchTarget.resolve("auto", activity.getDisplay() == null
                ? 0 : activity.getDisplay().getDisplayId(), MagicDeskRuntime.activeDesktopDisplayId()), null);
    }

    static void show(final Activity activity, final ToolLaunchTarget target, final String uniqueId) {
        final List<ConsoleTerminalRegistry.Snapshot> sessions = ConsoleTerminalRegistry.list();
        final String[] labels = new String[sessions.size()];
        for (int i = 0; i < labels.length; i++) {
            final var session = sessions.get(i);
            labels[i] = session.taskLabel(session.backend) + "\n" + session.id + "  " + session.workingDirectory;
        }
        final AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.terminal_sessions)
                .setNegativeButton(android.R.string.cancel, null);
        if (sessions.isEmpty()) {
            dialog.setMessage(R.string.terminal_no_sessions);
        } else {
            dialog.setItems(labels, (picker, index) -> {
                final var session = sessions.get(index);
                new AlertDialog.Builder(activity).setTitle(labels[index])
                        .setItems(new String[]{activity.getString(R.string.action_open),
                                activity.getString(R.string.terminal_end_session)}, (actions, action) -> {
                            if (action == 1) { ConsoleTerminalRegistry.close(session.id); return; }
                            final Intent intent = CommandConsoleActivity.attachIntent(activity, session);
                            ToolApplications.open(activity, intent, target, uniqueId, error -> {
                                        if (error != null) { Toast.makeText(activity,
                                                ShellAccess.usefulMessage(error), Toast.LENGTH_LONG).show(); }
                                    });
                        }).show();
            });
        }
        dialog.show();
    }
}
