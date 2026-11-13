package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;

import java.io.IOException;
import java.util.Locale;

/** Captures detailed task configuration only while a report is generated. */
final class DesktopTaskConfigurationReport {
    private static final int MAX_TASKS = 200;

    private DesktopTaskConfigurationReport() {
    }

    static void append(final StringBuilder report, final DesktopInputReportSnapshot input) {
        report.append("## Live task configuration\n");
        if (!ShellAccess.isReady()) {
            report.append("Unavailable: Privileged runtime unavailable\n\n");
            return;
        }
        final FrameworkTaskSnapshot[] tasks;
        final long taskStartedAtMillis = System.currentTimeMillis();
        try {
            tasks = ShellAccess.readDiagnosticTaskSnapshots(-1, MAX_TASKS);
        } catch (IOException | RuntimeException error) {
            report.append("Unavailable: ")
                    .append(ShellAccess.usefulMessage(error))
                    .append("\n\n");
            return;
        }
        final long taskCompletedAtMillis = System.currentTimeMillis();
        report.append("startedAtMillis=").append(taskStartedAtMillis)
                .append(" completedAtMillis=").append(taskCompletedAtMillis).append('\n');
        final java.util.Set<Integer> desktopDisplays = DesktopRuntimeBridge.workspaceDisplayIds();
        int reported = 0;
        for (final FrameworkTaskSnapshot task : tasks) {
            if (task == null || task.isHome()
                    || task.packageName.isEmpty()) {
                continue;
            }
            appendTask(report, task, desktopDisplays.contains(task.displayId) ? task.displayId : -1);
            reported++;
        }
        if (reported == 0) {
            report.append("No application tasks observed\n");
        }
        report.append('\n');
        appendGeometry(report, tasks, input);
    }

    private static void appendGeometry(StringBuilder report, FrameworkTaskSnapshot[] tasks,
            DesktopInputReportSnapshot input) {
        report.append("## Window surface geometry\n")
                .append("Read-only sequential snapshots, not an atomic frame. Missing fields are unknown.\n")
                .append("Numeric geometry uses each source's coordinate conventions; layerStack is not a logical display ID.\n")
                .append("Surface/input name matches are candidates, not proof of task ownership. Window titles are omitted.\n");
        var selected = new java.util.ArrayList<FrameworkTaskSnapshot>();
        int eligible = 0;
        // Preserve the windows under investigation before unrelated visible hosts.
        for (boolean freeform : new boolean[]{true, false}) {
            for (var task : tasks) {
                if (task == null || task.packageName.isEmpty()) continue;
                boolean isFreeform = task.windowingMode == FrameworkTaskSnapshot.WINDOWING_MODE_FREEFORM;
                if (isFreeform != freeform || (!isFreeform && !task.visible)) continue;
                eligible++;
                if (selected.size() < FrameworkWindowGeometryDiagnostics.MAX_TASKS) selected.add(task);
            }
        }
        report.append("selectedTasks=").append(selected.stream().map(task -> task.taskId).toList())
                .append(" omittedTasks=").append(eligible - selected.size()).append("\n\n");
        if (selected.isEmpty()) {
            report.append("No visible or freeform application tasks observed\n\n");
            return;
        }
        var selection = selected.toArray(new FrameworkTaskSnapshot[0]);
        input.appendWindowGeometry(report, selection);
        try {
            report.append(ShellAccess.readWindowGeometryDiagnostics(selection));
        } catch (IOException | RuntimeException error) {
            report.append("Surface/window capture unavailable: ")
                    .append(ShellAccess.usefulMessage(error)).append("\n\n");
        }
    }

    private static void appendTask(
            final StringBuilder report,
            final FrameworkTaskSnapshot task,
            final int activeDesktopDisplayId) {
        final AppReference stateKey = AppProfile.current(
                MagicDeskApplication.applicationContext()).windowReference(task);
        final AppWindowState saved = AppWindowStateStore.load(stateKey);
        final AppPresentationProfile presentation =
                AppPresentationProfileStore.load(stateKey == null ? null : stateKey.application);
        final DesktopTaskLaunchDiagnostics.Entry launch =
                DesktopTaskLaunchDiagnostics.find(task.taskId);
        report.append("- task=").append(task.taskId)
                .append(" user=").append(task.userId)
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
                .append(" presentation=")
                .append(presentationLabel(
                        task, presentation, activeDesktopDisplayId))
                .append(" modeMatch=")
                .append(modeMatchLabel(
                        saved, task.windowingMode,
                        task.displayId == activeDesktopDisplayId));
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
                ? "unspecified" : saved.mode.name().toLowerCase(Locale.ROOT);
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
            final int actualMode,
            final boolean onActiveDesktop) {
        if (!onActiveDesktop) {
            return "not-applicable";
        }
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

    static String presentationLabel(
            final FrameworkTaskSnapshot task,
            final AppPresentationProfile profile,
            final int activeDesktopDisplayId) {
        final String mode = profile == null
                ? "system"
                : "custom/scale=" + profile.scalePercent + '%';
        if (task.displayId != activeDesktopDisplayId) {
            return mode + "/applies=false/densityMatch=not-applicable";
        }
        final int displayDensity;
        try {
            displayDensity = DesktopTaskPresentationPolicy
                    .displayDensityDpi(task.displayId);
        } catch (IllegalArgumentException | IllegalStateException error) {
            return mode + "/expectedDpi=unknown/densityMatch=unknown";
        }
        final int expectedDensity =
                DesktopTaskPresentationPolicy.expectedDensityDpi(
                        profile, displayDensity);
        final String matches = task.taskConfigurationKnown
                ? Boolean.toString(task.densityDpi == expectedDensity)
                : "unknown";
        return mode
                + "/displayDpi=" + displayDensity
                + "/expectedDpi=" + expectedDensity
                + "/densityMatch=" + matches;
    }

    private static String rectLabel(final Rect bounds) {
        return bounds == null ? "unknown" : bounds.toShortString();
    }
}
