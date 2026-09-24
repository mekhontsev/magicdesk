package io.github.mekhontsev.magicdesk.x11;

import io.github.mekhontsev.magicdesk.hosted.HostedWindowGesture;

final class X11WindowGestures {
    private X11WindowGestures() { }
    static HostedWindowGesture decode(int direction) {
        return switch (direction) {
            case 0 -> HostedWindowGesture.NORTH_WEST;
            case 1 -> HostedWindowGesture.NORTH;
            case 2 -> HostedWindowGesture.NORTH_EAST;
            case 3 -> HostedWindowGesture.EAST;
            case 4 -> HostedWindowGesture.SOUTH_EAST;
            case 5 -> HostedWindowGesture.SOUTH;
            case 6 -> HostedWindowGesture.SOUTH_WEST;
            case 7 -> HostedWindowGesture.WEST;
            case 8 -> HostedWindowGesture.MOVE;
            case 11 -> HostedWindowGesture.CANCEL;
            default -> null;
        };
    }
}
