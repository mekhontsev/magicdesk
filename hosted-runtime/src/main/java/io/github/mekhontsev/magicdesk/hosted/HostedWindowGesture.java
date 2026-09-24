package io.github.mekhontsev.magicdesk.hosted;

/** Semantic client-decoration request. Protocol serial validation belongs to the backend. */
public enum HostedWindowGesture {
    MOVE(false, false, false, false), NORTH(false, true, false, false), SOUTH(false, false, false, true),
    WEST(true, false, false, false), EAST(false, false, true, false),
    NORTH_WEST(true, true, false, false), NORTH_EAST(false, true, true, false),
    SOUTH_WEST(true, false, false, true), SOUTH_EAST(false, false, true, true);

    public final boolean left, top, right, bottom;
    HostedWindowGesture(boolean left, boolean top, boolean right, boolean bottom) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
    }
}
