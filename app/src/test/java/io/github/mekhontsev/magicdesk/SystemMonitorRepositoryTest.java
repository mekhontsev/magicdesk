package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public final class SystemMonitorRepositoryTest {
    @Test
    public void cpuOnlyRefreshKeepsMemoryUntilNextMemorySample() {
        try (SystemMonitorRepository repository = new SystemMonitorRepository()) {
            repository.convert(raw(
                    new SystemProcessSnapshot("com.example.app", 1f, 100L),
                    new SystemProcessSnapshot("com.example.app:worker", 2f, 40L)), true);

            final SystemMonitorRepository.Snapshot cpuOnly = repository.convert(raw(
                    new SystemProcessSnapshot("com.example.app", 3f, -1L)), false);
            assertEquals(140L, cpuOnly.forPackage("com.example.app").pssKb);

            final SystemMonitorRepository.Snapshot refreshed = repository.convert(raw(
                    new SystemProcessSnapshot("com.example.app", 4f, 90L)), true);
            assertEquals(90L, refreshed.forPackage("com.example.app").pssKb);
            assertEquals(-1L, refreshed.forPackage("com.example.app:worker").pssKb);
        }
    }

    @Test
    public void failedMemorySampleDoesNotKeepReportingOldMeasurements() {
        try (SystemMonitorRepository repository = new SystemMonitorRepository()) {
            repository.convert(raw(
                    new SystemProcessSnapshot("com.example.app", 1f, 100L)), true);
            final SystemMonitorSnapshot memoryFailed = new SystemMonitorSnapshot(
                    true, 1000L, 400L, 100L, 80L, 1f,
                    new SystemProcessSnapshot[]{
                            new SystemProcessSnapshot("com.example.app", 2f, -1L)},
                    "process memory unavailable");

            assertEquals(-1L, repository.convert(memoryFailed, true)
                    .forPackage("com.example.app").pssKb);
            assertEquals(-1L, repository.convert(raw(), false)
                    .forPackage("com.example.app").pssKb);
        }
    }

    private static SystemMonitorSnapshot raw(final SystemProcessSnapshot... processes) {
        return new SystemMonitorSnapshot(
                true, 1000L, 400L, 100L, 80L, 1f, processes, "");
    }

    @Test
    public void aggregatesMainAndNamedPackageProcesses() {
        final Map<String, SystemMonitorRepository.ProcessResources> processes =
                new LinkedHashMap<>();
        processes.put(
                "com.example.app",
                new SystemMonitorRepository.ProcessResources(4f, 100L));
        processes.put(
                "com.example.app:worker",
                new SystemMonitorRepository.ProcessResources(2.5f, 40L));
        processes.put(
                "com.example.other",
                new SystemMonitorRepository.ProcessResources(9f, 200L));
        final SystemMonitorRepository.Snapshot snapshot =
                new SystemMonitorRepository.Snapshot(
                        true, 1000L, 400L, 20f, 1f, processes, "");

        final SystemMonitorRepository.ProcessResources resources =
                snapshot.forPackage("com.example.app");

        assertEquals(6.5f, resources.cpuPercent, 0.001f);
        assertEquals(140L, resources.pssKb);
    }
}
