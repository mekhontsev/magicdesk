package io.github.mekhontsev.magicdesk.wayland;

import java.util.ArrayList;
import java.util.List;

/** One event-loop snapshot; surface identities are session-local, not Android tasks or widgets. */
public record WaylandWindowInspection(boolean found, boolean truncated, List<Node> nodes) {
    public record Node(long id, long parent, String role, int left, int top, int right, int bottom,
            boolean mapped, boolean enabled, boolean focused) { }
    public WaylandWindowInspection { nodes = List.copyOf(nodes); }
    static WaylandWindowInspection decode(long[] values, int limit) {
        if (values == null || values.length < 2 || (values.length - 2) % 10 != 0 || (values.length - 2) / 10 > limit)
            throw new IllegalArgumentException("Invalid inspection reply");
        var nodes = new ArrayList<Node>();
        for (int i = 2; i < values.length; i += 10) {
            String role = switch (Math.toIntExact(values[i + 2])) {
                case 0 -> "owner"; case 1 -> "popup"; case 2 -> "subsurface"; case 3 -> "surface";
                default -> throw new IllegalArgumentException("Unknown surface role");
            };
            if (values[i] <= 0 || values[i + 5] < values[i + 3] || values[i + 6] < values[i + 4])
                throw new IllegalArgumentException("Invalid surface geometry");
            nodes.add(new Node(values[i], values[i + 1], role, Math.toIntExact(values[i + 3]), Math.toIntExact(values[i + 4]),
                    Math.toIntExact(values[i + 5]), Math.toIntExact(values[i + 6]), values[i + 7] != 0, values[i + 8] != 0, values[i + 9] != 0));
        }
        return new WaylandWindowInspection(values[0] != 0, values[1] != 0, nodes);
    }
}
