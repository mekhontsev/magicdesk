package io.github.mekhontsev.magicdesk.x11;

import android.os.IBinder;

interface IX11Server {
    void retain(IBinder owner);
    ParcelFileDescriptor openConnection();
    oneway void acceptClient(in ParcelFileDescriptor socket);
    oneway void stop();
    oneway void setColorScheme(int value);
    ParcelFileDescriptor openContentFile(String uri);
    String importContentFile(in ParcelFileDescriptor source, String name);
}
