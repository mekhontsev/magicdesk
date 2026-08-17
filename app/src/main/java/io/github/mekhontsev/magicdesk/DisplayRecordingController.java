package io.github.mekhontsev.magicdesk;

import android.media.MediaScannerConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class DisplayRecordingController implements ShellAccess.StateListener {
    private static final String TAG = "MagicDeskRecording";
    private static final String OUTPUT_DIRECTORY =
            "/storage/emulated/0/Movies/MagicDesk";
    private static final DisplayRecordingController INSTANCE =
            new DisplayRecordingController();

    enum State {
        IDLE,
        STARTING,
        RECORDING,
        FINALIZING
    }

    interface Listener {
        void onRecordingStateChanged(Snapshot snapshot);
    }

    static final class Snapshot {
        final State state;
        final String message;

        Snapshot(final State state, final String message) {
            this.state = state;
            this.message = message;
        }
    }

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(
                        runnable, "MagicDeskDisplayRecording");
                thread.setDaemon(true);
                return thread;
            });
    private final Set<Listener> mListeners = new CopyOnWriteArraySet<>();
    private final IBinder mOwnerToken = new Binder();

    private Snapshot mSnapshot = new Snapshot(State.IDLE, "");
    private String mCaptureDetail;
    private long mOperationGeneration;

    private DisplayRecordingController() {
        ShellAccess.addStateListener(this);
    }

    static DisplayRecordingController get() {
        return INSTANCE;
    }

    synchronized Snapshot snapshot() {
        return mSnapshot;
    }

    void addListener(final Listener listener) {
        if (listener == null) {
            return;
        }
        mListeners.add(listener);
        final Snapshot snapshot = snapshot();
        mMainHandler.post(() -> {
            if (mListeners.contains(listener) && snapshot == snapshot()) {
                listener.onRecordingStateChanged(snapshot);
            }
        });
    }

    void removeListener(final Listener listener) {
        mListeners.remove(listener);
    }

    void toggle() {
        final State operation;
        final Snapshot snapshot;
        final long generation;
        synchronized (this) {
            operation = mSnapshot.state;
            switch (operation) {
                case IDLE:
                    snapshot = setSnapshotLocked(
                            State.STARTING,
                            "Starting screen recording...");
                    break;
                case RECORDING:
                    snapshot = setSnapshotLocked(
                            State.FINALIZING,
                            "Finalizing recording...");
                    break;
                case STARTING:
                case FINALIZING:
                default:
                    return;
            }
            generation = ++mOperationGeneration;
        }
        dispatchSnapshot(snapshot);
        switch (operation) {
            case IDLE:
                start(generation);
                break;
            case RECORDING:
                stop(generation);
                break;
            case STARTING:
            case FINALIZING:
            default:
                break;
        }
    }

    @Override
    public void onShellStateChanged(final ShellAccess.Snapshot shell) {
        if (shell != null && shell.isReady()) {
            return;
        }
        final Snapshot snapshot;
        final String detail;
        synchronized (this) {
            if (mSnapshot.state == State.IDLE) {
                return;
            }
            ++mOperationGeneration;
            detail = (mCaptureDetail == null || mCaptureDetail.isEmpty())
                    ? "shell access disconnected"
                    : mCaptureDetail + ", shell access disconnected";
            mCaptureDetail = null;
            snapshot = setSnapshotLocked(
                    State.IDLE,
                    "Screen recording stopped because shell access disconnected");
        }
        CaptureDiagnostics.recordRecordingFailed(detail);
        dispatchSnapshot(snapshot);
    }

    private void start(final long generation) {
        mExecutor.execute(() -> {
            String outputPath = null;
            DesktopCaptureTarget capture = null;
            try {
                capture = DesktopCaptureTarget.resolveActive();
                final DisplayRecordingSettings.Values settings =
                        DisplayRecordingSettings.load(
                                MagicDeskApplication.applicationContext());
                int width = 0;
                int height = 0;
                if (settings.scalePercent
                        != DisplayRecordingSettings.DEFAULT_SCALE_PERCENT) {
                    final DisplayRecordingSettings.Dimensions source =
                            displayDimensions(capture.desktopDisplayId);
                    final DisplayRecordingSettings.Dimensions scaled =
                            DisplayRecordingSettings.scaledDimensions(
                                    source.width,
                                    source.height,
                                    settings.scalePercent);
                    width = scaled.width;
                    height = scaled.height;
                }
                outputPath = nextOutputPath();
                Log.i(TAG, "recording start requested path=" + outputPath
                        + " " + capture.diagnosticDetail()
                        + " size=" + width + "x" + height
                        + " bitrateMbps=" + settings.bitrateMbps
                        + " audioMode=" + settings.audioMode.storedValue());
                final String startedPath = ShellAccess.startDisplayRecording(
                        capture.physicalDisplayId,
                        outputPath,
                        width,
                        height,
                        settings.bitrateMbps,
                        settings.audioMode.storedValue(),
                        mOwnerToken);
                if (!outputPath.equals(startedPath)) {
                    throw new IOException(
                            "unexpected recording response: " + startedPath);
                }
                final String captureDetail = capture.diagnosticDetail()
                        + ", audioMode=" + settings.audioMode.storedValue();
                if (!publishIfCurrent(
                        generation,
                        State.STARTING,
                        State.RECORDING,
                        "Recording desktop display",
                        captureDetail)) {
                    return;
                }
                CaptureDiagnostics.recordRecordingStarted(captureDetail);
                showStatus("Screen recording started", false);
            } catch (IOException | RuntimeException error) {
                Log.w(TAG, "recording start failed path=" + outputPath, error);
                final String detail = (capture == null
                        ? "capture target unavailable"
                        : capture.diagnosticDetail())
                        + ", error=" + usefulMessage(error);
                if (!publishIfCurrent(
                        generation,
                        State.STARTING,
                        State.IDLE,
                        "Screen recording could not start",
                        null)) {
                    return;
                }
                CaptureDiagnostics.recordRecordingFailed(detail);
                CompatibilityDiagnostics.record(
                        "RECORDING-001",
                        "Could not start desktop display recording",
                        (capture == null ? "capture target unavailable"
                                : capture.diagnosticDetail())
                                + ", path=" + outputPath
                                + ", error=" + usefulMessage(error),
                        error);
                showStatus("Screen recording could not start", true);
            }
        });
    }

    private void stop(final long generation) {
        showStatus("Finalizing recording...", true);
        mExecutor.execute(() -> {
            try {
                final String outputPath =
                        ShellAccess.stopDisplayRecording(mOwnerToken);
                MediaScannerConnection.scanFile(
                        MagicDeskApplication.applicationContext(),
                        new String[] {outputPath},
                        new String[] {"video/mp4"},
                        null);
                final String message = "Saved to " + outputPath;
                final String captureDetail;
                synchronized (this) {
                    captureDetail = mCaptureDetail;
                }
                if (!publishIfCurrent(
                        generation,
                        State.FINALIZING,
                        State.IDLE,
                        message,
                        null)) {
                    return;
                }
                CaptureDiagnostics.recordRecordingCompleted(captureDetail);
                showStatus(message, true);
            } catch (IOException | RuntimeException error) {
                Log.w(TAG, "recording finalization failed", error);
                final String captureDetail;
                synchronized (this) {
                    captureDetail = mCaptureDetail;
                }
                if (!publishIfCurrent(
                        generation,
                        State.FINALIZING,
                        State.IDLE,
                        "Screen recording could not be saved",
                        null)) {
                    return;
                }
                CaptureDiagnostics.recordRecordingFailed(
                        (captureDetail == null ? "" : captureDetail + ", ")
                                + "error=" + usefulMessage(error));
                CompatibilityDiagnostics.record(
                        "RECORDING-002",
                        "Could not finalize desktop display recording",
                        usefulMessage(error),
                        error);
                showStatus("Screen recording could not be saved", true);
            }
        });
    }

    private boolean publishIfCurrent(
            final long generation,
            final State expectedState,
            final State state,
            final String message,
            final String captureDetail) {
        final Snapshot snapshot;
        synchronized (this) {
            if (generation != mOperationGeneration
                    || mSnapshot.state != expectedState) {
                return false;
            }
            mCaptureDetail = captureDetail;
            snapshot = setSnapshotLocked(state, message);
        }
        dispatchSnapshot(snapshot);
        return true;
    }

    private Snapshot setSnapshotLocked(
            final State state,
            final String message) {
        final Snapshot snapshot = new Snapshot(state, message);
        mSnapshot = snapshot;
        return snapshot;
    }

    private void dispatchSnapshot(final Snapshot snapshot) {
        if (snapshot != snapshot()) {
            return;
        }
        MagicDeskRuntime.setOperationStatus(
                snapshot.state == State.IDLE ? null : snapshot.message);
        mMainHandler.post(() -> {
            if (snapshot != snapshot()) {
                return;
            }
            for (final Listener listener : mListeners) {
                listener.onRecordingStateChanged(snapshot);
            }
        });
    }

    private void showStatus(final String message, final boolean longDuration) {
        mMainHandler.post(() -> DesktopRuntimeBridge.showTransientStatus(
                message, longDuration));
    }

    private static String nextOutputPath() {
        final String timestamp = new SimpleDateFormat(
                "yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        return OUTPUT_DIRECTORY + "/MagicDesk_" + timestamp + ".mp4";
    }

    private static DisplayRecordingSettings.Dimensions displayDimensions(
            final int displayId) throws IOException {
        final ConsoleDisplayController.DisplaySize size =
                ConsoleDisplayController.getDisplaySize(displayId);
        return new DisplayRecordingSettings.Dimensions(
                size.width, size.height);
    }

    private static String usefulMessage(final Throwable error) {
        final String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
