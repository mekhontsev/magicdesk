package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Typed association state from the existing one-shot InputManager snapshot. */
final class FrameworkInputRoutingSnapshot {
    private static final Pattern PORT = Pattern.compile("port:\\s+(.+?)\\s+display:\\s+(\\d+)");
    private static final Pattern UNIQUE = Pattern.compile("port:\\s+(.+?)\\s+uniqueId:\\s+(.+)");
    final Map<String, Integer> staticPorts = new LinkedHashMap<>();
    final Map<String, Integer> runtimePorts = new LinkedHashMap<>();
    final Map<String, String> uniqueIds = new LinkedHashMap<>();

    static FrameworkInputRoutingSnapshot parse(final String dump) throws IOException {
        if (dump == null || !dump.contains("Input Manager State:")
                || !dump.contains("Event Hub State:")) {
            throw new IOException("InputManager association state is unavailable");
        }
        final FrameworkInputRoutingSnapshot state = new FrameworkInputRoutingSnapshot();
        String section = "";
        for (final String line : dump.split("\\r?\\n")) {
            final String text = line.trim();
            if (text.endsWith(":")) {
                section = text;
                continue;
            }
            if (("Runtime Associations:".equals(section)
                    || "Static Associations:".equals(section)) && text.startsWith("port:")) {
                final Matcher port = PORT.matcher(text);
                if (!port.matches()) throw new IOException("malformed input port association");
                final int target;
                try {
                    target = Integer.parseInt(port.group(2));
                } catch (NumberFormatException error) {
                    throw new IOException("invalid input display port", error);
                }
                if (target > 255) throw new IOException("input display port is out of range");
                final Map<String, Integer> ports = "Runtime Associations:".equals(section)
                        ? state.runtimePorts : state.staticPorts;
                ports.put(port.group(1), target);
            } else if ("Unique Id Associations:".equals(section) && text.startsWith("port:")) {
                final Matcher unique = UNIQUE.matcher(text);
                if (!unique.matches()) throw new IOException("malformed input unique-id association");
                state.uniqueIds.put(unique.group(1), unique.group(2));
            }
        }
        return state;
    }

    Map<String, String> labels() {
        final Map<String, String> result = new LinkedHashMap<>();
        runtimePorts.forEach((port, target) -> result.put(port, "display:" + target));
        uniqueIds.forEach((port, target) -> result.merge(port, "uniqueId:" + target,
                (physical, unique) -> physical + "," + unique));
        return result;
    }
}
