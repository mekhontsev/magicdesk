package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

public final class SystemMonitorRepositoryTest {
    static SystemProcessSnapshot process(int pid, int uid, int parent, long start, long ticks) {
        return new SystemProcessSnapshot(pid, uid, parent, start, ticks, 100, "same-name", "S");
    }
    static SystemMonitorSnapshot sample(long time, SystemProcessSnapshot... p) {
        return new SystemMonitorSnapshot(true, 1000, 500, time, time / 2, 1, time, 100, p, "");
    }
    @Test public void processCpuUsesElapsedTimeAndCanExceedOneCore() {
        try (var monitor = new SystemMonitorRepository()) {
            assertEquals(-1, monitor.convert(sample(1000, process(10, 10001, 1, 1, 2))).processes().get(0).cpuPercent(), 0);
            var next = monitor.convert(sample(2000, process(10, 10001, 1, 1, 202)));
            assertEquals(200, next.processes().get(0).cpuPercent(), 0.001f);
            assertEquals(50, next.cpuPercent(), 0.001f);
        }
    }
    @Test public void recycledPidAndChangedUidDoNotInheritCounters() {
        try (var monitor = new SystemMonitorRepository()) {
            monitor.convert(sample(1000, process(10, 10001, 1, 1, 100)));
            assertEquals(-1, monitor.convert(sample(2000, process(10, 10001, 1, 2, 200))).processes().get(0).cpuPercent(), 0);
            assertEquals(-1, monitor.convert(sample(3000, process(10, 10002, 1, 2, 300))).processes().get(0).cpuPercent(), 0);
        }
    }
    @Test public void unavailableSnapshotAndExitedProcessesClearHistory() {
        try (var monitor = new SystemMonitorRepository()) {
            monitor.convert(sample(1000, process(10, 10001, 1, 1, 100)));
            monitor.convert(SystemMonitorSnapshot.unavailable("denied"));
            assertEquals(-1, monitor.convert(sample(2000, process(10, 10001, 1, 1, 200))).processes().get(0).cpuPercent(), 0);
            monitor.convert(sample(3000));
            assertEquals(-1, monitor.convert(sample(4000, process(10, 10001, 1, 1, 400))).processes().get(0).cpuPercent(), 0);
        }
    }
    @Test public void sameNamesRemainSeparateAndResourcesDeduplicateIdentities() {
        try (var monitor = new SystemMonitorRepository()) {
            var p = process(10, 10001, 1, 1, 1); var q = process(11, 10001, 1, 2, 2);
            var result = monitor.convert(sample(1000, p, q));
            assertEquals(2, result.processes().size());
            assertEquals(200, result.resources(Set.of(p.identity(), q.identity())).rssKb());
            assertEquals(-1, result.resources(Set.of(p.identity(), q.identity())).cpuPercent(), 0);
        }
    }
}
