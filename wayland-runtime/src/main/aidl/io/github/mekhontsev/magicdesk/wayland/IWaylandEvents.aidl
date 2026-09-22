package io.github.mekhontsev.magicdesk.wayland;
import android.os.ParcelFileDescriptor;

oneway interface IWaylandEvents {
    void window(long id, long parent, String title, String appId, boolean mapped,
            int width, int height, boolean removed);
    void frame(long output, long serial, in @nullable ParcelFileDescriptor pixels, int width, int height);
    void failed(long output, String message);
    void client(long request, in @nullable ParcelFileDescriptor connection, String error);
}