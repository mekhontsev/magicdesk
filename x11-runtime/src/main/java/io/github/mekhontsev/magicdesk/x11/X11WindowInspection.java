package io.github.mekhontsev.magicdesk.x11;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** One read-only X-server observation. Bounds use X root pixels, not Android pixels. */
public record X11WindowInspection(long windowId, boolean found, boolean truncated,
        Focus focus, Bounds screenBounds, List<Node> windows) {
    public X11WindowInspection { windows = List.copyOf(windows); }
    public record Bounds(int left, int top, int right, int bottom) { }
    public record Focus(String kind, long windowId) { }
    public enum Type { UNKNOWN, NORMAL, DIALOG, MENU, DROPDOWN, POPUP, TOOLTIP, SPLASH, UTILITY, OTHER }
    public record Node(long id, long parentId, long transientFor, long clientLeader,
            String title, Type type, Bounds bounds, boolean mapped, boolean realized,
            boolean inputOnly, boolean overrideRedirect, boolean modal) {
        // Native wire values end here; callers receive immutable typed state.
        private Node(int id, int parent, int transientFor, int leader, byte[] title,
                int x, int y, int width, int height, int flags, int type) {
            this(Integer.toUnsignedLong(id), Integer.toUnsignedLong(parent), Integer.toUnsignedLong(transientFor),
                    Integer.toUnsignedLong(leader), new String(title, StandardCharsets.UTF_8),
                    type >= 0 && type < Type.values().length ? Type.values()[type] : Type.UNKNOWN,
                    new Bounds(x, y, x + width, y + height), (flags & 1) != 0, (flags & 2) != 0,
                    (flags & 4) != 0, (flags & 8) != 0, (flags & 16) != 0);
        }
    }
}
