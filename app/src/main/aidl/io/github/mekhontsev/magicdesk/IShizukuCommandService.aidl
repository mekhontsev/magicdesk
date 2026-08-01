package io.github.mekhontsev.magicdesk;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;

interface IShizukuCommandService {
    void destroy() = 16777114;

    int uid() = 1;

    String execute(String command) = 2;

    String probeCapabilities() = 3;

    void closeStream(long requestId) = 5;

    void writeStream(long requestId, String line) = 6;

    String updateHardwareKeyboardLayout(
        String mode, String currentDescriptor) = 7;

    ParcelFileDescriptor openSystemWallpaper() = 8;

    ParcelFileDescriptor openHeartbeatStream(
        String command, long requestId, IBinder ownerToken) = 9;

    ParcelFileDescriptor openOwnedStream(
        String command, long requestId, IBinder ownerToken) = 10;
}
