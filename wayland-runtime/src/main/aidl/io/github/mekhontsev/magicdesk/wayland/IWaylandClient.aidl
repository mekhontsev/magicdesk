package io.github.mekhontsev.magicdesk.wayland;
import android.os.ParcelFileDescriptor;
import io.github.mekhontsev.magicdesk.wayland.IWaylandClientReceipt;

oneway interface IWaylandClient {
    void deliver(in ParcelFileDescriptor connection, IWaylandClientReceipt receipt);
}
