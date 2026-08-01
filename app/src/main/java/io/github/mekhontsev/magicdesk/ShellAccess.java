package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import rikka.shizuku.Shizuku;

final class ShellAccess {
    static final int REQUEST_PERMISSION_CODE = 7104;
    static final int SHELL_UID = 2000;
    static final String MANAGER_PACKAGE = "moe.shizuku.privileged.api";
    private static final String DOWNLOAD_URL = "https://shizuku.rikka.app/download/";
    private static final long BIND_TIMEOUT_MILLIS = 10_000;
    private static final Object LOCK = new Object();
    private static final AtomicLong NEXT_STREAM_ID =
            new AtomicLong();
    private static final Set<StateListener> STATE_LISTENERS =
            new CopyOnWriteArraySet<>();

    private static IShizukuCommandService sService;
    private static boolean sBinding;
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

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(
                final ComponentName componentName, final IBinder binder) {
            synchronized (LOCK) {
                sService = binder != null && binder.pingBinder()
                        ? IShizukuCommandService.Stub.asInterface(binder) : null;
                sBinding = false;
                LOCK.notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(final ComponentName componentName) {
            synchronized (LOCK) {
                sService = null;
                sBinding = false;
                LOCK.notifyAll();
            }
        }
    };

    private ShellAccess() {
    }

    static synchronized void initialize() {
        if (sInitialized) {
            return;
        }
        sInitialized = true;
        Shizuku.addBinderReceivedListenerSticky(BINDER_RECEIVED);
        Shizuku.addBinderDeadListener(BINDER_DEAD);
        Shizuku.addRequestPermissionResultListener(PERMISSION_RESULT);
        refresh();
    }

    static boolean isReady() {
        return sSnapshot.isReady();
    }

    static String statusLabel() {
        return isReady() ? "ready" : "unavailable";
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

    static synchronized Snapshot refresh() {
        return publish(inspectNow());
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
            } else if (uid != SHELL_UID) {
                error = "Shizuku must run as shell UID 2000; found UID " + uid;
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
            handleServiceFailure();
            throw new IOException("Shizuku command service failed: "
                    + usefulMessage(error), error);
        }
    }

    static String run(final String command) throws IOException {
        final String encoded;
        try {
            encoded = requireService().execute(command);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure();
            throw new IOException("Shizuku command service failed: "
                    + usefulMessage(error), error);
        }
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
        if (exitCode != 0) {
            throw new IOException("Shizuku command failed " + exitCode + ": "
                    + output.trim());
        }
        return output;
    }

    static String probeCapabilities() throws IOException {
        try {
            final String report = requireService().probeCapabilities();
            if (report == null || report.isEmpty()) {
                throw new IOException("Shizuku capability probe returned no report");
            }
            return report;
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure();
            throw new IOException("Shizuku capability probe failed: "
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
            handleServiceFailure();
            throw new IOException(
                    "Shizuku keyboard layout update failed: "
                            + usefulMessage(error),
                    error);
        } catch (RuntimeException error) {
            handleServiceFailure();
            throw new IOException(
                    "Shizuku keyboard layout update failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    static boolean capturePointerPosition() {
        if (!isReady()) {
            return false;
        }
        try {
            return requireService().capturePointerPosition();
        } catch (IOException | RemoteException | RuntimeException error) {
            handleServiceFailure();
            return false;
        }
    }

    static void restorePointerPositionIfDisplaced() {
        if (!isReady()) {
            return;
        }
        try {
            requireService().restorePointerPositionIfDisplaced();
        } catch (IOException | RemoteException | RuntimeException error) {
            handleServiceFailure();
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
            handleServiceFailure();
            throw new IOException(
                    "Shizuku wallpaper read failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    static StreamHandle openOwnedStream(final String command)
            throws IOException {
        return openStream(command, false);
    }

    static StreamHandle openHeartbeatStream(final String command)
            throws IOException {
        return openStream(command, true);
    }

    static TaskObserverHandle openTaskObserver(
            final ITaskObserverCallback callback,
            final Runnable disconnected) throws IOException {
        if (callback == null) {
            throw new IOException("missing task observer callback");
        }
        final IShizukuCommandService service = requireService();
        final TaskObserverHandle handle = new TaskObserverHandle(
                service, callback, disconnected);
        try {
            handle.start();
            return handle;
        } catch (RemoteException error) {
            handle.closeAfterStartFailure();
            handleServiceFailure();
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

    private static StreamHandle openStream(
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
            return new StreamHandle(
                    requestId,
                    descriptor,
                    ownerToken,
                    service);
        } catch (RemoteException | RuntimeException error) {
            handleServiceFailure();
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
        synchronized (LOCK) {
            if (!sBinding && sService == null) {
                return;
            }
        }
        try {
            if (Shizuku.pingBinder()) {
                Shizuku.unbindUserService(userServiceArgs(), CONNECTION, true);
            }
        } catch (RuntimeException ignored) {
            // The server may already be gone.
        } finally {
            clearService();
        }
    }

    private static IShizukuCommandService requireService() throws IOException {
        final Snapshot snapshot = sSnapshot;
        if (!snapshot.isReady()) {
            throw new IOException(snapshot.error.isEmpty()
                    ? "Shizuku shell access is unavailable" : snapshot.error);
        }
        synchronized (LOCK) {
            if (sService != null) {
                return sService;
            }
            if (!sBinding) {
                sBinding = true;
                try {
                    Shizuku.bindUserService(userServiceArgs(), CONNECTION);
                } catch (RuntimeException error) {
                    sBinding = false;
                    throw new IOException(
                            "could not bind Shizuku command service: "
                                    + usefulMessage(error),
                            error);
                }
            }
            final long deadline =
                    android.os.SystemClock.uptimeMillis() + BIND_TIMEOUT_MILLIS;
            while (sService == null && sBinding) {
                final long remaining =
                        deadline - android.os.SystemClock.uptimeMillis();
                if (remaining <= 0) {
                    sBinding = false;
                    throw new IOException("timed out binding Shizuku command service");
                }
                try {
                    LOCK.wait(remaining);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(
                            "interrupted while binding Shizuku command service",
                            error);
                }
            }
            if (sService == null) {
                throw new IOException("Shizuku command service disconnected");
            }
            return sService;
        }
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
        synchronized (LOCK) {
            sService = null;
            sBinding = false;
            LOCK.notifyAll();
        }
    }

    private static void handleServiceFailure() {
        clearService();
        refresh();
    }

    private static synchronized Snapshot publish(final Snapshot snapshot) {
        final Snapshot previous = sSnapshot;
        sSnapshot = snapshot;
        if (previous.sameState(snapshot)) {
            return snapshot;
        }
        for (final StateListener listener : STATE_LISTENERS) {
            listener.onShellStateChanged(snapshot);
        }
        return snapshot;
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

    private static String usefulMessage(final Throwable error) {
        final String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName() : message;
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
                    && uid == SHELL_UID
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

    static final class StreamHandle implements Closeable {
        private final long mRequestId;
        private final InputStream mInput;
        // Keep the local Binder alive while the UserService owns this stream.
        @SuppressWarnings("unused")
        private final IBinder mOwnerToken;
        private final IShizukuCommandService mService;
        private final AtomicBoolean mClosed = new AtomicBoolean();

        StreamHandle(
                final long requestId,
                final ParcelFileDescriptor descriptor,
                final IBinder ownerToken,
                final IShizukuCommandService service) {
            mRequestId = requestId;
            mInput = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
            mOwnerToken = ownerToken;
            mService = service;
        }

        InputStream inputStream() {
            return mInput;
        }

        void writeLine(final String line) throws IOException {
            if (mClosed.get()) {
                throw new IOException("Shizuku stream is closed");
            }
            try {
                mService.writeStream(mRequestId, line);
            } catch (RemoteException | RuntimeException error) {
                throw new IOException(
                        "Shizuku stream write failed: "
                                + usefulMessage(error),
                        error);
            }
        }

        @Override
        public void close() {
            if (!mClosed.compareAndSet(false, true)) {
                return;
            }
            try {
                mInput.close();
            } catch (IOException ignored) {
                // The remote stream may already have ended.
            }

            try {
                mService.closeStream(mRequestId);
            } catch (RemoteException | RuntimeException ignored) {
                // Closing a disconnected UserService is already complete.
            }
        }
    }

    static final class TaskObserverHandle implements Closeable {
        private final IShizukuCommandService mService;
        private final IBinder mServiceBinder;
        private final ITaskObserverCallback mCallback;
        private final Runnable mDisconnected;
        private final IBinder.DeathRecipient mServiceDeathRecipient;
        private final AtomicBoolean mClosed = new AtomicBoolean();

        private volatile boolean mRegistered;
        private boolean mServiceLinked;

        TaskObserverHandle(
                final IShizukuCommandService service,
                final ITaskObserverCallback callback,
                final Runnable disconnected) {
            mService = service;
            mServiceBinder = service.asBinder();
            mCallback = callback;
            mDisconnected = disconnected;
            mServiceDeathRecipient = this::serviceDisconnected;
        }

        void start() throws RemoteException {
            mServiceBinder.linkToDeath(mServiceDeathRecipient, 0);
            synchronized (this) {
                mServiceLinked = true;
            }
            mService.startTaskObserver(mCallback);
            if (mClosed.get()) {
                try {
                    mService.stopTaskObserver(mCallback);
                } catch (RemoteException | RuntimeException ignored) {
                    // The service disconnected while registering the observer.
                }
                throw new RemoteException(
                        "task observer disconnected during registration");
            }
            mRegistered = true;
        }

        void configure(
                final int displayId,
                final Rect displayBounds,
                final Rect workAreaBounds) throws IOException {
            if (displayBounds == null || workAreaBounds == null) {
                throw new IOException("missing task observer bounds");
            }
            callService(() -> mService.configureTaskObserver(
                    mCallback,
                    displayId,
                    displayBounds.left,
                    displayBounds.top,
                    displayBounds.right,
                    displayBounds.bottom,
                    workAreaBounds.left,
                    workAreaBounds.top,
                    workAreaBounds.right,
                    workAreaBounds.bottom));
        }

        void focusStack(
                final long sequence,
                final int displayId,
                final int[] taskIds) throws IOException {
            callService(() -> mService.focusTaskStack(
                    mCallback, sequence, displayId, taskIds));
        }

        boolean isClosed() {
            return mClosed.get();
        }

        @Override
        public void close() {
            if (!mClosed.compareAndSet(false, true)) {
                return;
            }
            unlinkServiceDeath();
            if (!mRegistered) {
                return;
            }
            mRegistered = false;
            try {
                mService.stopTaskObserver(mCallback);
            } catch (RemoteException | RuntimeException ignored) {
                // A disconnected service has already released its observer.
            }
        }

        private void callService(final RemoteServiceCall call)
                throws IOException {
            if (mClosed.get()) {
                throw new IOException("task observer is closed");
            }
            try {
                call.run();
            } catch (RemoteException error) {
                serviceDisconnected();
                throw new IOException(
                        "task observer call failed: "
                                + usefulMessage(error),
                        error);
            } catch (RuntimeException error) {
                stopRemoteObserver();
                serviceDisconnected();
                throw new IOException(
                        "task observer call failed: "
                                + usefulMessage(error),
                        error);
            }
        }

        private void stopRemoteObserver() {
            try {
                mService.stopTaskObserver(mCallback);
            } catch (RemoteException | RuntimeException ignored) {
                // The observer may already have failed or disconnected.
            }
        }

        private void closeAfterStartFailure() {
            stopRemoteObserver();
            if (!mClosed.compareAndSet(false, true)) {
                return;
            }
            unlinkServiceDeath();
        }

        private void serviceDisconnected() {
            if (!mClosed.compareAndSet(false, true)) {
                return;
            }
            mRegistered = false;
            unlinkServiceDeath();
            if (mDisconnected != null) {
                mDisconnected.run();
            }
        }

        private synchronized void unlinkServiceDeath() {
            if (!mServiceLinked) {
                return;
            }
            mServiceBinder.unlinkToDeath(mServiceDeathRecipient, 0);
            mServiceLinked = false;
        }

        @FunctionalInterface
        private interface RemoteServiceCall {
            void run() throws RemoteException;
        }
    }
}
