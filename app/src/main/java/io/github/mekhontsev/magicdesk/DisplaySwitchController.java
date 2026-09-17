package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/** Pointer/keyboard selection and MRU are separate from the committed image/input operation. */
final class DisplaySwitchController implements DisplayManager.DisplayListener {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final DisplaySwitchHistory HISTORY = new DisplaySwitchHistory();
    private static DisplaySwitchController active;
    private static boolean switching;
    private final int outputId;
    private final Activity fallback;
    private final DisplayManager displays;
    private DesktopDisplayInfo output;
    private DesktopDisplayInfo current;
    private List<DesktopDisplayInfo> choices = List.of();
    private DisplaySwitchPanel panel;
    private int offset;
    private int selected;
    private boolean commitPending;
    private boolean loaded;
    private DesktopShellActivity pointerHost;

    private DisplaySwitchController(int outputId, Activity fallback) {
        this.outputId = outputId;
        this.fallback = fallback;
        displays = context().getSystemService(DisplayManager.class);
        displays.registerDisplayListener(this, MAIN);
    }

    private static Context context() { return MagicDeskApplication.applicationContext(); }

    static void advanceForInput(boolean reverse) {
        final int input = MagicDeskRuntime.inputDisplayId();
        advance(outputForSource(input), null, reverse);
    }

    static void show(DesktopShellActivity host) {
        if (switching) return;
        cancel();
        active = new DisplaySwitchController(outputForSource(host.getCurrentDisplayId()), null);
        active.pointerHost = host;
        active.load();
    }

    private static int outputForSource(int displayId) {
        final var viewer = DisplayPresentations.forSource(displayId);
        return viewer != null && viewer.visible ? viewer.output.id : displayId;
    }

    static void advance(int outputId, Activity fallback, boolean reverse) {
        if (outputId < 0 || switching) return;
        final int delta = reverse ? -1 : 1;
        if (active != null && active.outputId != outputId) cancel();
        if (active == null) {
            active = new DisplaySwitchController(outputId, fallback);
            active.offset = delta;
            active.load();
        } else if (!active.loaded) active.offset += delta;
        else {
            active.selected = Math.floorMod(active.selected + delta, active.choices.size());
            active.panel.select(active.selected);
        }
    }

    private void load() {
        TaskCommandQueue.execute(() -> {
            try {
                final DesktopDisplayInfo[] catalog = DesktopDisplayCatalog.read();
                final TaskRepository.Snapshot tasks = TaskRepository.loadAllNow();
                MAIN.post(() -> loaded(catalog, tasks));
            } catch (Exception error) { MAIN.post(() -> failed(error)); }
        });
    }

    private void loaded(DesktopDisplayInfo[] catalog, TaskRepository.Snapshot tasks) {
        if (active != this) return;
        try {
            for (var display : catalog) if (display.id == outputId) output = display;
            if (output == null) throw new IllegalStateException("Output display disappeared");
            final var viewer = DisplayPresentations.forOutput(output.id);
            current = viewer != null && viewer.visible ? viewer.source : output;
            final List<DesktopDisplayInfo> eligible = new ArrayList<>();
            for (var display : catalog) {
                if (DisplayPresentations.canSwitchOutput(display, output)) eligible.add(display);
            }
            if (eligible.isEmpty()) throw new IllegalStateException("No available displays");
            HISTORY.retainDisplays(java.util.Arrays.stream(catalog).map(d -> d.uniqueId).toList());
            final List<String> order = HISTORY.order(output.uniqueId, current.uniqueId,
                    eligible.stream().map(d -> d.uniqueId).toList());
            choices = order.stream().map(id -> eligible.stream()
                    .filter(d -> d.uniqueId.equals(id)).findFirst().orElseThrow()).toList();
            selected = Math.floorMod(offset, choices.size());
            loaded = true;
            if (commitPending) { commit(); return; }
            final List<String> labels = new ArrayList<>();
            for (var display : choices) {
                final String title = display.id == output.id
                        ? context().getString(R.string.display_switch_this, display.name, display.id)
                        : display.name + " [" + display.id + "]";
                final String status = context().getString(DesktopRuntimeBridge.hasWorkspace(display.id)
                        ? R.string.display_desktop_active : R.string.display_no_desktop);
                final long count = tasks.tasks.stream().filter(task -> task.displayId == display.id
                        && DesktopManagedTaskPolicy.isControllableApplicationTask(task)).count();
                labels.add(title + "\n" + status + "  |  " + (tasks.available
                        ? context().getString(R.string.display_switch_apps, count)
                        : context().getString(R.string.display_tasks_unknown)));
            }
            if (pointerHost != null && pointerHost.isActivityUnavailable()) { cancel(); return; }
            panel = new DisplaySwitchPanel(output.id, fallback, labels, pointerHost, index -> {
                if (active != this || !loaded) return;
                selected = index;
                commit();
            }, () -> { if (active == this) cancel(); });
            panel.select(selected);
        } catch (RuntimeException error) { failed(error); }
    }

    static void commit() {
        final var picker = active;
        if (picker == null) return;
        if (!picker.loaded) { picker.commitPending = true; return; }
        final var selected = picker.choices.get(picker.selected);
        cancel();
        if (selected.uniqueId.equals(picker.current.uniqueId)
                && MagicDeskRuntime.readyInputDisplayId() == selected.id
                && !MagicDeskRuntime.inputTransitioning()) return;
        switching = true;
        new DisplaySwitchOperation(context(), picker.output, selected, error -> {
            switching = false;
            if (error == null) HISTORY.committed(picker.output.uniqueId, picker.current.uniqueId, selected.uniqueId);
            else report(error);
        }).start();
    }

    static void cancel() {
        final var picker = active;
        active = null;
        if (picker == null) return;
        picker.displays.unregisterDisplayListener(picker);
        if (picker.panel != null) picker.panel.close();
    }

    static void cancelFor(Activity activity) {
        if (active != null && active.fallback == activity) cancel();
    }

    private void failed(Throwable error) {
        if (active != this) return;
        cancel();
        report(error);
    }

    private static void report(Throwable error) {
        final String message = ShellAccess.usefulMessage(error);
        CompatibilityDiagnostics.record("DISPLAY-SWITCH-001", "Could not switch displays", message, error);
        Toast.makeText(context(), context().getString(R.string.display_switch_failed, message), Toast.LENGTH_LONG).show();
    }

    @Override public void onDisplayAdded(int id) { }
    @Override public void onDisplayChanged(int id) { }
    @Override public void onDisplayRemoved(int id) {
        if (active == this) cancel();
    }
}
