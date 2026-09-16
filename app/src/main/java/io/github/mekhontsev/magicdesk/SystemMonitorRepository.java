package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Sampling and rate calculation are independent of windows, packages and session ownership. */
final class SystemMonitorRepository implements AutoCloseable {
    record ProcessEntry(SystemProcessSnapshot process, float cpuPercent) { }
    record Resources(float cpuPercent, long rssKb) {
        static final Resources UNKNOWN = new Resources(-1, -1);
    }
    record Snapshot(boolean available, long totalMemoryKb, long availableMemoryKb,
            float cpuPercent, float loadAverage, List<ProcessEntry> processes, String error) {
        static Snapshot unavailable(String error) { return new Snapshot(false, -1, -1, -1, -1, List.of(), error); }
        Resources resources(java.util.Set<SystemProcessSnapshot.Identity> ids) {
            float cpu = 0; long memory = 0; int found = 0; boolean cpuKnown = true, memoryKnown = true;
            for (var entry : processes) {
                if (!ids.contains(entry.process.identity())) continue;
                found++;
                if (entry.cpuPercent < 0) cpuKnown = false; else cpu += entry.cpuPercent;
                if (entry.process.rssKb < 0) memoryKnown = false; else memory += entry.process.rssKb;
            }
            if (found == 0 || found != ids.size()) return Resources.UNKNOWN;
            return new Resources(cpuKnown ? cpu : -1, memoryKnown ? memory : -1);
        }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "MagicDeskSystemMonitor"));
    private Map<SystemProcessSnapshot.Identity, Long> previous = Map.of();
    private long previousTime = -1, previousTotal = -1, previousIdle = -1, previousTicks = -1;
    private volatile boolean closed;

    void load(Consumer<Snapshot> callback) {
        if (closed) return;
        try {
            worker.execute(() -> {
                Snapshot snapshot;
                try { snapshot = convert(ShellAccess.readSystemMonitorSnapshot()); }
                catch (Exception error) { snapshot = convert(SystemMonitorSnapshot.unavailable(ShellAccess.usefulMessage(error))); }
                if (!closed) callback.accept(snapshot);
            });
        } catch (RejectedExecutionException ignored) { /* close won scheduling. */ }
    }

    Snapshot convert(SystemMonitorSnapshot raw) {
        if (!raw.available) {
            previous = Map.of(); previousTime = previousTotal = previousIdle = previousTicks = -1;
            return Snapshot.unavailable(raw.error);
        }
        float total = -1;
        if (previousTotal >= 0 && raw.cpuTotal > previousTotal && raw.cpuIdle >= previousIdle)
            total = Math.max(0, Math.min(100, 100f * (1 - (float) (raw.cpuIdle - previousIdle) / (raw.cpuTotal - previousTotal))));
        final var next = new LinkedHashMap<SystemProcessSnapshot.Identity, Long>();
        final var rows = new ArrayList<ProcessEntry>();
        final long elapsed = raw.sampledAtMillis - previousTime;
        for (var p : raw.processes) {
            float cpu = -1;
            final Long before = previous.get(p.identity());
            if (before != null && p.cpuTicks >= before && elapsed > 0 && raw.ticksPerSecond > 0
                    && raw.ticksPerSecond == previousTicks)
                cpu = (float) ((p.cpuTicks - before) * 100000.0 / (raw.ticksPerSecond * (double) elapsed));
            next.put(p.identity(), p.cpuTicks);
            rows.add(new ProcessEntry(p, cpu));
        }
        previous = next; previousTime = raw.sampledAtMillis; previousTicks = raw.ticksPerSecond;
        previousTotal = raw.cpuTotal; previousIdle = raw.cpuIdle;
        return new Snapshot(true, raw.totalMemoryKb, raw.availableMemoryKb, total, raw.loadAverage, List.copyOf(rows), raw.error);
    }

    @Override public void close() { closed = true; worker.shutdownNow(); }
}
