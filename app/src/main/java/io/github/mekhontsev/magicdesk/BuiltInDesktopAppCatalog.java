package io.github.mekhontsev.magicdesk;

import android.content.Intent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Describes user-facing MagicDesk tasks without admitting shell infrastructure. */
final class BuiltInDesktopAppCatalog {
    private static final String BUILT_IN_KEY_PREFIX = "builtin|";

    static final class Entry {
        final AppLaunchTarget launchTarget;
        final int fallbackLabelResId;
        final boolean listedInLauncher;
        final boolean multipleWindows;
        final boolean pinnable;
        final boolean remembersWindowState;
        final RelativeWindowBounds defaultWindowBounds;

        private Entry(
                final AppLaunchTarget launchTarget,
                final int fallbackLabelResId,
                final boolean listedInLauncher,
                final boolean multipleWindows,
                final boolean pinnable,
                final boolean remembersWindowState,
                final RelativeWindowBounds defaultWindowBounds) {
            this.launchTarget = launchTarget;
            this.fallbackLabelResId = fallbackLabelResId;
            this.listedInLauncher = listedInLauncher;
            this.multipleWindows = multipleWindows;
            this.pinnable = pinnable;
            this.remembersWindowState = remembersWindowState;
            this.defaultWindowBounds = defaultWindowBounds;
        }
    }

    private static final String PACKAGE_NAME = BuildConfig.APPLICATION_ID;
    private static final Entry FILES = new Entry(
            AppLaunchTarget.explicit(
                    PACKAGE_NAME,
                    FileManagerActivity.class.getName(),
                    Intent.ACTION_MAIN),
            R.string.file_manager_title,
            true,
            true,
            true,
            true,
            null);
    private static final Entry SETTINGS = new Entry(
            AppLaunchTarget.explicit(
                    PACKAGE_NAME,
                    SettingsActivity.class.getName(),
                    ""),
            R.string.settings_title,
            false,
            false,
            false,
            true,
            new RelativeWindowBounds(5000, 5000, 4500, 8000));
    private static final Entry CONSOLE = new Entry(
            AppLaunchTarget.explicit(
                    PACKAGE_NAME,
                    CommandConsoleActivity.class.getName(),
                    ""),
            R.string.console_title,
            false,
            true,
            false,
            true,
            new RelativeWindowBounds(5000, 5000, 4800, 7600));
    private static final Entry TASK_MANAGER = new Entry(
            AppLaunchTarget.explicit(
                    PACKAGE_NAME,
                    TaskManagerActivity.class.getName(),
                    ""),
            R.string.task_manager_title,
            false,
            false,
            false,
            true,
            new RelativeWindowBounds(5000, 5000, 6200, 7600));
    private static final Entry DIAGNOSTICS = new Entry(
            AppLaunchTarget.explicit(
                    PACKAGE_NAME,
                    DiagnosticsActivity.class.getName(),
                    ""),
            R.string.diagnostics_title,
            false,
            false,
            false,
            true,
            new RelativeWindowBounds(5000, 5000, 6200, 8000));
    private static final Entry LOG_VIEWER = new Entry(
            AppLaunchTarget.explicit(
                    PACKAGE_NAME,
                    AppLogViewerActivity.class.getName(),
                    ""),
            R.string.app_logs_fallback_title,
            false,
            true,
            false,
            true,
            new RelativeWindowBounds(5000, 5000, 6200, 7600));
    private static final Entry ACTIVITY_EXPLORER = new Entry(
            AppLaunchTarget.explicit(
                    PACKAGE_NAME,
                    ActivityExplorerActivity.class.getName(),
                    ""),
            R.string.activity_explorer_title,
            false,
            false,
            false,
            true,
            new RelativeWindowBounds(5000, 5000, 6800, 8200));
    private static final List<Entry> ENTRIES = Collections.unmodifiableList(
            Arrays.asList(
                    FILES,
                    SETTINGS,
                    CONSOLE,
                    TASK_MANAGER,
                    DIAGNOSTICS,
                    LOG_VIEWER,
                    ACTIVITY_EXPLORER));

    private BuiltInDesktopAppCatalog() {
    }

    static AppLaunchTarget filesTarget() {
        return FILES.launchTarget;
    }

    static AppLaunchTarget settingsTarget() {
        return SETTINGS.launchTarget;
    }

    static AppLaunchTarget consoleTarget() {
        return CONSOLE.launchTarget;
    }

    static AppLaunchTarget taskManagerTarget() {
        return TASK_MANAGER.launchTarget;
    }

    static AppLaunchTarget diagnosticsTarget() {
        return DIAGNOSTICS.launchTarget;
    }

    static AppLaunchTarget logViewerTarget() {
        return LOG_VIEWER.launchTarget;
    }

    static AppLaunchTarget activityExplorerTarget() {
        return ACTIVITY_EXPLORER.launchTarget;
    }

    static List<Entry> launcherEntries() {
        final List<Entry> result = new ArrayList<>();
        for (final Entry entry : ENTRIES) {
            if (entry.listedInLauncher) {
                result.add(entry);
            }
        }
        return Collections.unmodifiableList(result);
    }

    static List<Entry> searchEntries() {
        final List<Entry> result = new ArrayList<>();
        for (final Entry entry : ENTRIES) {
            if (entry != LOG_VIEWER) {
                result.add(entry);
            }
        }
        return Collections.unmodifiableList(result);
    }

    static Entry find(final AppLaunchTarget target) {
        if (target == null) {
            return null;
        }
        for (final Entry entry : ENTRIES) {
            if (entry.launchTarget.equals(target)) {
                return entry;
            }
        }
        return null;
    }

    static Entry find(final TaskRepository.TaskEntry task) {
        if (task == null) {
            return null;
        }
        for (final Entry entry : ENTRIES) {
            if (entry.launchTarget.matchesTask(task)) {
                return entry;
            }
        }
        return null;
    }

    static boolean isManagedTask(final TaskRepository.TaskEntry task) {
        return find(task) != null;
    }

    static boolean supportsMultipleWindows(final AppLaunchTarget target) {
        final Entry entry = find(target);
        return entry == null || entry.multipleWindows;
    }

    static boolean isPinnable(final AppLaunchTarget target) {
        final Entry entry = find(target);
        return entry == null || entry.pinnable;
    }

    static boolean remembersWindowState(final AppLaunchTarget target) {
        final Entry entry = find(target);
        return entry == null || entry.remembersWindowState;
    }

    static boolean remembersWindowState(
            final TaskRepository.TaskEntry task) {
        final Entry entry = find(task);
        return entry == null || entry.remembersWindowState;
    }

    static String appIdentityKey(final AppLaunchTarget target) {
        if (target == null) {
            return null;
        }
        final Entry entry = find(target);
        if (entry != null) {
            return builtInKey(entry);
        }
        return PACKAGE_NAME.equals(target.packageName)
                ? null : target.packageName;
    }

    static String appIdentityKey(final TaskRepository.TaskEntry task) {
        if (task == null) {
            return null;
        }
        final Entry entry = find(task);
        if (entry != null) {
            return builtInKey(entry);
        }
        return PACKAGE_NAME.equals(task.packageName)
                ? null : task.packageName;
    }

    static String appIdentityKey(
            final String packageName,
            final String componentName) {
        if (!PackageNameValidator.isSafe(packageName)) {
            return null;
        }
        if (!PACKAGE_NAME.equals(packageName)) {
            return packageName;
        }
        for (final Entry entry : ENTRIES) {
            if (entry.launchTarget.matchesTask(
                    packageName, componentName, componentName)) {
                return builtInKey(entry);
            }
        }
        // Shell hosts and transient MagicDesk activities must not overwrite
        // the placement of a user-facing built-in window.
        return null;
    }

    static boolean isAppIdentityKey(final String key) {
        if (key == null) {
            return false;
        }
        for (final Entry entry : ENTRIES) {
            if (builtInKey(entry).equals(key)) {
                return true;
            }
        }
        return false;
    }

    static RelativeWindowBounds defaultWindowBounds(
            final AppLaunchTarget target) {
        final Entry entry = find(target);
        return entry == null ? null : entry.defaultWindowBounds;
    }

    private static String builtInKey(final Entry entry) {
        return BUILT_IN_KEY_PREFIX + entry.launchTarget.stableKey();
    }
}
