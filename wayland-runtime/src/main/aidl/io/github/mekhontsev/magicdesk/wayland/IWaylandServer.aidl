package io.github.mekhontsev.magicdesk.wayland;
import io.github.mekhontsev.magicdesk.wayland.IWaylandEvents;

interface IWaylandServer {
    void retain(IWaylandEvents owner);
    oneway void openOutput(long output, long window, long shellOwner, int width, int height);
    oneway void resize(long output, int width, int height);
    oneway void setVisible(long output, boolean visible);
    oneway void releaseOutput(long output);
    oneway void focus(long output, boolean focused);
    oneway void pointer(long output, double x, double y);
    oneway void button(long output, int button, boolean down);
    oneway void scroll(long output, double horizontal, double vertical);
    oneway void key(long output, int androidKey, int scanCode, boolean down);
    oneway void frameConsumed(long output, long serial);
    oneway void closeWindow(long window, boolean force);
    oneway void stop();
    oneway void openClient(long request);
    oneway void setShellOutput(long owner, int width, int height);
    oneway void releaseShell(long owner);
    oneway void configureShell(long owner, long surface, long revision, int x, int y, int width, int height);
}
