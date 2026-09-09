package io.github.mekhontsev.magicdesk;

import android.os.ParcelFileDescriptor;

interface IAppUpdateWorker {
    void begin(int sessionId, int userId, String updateId, in ParcelFileDescriptor receipt) = 0;
    void destroy() = 16777114;
}
