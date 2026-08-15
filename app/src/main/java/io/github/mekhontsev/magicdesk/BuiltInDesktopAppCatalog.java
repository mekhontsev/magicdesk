package io.github.mekhontsev.magicdesk;

import android.content.Intent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Describes user-facing MagicDesk tasks without admitting shell infrastructure. */
final class BuiltInDesktopAppCatalog {
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
            false,
            new RelativeWindowBounds(5000, 5000, 4500, 8000));
    private static final List<Entry> ENTRIES = Collections.unmodifiableList(
            Arrays.asList(FILES, SETTINGS));

    private BuiltInDesktopAppCatalog() {
    }

    static AppLaunchTarget filesTarget() {
        return FILES.launchTarget;
    }

    static AppLaunchTarget settingsTarget() {
        return SETTINGS.launchTarget;
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

    static RelativeWindowBounds defaultWindowBounds(
            final AppLaunchTarget target) {
        final Entry entry = find(target);
        return entry == null ? null : entry.defaultWindowBounds;
    }
}
