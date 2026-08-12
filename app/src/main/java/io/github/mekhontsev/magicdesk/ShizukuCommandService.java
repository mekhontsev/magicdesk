package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.system.Os;
import android.util.Log;

import java.io.Closeable;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class ShizukuCommandService extends IShizukuCommandService.Stub {
    private static final String TAG = "MagicDeskShizuku";
    private static final long HEARTBEAT_INTERVAL_MILLIS = 1_000L;
    private static final long STREAM_STOP_GRACE_MILLIS = 1_000L;
    private final Context mContext;
    private final Map<Long, StreamSession> mStreams =
            new ConcurrentHashMap<>();
    private final ShellTaskObserverManager mTaskObserverManager;
    private final PlatformPointerDriver mPointerDriver;
    private final PlatformPhoneUiDriver.NavigationGuard mNavigationGuard;
    private final ShellDisplayRecordingSession mDisplayRecording;
    private final ShellDesktopDirectory mDesktopDirectory;
    private final Object mInputRoutingLock = new Object();
    private final Object mMirrorTextInputLock = new Object();
    private DesktopInputRoutingSession mInputRoutingSession;
    private IBinder mInputRoutingOwner;
    private IBinder.DeathRecipient mInputRoutingOwnerDeath;
    private DesktopMirrorTextInput.Session mMirrorTextInputSession;
    private int mMirrorTextInputDisplayId = -1;

    public ShizukuCommandService() {
        this(null);
    }

    public ShizukuCommandService(final Context context) {
        mContext = context;
        mTaskObserverManager = new ShellTaskObserverManager(
                context,
                new PlatformPhoneUiDriver.InputOwner() {
                    @Override
                    public boolean isActive() {
                        synchronized (mInputRoutingLock) {
                            return mInputRoutingSession != null;
                        }
                    }

                    @Override
                    public void reclaimInput() {
                        reclaimInputAfterPlatformPanel();
                    }
                });
        mPointerDriver = PlatformDrivers.current().pointer();
        mNavigationGuard = PlatformDrivers.current().phoneUi()
                .createNavigationGuard();
        mDisplayRecording = new ShellDisplayRecordingSession(context);
        mDesktopDirectory = new ShellDesktopDirectory();
        Log.i(TAG, "command service started uid=" + Os.getuid());
    }

    @Override
    public int uid() {
        return Os.getuid();
    }

    @Override
    public String execute(final String command) {
        if (command == null || command.isEmpty()) {
            return "-1\nempty command";
        }
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            final BoundedProcessRunner.Result result =
                    BoundedProcessRunner.run(process);
            return result.exitCode + "\n" + result.output;
        } catch (IOException error) {
            return "-1\n" + usefulMessage(error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return "-1\ncommand interrupted";
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    @Override
    public String probeCapabilities() {
        return ShizukuCapabilityProbe.run(mContext);
    }

    @Override
    public String updateHardwareKeyboardLayout(
            final String mode,
            final String currentDescriptor) {
        try {
            final HardwareKeyboardLayoutCommand.Result result =
                    HardwareKeyboardLayoutCommand.execute(
                            mode, currentDescriptor);
            if (result.isAvailable() && !"catalog".equals(mode)) {
                persistHardwareKeyboardLayout(result);
            }
            return result.format();
        } catch (ReflectiveOperationException
                | IOException
                | RuntimeException error) {
            throw new IllegalStateException(
                    "cannot update hardware keyboard layout: "
                            + usefulMessage(error),
                    error);
        }
    }

    @Override
    @SuppressLint("MissingPermission")
    public ParcelFileDescriptor openSystemWallpaper() {
        if (mContext == null) {
            throw new IllegalStateException("Shizuku service context is unavailable");
        }
        // This method runs in the Shizuku UserService. Android's shell UID
        // holds READ_WALLPAPER_INTERNAL; the ordinary APK process never calls
        // WallpaperManager.getWallpaperFile directly.
        final ParcelFileDescriptor descriptor = WallpaperManager
                .getInstance(mContext)
                .getWallpaperFile(WallpaperManager.FLAG_SYSTEM);
        if (descriptor == null) {
            throw new IllegalStateException("system wallpaper is unavailable");
        }
        return descriptor;
    }

    private static void persistHardwareKeyboardLayout(
            final HardwareKeyboardLayoutCommand.Result result)
            throws IOException {
        final String command =
                "/system/bin/settings put global "
                        + HardwareKeyboardLayoutController.LAYOUT_LABEL_STATE
                        + " " + shellQuote(result.code) + "; "
                        + "/system/bin/settings put global "
                        + HardwareKeyboardLayoutController.LAYOUT_NAME_STATE
                        + " " + shellQuote(result.name) + "; "
                        + "/system/bin/settings put global "
                        + HardwareKeyboardLayoutController.LAYOUT_STATE
                        + " " + shellQuote(result.descriptor);
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "/system/bin/sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            final BoundedProcessRunner.Result output =
                    BoundedProcessRunner.run(process);
            if (output.exitCode != 0) {
                throw new IOException(
                        "settings command failed "
                                + output.exitCode + ": "
                                + output.output.trim());
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException(
                    "settings command interrupted", error);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String shellQuote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    @Override
    public ParcelFileDescriptor openOwnedStream(
            final String command,
            final long requestId,
            final IBinder ownerToken) {
        if (ownerToken == null) {
            throw new IllegalArgumentException("missing stream owner token");
        }
        return openStream(command, requestId, ownerToken, false);
    }

    @Override
    public void startTaskObserver(final ITaskObserverCallback callback) {
        mTaskObserverManager.start(callback);
    }

    @Override
    public void configureTaskObserver(
            final ITaskObserverCallback callback,
            final int displayId,
            final int displayLeft,
            final int displayTop,
            final int displayRight,
            final int displayBottom,
            final int workLeft,
            final int workTop,
            final int workRight,
            final int workBottom) {
        mTaskObserverManager.configure(
                callback,
                displayId,
                new Rect(displayLeft, displayTop, displayRight, displayBottom),
                new Rect(workLeft, workTop, workRight, workBottom));
    }

    @Override
    public void focusTaskStack(
            final ITaskObserverCallback callback,
            final long sequence,
            final int displayId,
            final int[] taskIds) {
        mTaskObserverManager.focusStack(
                callback, sequence, displayId, taskIds);
    }

    @Override
    public void stopTaskObserver(final ITaskObserverCallback callback) {
        mTaskObserverManager.stop(callback);
    }

    @Override
    public void setPhoneTouchpadPreservation(
            final ITaskObserverCallback callback,
            final boolean enabled) {
        mTaskObserverManager.setPhoneTouchpadPreservation(
                callback, enabled);
    }

    @Override
    public boolean capturePointerPosition() {
        return mPointerDriver.capturePosition();
    }

    @Override
    public void restorePointerPositionIfDisplaced() {
        mPointerDriver.restorePositionIfDisplaced();
    }

    @Override
    public boolean injectPointerClick(
            final int displayId,
            final int button) {
        return mPointerDriver.injectClick(displayId, button);
    }

    @Override
    public int[] getMousePosition(final int displayId) {
        return mPointerDriver.getPosition(displayId);
    }

    @Override
    public boolean updateMousePosition(
            final int displayId,
            final int x,
            final int y,
            final int action,
            final long downTime) {
        return mPointerDriver.updatePosition(
                displayId, x, y, action, downTime);
    }

    @Override
    public boolean updateMirrorTextInput(
            final int displayId,
            final int action,
            final String text,
            final int arg1,
            final int arg2,
            final int arg3) {
        if (displayId <= 0) {
            return false;
        }
        final DesktopMirrorTextInput.Session session;
        synchronized (mMirrorTextInputLock) {
            session = displayId == mMirrorTextInputDisplayId
                    ? mMirrorTextInputSession : null;
        }
        if (session == null) {
            return false;
        }
        try {
            return session.dispatch(
                    action, text, arg1, arg2, arg3);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.e(TAG, "mirror text input failed", error);
            return false;
        }
    }

    @Override
    public boolean beginMirrorTextInput(final int displayId) {
        if (displayId <= 0) {
            return false;
        }
        synchronized (mMirrorTextInputLock) {
            if (displayId == mMirrorTextInputDisplayId
                    && mMirrorTextInputSession != null) {
                return true;
            }
        }
        synchronized (mInputRoutingLock) {
            if (mInputRoutingSession == null
                    || mInputRoutingSession.displayId() != displayId) {
                return false;
            }
        }
        try {
            final DesktopMirrorTextInput.Session session =
                    DesktopMirrorTextInput.capture();
            if (session == null) {
                return false;
            }
            synchronized (mMirrorTextInputLock) {
                mMirrorTextInputSession = session;
                mMirrorTextInputDisplayId = displayId;
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.e(TAG, "mirror text input capture failed", error);
            return false;
        }
    }

    @Override
    public void endMirrorTextInput(final int displayId) {
        synchronized (mMirrorTextInputLock) {
            if (displayId != mMirrorTextInputDisplayId) {
                return;
            }
            mMirrorTextInputSession = null;
            mMirrorTextInputDisplayId = -1;
        }
    }

    @Override
    public boolean routeImeToPhone(final int displayId) {
        try {
            return DisplayImePolicyController.routeToPhone(displayId);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "cannot route the IME to the phone: "
                            + usefulMessage(error),
                    error);
        }
    }

    @Override
    public int[] startInputRouting(
            final int displayId,
            final int expectedVirtualKeyboardCount,
            final IBinder ownerToken) {
        if (ownerToken == null) {
            throw new IllegalArgumentException(
                    "missing input routing owner token");
        }
        synchronized (mInputRoutingLock) {
            stopInputRoutingLocked(null);
            DesktopInputRoutingSession session = null;
            IBinder.DeathRecipient ownerDeath = null;
            boolean ownerLinked = false;
            try {
                session = DesktopInputRoutingSession.open(
                        mContext,
                        displayId,
                        expectedVirtualKeyboardCount);
                ownerDeath = () -> stopInputRoutingForOwner(ownerToken);
                ownerToken.linkToDeath(ownerDeath, 0);
                ownerLinked = true;
                mInputRoutingSession = session;
                mInputRoutingOwner = ownerToken;
                mInputRoutingOwnerDeath = ownerDeath;
                return new int[] {
                        session.displayId(),
                        session.associationCount(),
                        session.keyboardAssociationCount(),
                        session.virtualKeyboardCount()
                };
            } catch (Exception error) {
                if (ownerLinked) {
                    ownerToken.unlinkToDeath(ownerDeath, 0);
                }
                if (session != null) {
                    session.close();
                }
                throw new IllegalStateException(
                        "cannot start input routing: "
                                + usefulMessage(error),
                        error);
            }
        }
    }

    @Override
    public int refreshInputRouting() {
        synchronized (mInputRoutingLock) {
            if (mInputRoutingSession == null) {
                return 0;
            }
            try {
                return mInputRoutingSession.refreshAssociations();
            } catch (Exception error) {
                throw new IllegalStateException(
                        "cannot refresh input routing: "
                                + usefulMessage(error),
                        error);
            }
        }
    }

    @Override
    public void stopInputRouting(final IBinder ownerToken) {
        synchronized (mInputRoutingLock) {
            stopInputRoutingLocked(ownerToken);
        }
    }

    @Override
    public int cleanupInputRouting() {
        synchronized (mInputRoutingLock) {
            if (mInputRoutingSession != null) {
                return 0;
            }
            try {
                return DesktopInputRoutingSession
                        .cleanupStaleAssociations();
            } catch (Exception error) {
                throw new IllegalStateException(
                        "cannot clean stale input routing: "
                                + usefulMessage(error),
                        error);
            }
        }
    }

    @Override
    public void startLocalDesktopNavigationGuard(final IBinder ownerToken) {
        mNavigationGuard.acquire(ownerToken);
        Log.i(TAG, "platform navigation guard acquired for local desktop");
    }

    @Override
    public void stopLocalDesktopNavigationGuard(final IBinder ownerToken) {
        mNavigationGuard.release(ownerToken);
        Log.i(TAG, "platform navigation guard released after local desktop");
    }

    @Override
    public String startDisplayRecording(
            final String physicalDisplayId,
            final String outputPath,
            final int width,
            final int height,
            final int bitrateMbps,
            final IBinder ownerToken) {
        return mDisplayRecording.start(
                physicalDisplayId,
                outputPath,
                width,
                height,
                bitrateMbps,
                ownerToken);
    }

    @Override
    public String stopDisplayRecording(final IBinder ownerToken) {
        return mDisplayRecording.stop(ownerToken);
    }

    @Override
    public DesktopFileInfo[] listDesktopFiles() {
        return mDesktopDirectory.list();
    }

    @Override
    public ParcelFileDescriptor openDesktopFile(
            final String relativePath, final String mode) {
        return mDesktopDirectory.open(relativePath, mode);
    }

    @Override
    public DesktopFileInfo createDesktopEntry(
            final String name, final boolean directory) {
        return mDesktopDirectory.create(name, directory);
    }

    @Override
    public DesktopFileInfo renameDesktopEntry(
            final String relativePath, final String newName) {
        return mDesktopDirectory.rename(relativePath, newName);
    }

    @Override
    public void deleteDesktopEntry(final String relativePath) {
        mDesktopDirectory.delete(relativePath);
    }

    @Override
    public void startDesktopFolderObserver(
            final IDesktopFolderObserverCallback callback) {
        mDesktopDirectory.startObserver(callback);
    }

    @Override
    public void stopDesktopFolderObserver(
            final IDesktopFolderObserverCallback callback) {
        mDesktopDirectory.stopObserver(callback);
    }

    @Override
    public DesktopFileInfo getDesktopFileInfo(final String relativePath) {
        return mDesktopDirectory.info(relativePath);
    }

    @Override
    public String readDesktopState() {
        return mDesktopDirectory.readState();
    }

    @Override
    public void writeDesktopState(final String encodedState) {
        mDesktopDirectory.writeState(encodedState);
    }

    @Override
    public ParcelFileDescriptor openDesktopWallpaper() {
        return mDesktopDirectory.openWallpaper();
    }

    @Override
    public void writeDesktopWallpaper(final ParcelFileDescriptor source) {
        mDesktopDirectory.writeWallpaper(source);
    }

    @Override
    public boolean deleteDesktopWallpaper() {
        return mDesktopDirectory.deleteWallpaper();
    }

    @Override
    public ParcelFileDescriptor openHeartbeatStream(
            final String command,
            final long requestId,
            final IBinder ownerToken) {
        if (ownerToken == null) {
            throw new IllegalArgumentException("missing stream owner token");
        }
        return openStream(command, requestId, ownerToken, true);
    }

    private ParcelFileDescriptor openStream(
            final String command,
            final long requestId,
            final IBinder ownerToken,
            final boolean heartbeatEnabled) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("empty stream command");
        }
        closeStream(requestId);

        ParcelFileDescriptor readSide = null;
        ParcelFileDescriptor writeSide = null;
        Process process = null;
        try {
            final ParcelFileDescriptor[] pipe =
                    ParcelFileDescriptor.createPipe();
            readSide = pipe[0];
            writeSide = pipe[1];
            process = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            final StreamSession session = new StreamSession(
                    requestId,
                    process,
                    writeSide,
                    ownerToken,
                    heartbeatEnabled);
            mStreams.put(Long.valueOf(requestId), session);
            try {
                session.start();
            } catch (RemoteException error) {
                mStreams.remove(Long.valueOf(requestId), session);
                session.stop();
                throw error;
            }
            Log.i(TAG, "stream opened id=" + requestId);
            return readSide;
        } catch (IOException | RemoteException error) {
            if (process != null) {
                process.destroyForcibly();
            }
            closeQuietly(writeSide);
            closeQuietly(readSide);
            throw new IllegalStateException(
                    "cannot open command stream: " + usefulMessage(error),
                    error);
        }
    }

    @Override
    public void closeStream(final long requestId) {
        final StreamSession session =
                mStreams.remove(Long.valueOf(requestId));
        if (session != null) {
            session.stop();
            Log.i(TAG, "stream closed id=" + requestId);
        }
    }

    @Override
    public void writeStream(final long requestId, final String line) {
        final StreamSession session =
                mStreams.get(Long.valueOf(requestId));
        if (session == null) {
            throw new IllegalStateException(
                    "Shizuku stream is not active: " + requestId);
        }
        try {
            session.writeLine(line);
        } catch (IOException error) {
            throw new IllegalStateException(
                    "cannot write Shizuku stream: "
                            + usefulMessage(error),
                    error);
        }
    }

    @Override
    public void destroy() {
        Log.i(TAG, "command service stopped");
        mDisplayRecording.close();
        mDesktopDirectory.close();
        synchronized (mInputRoutingLock) {
            stopInputRoutingLocked(null);
        }
        try {
            mNavigationGuard.close();
        } catch (RuntimeException error) {
            Log.w(TAG, "system navigation guard cleanup failed", error);
        }
        mPointerDriver.close();
        mTaskObserverManager.close();
        for (final StreamSession session
                : new ArrayList<>(mStreams.values())) {
            closeStream(session.requestId);
        }
        System.exit(0);
    }

    private void stopInputRoutingForOwner(final IBinder ownerToken) {
        synchronized (mInputRoutingLock) {
            stopInputRoutingLocked(ownerToken);
        }
    }

    private void reclaimInputAfterPlatformPanel() {
        final int displayId;
        synchronized (mInputRoutingLock) {
            if (mInputRoutingSession == null) {
                return;
            }
            displayId = mInputRoutingSession.displayId();
            try {
                mInputRoutingSession.refreshAssociations();
            } catch (Exception error) {
                Log.w(TAG,
                        "could not reclaim input routing after platform panel",
                        error);
                return;
            }
        }
        try {
            final Point position =
                    mPointerDriver.restoreKnownPosition(displayId);
            if (position != null) {
                DesktopPointerInjector.injectTouchpadMotion(
                        displayId,
                        position,
                        DesktopPointerInjector.TOUCHPAD_HOVER,
                        0L);
            }
            Log.i(TAG, "input reclaimed after platform panel task removal");
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG,
                    "could not restore pointer after platform panel",
                    error);
        }
    }

    private void stopInputRoutingLocked(final IBinder expectedOwner) {
        if (expectedOwner != null
                && (mInputRoutingOwner == null
                        || !mInputRoutingOwner.equals(expectedOwner))) {
            return;
        }
        final DesktopInputRoutingSession session = mInputRoutingSession;
        final IBinder owner = mInputRoutingOwner;
        final IBinder.DeathRecipient ownerDeath =
                mInputRoutingOwnerDeath;
        synchronized (mMirrorTextInputLock) {
            mMirrorTextInputSession = null;
            mMirrorTextInputDisplayId = -1;
        }
        mInputRoutingSession = null;
        mInputRoutingOwner = null;
        mInputRoutingOwnerDeath = null;
        if (owner != null && ownerDeath != null) {
            owner.unlinkToDeath(ownerDeath, 0);
        }
        if (session != null) {
            session.close();
        }
    }

    private static void closeQuietly(final Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Stream shutdown is best effort.
        }
    }

    private static String usefulMessage(final Throwable error) {
        final String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private final class StreamSession implements Runnable {
        final long requestId;
        final Process process;
        final ParcelFileDescriptor writeSide;
        final Thread thread;
        final BufferedWriter commandWriter;
        final IBinder ownerToken;
        final IBinder.DeathRecipient ownerDeathRecipient;
        final Thread heartbeatThread;
        volatile boolean stopped;
        boolean ownerLinked;

        StreamSession(
                final long requestId,
                final Process process,
                final ParcelFileDescriptor writeSide,
                final IBinder ownerToken,
                final boolean heartbeatEnabled) {
            this.requestId = requestId;
            this.process = process;
            this.writeSide = writeSide;
            this.ownerToken = ownerToken;
            commandWriter = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));
            thread = new Thread(this, "MagicDeskShizukuStream-" + requestId);
            thread.setDaemon(true);
            ownerDeathRecipient = () -> {
                Log.i(TAG, "stream owner died id=" + requestId);
                closeStream(requestId);
            };
            if (heartbeatEnabled) {
                heartbeatThread = new Thread(
                        this::runHeartbeat,
                        "MagicDeskShizukuHeartbeat-" + requestId);
                heartbeatThread.setDaemon(true);
            } else {
                heartbeatThread = null;
            }
        }

        synchronized void start() throws RemoteException {
            ownerToken.linkToDeath(ownerDeathRecipient, 0);
            ownerLinked = true;
            thread.start();
            if (heartbeatThread != null) {
                heartbeatThread.start();
            }
        }

        synchronized void stop() {
            if (stopped) {
                return;
            }
            stopped = true;
            if (ownerLinked) {
                ownerToken.unlinkToDeath(ownerDeathRecipient, 0);
                ownerLinked = false;
            }
            if (heartbeatThread != null) {
                heartbeatThread.interrupt();
            }
            closeQuietly(commandWriter);
            awaitProcessExit();
            closeQuietly(writeSide);
            if (process.isAlive()) {
                process.destroy();
            }
            thread.interrupt();
        }

        private void awaitProcessExit() {
            try {
                process.waitFor(
                        STREAM_STOP_GRACE_MILLIS,
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }

        synchronized void writeLine(final String line) throws IOException {
            if (stopped) {
                throw new IOException("stream is stopped");
            }
            commandWriter.write(line == null ? "" : line);
            commandWriter.newLine();
            commandWriter.flush();
        }

        private void runHeartbeat() {
            while (!stopped) {
                try {
                    writeLine("ping");
                    Thread.sleep(HEARTBEAT_INTERVAL_MILLIS);
                } catch (IOException error) {
                    if (!stopped) {
                        Log.w(TAG,
                                "stream heartbeat failed id=" + requestId,
                                error);
                        closeStream(requestId);
                    }
                    return;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        @Override
        public void run() {
            try (InputStream input = process.getInputStream();
                    OutputStream output =
                            new ParcelFileDescriptor.AutoCloseOutputStream(
                                    writeSide)) {
                final byte[] buffer = new byte[8192];
                int count;
                while (!stopped && (count = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, count);
                }
            } catch (IOException error) {
                if (!stopped) {
                    Log.w(TAG,
                            "stream failed id=" + requestId,
                            error);
                }
            } finally {
                mStreams.remove(Long.valueOf(requestId), this);
                stop();
            }
        }
    }
}
