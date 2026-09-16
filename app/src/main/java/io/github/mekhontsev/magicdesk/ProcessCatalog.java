package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Identity-safe process selection and tree projection, independent of the UI and Termux. */
final class ProcessCatalog {
    record Row(SystemMonitorRepository.ProcessEntry entry, int depth, boolean children) { }
    private final Map<Integer, SystemMonitorRepository.ProcessEntry> byPid = new LinkedHashMap<>();

    ProcessCatalog(List<SystemMonitorRepository.ProcessEntry> entries) {
        for (var entry : entries) byPid.put(entry.process().pid, entry);
    }

    Set<SystemProcessSnapshot.Identity> matching(Predicate<SystemProcessSnapshot> filter) {
        final var ids = new HashSet<SystemProcessSnapshot.Identity>();
        for (var e : byPid.values()) if (filter.test(e.process())) ids.add(e.process().identity());
        return Set.copyOf(ids);
    }

    Set<SystemProcessSnapshot.Identity> descendants(Set<Integer> roots, int uid) {
        final var ids = new HashSet<SystemProcessSnapshot.Identity>();
        for (var e : byPid.values()) {
            var p = e.process();
            final var visited = new HashSet<Integer>();
            while (p != null && p.uid == uid && visited.add(p.pid)) {
                if (roots.contains(p.pid)) { ids.add(e.process().identity()); break; }
                var parent = byPid.get(p.parentPid);
                p = parent != null && validParent(p, parent.process()) ? parent.process() : null;
            }
        }
        return Set.copyOf(ids);
    }

    static boolean validParent(SystemProcessSnapshot child, SystemProcessSnapshot parent) {
        return child.parentPid == parent.pid && parent.startTicks <= child.startTicks && child.pid != parent.pid;
    }

    List<Row> tree(Predicate<SystemProcessSnapshot> filter, Set<SystemProcessSnapshot.Identity> expanded,
            Comparator<SystemMonitorRepository.ProcessEntry> order) {
        final var selected = new LinkedHashMap<Integer, SystemMonitorRepository.ProcessEntry>();
        for (var entry : byPid.values()) if (filter.test(entry.process())) selected.put(entry.process().pid, entry);
        final var children = new LinkedHashMap<Integer, List<SystemMonitorRepository.ProcessEntry>>();
        final var roots = new ArrayList<SystemMonitorRepository.ProcessEntry>();
        for (var e : selected.values()) {
            var parent = selected.get(e.process().parentPid);
            if (parent == null || !validParent(e.process(), parent.process())) roots.add(e);
            else children.computeIfAbsent(parent.process().pid, ignored -> new ArrayList<>()).add(e);
        }
        roots.sort(order);
        for (var siblings : children.values()) siblings.sort(order);
        final var rows = new ArrayList<Row>();
        final var visited = new HashSet<Integer>();
        for (var root : roots) append(root, 0, children, expanded, rows, visited);
        // Broken ancestry must not hide processes or recurse forever.
        final var rooted = new HashSet<Integer>();
        for (var e : selected.values()) {
            var p = e.process();
            final var ancestry = new HashSet<Integer>();
            while (selected.containsKey(p.parentPid) && validParent(p, selected.get(p.parentPid).process())) {
                if (!ancestry.add(p.pid)) {
                    if (!visited.contains(e.process().pid) && rooted.addAll(ancestry))
                        append(e, 0, children, expanded, rows, visited);
                    break;
                }
                p = selected.get(p.parentPid).process();
            }
        }
        return rows;
    }

    private static void append(SystemMonitorRepository.ProcessEntry e, int depth,
            Map<Integer, List<SystemMonitorRepository.ProcessEntry>> children,
            Set<SystemProcessSnapshot.Identity> expanded, List<Row> rows, Set<Integer> visited) {
        if (!visited.add(e.process().pid)) return;
        var nested = children.getOrDefault(e.process().pid, List.of());
        rows.add(new Row(e, depth, !nested.isEmpty()));
        if (depth < 64 && expanded.contains(e.process().identity()))
            for (var child : nested) append(child, depth + 1, children, expanded, rows, visited);
    }
}
