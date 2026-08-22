package io.github.mekhontsev.magicdesk;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import io.github.mekhontsev.magicdesk.DesktopFileInfo;
import io.github.mekhontsev.magicdesk.IDesktopFolderObserverCallback;
import io.github.mekhontsev.magicdesk.IFileOperationCallback;
import io.github.mekhontsev.magicdesk.IFileSearchCallback;
import io.github.mekhontsev.magicdesk.IShellDirectoryObserverCallback;
import io.github.mekhontsev.magicdesk.ITaskObserverCallback;
import io.github.mekhontsev.magicdesk.SelfTestTaskStackReport;
import io.github.mekhontsev.magicdesk.ShellFileInfo;
import io.github.mekhontsev.magicdesk.ShellFilePage;
import io.github.mekhontsev.magicdesk.SystemMonitorSnapshot;

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
        int workBottom,
        boolean managedTaskArea,
        int desktopHostTaskId) = 12;

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
        String audioMode,
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

    boolean routeImeToPhone(int displayId) = 47;

    void refreshTaskCaption(
        ITaskObserverCallback callback,
        int displayId,
        int taskId,
        int sourceId) = 48;

    ShellFilePage listShellDirectory(
        String absolutePath,
        int offset,
        int limit,
        boolean showHidden,
        int sortMode,
        boolean ascending) = 49;

    ShellFileInfo getShellFileInfo(String absolutePath) = 50;

    ParcelFileDescriptor openShellFile(String absolutePath, String mode) = 51;

    ShellFileInfo createShellEntry(
        String parentPath, String name, boolean directory) = 52;

    ShellFileInfo renameShellEntry(
        String absolutePath, String newName) = 53;

    long startShellFileOperation(
        int operation,
        in String[] sourcePaths,
        String destinationDirectory,
        IFileOperationCallback callback,
        IBinder ownerToken) = 54;

    void cancelShellFileOperation(long operationId) = 55;

    ParcelFileDescriptor openVerifiedShellFile(
        String absolutePath,
        String mode,
        long deviceId,
        long inode) = 56;

    ShellFileInfo createAvailableShellEntry(
        String parentPath, String name, boolean directory) = 57;

    void setPreferredFileHandler(
        String mimeType,
        in String[] candidateComponents,
        String selectedComponent,
        int match) = 58;

    String getSelectedFileHandler(
        String mimeType,
        String dataUri) = 59;

    void setExternalTaskMigrationProtection(
        ITaskObserverCallback callback, boolean enabled) = 60;

    boolean restoreFullscreenTask(
        ITaskObserverCallback callback,
        int displayId,
        int taskId,
        int left,
        int top,
        int right,
        int bottom) = 61;

    void startSelfTestTaskStackGuard(
        ITaskObserverCallback callback,
        int displayId,
        int hostTaskId,
        String stage) = 62;

    void setSelfTestTaskStackGuardStage(
        ITaskObserverCallback callback,
        String stage) = 63;

    SelfTestTaskStackReport stopSelfTestTaskStackGuard(
        ITaskObserverCallback callback) = 64;

    boolean closeFullscreenTask(
        ITaskObserverCallback callback,
        int displayId,
        int taskId) = 65;

    void startShellDirectoryObserver(
        String absolutePath,
        IShellDirectoryObserverCallback callback) = 66;

    void stopShellDirectoryObserver(
        IShellDirectoryObserverCallback callback) = 67;

    long startShellFileSearch(
        String rootPath,
        String query,
        boolean showHidden,
        int maxResults,
        IFileSearchCallback callback,
        IBinder ownerToken) = 68;

    void cancelShellFileSearch(long searchId) = 69;

    SystemMonitorSnapshot readSystemMonitorSnapshot(
        boolean includeProcessMemory) = 70;

    void refreshPointerViewport() = 71;

    boolean beginAppFullscreenTask(
        ITaskObserverCallback callback,
        int displayId,
        int taskId,
        int restoreLeft,
        int restoreTop,
        int restoreRight,
        int restoreBottom) = 72;

    int launchWindowedTask(
        ITaskObserverCallback callback,
        int displayId,
        String intentUri,
        int left,
        int top,
        int right,
        int bottom) = 73;

    void placeTaskInDesktopArea(
        ITaskObserverCallback callback,
        int taskId,
        int sourceDisplayId,
        int targetDisplayId,
        int left,
        int top,
        int right,
        int bottom) = 74;

    int launchDesktopHost(int displayId, String intentUri) = 75;

    boolean closeDesktopTask(
        ITaskObserverCallback callback,
        int displayId,
        int taskId,
        int focusTaskId) = 76;

    boolean removeDesktopPackageTasks(
        ITaskObserverCallback callback,
        int displayId,
        String packageName,
        int focusTaskId) = 77;

    void launchTaskAction(
        ITaskObserverCallback callback,
        int displayId,
        int taskId,
        String intentUri) = 78;

    boolean beginFullscreenTask(
        ITaskObserverCallback callback,
        int displayId,
        int taskId) = 79;

    int launchFullscreenTaskInDesktopArea(
        ITaskObserverCallback callback,
        int displayId,
        String intentUri) = 80;

    void placeFullscreenTaskInDesktopArea(
        ITaskObserverCallback callback,
        int taskId,
        int sourceDisplayId,
        int targetDisplayId) = 81;

    ParcelFileDescriptor openDisplayCapture(
        String captureSource,
        int left,
        int top,
        int right,
        int bottom,
        int outputWidth,
        int outputHeight) = 82;

    int[] captureDisplayPixels(
        String captureSource,
        in int[] xCoordinates,
        in int[] yCoordinates) = 83;

}
