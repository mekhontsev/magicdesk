package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import rikka.shizuku.Shizuku;

final class ShizukuAccess {
    static final int REQUEST_PERMISSION_CODE = 7104;
    static final String MANAGER_PACKAGE = "moe.shizuku.privileged.api";
    private static final String DOWNLOAD_URL = "https://shizuku.rikka.app/download/";
    private static final long BIND_TIMEOUT_MILLIS = 10_000;
    private static final Object LOCK = new Object();
    private static final AtomicLong NEXT_STREAM_ID =
            new AtomicLong();

    private static Context sContext;
    private static IShizukuCommandService sService;
    private static boolean sBinding;

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

    private ShizukuAccess() {
    }

    static void initialize(final Context context) {
        sContext = context.getApplicationContext();
    }

    static Snapshot inspect() {
        final Context context = sContext;
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
            return new Snapshot(
                    installed, true, permissionGranted, uid, version,
                    permissionGranted ? "" : "Shizuku permission is not granted");
        } catch (RuntimeException error) {
            return Snapshot.unavailable(installed, usefulMessage(error));
        }
    }

    static int connectAndGetUid() throws IOException {
        try {
            return requireService().uid();
        } catch (RemoteException | RuntimeException error) {
            clearService();
            throw new IOException("Shizuku command service failed: "
                    + usefulMessage(error), error);
        }
    }

    static String run(final String command) throws IOException {
        final String encoded;
        try {
            encoded = requireService().execute(command);
        } catch (RemoteException | RuntimeException error) {
            clearService();
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
            clearService();
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
            clearService();
            throw new IOException(
                    "Shizuku keyboard layout update failed: "
                            + usefulMessage(error),
                    error);
        } catch (RuntimeException error) {
            throw new IOException(
                    "Shizuku keyboard layout update failed: "
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
            throw new IOException(
                    "Shizuku wallpaper read failed: "
                            + usefulMessage(error),
                    error);
        }
    }

    static StreamHandle openStream(final String command) throws IOException {
        return openStream(command, false);
    }

    static StreamHandle openHeartbeatStream(final String command)
            throws IOException {
        return openStream(command, true);
    }

    private static StreamHandle openStream(
            final String command,
            final boolean userServiceHeartbeat) throws IOException {
        if (command == null || command.isEmpty()) {
            throw new IOException("empty Shizuku stream command");
        }
        final long requestId = NEXT_STREAM_ID.incrementAndGet();
        final IBinder ownerToken = userServiceHeartbeat
                ? new Binder() : null;
        try {
            final IShizukuCommandService service = requireService();
            final ParcelFileDescriptor descriptor = userServiceHeartbeat
                    ? service.openHeartbeatStream(
                            command, requestId, ownerToken)
                    : service.openStream(command, requestId);
            if (descriptor == null) {
                throw new IOException(
                        "Shizuku command service returned no stream");
            }
            return new StreamHandle(requestId, descriptor, ownerToken);
        } catch (RemoteException | RuntimeException error) {
            clearService();
            throw new IOException("Shizuku command stream failed: "
                    + usefulMessage(error), error);
        }
    }

    static void requestPermission() {
        final Snapshot snapshot = inspect();
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
        final Snapshot snapshot = inspect();
        if (!snapshot.running) {
            throw new IOException(snapshot.error);
        }
        if (!snapshot.permissionGranted) {
            throw new IOException("Shizuku permission is not granted");
        }
        synchronized (LOCK) {
            if (sService != null && sService.asBinder().pingBinder()) {
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
        final Context context = sContext;
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
    }

    static final class StreamHandle implements Closeable {
        private final long mRequestId;
        private final InputStream mInput;
        // Keep the local Binder alive while the UserService owns this stream.
        @SuppressWarnings("unused")
        private final IBinder mOwnerToken;
        private final AtomicBoolean mClosed = new AtomicBoolean();

        StreamHandle(
                final long requestId,
                final ParcelFileDescriptor descriptor,
                final IBinder ownerToken) {
            mRequestId = requestId;
            mInput = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
            mOwnerToken = ownerToken;
        }

        InputStream inputStream() {
            return mInput;
        }

        void writeLine(final String line) throws IOException {
            if (mClosed.get()) {
                throw new IOException("Shizuku stream is closed");
            }
            final IShizukuCommandService service;
            synchronized (LOCK) {
                service = sService;
            }
            if (service == null || !service.asBinder().pingBinder()) {
                throw new IOException(
                        "Shizuku command service disconnected");
            }
            try {
                service.writeStream(mRequestId, line);
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

            final IShizukuCommandService service;
            synchronized (LOCK) {
                service = sService;
            }
            if (service == null || !service.asBinder().pingBinder()) {
                return;
            }
            try {
                service.closeStream(mRequestId);
            } catch (RemoteException | RuntimeException ignored) {
                // Closing a disconnected UserService is already complete.
            }
        }
    }
}
