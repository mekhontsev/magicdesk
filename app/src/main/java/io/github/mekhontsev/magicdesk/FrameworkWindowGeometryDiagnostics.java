package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/** Bounded, read-only shell capture, invoked only by explicit report generation. */
final class FrameworkWindowGeometryDiagnostics {
    static final int MAX_TASKS = 32;

    private FrameworkWindowGeometryDiagnostics() { }

    static List<WindowGeometryReportParser.Target> targets(FrameworkTaskSnapshot[] tasks) {
        if (tasks == null || tasks.length > MAX_TASKS) {
            throw new IllegalArgumentException("invalid geometry task selection");
        }
        List<WindowGeometryReportParser.Target> result = new ArrayList<>();
        for (FrameworkTaskSnapshot task : tasks) {
            if (task == null || task.taskId < 0 || task.packageName.isEmpty()) continue;
            result.add(new WindowGeometryReportParser.Target(task.taskId, task.packageName));
        }
        return result;
    }

    static String capture(FrameworkTaskSnapshot[] tasks) {
        var targets = targets(tasks);
        StringBuilder report = new StringBuilder();
        append(report, "WindowManager", new String[]{"window", "windows"}, targets,
                WindowGeometryReportParser::windows);
        append(report, "SurfaceFlinger", new String[]{"SurfaceFlinger", "--layers"}, targets,
                WindowGeometryReportParser::surfaces);
        return report.toString();
    }

    private static void append(StringBuilder report, String source, String[] arguments,
            List<WindowGeometryReportParser.Target> targets,
            BiFunction<String, List<WindowGeometryReportParser.Target>, String> parser) {
        long started = System.currentTimeMillis();
        report.append("### ").append(source).append('\n')
                .append("startedAtMillis=").append(started).append('\n');
        try {
            List<String> command = new ArrayList<>(List.of("/system/bin/dumpsys", "-t", "3"));
            command.addAll(List.of(arguments));
            var result = BoundedProcessRunner.run(new ProcessBuilder(command)
                    .redirectErrorStream(true).start(), 4_000L, 2 * 1024 * 1024);
            report.append("exitCode=").append(result.exitCode)
                    .append(" sourceTruncated=").append(result.truncated).append('\n');
            if (result.exitCode == 0) report.append(parser.apply(result.output, targets));
            else report.append("Unavailable: diagnostic command failed\n");
        } catch (IOException | InterruptedException | RuntimeException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            // Process-runner exceptions may contain unfiltered window titles or input history.
            report.append("Unavailable: ").append(error.getClass().getSimpleName()).append('\n');
        }
        report.append("completedAtMillis=").append(System.currentTimeMillis()).append("\n\n");
    }
}
