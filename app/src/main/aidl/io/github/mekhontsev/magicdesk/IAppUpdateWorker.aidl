package io.github.mekhontsev.magicdesk;

import android.os.ParcelFileDescriptor;

interface IAppUpdateWorker {
    void begin(int sessionId, int userId, String updateId, in ParcelFileDescriptor receipt) = 0;
    int uid() = 1;
    String sourceId() = 2;
    void destroy() = 16777114;
}
