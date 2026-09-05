package io.github.mekhontsev.magicdesk;

import android.view.Display;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Phone HOME recents come from phone tasks, not desktop launch history. */
final class PhoneRecentApps {
    private PhoneRecentApps() {
    }

    static List<String> select(
            final List<TaskRepository.TaskEntry> tasks,
            final List<AppItem> apps,
            final String previousHomePackage) {
        final Set<String> keys = new LinkedHashSet<>();
        final Set<Integer> roots = new HashSet<>();
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task == null
                    || task.displayId != Display.DEFAULT_DISPLAY
                    || task.taskId < 0
                    || task.rootTaskId < 0
                    || task.home
                    || !PackageNameValidator.isSafe(task.packageName)
                    || task.packageName.equals(previousHomePackage)
                    || !roots.add(task.rootTaskId)) {
                continue;
            }
            final String key = BuiltInDesktopAppCatalog.appIdentityKey(task);
            // The catalog excludes shell surfaces and activities that have no
            // launcher entry, while retaining distinct built-in applications.
            if (LauncherAppRepository.findByIdentityKey(apps, key) != null) {
                keys.add(key);
            }
        }
        return new ArrayList<>(keys);
    }
}
