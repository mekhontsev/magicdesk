package io.github.mekhontsev.magicdesk;

import android.app.UiAutomation;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.List;

/** One fresh accessibility window inventory, shared by scope selection and handle validation. */
final class AndroidUiWindows implements AutoCloseable {
    record Identity(int displayId, int windowId, Integer taskId) { }
    record Window(AccessibilityWindowInfo info, Identity identity) { }
    record Selection(int displayId, List<Window> windows, String unavailableReason) {
        boolean available() { return !windows.isEmpty(); }
        boolean complete() { return available() && unavailableReason.isEmpty(); }
    }

    final boolean cacheCleared;
    private final List<Window> windows;

    AndroidUiWindows(boolean cacheCleared, List<Window> windows) {
        this.cacheCleared = cacheCleared;
        this.windows = windows;
    }

    static AndroidUiWindows read(UiAutomation automation) {
        final boolean cleared = automation.clearCache();
        final var all = automation.getWindowsOnAllDisplays();
        final List<Window> windows = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            final var displayWindows = all.valueAt(i);
            if (displayWindows == null) continue;
            for (final var window : displayWindows) {
                windows.add(new Window(window, new Identity(window.getDisplayId(), window.getId(),
                        FrameworkUiAutomationApi.windowTaskId(window))));
            }
        }
        return new AndroidUiWindows(cleared, windows);
    }

    Selection select(AndroidUiScope scope, int windowId) {
        final List<Window> selected = new ArrayList<>();
        boolean unknownOwner = false;
        int display = scope.displayId();
        for (final Window window : windows) {
            final Identity owner = window.identity();
            if (windowId >= 0 && owner.windowId() != windowId) continue;
            if (scope.taskId() >= 0 && owner.taskId() == null) unknownOwner = true;
            if (!scope.contains(owner.displayId(), owner.taskId())) continue;
            if (display >= 0 && display != owner.displayId()) {
                return new Selection(-1, List.of(), "task_windows_span_displays");
            }
            display = owner.displayId();
            selected.add(window);
        }
        final String error = unknownOwner ? "task_window_mapping_unavailable"
                : !selected.isEmpty() ? ""
                : windowId >= 0 ? "window_unavailable"
                : scope.taskId() >= 0 ? "task_windows_unavailable" : "display_windows_unavailable";
        return new Selection(display, selected, error);
    }

    void require(Identity previous, AndroidUiScope scope) {
        if (!cacheCleared) throw new IllegalStateException("cannot verify UI window ownership; inspect again");
        if (!scope.contains(previous.displayId(), previous.taskId())
                || (scope.windowId() >= 0 && scope.windowId() != previous.windowId())) {
            throw new IllegalArgumentException("UI handle belongs to another display, task or window");
        }
        for (final Window window : windows) {
            if (previous.equals(window.identity())) return;
        }
        throw new IllegalArgumentException("UI window ownership changed or is unavailable; inspect again");
    }

    @Override public void close() {
        for (final Window window : windows) window.info().recycle();
    }
}
