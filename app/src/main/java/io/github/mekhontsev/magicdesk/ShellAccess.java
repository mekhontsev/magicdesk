package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

import rikka.shizuku.Shizuku;

public final class ShellAccess {
    static final int REQUEST_PERMISSION_CODE = 7104;
    static final int ROOT_UID = 0;
    static final int SHELL_UID = 2000;
    static final String MANAGER_PACKAGE = "moe.shizuku.privileged.api";
    private static final String DOWNLOAD_URL = "https://shizuku.rikka.app/download/";
    private static final AtomicLong NEXT_STREAM_ID =
            new AtomicLong();
    private static final Set<StateListener> STATE_LISTENERS =
            new CopyOnWriteArraySet<>();

    private static final ShellServiceConnection SERVICE_CONNECTION =
            new ShellServiceConnection(() -> {
                // The Shizuku manager may stay ready while its per-app
                // command service is recreated. Runtime reconciliation must
                // still run after that second connection becomes usable.
                publish(inspectNow(), true);
            });
    private static boolean sInitialized;
    private static volatile Snapshot sSnapshot = Snapshot.unavailable(
            false, "Shizuku access is not initialized");

    interface StateListener {
        void onShellStateChanged(Snapshot snapshot);
    }

    private static final Shizuku.OnBinderReceivedListener BINDER_RECEIVED = () -> {
        clearService();
        refresh();
    };
    private static final Shizuku.OnBinderDeadListener BINDER_DEAD = () -> {
        clearService();
        publish(Snapshot.unavailable(
                sSnapshot.installed,
                "Shizuku server is not running"));
    };
    private static final Shizuku.OnRequestPermissionResultListener
            PERMISSION_RESULT = (requestCode, grantResult) -> {
                if (requestCode == REQUEST_PERMISSION_CODE) {
                    refresh();
                }
            };

    private ShellAccess() {
    }

    static void initialize() {
        synchronized (ShellAccess.class) {
            if (sInitialized) {
                return;
            }
            sInitialized = true;
        }
        Shizuku.addBinderReceivedListenerSticky(BINDER_RECEIVED);
        Shizuku.addBinderDeadListener(BINDER_DEAD);
        Shizuku.addRequestPermissionResultListener(PERMISSION_RESULT);
        refresh();
    }

    public static boolean isReady() {
        return sSnapshot.isReady();
    }

    public static String statusLabel() {
        return isReady() ? "ready" : "unavailable";
    }

    static Snapshot currentSnapshot() {
        return sSnapshot;
    }

    static void addStateListener(final StateListener listener) {
        if (listener == null) {
            return;
        }
        STATE_LISTENERS.add(listener);
        listener.onShellStateChanged(sSnapshot);
    }

    static void removeStateListener(final StateListener listener) {
        STATE_LISTENERS.remove(listener);
    }

    static Snapshot refresh() {
        final Snapshot snapshot = publish(inspectNow());
        SERVICE_CONNECTION.connect(snapshot, ShellAccess::userServiceArgs);
        return snapshot;
    }

    private static Snapshot inspectNow() {
        final Context context = MagicDeskApplication.applicationContext();
        if (context == null) {
            return Snapshot.unavailable(false, "Shizuku access is not initialized");
        }
        final boolean installed = isManagerInstalled(context);
        try {
            if (!Shizuku.pingBinder()) {
                return Snapshot.unavailable(installed,
                        installed
                                ? "Shizuku is installed but its server is not running"
                                : "Shizuku is not installed");
            }
            final int version = Shizuku.getVersion();
            if (version < 11) {
                return new Snapshot(
                        installed, true, false, -1, version,
                        "Shizuku API 11 or newer is required");
            }
            final int uid = Shizuku.getUid();
            final boolean permissionGranted =
                    Shizuku.checkSelfPermission()
                            == PackageManager.PERMISSION_GRANTED;
            final String error;
            if (!permissionGranted) {
                error = "Shizuku permission is not granted";
            } else if (!isSupportedServiceUid(uid)) {
                error = "Shizuku service UID is unsupported: " + uid;
            } else {
                error = "";
            }
            return new Snapshot(
                    installed, true, permissionGranted, uid, version,
                    error);
        } catch (RuntimeException error) {
            return Snapshot.unavailable(installed, usefulMessage(error));
        }
    }

    static int connectAndGetUid() throws IOException {
        try {
            return requireService().uid();
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            throw new IOException("Shizuku command service failed: "
                    + usefulMessage(error), error);
        }
    }

    static boolean isSupportedServiceUid(final int uid) {
        return uid == SHELL_UID || uid == ROOT_UID;
    }

    public static String run(final String command) throws IOException {
        final CommandResult result = executeForConsole(command);
        if (result.exitCode != 0) {
            throw new IOException("Shizuku command failed " + result.exitCode + ": "
                    + result.output.trim());
        }
        return result.output;
    }

    static int launchDesktopHost(
            final int displayId,
            final Intent intent) throws IOException {
        if (displayId < 0 || intent == null || intent.getComponent() == null) {
            throw new IOException("invalid desktop host launch");
        }
        try {
            return requireService().launchDesktopHost(
                    displayId,
                    intent.toUri(Intent.URI_INTENT_SCHEME));
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "desktop host launch failed: " + usefulMessage(error),
                    error);
        }
    }

    public static CommandResult executeForConsole(final String command) throws IOException {
        final String encoded;
        try {
            encoded = requireService().execute(command);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            throw new IOException("Shizuku command service failed: "
                    + usefulMessage(error), error);
        }
        return parseCommandResult(encoded);
    }

    static CommandResult parseCommandResult(final String encoded)
            throws IOException {
        final int separator = encoded == null ? -1 : encoded.indexOf('\n');
        if (separator <= 0) {
            throw new IOException("invalid response from Shizuku command service");
        }
        final int exitCode;
        try {
            exitCode = Integer.parseInt(encoded.substring(0, separator));
        } catch (NumberFormatException error) {
            throw new IOException("invalid Shizuku command exit code", error);
        }
        final String output = encoded.substring(separator + 1);
        return new CommandResult(exitCode, output);
    }

    static String probeCapabilities() throws IOException {
        try {
            final String report = requireService().probeCapabilities();
            if (report == null || report.isEmpty()) {
                throw new IOException("Shizuku capability probe returned no report");
            }
            return report;
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            throw new IOException("Shizuku capability probe failed: "
                    + usefulMessage(error), error);
        }
    }

    static SystemMonitorSnapshot readSystemMonitorSnapshot(
            final boolean includeProcessMemory) throws IOException {
        try {
            final SystemMonitorSnapshot snapshot = requireService()
                    .readSystemMonitorSnapshot(includeProcessMemory);
            if (snapshot == null) {
                throw new IOException(
                        "Shell service returned no system snapshot");
            }
            return snapshot;
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            throw new IOException("Shell system monitor failed: "
                    + usefulMessage(error), error);
        }
    }

    static ParcelFileDescriptor openDisplayCapture(
            final DisplayCaptureSource source,
            final Rect crop,
            final int outputWidth,
            final int outputHeight) throws IOException {
        if (source == null || crop == null) {
            throw new IllegalArgumentException("display capture is required");
        }
        try {
            final ParcelFileDescriptor descriptor = requireService()
                    .openDisplayCapture(
                            source.commandArgument(),
                            crop.left,
                            crop.top,
                            crop.right,
                            crop.bottom,
                            outputWidth,
                            outputHeight);
            if (descriptor == null) {
                throw new IOException(
                        "shell service returned no display capture");
            }
            return descriptor;
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "display capture failed: " + usefulMessage(error), error);
        } catch (RuntimeException error) {
            throw new IOException(
                    "display capture failed: " + usefulMessage(error), error);
        }
    }

    static int[] captureDisplayPixels(
            final DisplayCaptureSource source,
            final int[] xCoordinates,
            final int[] yCoordinates) throws IOException {
        if (source == null) {
            throw new IllegalArgumentException("display capture is required");
        }
        try {
            final int[] pixels = requireService().captureDisplayPixels(
                    source.commandArgument(), xCoordinates, yCoordinates);
            if (pixels == null || pixels.length != xCoordinates.length) {
                throw new IOException(
                        "shell service returned invalid pixel data");
            }
            return pixels;
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "display pixel capture failed: "
                            + usefulMessage(error), error);
        } catch (RuntimeException error) {
            throw new IOException(
                    "display pixel capture failed: "
                            + usefulMessage(error), error);
        }
    }

    static String updateHardwareKeyboardLayout(
            final String mode,
            final String currentDescriptor)
            throws IOException {
        try {
            return requireService().updateHardwareKeyboardLayout(
                    mode, currentDescriptor);
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "Shizuku keyboard layout update failed: "
                            + usefulMessage(error),
                    error);
        } catch (RuntimeException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "Shizuku keyboard layout update failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    public static boolean capturePointerPosition() {
        if (!isReady()) {
            return false;
        }
        final IShizukuCommandService service = connectedServiceOrConnect();
        if (service == null) {
            return false;
        }
        try {
            return service.capturePointerPosition();
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            return false;
        }
    }

    public static void restorePointerPositionIfDisplaced() {
        if (!isReady()) {
            return;
        }
        final IShizukuCommandService service = connectedServiceOrConnect();
        if (service == null) {
            return;
        }
        try {
            service.restorePointerPositionIfDisplaced();
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
        }
    }

    static boolean refreshPointerViewport() {
        if (!isReady()) {
            return false;
        }
        final IShizukuCommandService service = connectedServiceOrConnect();
        if (service == null) {
            return false;
        }
        try {
            service.refreshPointerViewport();
            return true;
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            return false;
        }
    }

    static boolean injectPointerClick(
            final int displayId,
            final int button) {
        if (!isReady() || displayId <= 0) {
            return false;
        }
        final IShizukuCommandService service = connectedServiceOrConnect();
        if (service == null) {
            return false;
        }
        try {
            return service.injectPointerClick(displayId, button);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            return false;
        }
    }

    static boolean injectPointerHoverAt(
            final int displayId,
            final int x,
            final int y) {
        if (!isReady() || displayId <= 0) {
            return false;
        }
        final IShizukuCommandService service = connectedServiceOrConnect();
        if (service == null) {
            return false;
        }
        try {
            return service.injectPointerHoverAt(displayId, x, y);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            return false;
        }
    }

    static boolean injectPointerClickAt(
            final int displayId,
            final int x,
            final int y,
            final int button) {
        if (!isReady() || displayId <= 0) {
            return false;
        }
        final IShizukuCommandService service = connectedServiceOrConnect();
        if (service == null) {
            return false;
        }
        try {
            return service.injectPointerClickAt(
                    displayId, x, y, button);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            return false;
        }
    }

    public static Point getMousePosition(final int displayId) {
        if (!isReady() || displayId <= 0) {
            return null;
        }
        final IShizukuCommandService service = connectedServiceOrConnect();
        if (service == null) {
            return null;
        }
        try {
            final int[] position = service.getMousePosition(displayId);
            return position != null && position.length == 2
                    ? new Point(position[0], position[1]) : null;
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            return null;
        }
    }

    public static boolean updateMousePosition(
            final int displayId,
            final int x,
            final int y,
            final int action,
            final long downTime) {
        if (!isReady() || displayId <= 0) {
            return false;
        }
        final IShizukuCommandService service = connectedServiceOrConnect();
        if (service == null) {
            return false;
        }
        try {
            return service.updateMousePosition(
                    displayId, x, y, action, downTime);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            return false;
        }
    }

    static boolean updateMirrorTextInput(
            final int displayId,
            final int action,
            final String text,
            final int arg1,
            final int arg2,
            final int arg3) {
        if (!isReady() || displayId <= 0) {
            return false;
        }
        final IShizukuCommandService service = connectedServiceOrConnect();
        if (service == null) {
            return false;
        }
        try {
            return service.updateMirrorTextInput(
                    displayId, action, text, arg1, arg2, arg3);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            return false;
        }
    }

    static boolean beginMirrorTextInput(final int displayId) {
        if (!isReady() || displayId <= 0) {
            return false;
        }
        final IShizukuCommandService service = connectedServiceOrConnect();
        if (service == null) {
            return false;
        }
        try {
            return service.beginMirrorTextInput(displayId);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            return false;
        }
    }

    static void endMirrorTextInput(final int displayId) {
        if (!isReady() || displayId <= 0) {
            return;
        }
        final IShizukuCommandService service = connectedServiceOrConnect();
        if (service == null) {
            return;
        }
        try {
            service.endMirrorTextInput(displayId);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
        }
    }

    static boolean routeImeToPhone(final int displayId) throws IOException {
        try {
            return requireService().routeImeToPhone(displayId);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "Shizuku display IME routing failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    static ParcelFileDescriptor openSystemWallpaper() throws IOException {
        try {
            final ParcelFileDescriptor descriptor =
                    requireService().openSystemWallpaper();
            if (descriptor == null) {
                throw new IOException(
                        "Shizuku command service returned no wallpaper");
            }
            return descriptor;
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "Shizuku wallpaper read failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    static DesktopFileInfo[] listDesktopFiles() throws IOException {
        try {
            final DesktopFileInfo[] files =
                    requireService().listDesktopFiles();
            return files == null ? new DesktopFileInfo[0] : files;
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "Shizuku desktop directory read failed: "
                            + usefulMessage(error),
                    error);
        } catch (RuntimeException error) {
            throw new IOException(
                    "Shizuku desktop directory read failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    static ParcelFileDescriptor openDesktopFile(final String relativePath)
            throws IOException {
        return openDesktopFile(relativePath, "r");
    }

    static ParcelFileDescriptor openDesktopFile(
            final String relativePath, final String mode) throws IOException {
        try {
            final ParcelFileDescriptor descriptor =
                    requireService().openDesktopFile(relativePath, mode);
            if (descriptor == null) {
                throw new IOException(
                        "Shizuku command service returned no desktop file");
            }
            return descriptor;
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "Shizuku desktop file open failed: "
                            + usefulMessage(error),
                    error);
        } catch (RuntimeException error) {
            throw new IOException(
                    "Shizuku desktop file open failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    static DesktopFileInfo getDesktopFileInfo(final String relativePath)
            throws IOException {
        try {
            final DesktopFileInfo file = requireService()
                    .getDesktopFileInfo(relativePath);
            if (file == null) {
                throw new IOException(
                        "Shizuku command service returned no desktop entry");
            }
            return file;
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "Shizuku desktop entry read failed: "
                            + usefulMessage(error),
                    error);
        } catch (RuntimeException error) {
            throw new IOException(
                    "Shizuku desktop entry read failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    static DesktopFileInfo createDesktopEntry(
            final String name, final boolean directory) throws IOException {
        try {
            final DesktopFileInfo file = requireService()
                    .createDesktopEntry(name, directory);
            if (file == null) {
                throw new IOException(
                        "Shizuku command service returned no desktop entry");
            }
            return file;
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "Shizuku desktop entry creation failed: "
                            + usefulMessage(error),
                    error);
        } catch (RuntimeException error) {
            throw new IOException(
                    "Shizuku desktop entry creation failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    static DesktopFileInfo renameDesktopEntry(
            final String relativePath, final String newName)
            throws IOException {
        try {
            final DesktopFileInfo file = requireService()
                    .renameDesktopEntry(relativePath, newName);
            if (file == null) {
                throw new IOException(
                        "Shizuku command service returned no desktop entry");
            }
            return file;
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "Shizuku desktop entry rename failed: "
                            + usefulMessage(error),
                    error);
        } catch (RuntimeException error) {
            throw new IOException(
                    "Shizuku desktop entry rename failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    static void deleteDesktopEntry(final String relativePath)
            throws IOException {
        try {
            requireService().deleteDesktopEntry(relativePath);
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "Shizuku desktop entry deletion failed: "
                            + usefulMessage(error),
                    error);
        } catch (RuntimeException error) {
            throw new IOException(
                    "Shizuku desktop entry deletion failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    static ShellFilePage listShellDirectory(
            final String absolutePath,
            final int offset,
            final int limit,
            final boolean showHidden,
            final int sortMode,
            final boolean ascending) throws IOException {
        try {
            final ShellFilePage page = requireService().listShellDirectory(
                    absolutePath,
                    offset,
                    limit,
                    showHidden,
                    sortMode,
                    ascending);
            if (page == null) {
                throw new IOException(
                        "Shizuku command service returned no file page");
            }
            return page;
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw shellFileFailure("directory read", error);
        } catch (RuntimeException error) {
            throw shellFileFailure("directory read", error);
        }
    }

    static ShellFileInfo getShellFileInfo(final String absolutePath)
            throws IOException {
        try {
            final ShellFileInfo info = requireService()
                    .getShellFileInfo(absolutePath);
            if (info == null) {
                throw new IOException(
                        "Shizuku command service returned no file info");
            }
            return info;
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw shellFileFailure("file info", error);
        } catch (RuntimeException error) {
            throw shellFileFailure("file info", error);
        }
    }

    static ParcelFileDescriptor openShellFile(
            final String absolutePath, final String mode) throws IOException {
        try {
            final ParcelFileDescriptor descriptor = requireService()
                    .openShellFile(absolutePath, mode);
            if (descriptor == null) {
                throw new IOException(
                        "Shizuku command service returned no file");
            }
            return descriptor;
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw shellFileFailure("file open", error);
        } catch (RuntimeException error) {
            throw shellFileFailure("file open", error);
        }
    }

    static ParcelFileDescriptor openVerifiedShellFile(
            final ShellFileInfo info, final String mode) throws IOException {
        if (info == null) {
            throw new IOException("missing file grant");
        }
        try {
            final ParcelFileDescriptor descriptor = requireService()
                    .openVerifiedShellFile(
                            info.absolutePath,
                            mode,
                            info.deviceId,
                            info.inode);
            if (descriptor == null) {
                throw new IOException(
                        "Shizuku command service returned no verified file");
            }
            return descriptor;
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw shellFileFailure("verified file open", error);
        } catch (RuntimeException error) {
            throw shellFileFailure("verified file open", error);
        }
    }

    static ShellFileInfo createShellEntry(
            final String parentPath,
            final String name,
            final boolean directory) throws IOException {
        try {
            final ShellFileInfo info = requireService().createShellEntry(
                    parentPath, name, directory);
            if (info == null) {
                throw new IOException(
                        "Shizuku command service returned no created entry");
            }
            return info;
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw shellFileFailure("entry creation", error);
        } catch (RuntimeException error) {
            throw shellFileFailure("entry creation", error);
        }
    }

    static ShellFileInfo createAvailableShellEntry(
            final String parentPath,
            final String name,
            final boolean directory) throws IOException {
        try {
            final ShellFileInfo info = requireService()
                    .createAvailableShellEntry(
                            parentPath, name, directory);
            if (info == null) {
                throw new IOException(
                        "Shizuku command service returned no created entry");
            }
            return info;
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw shellFileFailure("available entry creation", error);
        } catch (RuntimeException error) {
            throw shellFileFailure("available entry creation", error);
        }
    }

    static ShellFileInfo renameShellEntry(
            final String absolutePath, final String newName)
            throws IOException {
        try {
            final ShellFileInfo info = requireService().renameShellEntry(
                    absolutePath, newName);
            if (info == null) {
                throw new IOException(
                        "Shizuku command service returned no renamed entry");
            }
            return info;
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw shellFileFailure("entry rename", error);
        } catch (RuntimeException error) {
            throw shellFileFailure("entry rename", error);
        }
    }

    static long startShellFileOperation(
            final int operation,
            final String[] sourcePaths,
            final String destinationDirectory,
            final IFileOperationCallback callback,
            final IBinder ownerToken) throws IOException {
        try {
            return requireService().startShellFileOperation(
                    operation,
                    sourcePaths,
                    destinationDirectory,
                    callback,
                    ownerToken);
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw shellFileFailure("operation start", error);
        } catch (RuntimeException error) {
            throw shellFileFailure("operation start", error);
        }
    }

    static void cancelShellFileOperation(final long operationId)
            throws IOException {
        try {
            requireService().cancelShellFileOperation(operationId);
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw shellFileFailure("operation cancellation", error);
        } catch (RuntimeException error) {
            throw shellFileFailure("operation cancellation", error);
        }
    }

    static ShellDirectoryObserverHandle openShellDirectoryObserver(
            final String absolutePath,
            final IShellDirectoryObserverCallback callback,
            final Runnable disconnected) throws IOException {
        if (callback == null) {
            throw new IOException("missing directory observer callback");
        }
        final IShizukuCommandService service = requireService();
        final ShellDirectoryObserverHandle handle =
                new ShellDirectoryObserverHandle(
                        service, absolutePath, callback, disconnected);
        try {
            handle.start();
            return handle;
        } catch (RemoteException error) {
            handle.closeAfterStartFailure();
            handleServiceFailure(error);
            throw shellFileFailure("directory observer", error);
        } catch (RuntimeException error) {
            handle.closeAfterStartFailure();
            throw shellFileFailure("directory observer", error);
        }
    }

    static long startShellFileSearch(
            final String rootPath,
            final String query,
            final boolean showHidden,
            final int maxResults,
            final IFileSearchCallback callback,
            final IBinder ownerToken) throws IOException {
        try {
            return requireService().startShellFileSearch(
                    rootPath,
                    query,
                    showHidden,
                    maxResults,
                    callback,
                    ownerToken);
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw shellFileFailure("search start", error);
        } catch (RuntimeException error) {
            throw shellFileFailure("search start", error);
        }
    }

    static void cancelShellFileSearch(final long searchId)
            throws IOException {
        try {
            requireService().cancelShellFileSearch(searchId);
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw shellFileFailure("search cancellation", error);
        } catch (RuntimeException error) {
            throw shellFileFailure("search cancellation", error);
        }
    }

    private static IOException shellFileFailure(
            final String action, final Throwable error) {
        return new IOException(
                "Shell filesystem " + action + " failed: "
                        + usefulMessage(error),
                error);
    }

    static ShellDesktopFolderHandle openDesktopFolderObserver(
            final IDesktopFolderObserverCallback callback,
            final Runnable disconnected) throws IOException {
        if (callback == null) {
            throw new IOException("missing desktop folder callback");
        }
        final IShizukuCommandService service = requireService();
        final ShellDesktopFolderHandle handle =
                new ShellDesktopFolderHandle(
                        service, callback, disconnected);
        try {
            handle.start();
            return handle;
        } catch (RemoteException error) {
            handle.closeAfterStartFailure();
            handleServiceFailure(error);
            throw new IOException(
                    "Shizuku desktop folder observer failed: "
                            + usefulMessage(error),
                    error);
        } catch (RuntimeException error) {
            handle.closeAfterStartFailure();
            throw new IOException(
                    "Shizuku desktop folder observer failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    static String readDesktopState() throws IOException {
        try {
            return requireService().readDesktopState();
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "Shizuku desktop state read failed: "
                            + usefulMessage(error),
                    error);
        } catch (RuntimeException error) {
            throw new IOException(
                    "Shizuku desktop state read failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    static void writeDesktopState(final String encodedState)
            throws IOException {
        try {
            requireService().writeDesktopState(encodedState);
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "Shizuku desktop state write failed: "
                            + usefulMessage(error),
                    error);
        } catch (RuntimeException error) {
            throw new IOException(
                    "Shizuku desktop state write failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    static ParcelFileDescriptor openDesktopWallpaper() throws IOException {
        try {
            return requireService().openDesktopWallpaper();
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "Shizuku desktop wallpaper read failed: "
                            + usefulMessage(error),
                    error);
        } catch (RuntimeException error) {
            throw new IOException(
                    "Shizuku desktop wallpaper read failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    static void writeDesktopWallpaper(final ParcelFileDescriptor source)
            throws IOException {
        try {
            requireService().writeDesktopWallpaper(source);
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "Shizuku desktop wallpaper write failed: "
                            + usefulMessage(error),
                    error);
        } catch (RuntimeException error) {
            throw new IOException(
                    "Shizuku desktop wallpaper write failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    static boolean deleteDesktopWallpaper() throws IOException {
        try {
            return requireService().deleteDesktopWallpaper();
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "Shizuku desktop wallpaper deletion failed: "
                            + usefulMessage(error),
                    error);
        } catch (RuntimeException error) {
            throw new IOException(
                    "Shizuku desktop wallpaper deletion failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    static void setPreferredFileHandler(
            final String mimeType,
            final String[] candidateComponents,
            final String selectedComponent,
            final int match) throws IOException {
        try {
            requireService().setPreferredFileHandler(
                    mimeType,
                    candidateComponents,
                    selectedComponent,
                    match);
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "Shizuku preferred-handler update failed: "
                            + usefulMessage(error),
                    error);
        } catch (RuntimeException error) {
            throw new IOException(
                    "Shizuku preferred-handler update failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    static String getSelectedFileHandler(
            final String mimeType, final String dataUri) throws IOException {
        try {
            return requireService().getSelectedFileHandler(
                    mimeType, dataUri);
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "Shizuku selected-handler lookup failed: "
                            + usefulMessage(error),
                    error);
        } catch (RuntimeException error) {
            throw new IOException(
                    "Shizuku selected-handler lookup failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    static ShellStreamHandle openOwnedStream(final String command)
            throws IOException {
        return openStream(command, false);
    }

    public static ShellStreamHandle openHeartbeatStream(final String command)
            throws IOException {
        return openStream(command, true);
    }

    static ShellTaskObserverHandle openTaskObserver(
            final ITaskObserverCallback callback,
            final Runnable disconnected) throws IOException {
        if (callback == null) {
            throw new IOException("missing task observer callback");
        }
        final IShizukuCommandService service = requireService();
        final ShellTaskObserverHandle handle = new ShellTaskObserverHandle(
                service, callback, disconnected);
        try {
            handle.start();
            return handle;
        } catch (RemoteException error) {
            handle.closeAfterStartFailure();
            handleServiceFailure(error);
            throw new IOException(
                    "Shizuku task observer failed: "
                            + usefulMessage(error),
                    error);
        } catch (RuntimeException error) {
            handle.closeAfterStartFailure();
            throw new IOException(
                    "Shizuku task observer failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    static ShellInputRoutingHandle openInputRouting(
            final int displayId,
            final int expectedVirtualKeyboardCount) throws IOException {
        if (displayId <= 0) {
            throw new IOException(
                    "input routing requires a secondary display");
        }
        if (expectedVirtualKeyboardCount < 0) {
            throw new IOException(
                    "virtual keyboard count must not be negative");
        }
        final IShizukuCommandService service = requireService();
        final IBinder ownerToken = new Binder();
        try {
            final int[] state = service.startInputRouting(
                    displayId, expectedVirtualKeyboardCount, ownerToken);
            if (state == null || state.length != 4) {
                service.stopInputRouting(ownerToken);
                throw new IOException("invalid input routing state");
            }
            return new ShellInputRoutingHandle(service, ownerToken, state);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "Shizuku input routing failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    static int cleanupInputRouting() throws IOException {
        try {
            return requireService().cleanupInputRouting();
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "Shizuku input routing cleanup failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    static void startLocalDesktopNavigationGuard(
            final IBinder ownerToken) throws IOException {
        try {
            requireService().startLocalDesktopNavigationGuard(ownerToken);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "Shizuku local desktop navigation guard failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    static void stopLocalDesktopNavigationGuard(
            final IBinder ownerToken) throws IOException {
        if (!isReady()) {
            return;
        }
        try {
            requireService().stopLocalDesktopNavigationGuard(ownerToken);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "Shizuku local desktop navigation restore failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    static String startDisplayRecording(
            final String physicalDisplayId,
            final String outputPath,
            final int width,
            final int height,
            final int bitrateMbps,
            final String audioMode,
            final IBinder ownerToken) throws IOException {
        try {
            return requireService().startDisplayRecording(
                    physicalDisplayId,
                    outputPath,
                    width,
                    height,
                    bitrateMbps,
                    audioMode,
                    ownerToken);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "Shizuku display recording start failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    static String stopDisplayRecording(final IBinder ownerToken)
            throws IOException {
        try {
            return requireService().stopDisplayRecording(ownerToken);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "Shizuku display recording finalization failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    private static ShellStreamHandle openStream(
            final String command,
            final boolean heartbeatEnabled) throws IOException {
        if (command == null || command.isEmpty()) {
            throw new IOException("empty Shizuku stream command");
        }
        final long requestId = NEXT_STREAM_ID.incrementAndGet();
        final IBinder ownerToken = new Binder();
        try {
            final IShizukuCommandService service = requireService();
            final ParcelFileDescriptor descriptor;
            if (heartbeatEnabled) {
                descriptor = service.openHeartbeatStream(
                        command, requestId, ownerToken);
            } else {
                descriptor = service.openOwnedStream(
                        command, requestId, ownerToken);
            }
            if (descriptor == null) {
                throw new IOException(
                        "Shizuku command service returned no stream");
            }
            return new ShellStreamHandle(
                    requestId,
                    descriptor,
                    ownerToken,
                    service);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            throw new IOException("Shizuku command stream failed: "
                    + usefulMessage(error), error);
        }
    }

    static void requestPermission() {
        final Snapshot snapshot = refresh();
        if (!snapshot.running) {
            throw new IllegalStateException(snapshot.error);
        }
        Shizuku.requestPermission(REQUEST_PERMISSION_CODE);
    }

    static void openManagerOrWebsite(final Context context) {
        final Intent manager =
                context.getPackageManager().getLaunchIntentForPackage(MANAGER_PACKAGE);
        if (manager != null) {
            context.startActivity(manager.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        context.startActivity(new Intent(
                Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL)));
    }

    static void disconnect() {
        SERVICE_CONNECTION.disconnect(ShellAccess::userServiceArgs);
    }

    private static IShizukuCommandService requireService() throws IOException {
        return SERVICE_CONNECTION.require(sSnapshot, ShellAccess::userServiceArgs);
    }

    private static IShizukuCommandService connectedServiceOrConnect() {
        final IShizukuCommandService service =
                SERVICE_CONNECTION.connectedService();
        if (service != null) {
            return service;
        }
        SERVICE_CONNECTION.connect(sSnapshot, ShellAccess::userServiceArgs);
        return null;
    }

    private static Shizuku.UserServiceArgs userServiceArgs() {
        final Context context = MagicDeskApplication.applicationContext();
        if (context == null) {
            throw new IllegalStateException("Shizuku access is not initialized");
        }
        return new Shizuku.UserServiceArgs(new ComponentName(
                context.getPackageName(), ShizukuCommandService.class.getName()))
                .daemon(false)
                .processNameSuffix("shizuku")
                .debuggable((context.getApplicationInfo().flags
                        & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                .version(appVersionCode(context));
    }

    private static void clearService() {
        SERVICE_CONNECTION.clear();
    }

    static boolean isServiceTransportFailure(final Throwable error) {
        return error instanceof RemoteException;
    }

    private static void handleServiceFailure(final Throwable error) {
        if (!isServiceTransportFailure(error)) {
            return;
        }
        clearService();
        refresh();
    }

    private static Snapshot publish(final Snapshot snapshot) {
        return publish(snapshot, false);
    }

    private static Snapshot publish(
            final Snapshot snapshot,
            final boolean notifyUnchanged) {
        final boolean notify;
        synchronized (ShellAccess.class) {
            final Snapshot previous = sSnapshot;
            sSnapshot = snapshot;
            notify = shouldNotifyStateListeners(
                    previous, snapshot, notifyUnchanged);
        }
        if (notify) {
            for (final StateListener listener : STATE_LISTENERS) {
                listener.onShellStateChanged(snapshot);
            }
        }
        return snapshot;
    }

    static boolean shouldNotifyStateListeners(
            final Snapshot previous,
            final Snapshot current,
            final boolean commandServiceConnected) {
        return commandServiceConnected
                || previous == null
                || !previous.sameState(current);
    }

    private static boolean isManagerInstalled(final Context context) {
        try {
            context.getPackageManager().getPackageInfo(
                    MANAGER_PACKAGE, PackageManager.PackageInfoFlags.of(0));
            return true;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    private static int appVersionCode(final Context context) {
        try {
            final long versionCode = context.getPackageManager()
                    .getPackageInfo(
                            context.getPackageName(),
                            PackageManager.PackageInfoFlags.of(0))
                    .getLongVersionCode();
            return (int) Math.min(Integer.MAX_VALUE, versionCode);
        } catch (PackageManager.NameNotFoundException error) {
            return 1;
        }
    }

    public static String usefulMessage(final Throwable error) {
        final String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    public static final class CommandResult {
        public final int exitCode;
        public final String output;

        public CommandResult(final int exitCode, final String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }

    static final class Snapshot {
        final boolean installed;
        final boolean running;
        final boolean permissionGranted;
        final int uid;
        final int version;
        final String error;

        Snapshot(
                final boolean installed,
                final boolean running,
                final boolean permissionGranted,
                final int uid,
                final int version,
                final String error) {
            this.installed = installed;
            this.running = running;
            this.permissionGranted = permissionGranted;
            this.uid = uid;
            this.version = version;
            this.error = error == null ? "" : error;
        }

        static Snapshot unavailable(
                final boolean installed, final String error) {
            return new Snapshot(installed, false, false, -1, -1, error);
        }

        boolean isReady() {
            return running
                    && permissionGranted
                    && isSupportedServiceUid(uid)
                    && version >= 11;
        }

        private boolean sameState(final Snapshot other) {
            return other != null
                    && installed == other.installed
                    && running == other.running
                    && permissionGranted == other.permissionGranted
                    && uid == other.uid
                    && version == other.version
                    && Objects.equals(error, other.error);
        }
    }

}
