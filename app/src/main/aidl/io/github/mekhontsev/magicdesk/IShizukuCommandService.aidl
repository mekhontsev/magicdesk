package io.github.mekhontsev.magicdesk;

import android.os.ParcelFileDescriptor;

interface IShizukuCommandService {
    void destroy() = 16777114;

    int uid() = 1;

    String execute(String command) = 2;

    String probeCapabilities() = 3;

    ParcelFileDescriptor openStream(String command, long requestId) = 4;

    void closeStream(long requestId) = 5;
}
