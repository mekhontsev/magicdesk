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

final class DisplayRecordingController {
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

    private DisplayRecordingController() {
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
        mMainHandler.post(() -> listener.onRecordingStateChanged(snapshot));
    }

    void removeListener(final Listener listener) {
        mListeners.remove(listener);
    }

    synchronized void toggle() {
        switch (mSnapshot.state) {
            case IDLE:
                start();
                break;
            case RECORDING:
                stop();
                break;
            case STARTING:
            case FINALIZING:
            default:
                break;
        }
    }

    private void start() {
        publish(State.STARTING, "Starting screen recording...");
        mExecutor.execute(() -> {
            String outputPath = null;
            try {
                final DesktopCaptureTarget capture =
                        DesktopCaptureTarget.resolveActive();
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
                final String startedPath = ShellAccess.startDisplayRecording(
                        capture.physicalDisplayId,
                        outputPath,
                        width,
                        height,
                        settings.bitrateMbps,
                        mOwnerToken);
                if (!outputPath.equals(startedPath)) {
                    throw new IOException(
                            "unexpected recording response: " + startedPath);
                }
                publish(State.RECORDING, "Recording desktop display");
                showStatus("Screen recording started", false);
            } catch (IOException | RuntimeException error) {
                Log.w(TAG, "recording start failed path=" + outputPath, error);
                CompatibilityDiagnostics.record(
                        "RECORDING-001",
                        "Could not start desktop display recording",
                        usefulMessage(error));
                publish(State.IDLE, "Screen recording could not start");
                showStatus("Screen recording could not start", true);
            }
        });
    }

    private void stop() {
        publish(State.FINALIZING, "Finalizing recording...");
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
                publish(State.IDLE, message);
                showStatus(message, true);
            } catch (IOException | RuntimeException error) {
                Log.w(TAG, "recording finalization failed", error);
                CompatibilityDiagnostics.record(
                        "RECORDING-002",
                        "Could not finalize desktop display recording",
                        usefulMessage(error));
                publish(State.IDLE, "Screen recording could not be saved");
                showStatus("Screen recording could not be saved", true);
            }
        });
    }

    private synchronized void publish(
            final State state,
            final String message) {
        final Snapshot snapshot = new Snapshot(state, message);
        mSnapshot = snapshot;
        MagicDeskRuntimeService.setOperationStatusIfRunning(
                state == State.IDLE ? null : message);
        mMainHandler.post(() -> {
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
