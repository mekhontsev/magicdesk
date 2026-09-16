package io.github.mekhontsev.magicdesk;

import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/** Bounded procfs observation under the already selected service identity. */
final class SystemMonitorReader {
    private static final int MAX_PROCESSES = 2048;
    private static final int MAX_FILE_BYTES = 16 * 1024;

    static SystemMonitorSnapshot read() {
        try {
            final String memory = read(Path.of("/proc/meminfo"));
            final Cpu cpu = parseCpuStat(read(Path.of("/proc/stat")).split("\n", 2)[0]);
            final float load = Float.parseFloat(read(Path.of("/proc/loadavg")).split(" ", 2)[0]);
            final long pageKb = Os.sysconf(OsConstants._SC_PAGESIZE) / 1024;
            final long ticks = Os.sysconf(OsConstants._SC_CLK_TCK);
            if (pageKb <= 0 || ticks <= 0) throw new IOException("process counter units unavailable");
            final var processes = new ArrayList<SystemProcessSnapshot>();
            int denied = 0;
            boolean truncated = false;
            try (var dirs = Files.newDirectoryStream(Path.of("/proc"), p -> p.getFileName().toString().matches("[0-9]+"))) {
                for (Path dir : dirs) {
                    if (processes.size() == MAX_PROCESSES) { truncated = true; break; }
                    try {
                        final int pid = Integer.parseInt(dir.getFileName().toString());
                        final String stat = read(dir.resolve("stat"));
                        final String status = read(dir.resolve("status"));
                        String command = "";
                        try { command = read(dir.resolve("cmdline")).split("\u0000", 2)[0]; }
                        catch (IOException ignored) { /* comm remains a valid display label. */ }
                        final var row = parseProcess(pid, stat, status, command, pageKb);
                        // A reused PID must not combine metadata from different processes.
                        if (parseStat(pid, read(dir.resolve("stat"))).startTicks == row.startTicks) processes.add(row);
                    } catch (java.nio.file.NoSuchFileException disappeared) {
                        // Processes may exit during observation.
                    } catch (IOException | IllegalArgumentException error) { denied++; }
                }
            }
            final String warning = (denied == 0 ? "" : "Unreadable processes: " + denied)
                    + (truncated ? "; process list truncated" : "");
            return new SystemMonitorSnapshot(true, counter(memory, "MemTotal:"), counter(memory, "MemAvailable:"),
                    cpu.total, cpu.idle, load, SystemClock.elapsedRealtime(), ticks,
                    processes.toArray(new SystemProcessSnapshot[0]), warning);
        } catch (IOException | RuntimeException error) {
            return SystemMonitorSnapshot.unavailable(ShellAccess.usefulMessage(error));
        }
    }

    static SystemProcessSnapshot parseProcess(int pid, String stat, String status, String command, long pageKb)
            throws IOException {
        final Stat s = parseStat(pid, stat);
        final long uid = counter(status, "Uid:");
        if (uid < 0 || uid > Integer.MAX_VALUE || pageKb <= 0) throw new IOException("invalid process owner/units");
        final String name = (command == null || command.isEmpty() ? s.name : command)
                .replaceAll("[\\p{Cntrl}]", " ");
        return new SystemProcessSnapshot(pid, (int) uid, s.parent, s.startTicks, s.cpuTicks,
                s.rssPages < 0 ? -1 : Math.multiplyExact(s.rssPages, pageKb),
                name.substring(0, Math.min(256, name.length())), s.state);
    }

    record Stat(String name, String state, int parent, long startTicks, long cpuTicks, long rssPages) { }

    static Stat parseStat(int pid, String line) throws IOException {
        try {
            final int open = line.indexOf('('), close = line.lastIndexOf(')');
            if (open < 1 || close <= open || Integer.parseInt(line.substring(0, open).trim()) != pid)
                throw new IllegalArgumentException();
            final String[] f = line.substring(close + 1).trim().split("\\s+");
            if (f.length < 22) throw new IllegalArgumentException();
            final long start = Long.parseLong(f[19]);
            final long user = Long.parseLong(f[11]), system = Long.parseLong(f[12]);
            if (start < 0 || user < 0 || system < 0) throw new IllegalArgumentException();
            return new Stat(line.substring(open + 1, close), f[0], Integer.parseInt(f[1]),
                    start, Math.addExact(user, system), Long.parseLong(f[21]));
        } catch (RuntimeException error) { throw new IOException("invalid process stat", error); }
    }

    static long counter(String text, String key) throws IOException {
        for (String line : text.split("\n")) {
            if (line.startsWith(key)) {
                try { return Long.parseLong(line.substring(key.length()).trim().split("\\s+", 2)[0]); }
                catch (NumberFormatException error) { throw new IOException("invalid " + key, error); }
            }
        }
        throw new IOException("missing " + key);
    }

    static Cpu parseCpuStat(String line) throws IOException {
        try {
            String[] f = line.trim().split("\\s+");
            if (f.length < 5 || !"cpu".equals(f[0])) throw new IllegalArgumentException();
            long total = 0;
            for (int i = 1; i < Math.min(f.length, 9); i++) {
                long value = Long.parseLong(f[i]);
                if (value < 0) throw new IllegalArgumentException();
                total = Math.addExact(total, value);
            }
            return new Cpu(total, Math.addExact(Long.parseLong(f[4]), f.length > 5 ? Long.parseLong(f[5]) : 0));
        } catch (RuntimeException error) { throw new IOException("invalid aggregate /proc/stat", error); }
    }

    private static String read(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            return new String(in.readNBytes(MAX_FILE_BYTES), StandardCharsets.UTF_8);
        }
    }
    record Cpu(long total, long idle) { }
    private SystemMonitorReader() { }
}
