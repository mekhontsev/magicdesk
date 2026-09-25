package io.github.mekhontsev.magicdesk;

import io.github.mekhontsev.magicdesk.x11.X11Session;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Main-thread launch correlation. A class hint never selects an old or ambiguous window. */
final class X11LaunchTracker {
    record Window(String session, long id) { }
    record Match(String launch, Window window) { }
    private record Observed(String scope, long generation, X11Session.Window info) { }
    private static final class Pending {
        final String scope, expectedClass;
        final long generation;
        boolean completed;
        Pending(String scope, String expectedClass, long generation) {
            this.scope = scope; this.expectedClass = expectedClass; this.generation = generation;
        }
    }
    private final Map<Window, Observed> windows = new LinkedHashMap<>();
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Set<Window> assigned = new HashSet<>();
    private long generation;

    void update(String session, String scope, List<X11Session.Window> snapshot) {
        Set<Window> live = new HashSet<>();
        for (var info : snapshot) {
            Window key = new Window(session, info.id());
            live.add(key);
            Observed previous = windows.get(key);
            windows.put(key, new Observed(scope, previous == null ? ++generation : previous.generation(), info));
        }
        windows.keySet().removeIf(key -> key.session().equals(session) && !live.contains(key));
        assigned.retainAll(windows.keySet());
        if (snapshot.stream().anyMatch(info -> !info.provisional())) pending.remove(session);
    }

    void begin(String session, String scope, String expectedClass) {
        if (!expectedClass.isEmpty()) pending.put(session, new Pending(scope, expectedClass, generation));
    }

    void completed(String session) {
        Pending launch = pending.get(session);
        if (launch != null) launch.completed = true;
    }

    void presented(String session, long id) {
        Window key = new Window(session, id);
        if (windows.containsKey(key)) assigned.add(key);
    }

    void remove(String session) {
        pending.remove(session);
        windows.keySet().removeIf(key -> key.session().equals(session));
        assigned.retainAll(windows.keySet());
    }

    boolean reserved(String session, long window) {
        Window key = new Window(session, window);
        // WM_CLASS can change from a toolkit default after the initial MapWindow.
        for (var entry : pending.entrySet()) if (eligible(entry.getKey(), entry.getValue(), key)) return true;
        return false;
    }

    List<Match> takeMatches() {
        Map<Window, List<String>> candidates = new HashMap<>();
        Map<String, List<Window>> launches = new LinkedHashMap<>();
        for (var entry : pending.entrySet()) {
            List<Window> found = new ArrayList<>();
            for (Window window : windows.keySet()) if (matches(entry.getKey(), entry.getValue(), window)) {
                found.add(window);
                candidates.computeIfAbsent(window, key -> new ArrayList<>()).add(entry.getKey());
            }
            launches.put(entry.getKey(), found);
        }
        Set<String> ambiguous = new HashSet<>();
        for (var entry : launches.entrySet()) {
            if (entry.getValue().size() > 1) ambiguous.add(entry.getKey());
            for (Window window : entry.getValue()) if (candidates.get(window).size() > 1)
                ambiguous.addAll(candidates.get(window));
        }
        // Cancellation cannot turn previously ambiguous evidence into a positive identification.
        for (String launch : ambiguous) {
            assigned.addAll(launches.get(launch));
            pending.remove(launch);
        }
        List<Match> result = new ArrayList<>();
        for (var entry : launches.entrySet()) {
            Pending launch = pending.get(entry.getKey());
            if (launch == null || !launch.completed || entry.getValue().size() != 1) continue;
            Window window = entry.getValue().get(0);
            if (candidates.get(window).size() != 1) continue;
            assigned.add(window);
            pending.remove(entry.getKey());
            result.add(new Match(entry.getKey(), window));
        }
        return result;
    }

    private boolean matches(String session, Pending launch, Window key) {
        if (!eligible(session, launch, key)) return false;
        var info = windows.get(key).info();
        return info.mapped() && info.applicationWindow() && info.matchesClass(launch.expectedClass);
    }

    private boolean eligible(String session, Pending launch, Window key) {
        Observed value = windows.get(key);
        return value != null && !assigned.contains(key) && !key.session().equals(session)
                && value.info().role() != X11Session.WindowRole.DIALOG && value.info().layout().parent() == 0
                && value.generation() > launch.generation && value.scope().equals(launch.scope);
    }
}
