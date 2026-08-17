package io.github.mekhontsev.magicdesk;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads bounded resource data while running under the shell service identity. */
final class SystemMonitorReader {
    private static final String DUMPSYS = "/system/bin/dumpsys";
    private static final long COMMAND_TIMEOUT_MILLIS = 5_000L;
    private static final int MAX_OUTPUT_BYTES = 512 * 1024;
    private static final Pattern CPU_PROCESS = Pattern.compile(
            "^\\s*([0-9]+(?:\\.[0-9]+)?)%\\s+[0-9]+/(.+?):\\s.*$");
    private static final Pattern MEMORY_PROCESS = Pattern.compile(
            "^\\s*([0-9,]+)K:\\s+(.+?)\\s+\\(pid\\s+[0-9]+.*$");

    private SystemMonitorReader() {
    }

    static SystemMonitorSnapshot read(final boolean includeProcessMemory) {
        try {
            final Memory memory = readMemory();
            final Cpu cpu = readCpu();
            final float load = readLoadAverage();
            final Map<String, MutableProcess> processes = new LinkedHashMap<>();
            String warning = "";
            try {
                parseCpuInfo(runDumpsys("cpuinfo"), processes);
            } catch (IOException error) {
                warning = "process CPU unavailable: " + usefulMessage(error);
            }
            if (includeProcessMemory) {
                try {
                    parseProcessMemory(
                            runDumpsys("meminfo", "--local"),
                            processes);
                } catch (IOException error) {
                    final String message = "process memory unavailable: "
                            + usefulMessage(error);
                    warning = warning.isEmpty()
                            ? message : warning + "; " + message;
                }
            }
            final List<SystemProcessSnapshot> result = new ArrayList<>();
            for (final Map.Entry<String, MutableProcess> entry
                    : processes.entrySet()) {
                result.add(new SystemProcessSnapshot(
                        entry.getKey(),
                        entry.getValue().cpuPercent,
                        entry.getValue().pssKb));
            }
            return new SystemMonitorSnapshot(
                    true,
                    memory.totalKb,
                    memory.availableKb,
                    cpu.total,
                    cpu.idle,
                    load,
                    result.toArray(new SystemProcessSnapshot[0]),
                    warning);
        } catch (IOException | RuntimeException error) {
            return SystemMonitorSnapshot.unavailable(usefulMessage(error));
        }
    }

    static void parseCpuInfo(
            final String output,
            final Map<String, MutableProcess> processes) {
        if (output == null) {
            return;
        }
        for (final String line : output.split("\\r?\\n")) {
            final Matcher matcher = CPU_PROCESS.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            try {
                final float cpu = Float.parseFloat(matcher.group(1));
                final String name = matcher.group(2).trim();
                if (!name.isEmpty()) {
                    process(processes, name).addCpu(cpu);
                }
            } catch (NumberFormatException ignored) {
                // One malformed process row must not discard the snapshot.
            }
        }
    }

    static void parseProcessMemory(
            final String output,
            final Map<String, MutableProcess> processes) {
        if (output == null) {
            return;
        }
        boolean inProcessSection = false;
        for (final String line : output.split("\\r?\\n")) {
            if ("Total PSS by process:".equals(line.trim())) {
                inProcessSection = true;
                continue;
            }
            if (inProcessSection && line.startsWith("Total PSS by ")) {
                break;
            }
            if (!inProcessSection) {
                continue;
            }
            final Matcher matcher = MEMORY_PROCESS.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            try {
                final long pssKb = Long.parseLong(
                        matcher.group(1).replace(",", ""));
                final String name = matcher.group(2).trim();
                if (!name.isEmpty()) {
                    process(processes, name).addPss(pssKb);
                }
            } catch (NumberFormatException ignored) {
                // One malformed process row must not discard the snapshot.
            }
        }
    }

    private static Memory readMemory() throws IOException {
        long total = -1L;
        long available = -1L;
        try (BufferedReader reader = new BufferedReader(
                new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("MemTotal:")) {
                    total = parseFirstLong(line);
                } else if (line.startsWith("MemAvailable:")) {
                    available = parseFirstLong(line);
                }
                if (total >= 0L && available >= 0L) {
                    break;
                }
            }
        }
        if (total < 0L || available < 0L) {
            throw new IOException("incomplete /proc/meminfo");
        }
        return new Memory(total, available);
    }

    private static Cpu readCpu() throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new FileReader("/proc/stat"))) {
            final String line = reader.readLine();
            if (line == null || !line.startsWith("cpu ")) {
                throw new IOException("missing aggregate /proc/stat row");
            }
            final String[] fields = line.trim().split("\\s+");
            long total = 0L;
            for (int index = 1; index < fields.length; index++) {
                total += Long.parseLong(fields[index]);
            }
            final long idle = Long.parseLong(fields[4])
                    + (fields.length > 5 ? Long.parseLong(fields[5]) : 0L);
            return new Cpu(total, idle);
        } catch (NumberFormatException error) {
            throw new IOException("invalid /proc/stat", error);
        }
    }

    private static float readLoadAverage() throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new FileReader("/proc/loadavg"))) {
            final String line = reader.readLine();
            if (line == null) {
                throw new IOException("empty /proc/loadavg");
            }
            return Float.parseFloat(line.trim().split("\\s+")[0]);
        } catch (NumberFormatException error) {
            throw new IOException("invalid /proc/loadavg", error);
        }
    }

    private static long parseFirstLong(final String line) throws IOException {
        final String[] fields = line.trim().split("\\s+");
        if (fields.length < 2) {
            throw new IOException("invalid memory counter: " + line);
        }
        try {
            return Long.parseLong(fields[1]);
        } catch (NumberFormatException error) {
            throw new IOException("invalid memory counter: " + line, error);
        }
    }

    private static String runDumpsys(final String... arguments)
            throws IOException {
        final String[] command = new String[arguments.length + 1];
        command[0] = DUMPSYS;
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            final BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                    process,
                    COMMAND_TIMEOUT_MILLIS,
                    MAX_OUTPUT_BYTES);
            if (result.exitCode != 0) {
                throw new IOException(String.format(
                        Locale.ROOT,
                        "dumpsys %s failed %d: %s",
                        arguments.length == 0 ? "" : arguments[0],
                        result.exitCode,
                        result.output.trim()));
            }
            return result.output;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("dumpsys interrupted", error);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static MutableProcess process(
            final Map<String, MutableProcess> processes,
            final String name) {
        MutableProcess process = processes.get(name);
        if (process == null) {
            process = new MutableProcess();
            processes.put(name, process);
        }
        return process;
    }

    private static String usefulMessage(final Throwable error) {
        final String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message.trim();
    }

    static final class MutableProcess {
        float cpuPercent = -1f;
        long pssKb = -1L;

        void addCpu(final float value) {
            cpuPercent = cpuPercent < 0f ? value : cpuPercent + value;
        }

        void addPss(final long value) {
            pssKb = pssKb < 0L ? value : pssKb + value;
        }
    }

    private static final class Memory {
        final long totalKb;
        final long availableKb;

        Memory(final long totalKb, final long availableKb) {
            this.totalKb = totalKb;
            this.availableKb = availableKb;
        }
    }

    private static final class Cpu {
        final long total;
        final long idle;

        Cpu(final long total, final long idle) {
            this.total = total;
            this.idle = idle;
        }
    }
}
