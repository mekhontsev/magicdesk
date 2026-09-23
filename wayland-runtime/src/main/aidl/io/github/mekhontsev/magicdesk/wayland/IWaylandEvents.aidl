package io.github.mekhontsev.magicdesk.wayland;
import android.os.ParcelFileDescriptor;
import io.github.mekhontsev.magicdesk.wayland.WaylandShellSurface;
import io.github.mekhontsev.magicdesk.wayland.WaylandViewGeometry;

oneway interface IWaylandEvents {
    void window(long id, long parent, String title, String appId, boolean mapped,
            int width, int height, boolean removed);
    void frame(long output, long serial, long generation, in @nullable ParcelFileDescriptor pixels, int width, int height);
    void failed(long output, long generation, String message);
    void client(long request, in @nullable ParcelFileDescriptor connection, String error);
    void shellSurface(long owner, long id, in @nullable WaylandShellSurface surface);
    void shellOutput(long owner, int width, int height, String error);
    void geometry(long shellOwner, in WaylandViewGeometry geometry);
}
