package io.github.mekhontsev.magicdesk;

import java.util.Comparator;
import java.util.function.Function;

/** Resource sorts put unknown values last; identity breaks ties consistently between samples. */
enum TaskManagerSort {
    NAME, CPU, MEMORY, PID, TREE;

    Comparator<SystemMonitorRepository.ProcessEntry> processes() {
        final Comparator<SystemMonitorRepository.ProcessEntry> order = switch (this) {
            case NAME -> Comparator.comparing(e -> processName(e.process()), String.CASE_INSENSITIVE_ORDER);
            case CPU -> Comparator.comparingDouble(SystemMonitorRepository.ProcessEntry::cpuPercent).reversed();
            case MEMORY -> Comparator.<SystemMonitorRepository.ProcessEntry>comparingLong(e -> e.process().rssKb).reversed();
            default -> Comparator.comparingInt(e -> e.process().pid);
        };
        return order.thenComparingInt(e -> e.process().pid);
    }

    Comparator<TaskManagerApplications.Entry> applications(
            Function<TaskManagerApplications.Entry, SystemMonitorRepository.Resources> resources) {
        final Comparator<TaskManagerApplications.Entry> names = Comparator.comparing(
                TaskManagerApplications.Entry::title, String.CASE_INSENSITIVE_ORDER);
        final Comparator<TaskManagerApplications.Entry> order = switch (this) {
            case CPU -> Comparator.<TaskManagerApplications.Entry>comparingDouble(e -> resources.apply(e).cpuPercent()).reversed();
            case MEMORY -> Comparator.<TaskManagerApplications.Entry>comparingLong(e -> resources.apply(e).rssKb()).reversed();
            default -> names;
        };
        return order.thenComparing(names).thenComparing(TaskManagerApplications.Entry::id);
    }

    static String processName(SystemProcessSnapshot process) {
        return process.name.substring(process.name.lastIndexOf('/') + 1);
    }
}
