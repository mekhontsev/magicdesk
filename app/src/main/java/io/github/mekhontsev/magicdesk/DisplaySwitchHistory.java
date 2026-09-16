package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Process-local MRU of exact display identities, including an output's own content. */
final class DisplaySwitchHistory {
    private final Map<String, List<String>> outputs = new LinkedHashMap<>();

    void retainDisplays(List<String> live) {
        outputs.keySet().retainAll(live);
        for (var history : outputs.values()) history.retainAll(live);
    }

    List<String> order(String output, String current, List<String> available) {
        final List<String> history = outputs.computeIfAbsent(output, ignored -> new ArrayList<>());
        final List<String> ordered = new ArrayList<>();
        if (available.contains(current)) ordered.add(current);
        for (String id : history) if (available.contains(id) && !ordered.contains(id)) ordered.add(id);
        for (String id : available) if (!ordered.contains(id)) ordered.add(id);
        return ordered;
    }

    void committed(String output, String previous, String selected) {
        final List<String> history = outputs.computeIfAbsent(output, ignored -> new ArrayList<>());
        history.remove(previous);
        history.add(0, previous);
        history.remove(selected);
        history.add(0, selected);
    }
}
