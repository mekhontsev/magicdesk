package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public final class SystemMonitorReaderTest {
    @Test
    public void parsesCpuForMainAndNamedProcesses() {
        final Map<String, SystemMonitorReader.MutableProcess> processes =
                new LinkedHashMap<>();

        SystemMonitorReader.parseCpuInfo(
                "CPU usage from 100ms to 0ms ago:\n"
                        + "  12% 100/com.example.app: 8% user + 4% kernel\n"
                        + "  3.5% 101/com.example.app:worker: 2% user\n"
                        + "  malformed row\n",
                processes);

        assertEquals(12f, processes.get("com.example.app").cpuPercent, 0.001f);
        assertEquals(
                3.5f,
                processes.get("com.example.app:worker").cpuPercent,
                0.001f);
    }

    @Test
    public void parsesOnlyTotalPssProcessSection() {
        final Map<String, SystemMonitorReader.MutableProcess> processes =
                new LinkedHashMap<>();

        SystemMonitorReader.parseProcessMemory(
                "Total RSS by process:\n"
                        + "  900,000K: com.example.app (pid 100)\n"
                        + "Total PSS by process:\n"
                        + "  123,456K: com.example.app (pid 100 / activities)\n"
                        + "   10,000K: com.example.app:worker (pid 101)\n"
                        + "Total PSS by OOM adjustment:\n"
                        + "  999,999K: com.example.app (pid 100)\n",
                processes);

        assertEquals(123456L, processes.get("com.example.app").pssKb);
        assertEquals(10000L, processes.get("com.example.app:worker").pssKb);
        assertEquals(2, processes.size());
    }

    @Test
    public void ignoresMissingSectionsAndMalformedRows() {
        final Map<String, SystemMonitorReader.MutableProcess> processes =
                new LinkedHashMap<>();

        SystemMonitorReader.parseProcessMemory(
                "Applications Memory Usage\nnot a process row\n",
                processes);

        assertTrue(processes.isEmpty());
    }
}
