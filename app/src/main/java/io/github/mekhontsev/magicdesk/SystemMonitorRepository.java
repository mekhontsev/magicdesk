package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Converts raw shell counters into UI-ready resource snapshots. */
final class SystemMonitorRepository implements AutoCloseable {
    static final class ProcessResources {
        static final ProcessResources UNAVAILABLE =
                new ProcessResources(-1f, -1L);

        final float cpuPercent;
        final long pssKb;

        ProcessResources(final float cpuPercent, final long pssKb) {
            this.cpuPercent = cpuPercent;
            this.pssKb = pssKb;
        }
    }

    static final class Snapshot {
        final boolean available;
        final long totalMemoryKb;
        final long availableMemoryKb;
        final float cpuPercent;
        final float loadAverage;
        final String error;
        private final Map<String, ProcessResources> mProcesses;

        Snapshot(
                final boolean available,
                final long totalMemoryKb,
                final long availableMemoryKb,
                final float cpuPercent,
                final float loadAverage,
                final Map<String, ProcessResources> processes,
                final String error) {
            this.available = available;
            this.totalMemoryKb = totalMemoryKb;
            this.availableMemoryKb = availableMemoryKb;
            this.cpuPercent = cpuPercent;
            this.loadAverage = loadAverage;
            mProcesses = new LinkedHashMap<>(processes);
            this.error = error == null ? "" : error;
        }

        ProcessResources forPackage(final String packageName) {
            if (packageName == null || packageName.isEmpty()) {
                return ProcessResources.UNAVAILABLE;
            }
            float cpu = -1f;
            long memory = -1L;
            for (final Map.Entry<String, ProcessResources> entry
                    : mProcesses.entrySet()) {
                final String processName = entry.getKey();
                if (!processName.equals(packageName)
                        && !processName.startsWith(packageName + ":")) {
                    continue;
                }
                final ProcessResources value = entry.getValue();
                if (value.cpuPercent >= 0f) {
                    cpu = cpu < 0f
                            ? value.cpuPercent : cpu + value.cpuPercent;
                }
                if (value.pssKb >= 0L) {
                    memory = memory < 0L ? value.pssKb : memory + value.pssKb;
                }
            }
            return new ProcessResources(cpu, memory);
        }

        static Snapshot unavailable(final String error) {
            return new Snapshot(
                    false,
                    -1L,
                    -1L,
                    -1f,
                    -1f,
                    new LinkedHashMap<>(),
                    error);
        }
    }

    private final ExecutorService mWorker =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(
                        runnable, "MagicDeskSystemMonitor");
                thread.setDaemon(true);
                return thread;
            });
    private final Map<String, Long> mProcessMemory = new LinkedHashMap<>();

    private long mPreviousCpuTotal = -1L;
    private long mPreviousCpuIdle = -1L;
    private boolean mClosed;

    void load(
            final boolean includeProcessMemory,
            final Consumer<Snapshot> callback) {
        mWorker.execute(() -> {
            Snapshot snapshot;
            try {
                snapshot = convert(ShellAccess.readSystemMonitorSnapshot(
                        includeProcessMemory));
            } catch (IOException | RuntimeException error) {
                snapshot = Snapshot.unavailable(
                        ShellAccess.usefulMessage(error));
            }
            if (!mClosed) {
                callback.accept(snapshot);
            }
        });
    }

    private Snapshot convert(final SystemMonitorSnapshot raw) {
        if (!raw.available) {
            return Snapshot.unavailable(raw.error);
        }
        final float totalCpu = cpuPercent(raw.cpuTotal, raw.cpuIdle);
        final Map<String, Float> processCpu = new LinkedHashMap<>();
        if (raw.processes != null) {
            for (final SystemProcessSnapshot process : raw.processes) {
                if (process == null || process.processName.isEmpty()) {
                    continue;
                }
                if (process.cpuPercent >= 0f) {
                    processCpu.put(process.processName, process.cpuPercent);
                }
                if (process.pssKb >= 0L) {
                    mProcessMemory.put(process.processName, process.pssKb);
                }
            }
        }
        final Map<String, ProcessResources> processes = new LinkedHashMap<>();
        for (final Map.Entry<String, Float> entry : processCpu.entrySet()) {
            final Long memory = mProcessMemory.get(entry.getKey());
            processes.put(entry.getKey(), new ProcessResources(
                    entry.getValue(),
                    memory == null ? -1L : memory.longValue()));
        }
        for (final Map.Entry<String, Long> entry : mProcessMemory.entrySet()) {
            if (!processes.containsKey(entry.getKey())) {
                processes.put(entry.getKey(), new ProcessResources(
                        -1f, entry.getValue()));
            }
        }
        return new Snapshot(
                true,
                raw.totalMemoryKb,
                raw.availableMemoryKb,
                totalCpu,
                raw.loadAverage,
                processes,
                raw.error);
    }

    private float cpuPercent(final long total, final long idle) {
        float result = -1f;
        if (mPreviousCpuTotal >= 0L && total > mPreviousCpuTotal) {
            final long totalDelta = total - mPreviousCpuTotal;
            final long idleDelta = Math.max(0L, idle - mPreviousCpuIdle);
            result = Math.max(
                    0f,
                    Math.min(100f, (totalDelta - idleDelta) * 100f / totalDelta));
        }
        mPreviousCpuTotal = total;
        mPreviousCpuIdle = idle;
        return result;
    }

    @Override
    public void close() {
        mClosed = true;
        mWorker.shutdownNow();
    }
}
