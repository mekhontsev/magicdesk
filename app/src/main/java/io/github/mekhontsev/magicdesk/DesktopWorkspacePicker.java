package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.hardware.display.DisplayManager;
import android.view.Display;

import java.util.List;
import java.util.function.Consumer;

/** Resolves a workspace for phone controls which are not inside a Desktop. */
final class DesktopWorkspacePicker {
    private DesktopWorkspacePicker() { }

    static void select(final Activity activity, final Consumer<DesktopDisplayTarget> action) {
        final DesktopHomeRoleLease.State lease = DesktopHomeRoleLease.snapshot();
        final List<DesktopDisplayTarget> targets = lease == null
                ? DesktopRuntimeBridge.workspaceTargets() : lease.targets;
        if (targets.isEmpty()) { return; }
        if (targets.size() == 1) { action.accept(targets.get(0)); return; }
        final DisplayManager manager = activity.getSystemService(DisplayManager.class);
        final String[] labels = targets.stream().map(target -> {
            final Display display = manager == null ? null : manager.getDisplay(target.workspaceDisplayId);
            return (display == null ? target.output.kind.name() : display.getName())
                    + " (" + target.workspaceDisplayId + ")";
        }).toArray(String[]::new);
        new AlertDialog.Builder(activity).setTitle(R.string.action_close_desktop)
                .setItems(labels, (dialog, index) -> action.accept(targets.get(index)))
                .setNegativeButton(android.R.string.cancel, null).show();
    }
}
