package io.github.mekhontsev.magicdesk;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import io.github.mekhontsev.magicdesk.DesktopFileInfo;
import io.github.mekhontsev.magicdesk.IDesktopFolderObserverCallback;
import io.github.mekhontsev.magicdesk.ITaskObserverCallback;

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

    void startTaskObserver(ITaskObserverCallback callback) = 11;

    void configureTaskObserver(
        ITaskObserverCallback callback,
        int displayId,
        int displayLeft,
        int displayTop,
        int displayRight,
        int displayBottom,
        int workLeft,
        int workTop,
        int workRight,
        int workBottom) = 12;

    void focusTaskStack(
        ITaskObserverCallback callback,
        long sequence,
        int displayId,
        in int[] taskIds) = 13;

    void stopTaskObserver(ITaskObserverCallback callback) = 14;

    boolean capturePointerPosition() = 15;

    void restorePointerPositionIfDisplaced() = 16;

    boolean injectPointerClick(int displayId, int button) = 17;

    int[] startInputRouting(
        int displayId,
        int expectedVirtualKeyboardCount,
        IBinder ownerToken) = 18;

    int refreshInputRouting() = 19;

    void stopInputRouting(IBinder ownerToken) = 20;

    int cleanupInputRouting() = 21;

    void startLocalDesktopNavigationGuard(IBinder ownerToken) = 22;

    void stopLocalDesktopNavigationGuard(IBinder ownerToken) = 23;

    String startDisplayRecording(
        String physicalDisplayId,
        String outputPath,
        int width,
        int height,
        int bitrateMbps,
        IBinder ownerToken) = 24;

    String stopDisplayRecording(IBinder ownerToken) = 25;

    DesktopFileInfo[] listDesktopFiles() = 26;

    ParcelFileDescriptor openDesktopFile(String relativePath, String mode) = 27;

    DesktopFileInfo createDesktopEntry(String name, boolean directory) = 28;

    DesktopFileInfo renameDesktopEntry(
        String relativePath, String newName) = 29;

    void deleteDesktopEntry(String relativePath) = 30;

    void startDesktopFolderObserver(
        IDesktopFolderObserverCallback callback) = 31;

    void stopDesktopFolderObserver(
        IDesktopFolderObserverCallback callback) = 32;

    DesktopFileInfo getDesktopFileInfo(String relativePath) = 33;

    int[] getMousePosition(int displayId) = 35;

    boolean updateMousePosition(
        int displayId, int x, int y, int action, long downTime) = 36;

    boolean updateMirrorTextInput(
        int displayId, int action, String text,
        int arg1, int arg2, int arg3) = 38;

    boolean beginMirrorTextInput(int displayId) = 39;

    void endMirrorTextInput(int displayId) = 40;

    void setPhoneTouchpadPreservation(
        ITaskObserverCallback callback, boolean enabled) = 41;

    String readDesktopState() = 42;

    void writeDesktopState(String encodedState) = 43;

    ParcelFileDescriptor openDesktopWallpaper() = 44;

    void writeDesktopWallpaper(in ParcelFileDescriptor source) = 45;

    boolean deleteDesktopWallpaper() = 46;
}
