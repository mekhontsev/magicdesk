package io.github.mekhontsev.magicdesk.x11;

import android.os.IBinder;

interface IX11Server {
    void retain(IBinder owner);
    ParcelFileDescriptor openConnection();
    oneway void stop();
    ParcelFileDescriptor openContentFile(String uri);
    String importContentFile(in ParcelFileDescriptor source, String name);
}
