package io.github.mekhontsev.magicdesk.wayland;
import io.github.mekhontsev.magicdesk.wayland.IWaylandEvents;
import android.os.ParcelFileDescriptor;

interface IWaylandServer {
    void retain(IWaylandEvents owner);
    oneway void openOutput(long output, long window, long shellOwner, long parentOutput, int width, int height);
    oneway void viewport(long output, long generation, int x, int y, int width, int height, boolean configureClient);
    oneway void setVisible(long output, boolean visible);
    oneway void scale(long output, double scale);
    oneway void releaseOutput(long output);
    oneway void focus(long output, boolean focused);
    oneway void pointer(long output, double x, double y);
    oneway void button(long output, int button, boolean down);
    oneway void scroll(long output, double horizontal, double vertical);
    oneway void key(long output, int androidKey, int scanCode, boolean down);
    oneway void text(long output, long editor, in byte[] utf8, boolean composing, int cursor);
    oneway void deleteText(long output, long editor, long revision, int before, int after, in byte[] preedit, int cursor);
    oneway void frameConsumed(long output, long serial);
    oneway void closeWindow(long window, boolean force);
    oneway void confirmFullscreen(long window, long serial, boolean fullscreen);
    oneway void contentActive(boolean active);
    oneway void publishContent(int channel, long id, String types);
    oneway void readContent(int channel, long id, long request, String type);
    oneway void replyContent(long request, in @nullable ParcelFileDescriptor data);
    oneway void drag(long output, int action, long offer, double x, double y, boolean accepted);
    @nullable ParcelFileDescriptor openContentFile(String uri);
    String importContentFile(in ParcelFileDescriptor file, String name);
    oneway void stop();
    oneway void openClient(long request);
    oneway void setShellOutput(long owner, int width, int height);
    oneway void releaseShell(long owner);
    oneway void configureShell(long owner, long surface, long revision, int x, int y, int width, int height);
    oneway void publishToplevel(long owner, long id, String title, String appId, boolean active, boolean maximized, boolean fullscreen, boolean removed);
}
