package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Owns task-local firmware policy only during external Desktop residency. */
final class ShellWorkspaceFreeformPolicy {
    interface Writer {
        void set(Object token, boolean exempt) throws ReflectiveOperationException;
    }

    private final PlatformWindowingDriver.FreeformMultiplicity policy;
    private final Writer writer;
    private final Consumer<String> report;
    // A null token denotes a foreign profile or an unsuccessful claim. Never retry per sample.
    private final Map<Integer, Object> claims = new HashMap<>();
    private int display = -1;

    ShellWorkspaceFreeformPolicy(PlatformWindowingDriver.FreeformMultiplicity policy,
            Writer writer, Consumer<String> report) {
        this.policy = policy;
        this.writer = writer;
        this.report = report;
    }

    synchronized void configure(int next) {
        if (display == next) return;
        display = next;
        for (int id : new ArrayList<>(claims.keySet())) release(id);
    }

    synchronized void observe(int sampledDisplay, List<FrameworkTaskSnapshot> tasks,
            ShellDesktopTaskOwnership ownership) {
        if (policy == null || display <= 0 || display != sampledDisplay) return;
        for (FrameworkTaskSnapshot task : tasks) {
            if (task.displayId != display || !ownership.isRememberedDesktopTask(task.taskId)
                    || ownership.isDesktopHostTask(task.taskId)) continue;
            if (task.windowingMode != FrameworkTaskSnapshot.WINDOWING_MODE_FREEFORM) continue;
            if (claims.containsKey(task.taskId)) continue;
            claims.put(task.taskId, null);
            try {
                if (!policy.canClaim(task.task)) continue;
                Object token = HiddenTaskApi.getTaskToken(task.task);
                writer.set(token, true);
                claims.put(task.taskId, token);
            } catch (ReflectiveOperationException | RuntimeException error) {
                report.accept("freeform policy claim task=" + task.taskId + ": "
                        + TaskDisplayAreaLaunchCommand.causeChain(error));
            }
        }
    }

    synchronized void departed(int id, int destination) {
        if (destination != display) release(id);
    }

    synchronized void removed(int id) { claims.remove(id); }

    synchronized void release(int id) {
        Object token = claims.remove(id);
        if (token == null) return;
        try {
            writer.set(token, false);
        } catch (ReflectiveOperationException | RuntimeException error) {
            report.accept("freeform policy release task=" + id + ": "
                    + TaskDisplayAreaLaunchCommand.causeChain(error));
        }
    }
}
