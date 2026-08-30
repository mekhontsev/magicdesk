package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;

import java.io.IOException;

/** Captures detailed task configuration only while a report is generated. */
final class DesktopTaskConfigurationReport {
    private static final int MAX_TASKS = 200;

    private DesktopTaskConfigurationReport() {
    }

    static void append(final StringBuilder report) {
        report.append("## Live task configuration\n");
        if (!ShellAccess.isReady()) {
            report.append("Unavailable: Shizuku runtime unavailable\n\n");
            return;
        }
        final FrameworkTaskSnapshot[] tasks;
        try {
            tasks = ShellAccess.readDiagnosticTaskSnapshots(-1, MAX_TASKS);
        } catch (IOException | RuntimeException error) {
            report.append("Unavailable: ")
                    .append(ShellAccess.usefulMessage(error))
                    .append("\n\n");
            return;
        }
        int reported = 0;
        for (final FrameworkTaskSnapshot task : tasks) {
            if (task == null || task.isHome()
                    || task.packageName.isEmpty()) {
                continue;
            }
            appendTask(report, task);
            reported++;
        }
        if (reported == 0) {
            report.append("No application tasks observed\n");
        }
        report.append('\n');
    }

    private static void appendTask(
            final StringBuilder report,
            final FrameworkTaskSnapshot task) {
        final String stateKey = BuiltInDesktopAppCatalog.appIdentityKey(
                task.packageName, task.componentName);
        final AppWindowState saved = AppWindowStateStore.load(stateKey);
        final DesktopTaskLaunchDiagnostics.Entry launch =
                DesktopTaskLaunchDiagnostics.find(task.taskId);
        report.append("- task=").append(task.taskId)
                .append(" root=").append(task.rootTaskId)
                .append(" display=").append(task.displayId)
                .append(" area=").append(task.displayAreaFeatureId)
                .append(" package=").append(task.packageName)
                .append(" component=").append(task.topActivityName)
                .append(" actualMode=").append(task.windowingModeName())
                .append(" actualBounds=").append(rectLabel(task.bounds))
                .append(" visible=").append(task.visible)
                .append(" focused=").append(task.focused)
                .append(" config=").append(configurationLabel(task))
                .append(" saved=").append(savedStateLabel(saved))
                .append(" modeMatch=")
                .append(modeMatchLabel(saved, task.windowingMode));
        if (launch == null) {
            report.append(" launch=unknown");
        } else {
            report.append(" launch=").append(launch.path)
                    .append(" originalDisplay=")
                    .append(launch.originalDisplayId)
                    .append(" targetDisplay=")
                    .append(launch.targetDisplayId);
        }
        report.append('\n');
    }

    static String savedStateLabel(final AppWindowState saved) {
        if (saved == null) {
            return "none";
        }
        final String mode = saved.mode == null
                ? "unspecified" : saved.mode.name().toLowerCase();
        final RelativeWindowBounds bounds = saved.windowBounds;
        if (bounds == null) {
            return mode + "/bounds=none";
        }
        return mode + "/bounds="
                + bounds.x + ',' + bounds.y + ','
                + bounds.width + ',' + bounds.height;
    }

    private static String modeMatchLabel(
            final AppWindowState saved,
            final int actualMode) {
        if (saved == null || saved.mode == null) {
            return "unknown";
        }
        final boolean matches = saved.mode == AppWindowState.Mode.FULLSCREEN
                ? actualMode == FrameworkTaskSnapshot.WINDOWING_MODE_FULLSCREEN
                : actualMode == FrameworkTaskSnapshot.WINDOWING_MODE_FREEFORM;
        return Boolean.toString(matches);
    }

    private static String configurationLabel(
            final FrameworkTaskSnapshot task) {
        if (!task.taskConfigurationKnown) {
            return "unknown";
        }
        return "densityDpi:" + task.densityDpi
                + ",widthDp:" + task.screenWidthDp
                + ",heightDp:" + task.screenHeightDp
                + ",smallestWidthDp:" + task.smallestScreenWidthDp;
    }

    private static String rectLabel(final Rect bounds) {
        return bounds == null ? "unknown" : bounds.toShortString();
    }
}
