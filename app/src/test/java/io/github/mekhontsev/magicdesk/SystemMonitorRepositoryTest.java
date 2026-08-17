package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public final class SystemMonitorRepositoryTest {
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
