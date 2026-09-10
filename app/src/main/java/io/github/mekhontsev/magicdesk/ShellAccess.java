package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
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
                                ? "Shizuku API unavailable; selected manager: "
                                        + IntegrationPackage.SHIZUKU.selected()
                                : "Shizuku API unavailable; manager not installed: "
                                        + IntegrationPackage.SHIZUKU.selected());
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
        final CommandResult result = executeCommand(command);
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

    static DisplayWindowingSnapshot readDisplayWindowing(final int displayId)
            throws IOException {
        try {
            return requireService().readDisplayWindowing(displayId);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            throw new IOException("display mode read failed: " + usefulMessage(error), error);
        }
    }

    static void setDisplayWindowing(
            final int displayId, final String uniqueId, final int mode,
            final boolean systemDecorations) throws IOException {
        try {
            requireService().setDisplayWindowing(displayId, uniqueId, mode, systemDecorations);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            throw new IOException("display mode write failed: " + usefulMessage(error), error);
        }
    }

    public static CommandResult executeCommand(final String command) throws IOException {
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

    static String executeAppFunction(
            final String packageName,
            final String functionIdentifier,
            final String parametersJson,
            final long timeoutMillis) throws IOException {
        try {
            return requireService().executeAppFunction(
                    packageName,
                    functionIdentifier,
                    parametersJson,
                    timeoutMillis);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            throw new IOException("App Function execution failed: "
                    + usefulMessage(error), error);
        }
    }

    static String searchAppFunctions(
            final String searchJson,
            final long timeoutMillis) throws IOException {
        try {
            return requireService().searchAppFunctions(
                    searchJson, timeoutMillis);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            throw new IOException("App Function discovery failed: "
                    + usefulMessage(error), error);
        }
    }

    static String queryIntentHandlers(final String requestJson)
            throws IOException {
        try {
            return requireService().queryIntentHandlers(requestJson);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            throw new IOException("Android Intent discovery failed: "
                    + usefulMessage(error), error);
        }
    }

    static AndroidActivityResolution resolveActivity(final Intent intent)
            throws IOException {
        try {
            return requireService().resolveActivity(intent);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            throw new IOException("Android Activity resolution failed: "
                    + usefulMessage(error), error);
        }
    }

    static void launchActivityOnDisplay(
            final Intent intent,
            final int displayId) throws IOException {
        launchActivityOnDisplay(intent, displayId, false);
    }

    static void launchActivityOnDisplay(final Intent intent, final int displayId,
            final boolean fullscreen) throws IOException {
        try {
            requireService().launchActivityOnDisplay(intent, displayId, fullscreen);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            throw new IOException("Android Activity launch failed: "
                    + usefulMessage(error), error);
        }
    }

    static ShortcutInfo[] queryAppShortcuts(final String packageName)
            throws IOException {
        try {
            return requireService().queryAppShortcuts(packageName);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "shortcut query failed: " + usefulMessage(error), error);
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

    static String updateHardwareKeyboardLayout(final String mode)
            throws IOException {
        try {
            return requireService().updateHardwareKeyboardLayout(mode);
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

    static boolean injectPointerHoverAt(
            final int displayId,
            final int x,
            final int y) {
        if (!isReady() || displayId < 0) {
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
        if (!isReady() || displayId < 0) {
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

    static PointerPosition observeMousePosition() {
        if (!isReady()) {
            return null;
        }
        final IShizukuCommandService service = connectedServiceOrConnect();
        if (service == null) {
            return null;
        }
        try {
            final int[] position = service.observeMousePosition();
            return position != null && position.length == 3
                    ? new PointerPosition(position[0], position[1], position[2]) : null;
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            return null;
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

    static ShellFileCreation beginShellFileCreation(
            final String parentPath, final String name) throws IOException {
        return new ShellFileCreation(requireService(), parentPath, name);
    }

    static ShellFileInfo publishVerifiedShellFile(final ShellFileInfo file,
            final String target, final boolean overwrite) throws IOException {
        try {
            return requireService().publishVerifiedShellFile(file.absolutePath,
                    file.deviceId, file.inode, target, overwrite);
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw shellFileFailure("file publish", error);
        } catch (RuntimeException error) {
            throw shellFileFailure("file publish", error);
        }
    }

    static String prepareMagicDeskUpdate(ShellFileInfo apk, String sha256, int userId) throws IOException {
        try {
            return requireService().prepareMagicDeskUpdate(apk.absolutePath, apk.deviceId, apk.inode, sha256, userId);
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw shellFileFailure("app update", error);
        } catch (RuntimeException error) { throw shellFileFailure("app update", error); }
    }

    static void abandonMagicDeskUpdate(int sessionId, int userId) throws IOException {
        try { requireService().abandonMagicDeskUpdate(sessionId, userId); }
        catch (RemoteException error) {
            handleServiceFailure(error);
            throw shellFileFailure("update cleanup", error);
        } catch (RuntimeException error) { throw shellFileFailure("update cleanup", error); }
    }

    static void deleteVerifiedShellFile(final ShellFileInfo file) throws IOException {
        deleteVerifiedShellFile(file.absolutePath, file.deviceId, file.inode);
    }

    static void deleteVerifiedShellFile(final String path, final long deviceId,
            final long inode) throws IOException {
        try {
            requireService().deleteVerifiedShellFile(path, deviceId, inode);
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw shellFileFailure("file cleanup", error);
        } catch (RuntimeException error) {
            throw shellFileFailure("file cleanup", error);
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

    static ShellFileOperationHandle startShellFileOperation(
            final int operation,
            final String[] sourcePaths,
            final String destinationDirectory,
            final IFileOperationCallback callback,
            final IBinder ownerToken) throws IOException {
        try {
            final IShizukuCommandService service = requireService();
            return new ShellFileOperationHandle(service.startShellFileOperation(
                    operation,
                    sourcePaths,
                    destinationDirectory,
                    callback,
                    ownerToken), service);
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw shellFileFailure("operation start", error);
        } catch (RuntimeException error) {
            throw shellFileFailure("operation start", error);
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

    static ShellFileSearchHandle startShellFileSearch(
            final String rootPath,
            final String query,
            final boolean showHidden,
            final int maxResults,
            final IFileSearchCallback callback,
            final IBinder ownerToken) throws IOException {
        try {
            final IShizukuCommandService service = requireService();
            return new ShellFileSearchHandle(service.startShellFileSearch(
                    rootPath,
                    query,
                    showHidden,
                    maxResults,
                    callback,
                    ownerToken), service);
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw shellFileFailure("search start", error);
        } catch (RuntimeException error) {
            throw shellFileFailure("search start", error);
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

    static FrameworkTaskSnapshot[] readTaskSnapshots(
            final int displayId,
            final int limit) throws IOException {
        try {
            final FrameworkTaskSnapshot[] snapshots =
                    requireService().readTaskSnapshots(displayId, limit);
            return snapshots == null
                    ? new FrameworkTaskSnapshot[0] : snapshots;
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "Shizuku task snapshot read failed: "
                            + usefulMessage(error),
                    error);
        } catch (RuntimeException error) {
            throw new IOException(
                    "Shizuku task snapshot read failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    static FrameworkTaskSnapshot[] readDiagnosticTaskSnapshots(
            final int displayId,
            final int limit) throws IOException {
        try {
            final FrameworkTaskSnapshot[] snapshots =
                    requireService().readDiagnosticTaskSnapshots(
                            displayId, limit);
            return snapshots == null
                    ? new FrameworkTaskSnapshot[0] : snapshots;
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "Shizuku diagnostic task snapshot read failed: "
                            + usefulMessage(error),
                    error);
        } catch (RuntimeException error) {
            throw new IOException(
                    "Shizuku diagnostic task snapshot read failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    static String frameworkRuntimeDiagnostics() throws IOException {
        try {
            return requireService().getFrameworkRuntimeDiagnostics();
        } catch (RemoteException error) {
            handleServiceFailure(error);
            throw new IOException(
                    "framework runtime diagnostics failed: "
                            + usefulMessage(error),
                    error);
        } catch (RuntimeException error) {
            throw new IOException(
                    "framework runtime diagnostics failed: "
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

    static ShellPtyHandle openPty(
            final String workingDirectory,
            final int rows,
            final int columns) throws IOException {
        if (workingDirectory == null || !workingDirectory.startsWith("/")) {
            throw new IOException("PTY working directory must be absolute");
        }
        if (rows < 2 || columns < 2) {
            throw new IOException("invalid PTY dimensions");
        }
        final long requestId = NEXT_STREAM_ID.incrementAndGet();
        final IBinder ownerToken = new Binder();
        try {
            final IShizukuCommandService service = requireService();
            final ParcelFileDescriptor descriptor = service.openPtyStream(
                    workingDirectory,
                    rows,
                    columns,
                    requestId,
                    ownerToken);
            if (descriptor == null) {
                throw new IOException(
                        "Shizuku command service returned no PTY");
            }
            return new ShellPtyHandle(
                    requestId, descriptor, ownerToken, service);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            throw new IOException("Shizuku PTY failed: "
                    + usefulMessage(error), error);
        }
    }

    public static ShellStreamHandle openHeartbeatStream(final String command)
            throws IOException {
        return openStream(command, true);
    }

    static ShellTaskObserverHandle openTaskObserver(
            final ITaskObserverCallback callback,
            final IActivityLaunchCallback activityLauncher,
            final Runnable disconnected) throws IOException {
        if (callback == null || activityLauncher == null) {
            throw new IOException("missing task observer callbacks");
        }
        final IShizukuCommandService service = requireService();
        final ShellTaskObserverHandle handle = new ShellTaskObserverHandle(
                service, callback, activityLauncher, disconnected);
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

    private static final IBinder VIRTUAL_DISPLAY_OWNER = new Binder();

    static DesktopDisplayInfo[] listDesktopDisplays() throws IOException {
        try {
            return requireService().listDesktopDisplays();
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            throw new IOException("display catalog read failed: " + usefulMessage(error), error);
        }
    }

    static DesktopDisplayInfo createVirtualDisplay(final VirtualDisplaySpec spec) throws IOException {
        try {
            return requireService().createVirtualDisplay(
                    spec.width, spec.height, spec.densityDpi, VIRTUAL_DISPLAY_OWNER);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            throw new IOException("virtual display creation failed: " + usefulMessage(error), error);
        }
    }

    static void removeVirtualDisplay(final DesktopDisplayInfo display) throws IOException {
        try {
            requireService().removeVirtualDisplay(display.id, display.uniqueId, VIRTUAL_DISPLAY_OWNER);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure(error);
            throw new IOException("virtual display removal failed: " + usefulMessage(error), error);
        }
    }

    static ShellInputRoutingHandle openInputRouting(
            final int displayId) throws IOException {
        if (displayId < 0) {
            throw new IOException(
                    "input routing requires an active display");
        }
        final IShizukuCommandService service = requireService();
        final IBinder ownerToken = new Binder();
        try {
            final int[] state = service.startInputRouting(
                    displayId,
                    ownerToken);
            if (state == null || state.length != 2 || state[0] != displayId) {
                service.stopInputRouting(ownerToken);
                throw new IOException("invalid input routing state");
            }
            return new ShellInputRoutingHandle(service, ownerToken, state);
        } catch (RemoteException | RuntimeException error) {
            try {
                service.stopInputRouting(ownerToken);
            } catch (RemoteException | RuntimeException cleanup) {
                error.addSuppressed(cleanup);
            }
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

    static ShellUiAutomationHandle openUiAutomation() throws IOException {
        return new ShellUiAutomationHandle(requireService());
    }

    static java.util.Set<String> ownedInputPorts() throws IOException {
        try {
            return java.util.Set.of(requireService().getOwnedInputPorts());
        } catch (RemoteException | RuntimeException error) {
            throw new IOException("cannot read input routing ownership", error);
        }
    }

    static int[] routedKeyboardDeviceIds(final int displayId) throws IOException {
        try {
            return requireService().getRoutedKeyboardDeviceIds(displayId);
        } catch (RemoteException | RuntimeException error) {
            throw new IOException("cannot observe routed keyboards", error);
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
                context.getPackageManager().getLaunchIntentForPackage(
                        IntegrationPackage.SHIZUKU.selected());
        if (manager != null) {
            context.startActivity(manager.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if (IntegrationPackage.SHIZUKU.defaultPackage.equals(IntegrationPackage.SHIZUKU.selected())) {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL)));
        } else {
            android.widget.Toast.makeText(context,
                    context.getString(R.string.settings_integration_missing_manager,
                            IntegrationPackage.SHIZUKU.selected()), android.widget.Toast.LENGTH_LONG).show();
        }
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
                    IntegrationPackage.SHIZUKU.selected(), PackageManager.PackageInfoFlags.of(0));
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
