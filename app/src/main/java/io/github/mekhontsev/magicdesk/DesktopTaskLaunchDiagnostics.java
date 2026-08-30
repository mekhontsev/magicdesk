package io.github.mekhontsev.magicdesk;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded launch provenance retained for one-shot compatibility reports. */
final class DesktopTaskLaunchDiagnostics {
    private static final int MAX_TASKS = 128;
    private static final LinkedHashMap<Integer, Entry> ENTRIES =
            new LinkedHashMap<>();

    private DesktopTaskLaunchDiagnostics() {
    }

    static synchronized void note(
            final int taskId,
            final int originalDisplayId,
            final int targetDisplayId,
            final String path) {
        if (taskId < 0) {
            return;
        }
        ENTRIES.remove(Integer.valueOf(taskId));
        ENTRIES.put(
                Integer.valueOf(taskId),
                new Entry(
                        originalDisplayId,
                        targetDisplayId,
                        clean(path)));
        trim();
    }

    static synchronized void noteIfAbsent(
            final int taskId,
            final int originalDisplayId,
            final int targetDisplayId,
            final String path) {
        if (!ENTRIES.containsKey(Integer.valueOf(taskId))) {
            note(taskId, originalDisplayId, targetDisplayId, path);
        }
    }

    static synchronized Entry find(final int taskId) {
        return ENTRIES.get(Integer.valueOf(taskId));
    }

    static synchronized void resetForTests() {
        ENTRIES.clear();
    }

    private static void trim() {
        while (ENTRIES.size() > MAX_TASKS) {
            final Map.Entry<Integer, Entry> oldest =
                    ENTRIES.entrySet().iterator().next();
            ENTRIES.remove(oldest.getKey());
        }
    }

    private static String clean(final String value) {
        if (value == null) {
            return "unknown";
        }
        final String result = value.replace('\n', ' ')
                .replace('\r', ' ').trim();
        return result.isEmpty() ? "unknown" : result;
    }

    static final class Entry {
        final int originalDisplayId;
        final int targetDisplayId;
        final String path;

        Entry(
                final int originalDisplay,
                final int targetDisplay,
                final String launchPath) {
            originalDisplayId = originalDisplay;
            targetDisplayId = targetDisplay;
            path = launchPath;
        }
    }
}
