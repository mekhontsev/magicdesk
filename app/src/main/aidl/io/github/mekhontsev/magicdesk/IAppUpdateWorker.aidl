package io.github.mekhontsev.magicdesk;

import android.os.ParcelFileDescriptor;
import android.os.Bundle;

interface IAppUpdateWorker {
    void begin(int sessionId, int userId, String updateId, in ParcelFileDescriptor receipt, in Bundle powerState) = 0;
    int uid() = 1;
    String sourceId() = 2;
    Bundle claimPowerState(String updateId) = 3;
    void completeResume(String updateId, String error) = 4;
    void destroy() = 16777114;
}
