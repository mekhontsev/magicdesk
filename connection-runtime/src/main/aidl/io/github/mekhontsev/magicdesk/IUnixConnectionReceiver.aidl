package io.github.mekhontsev.magicdesk;
import android.os.ParcelFileDescriptor;
oneway interface IUnixConnectionReceiver {
    void accepted(long serial, in ParcelFileDescriptor socket);
    void failed(String message);
}
