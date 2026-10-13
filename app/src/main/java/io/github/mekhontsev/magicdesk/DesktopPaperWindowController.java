package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Scrollable layout policy. State advances only through an acknowledged continuation. */
final class DesktopPaperWindowController implements WindowLayoutPolicy {
    enum Command { TOGGLE, PREVIOUS, NEXT, MOVE_LEFT, MOVE_RIGHT, WIDTH, FLOAT, REFLOW, FOCUS, MAXIMIZE, UNMAXIMIZE }

    private final PaperWindowLayout layout;
    private final Set<Integer> floating;
    private final Map<Integer, Bounds> observed;
    private final Map<Integer, Bounds> submitted;
    private Bounds observedArea;
    private final int gap, edge;

    record CommandPlan(Plan plan, int focusTaskId) {}

    DesktopPaperWindowController(int gap, int edge) {
        this.layout = new PaperWindowLayout();
        this.floating = new HashSet<>();
        this.observed = new LinkedHashMap<>();
        this.submitted = new LinkedHashMap<>();
        this.gap = gap;
        this.edge = edge;
    }

    private DesktopPaperWindowController(DesktopPaperWindowController source) {
        layout = new PaperWindowLayout(source.layout);
        floating = new HashSet<>(source.floating);
        observed = new LinkedHashMap<>(source.observed);
        submitted = new LinkedHashMap<>(source.submitted);
        observedArea = source.observedArea;
        gap = source.gap;
        edge = source.edge;
    }

    static Command commandFor(DesktopWindowAction action) {
        return switch (action) {
            case PAPER_TOGGLE -> Command.TOGGLE;
            case PAPER_PREVIOUS -> Command.PREVIOUS;
            case PAPER_NEXT -> Command.NEXT;
            case PAPER_MOVE_LEFT -> Command.MOVE_LEFT;
            case PAPER_MOVE_RIGHT -> Command.MOVE_RIGHT;
            case PAPER_WIDTH -> Command.WIDTH;
            case PAPER_FLOAT -> Command.FLOAT;
            default -> null;
        };
    }
    boolean owns(int id) { return layout.contains(id) && !floating.contains(id); }
    boolean requiresReveal(int id) { return owns(id) && layout.requiresReveal(id); }
    boolean canNavigate(int direction) { return layout.canSelect(direction); }
    boolean maximized(int id) { return layout.isMaximized(id); }
    List<Integer> activationOrder(int id) { return layout.activationOrder(id); }

    @Override public Plan plan(Workspace workspace, Request request) {
        if (request.event() == Event.RELEASE) {
            return new FloatingWindowLayoutPolicy().plan(workspace, request);
        }
        if (request.event() == Event.PLACE) {
            if (!owns(request.taskId())) return new Plan(List.of(request.placement()));
            return command(workspace, fills(request.placement().bounds(), workspace.workArea())
                    ? (maximized(request.taskId()) ? Command.UNMAXIMIZE : Command.MAXIMIZE) : Command.REFLOW,
                    request.taskId(), request.placement().bounds()).plan();
        }
        return command(workspace, request.event() == Event.REVEAL ? Command.FOCUS : Command.REFLOW,
                request.event() == Event.REVEAL ? request.taskId() : workspace.focusedTaskId()).plan();
    }

    CommandPlan command(Workspace workspace, Command command, int requestedId) {
        return command(workspace, command, requestedId, null);
    }

    private CommandPlan command(Workspace workspace, Command command, int requestedId, Bounds requestedBounds) {
        if (command == Command.FOCUS) workspace.requireWindow(requestedId);
        var next = new DesktopPaperWindowController(this);
        int selected = workspace.windows().stream().anyMatch(w -> w.taskId() == requestedId)
                ? requestedId : layout.selectedId();
        Map<Integer, Placement> placements = new LinkedHashMap<>();
        if (command == Command.FLOAT && selected >= 0) {
            Window window = workspace.requireWindow(selected);
            if (!next.floating.remove(selected) && owns(selected)) {
                next.floating.add(selected);
                if (window.freeform() && window.floatingBounds() != null) {
                    placements.put(selected, new Placement(selected,
                            fit(window.floatingBounds(), workspace.workArea()), Retention.RELEASE_LAYOUT));
                }
            }
        }
        List<PaperWindowLayout.Window> windows = new ArrayList<>();
        Set<Integer> live = new HashSet<>();
        for (Window window : workspace.windows()) {
            live.add(window.taskId());
            if (next.floating.contains(window.taskId()) || window.bounds() == null
                    || (!window.freeform() && !layout.contains(window.taskId()))) continue;
            windows.add(new PaperWindowLayout.Window(window.taskId(),
                    window.bounds().right() - window.bounds().left(),
                    !window.freeform() || (window.concealed()
                            && !(command == Command.FOCUS && window.taskId() == requestedId))));
        }
        next.floating.retainAll(live);
        next.observed.keySet().retainAll(live);
        next.submitted.keySet().retainAll(live);
        Bounds area = workspace.workArea();
        boolean dirty = next.layout.update(windows, new PaperWindowLayout.Bounds(
                area.left(), area.top(), area.right(), area.bottom()), gap, edge,
                command == Command.FOCUS ? requestedId : workspace.focusedTaskId());
        for (Window window : workspace.windows()) {
            if (!window.freeform() || next.floating.contains(window.taskId())) {
                continue;
            }
            Bounds previous = next.observed.put(window.taskId(), window.bounds());
            if (!java.util.Objects.equals(previous, window.bounds())) {
                // Consume constrained geometry once, rather than retrying an identical observation.
                dirty = true;
                if (previous != null && window.bounds() != null && !window.concealed()
                        && area.equals(observedArea)
                        && !window.bounds().equals(submitted.get(window.taskId()))) {
                    if (fills(window.bounds(), area)) {
                        // Android offers Maximize again after tiling applies its
                        // inset rectangle. Only a fresh native expansion from
                        // the observed submitted rectangle is a restore gesture.
                        boolean restore = layout.isMaximized(window.taskId())
                                && previous.equals(submitted.get(window.taskId()));
                        next.layout.setMaximized(window.taskId(), !restore);
                    } else if (width(previous) != width(window.bounds())) {
                        next.layout.setWidth(window.taskId(), width(window.bounds()));
                    }
                }
            }
        }
        next.observedArea = area;
        if (requestedBounds != null && command == Command.REFLOW) {
            next.layout.setWidth(selected, width(requestedBounds));
            dirty = true;
        }
        int focus = -1;
        if (command == Command.PREVIOUS || command == Command.NEXT || command == Command.MOVE_LEFT || command == Command.MOVE_RIGHT) {
            focus = next.layout.select(selected,
                    command == Command.PREVIOUS || command == Command.MOVE_LEFT ? -1 : 1,
                    command == Command.MOVE_LEFT || command == Command.MOVE_RIGHT);
        } else if (command == Command.WIDTH) {
            next.layout.cycleWidth(selected);
        } else if (command == Command.MAXIMIZE || command == Command.UNMAXIMIZE) {
            next.layout.setMaximized(selected, command == Command.MAXIMIZE);
        }
        if (command != Command.REFLOW || dirty) {
            for (var entry : next.layout.bounds().entrySet()) {
                Window window = workspace.requireWindow(entry.getKey());
                if (!window.freeform()) continue;
                var frame = entry.getValue();
                Bounds target = new Bounds(frame.left(), frame.top(), frame.right(), frame.bottom());
                if (!target.equals(window.bounds()) || window.floatingBounds() == null) {
                    placements.put(window.taskId(), new Placement(window.taskId(), target, Retention.LAYOUT));
                    next.submitted.put(window.taskId(), target);
                }
            }
        }
        // The new policy includes selection and consumed observations even when no resize is needed.
        return new CommandPlan(new Plan(List.copyOf(placements.values()), next), focus);
    }

    private static int width(Bounds bounds) {
        return bounds.right() - bounds.left();
    }

    private static boolean fills(Bounds bounds, Bounds area) {
        return bounds != null && bounds.left() <= area.left() && bounds.top() <= area.top()
                && bounds.right() >= area.right() && bounds.bottom() >= area.bottom();
    }

    private static Bounds fit(Bounds bounds, Bounds area) {
        int width = Math.min(bounds.right() - bounds.left(), area.right() - area.left());
        int height = Math.min(bounds.bottom() - bounds.top(), area.bottom() - area.top());
        int left = Math.max(area.left(), Math.min(bounds.left(), area.right() - width));
        int top = Math.max(area.top(), Math.min(bounds.top(), area.bottom() - height));
        return new Bounds(left, top, left + width, top + height);
    }
}
