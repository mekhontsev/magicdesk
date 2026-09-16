package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public final class SystemMonitorReaderTest {
    @Test public void cpuExcludesGuestAndAcceptsWhitespace() throws Exception {
        var cpu = SystemMonitorReader.parseCpuStat(" cpu 100 20 30 400 5 6 7 8 40 10 ");
        assertEquals(576, cpu.total()); assertEquals(405, cpu.idle());
    }
    @Test public void invalidCountersRemainUnknown() {
        for (String row : new String[]{null, "", "cpu0 1 2 3 4", "cpu 1 2 3", "cpu 1 2 -3 4",
                "cpu x 2 3 4", "cpu 9223372036854775807 1 0 0"})
            assertThrows(IOException.class, () -> SystemMonitorReader.parseCpuStat(row));
    }
    static String stat(int pid, String name, int parent, long start, long cpu, long rss) {
        return pid + " (" + name + ") S " + parent + " 1 1 0 0 0 0 0 0 0 "
                + cpu + " 2 0 0 20 0 1 0 " + start + " 1000 " + rss + " 0";
    }
    @Test public void namesWithSpacesAndParenthesesKeepFieldOffsets() throws Exception {
        var p = SystemMonitorReader.parseProcess(12, stat(12, "a ) b (c)", 8, 77, 9, 4),
                "Name: ignored\nUid:\t10601\t10601\t10601\t10601\n", "", 4);
        assertEquals("a ) b (c)", p.name); assertEquals(8, p.parentPid); assertEquals(77, p.startTicks);
        assertEquals(11, p.cpuTicks); assertEquals(16, p.rssKb); assertEquals(10601, p.uid);
    }
    @Test public void commandNameIsBoundedAndControlsAreRemoved() throws Exception {
        var p = SystemMonitorReader.parseProcess(12, stat(12, "sh", 8, 77, 9, 4),
                "Uid: 2000 2000", "/bin/a\nb" + "x".repeat(400), 4);
        assertEquals(256, p.name.length()); assertFalse(p.name.contains("\n"));
    }
    @Test public void invalidIdentityAndMissingOwnerFail() {
        assertThrows(IOException.class, () -> SystemMonitorReader.parseStat(9, stat(12, "sh", 8, 77, 0, 1)));
        assertThrows(IOException.class, () -> SystemMonitorReader.parseStat(12, stat(12, "sh", 8, -1, 0, 1)));
        assertThrows(IOException.class, () -> SystemMonitorReader.parseProcess(12, stat(12, "sh", 8, 77, 0, 1), "", "", 4));
    }
}
