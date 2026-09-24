package io.github.mekhontsev.magicdesk.wayland;
import android.os.ParcelFileDescriptor;
import io.github.mekhontsev.magicdesk.wayland.WaylandShellSurface;
import io.github.mekhontsev.magicdesk.wayland.WaylandViewGeometry;

oneway interface IWaylandEvents {
    void window(long id, long parent, String title, String appId, boolean mapped,
            int width, int height, long requestSerial, boolean fullscreen, boolean removed);
    void frame(long output, long serial, long generation, in @nullable ParcelFileDescriptor pixels, int width, int height);
    void failed(long output, long generation, String message);
    void client(long request, in @nullable ParcelFileDescriptor connection, String error);
    void shellSurface(long owner, long id, in @nullable WaylandShellSurface surface);
    void shellOutput(long owner, int width, int height, String error);
    void geometry(long shellOwner, in WaylandViewGeometry geometry);
    void toplevelAction(long shellOwner, long id, int action);
    void textInput(long output, boolean enabled);
    void contentOffer(int channel, long id, long output, String types);
    void contentRequest(int channel, long id, long request, String type);
    void contentReply(long request, in @nullable ParcelFileDescriptor data);
    void dragEvent(long output, long offer, boolean finished, boolean accepted);
    void cursor(long output, in @nullable int[] pixels, int width, int height, int hotspotX, int hotspotY, boolean hidden);
}
