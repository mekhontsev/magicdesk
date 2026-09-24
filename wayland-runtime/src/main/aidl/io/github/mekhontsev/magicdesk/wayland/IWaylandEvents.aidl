package io.github.mekhontsev.magicdesk.wayland;
import android.os.ParcelFileDescriptor;
import io.github.mekhontsev.magicdesk.hosted.HostedFrame;
import io.github.mekhontsev.magicdesk.wayland.WaylandShellSurface;
import io.github.mekhontsev.magicdesk.wayland.WaylandViewGeometry;

oneway interface IWaylandEvents {
    void window(long id, long parent, String title, String appId, boolean mapped,
            int width, int height, int minWidth, int minHeight, int maxWidth, int maxHeight,
            long requestSerial, boolean fullscreen, long maximizeSerial, boolean maximized, boolean removed);
    void windowGesture(long window, int edges);
    void frame(long output, long serial, long generation, in @nullable HostedFrame frame);
    void failed(long output, long generation, String message);
    void client(long request, in @nullable ParcelFileDescriptor connection, String error);
    void shellSurface(long owner, long id, in @nullable WaylandShellSurface surface);
    void shellOutput(long owner, int width, int height, String error);
    void geometry(long shellOwner, in WaylandViewGeometry geometry);
    void toplevelAction(long shellOwner, long id, int action);
    void textInput(long output, long editor, long revision, in @nullable byte[] surrounding,
            int cursor, int anchor, int purpose, int hints, boolean caretValid,
            float left, float top, float right, float bottom);
    void contentOffer(int channel, long id, long output, String types);
    void contentRequest(int channel, long id, long request, String type);
    void contentReply(long request, in @nullable ParcelFileDescriptor data);
    void dragEvent(long output, long offer, boolean finished, boolean accepted);
    void cursor(long output, in @nullable int[] pixels, int width, int height, int hotspotX, int hotspotY, boolean hidden);
}
