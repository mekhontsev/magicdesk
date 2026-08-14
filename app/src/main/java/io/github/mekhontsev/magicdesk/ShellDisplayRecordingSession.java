package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

final class ShellDisplayRecordingSession implements AutoCloseable {
    private static final String TAG = "MagicDeskRecording";
    private static final String OUTPUT_ROOT =
            "/storage/emulated/0/Movies/MagicDesk/";
    private static final String TEMP_DIRECTORY = ".recording";
    private static final long VIDEO_STOP_TIMEOUT_SECONDS = 8L;
    private static final long VIDEO_START_TIMEOUT_MILLIS = 3_000L;
    private static final int MAX_CAPTURE_DIMENSION = 8_192;
    private static final int MIN_BITRATE_MBPS = 1;
    private static final int MAX_BITRATE_MBPS = 100;

    private enum State {
        IDLE,
        RECORDING,
        FINALIZING
    }

    private final Context mContext;
    private State mState = State.IDLE;
    private Process mVideoProcess;
    private PlatformAudioCaptureDriver.Recorder mAudioRecorder;
    private IBinder mOwnerToken;
    private IBinder.DeathRecipient mOwnerDeath;
    private String mOutputPath;
    private String mVideoPath;
    private String mAudioPath;
    private String mMuxPath;
    private String mVideoLogPath;
    private String mVideoPidPath;
    private int mVideoPid = -1;
    private long mVideoStartedNanos;
    private long mAudioStartedNanos;

    ShellDisplayRecordingSession(final Context context) {
        mContext = context;
    }

    synchronized String start(
            final String physicalDisplayId,
            final String outputPath,
            final int width,
            final int height,
            final int bitrateMbps,
            final IBinder ownerToken) {
        if (mState != State.IDLE) {
            throw new IllegalStateException("display recording is already active");
        }
        if (ownerToken == null) {
            throw new IllegalArgumentException("missing recording owner token");
        }
        if (physicalDisplayId == null
                || !physicalDisplayId.matches("[0-9]+")) {
            throw new IllegalArgumentException("invalid physical display id");
        }
        validateVideoOptions(width, height, bitrateMbps);
        final String validatedOutput = validateOutputPath(outputPath);
        preparePaths(validatedOutput);
        try {
            prepareOutputDirectory();
            linkOwner(ownerToken);
            startAudio();
            startVideo(
                    physicalDisplayId,
                    width,
                    height,
                    bitrateMbps);
            if (!mVideoProcess.isAlive()) {
                throw new IOException("screenrecord stopped during startup: "
                        + readVideoLog());
            }
            mState = State.RECORDING;
            Log.i(TAG, "recording started physicalDisplay="
                    + physicalDisplayId
                    + " videoOffsetUs="
                    + Math.max(0L, (mVideoStartedNanos
                            - mAudioStartedNanos) / 1_000L)
                    + " size=" + (width == 0
                            ? "native" : width + "x" + height)
                    + " bitrateMbps=" + bitrateMbps
                    + " output=" + mOutputPath);
            return mOutputPath;
        } catch (IOException | RemoteException | RuntimeException error) {
            cleanupAfterFailure();
            throw new IllegalStateException(
                    "cannot start display recording: " + usefulMessage(error),
                    error);
        }
    }

    synchronized String stop(final IBinder ownerToken) {
        if (mState == State.IDLE) {
            throw new IllegalStateException("display recording is not active");
        }
        if (ownerToken != null
                && (mOwnerToken == null || !mOwnerToken.equals(ownerToken))) {
            throw new SecurityException("display recording owner mismatch");
        }
        mState = State.FINALIZING;
        try {
            stopCapture();
            validateCaptureFiles();
            MediaTrackMuxer.mux(
                    mVideoPath,
                    mAudioPath,
                    mMuxPath,
                    mVideoStartedNanos,
                    mAudioStartedNanos);
            Os.chmod(mMuxPath, 0664);
            Os.rename(mMuxPath, mOutputPath);
            Log.i(TAG, "recording saved output=" + mOutputPath);
            return mOutputPath;
        } catch (IOException | ErrnoException | RuntimeException error) {
            throw new IllegalStateException(
                    "cannot finalize display recording: "
                            + usefulMessage(error),
                    error);
        } finally {
            reset();
        }
    }

    @Override
    public synchronized void close() {
        if (mState == State.IDLE) {
            return;
        }
        try {
            stop(null);
        } catch (RuntimeException error) {
            Log.w(TAG, "recording cleanup failed", error);
        }
    }

    private void startVideo(
            final String physicalDisplayId,
            final int width,
            final int height,
            final int bitrateMbps) throws IOException {
        final int servicePid = android.os.Process.myPid();
        final String command = "/system/bin/screenrecord"
                + " --display-id " + physicalDisplayId
                + (width == 0 ? "" : " --size " + width + "x" + height)
                + " --bit-rate " + bitrateMbps + "M"
                + " --time-limit 0 " + shellQuote(mVideoPath)
                + " & recording_pid=$!"
                + "; echo $recording_pid > " + shellQuote(mVideoPidPath)
                + "; while kill -0 $recording_pid 2>/dev/null; do"
                + " if ! kill -0 " + servicePid + " 2>/dev/null; then"
                + " kill -2 $recording_pid 2>/dev/null"
                + "; fi"
                + "; sleep 1"
                + "; done"
                + "; wait $recording_pid";
        final ProcessBuilder builder = new ProcessBuilder(
                "/system/bin/sh", "-c", command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(new File(mVideoLogPath));
        mVideoProcess = builder.start();
        mVideoPid = awaitVideoPid();
        mVideoStartedNanos = awaitVideoOutput();
    }

    private void startAudio() throws IOException {
        mAudioRecorder = PlatformDrivers.current().audioCapture()
                .createRecorder(mContext, mAudioPath);
        mAudioRecorder.start();
        mAudioStartedNanos = SystemClock.elapsedRealtimeNanos();
    }

    private void stopCapture() throws IOException {
        RuntimeException audioError = null;
        try {
            if (mAudioRecorder != null) {
                mAudioRecorder.stop();
            }
        } catch (RuntimeException error) {
            audioError = error;
        }
        final Process process = mVideoProcess;
        if (process != null && process.isAlive()) {
            try {
                Os.kill(mVideoPid, OsConstants.SIGINT);
            } catch (ErrnoException | RuntimeException error) {
                process.destroy();
            }
            try {
                if (!process.waitFor(
                        VIDEO_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    forceStopVideo();
                    process.destroyForcibly();
                    throw new IOException("screenrecord did not stop cleanly");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException("screenrecord stop was interrupted", error);
            }
        }
        if (audioError != null) {
            throw audioError;
        }
    }

    private void validateCaptureFiles() throws IOException {
        if (!new File(mVideoPath).isFile()
                || new File(mVideoPath).length() == 0L) {
            throw new IOException("screenrecord produced no video: "
                    + readVideoLog());
        }
        if (!new File(mAudioPath).isFile()
                || new File(mAudioPath).length() == 0L) {
            throw new IOException("internal audio recorder produced no audio");
        }
    }

    private void preparePaths(final String outputPath) {
        mOutputPath = outputPath;
        final File output = new File(outputPath);
        final File temporaryDirectory = new File(
                output.getParentFile(), TEMP_DIRECTORY);
        final String name = output.getName();
        mVideoPath = new File(
                temporaryDirectory, name + ".video.mp4").getPath();
        mAudioPath = new File(
                temporaryDirectory, name + ".audio.mp4").getPath();
        mMuxPath = new File(
                temporaryDirectory, name + ".finalizing.mp4").getPath();
        mVideoLogPath = new File(
                temporaryDirectory, name + ".screenrecord.log").getPath();
        mVideoPidPath = new File(
                temporaryDirectory, name + ".screenrecord.pid").getPath();
    }

    private void prepareOutputDirectory() throws IOException {
        final File directory = new File(mOutputPath).getParentFile();
        if (directory == null
                || (!directory.isDirectory() && !directory.mkdirs())) {
            throw new IOException("cannot create recording directory");
        }
        final File temporaryDirectory = new File(directory, TEMP_DIRECTORY);
        final File noMedia = new File(temporaryDirectory, ".nomedia");
        if ((!temporaryDirectory.isDirectory()
                && !temporaryDirectory.mkdirs())
                || (!noMedia.isFile() && !noMedia.createNewFile())) {
            throw new IOException("cannot create recording workspace");
        }
        deleteTemporaryFiles();
    }

    private void linkOwner(final IBinder ownerToken) throws RemoteException {
        final IBinder.DeathRecipient death = () -> {
            final Thread cleanup = new Thread(
                    () -> stopAfterOwnerDeath(ownerToken),
                    "MagicDeskRecordingCleanup");
            cleanup.start();
        };
        ownerToken.linkToDeath(death, 0);
        mOwnerToken = ownerToken;
        mOwnerDeath = death;
    }

    private void stopAfterOwnerDeath(final IBinder ownerToken) {
        try {
            stop(ownerToken);
        } catch (RuntimeException error) {
            Log.w(TAG, "recording owner died; cleanup failed", error);
        }
    }

    private void cleanupAfterFailure() {
        try {
            if (mAudioRecorder != null) {
                mAudioRecorder.close();
            }
        } catch (RuntimeException ignored) {
            // The original startup error is more useful.
        }
        final Process process = mVideoProcess;
        if (process != null && process.isAlive()) {
            if (mVideoPid > 0) {
                try {
                    Os.kill(mVideoPid, OsConstants.SIGINT);
                    process.waitFor(2L, TimeUnit.SECONDS);
                } catch (ErrnoException ignored) {
                    // The process may already have exited.
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            if (process.isAlive()) {
                forceStopVideo();
                process.destroyForcibly();
            }
        }
        reset();
    }

    private void reset() {
        unlinkOwner();
        mVideoProcess = null;
        mAudioRecorder = null;
        mState = State.IDLE;
        deleteTemporaryFiles();
        mOutputPath = null;
        mVideoPath = null;
        mAudioPath = null;
        mMuxPath = null;
        mVideoLogPath = null;
        mVideoPidPath = null;
        mVideoPid = -1;
        mVideoStartedNanos = 0L;
        mAudioStartedNanos = 0L;
    }

    private void unlinkOwner() {
        if (mOwnerToken != null && mOwnerDeath != null) {
            mOwnerToken.unlinkToDeath(mOwnerDeath, 0);
        }
        mOwnerToken = null;
        mOwnerDeath = null;
    }

    private void deleteTemporaryFiles() {
        deleteIfPresent(mVideoPath);
        deleteIfPresent(mAudioPath);
        deleteIfPresent(mMuxPath);
        deleteIfPresent(mVideoLogPath);
        deleteIfPresent(mVideoPidPath);
    }

    private static void deleteIfPresent(final String path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(new File(path).toPath());
        } catch (IOException error) {
            Log.w(TAG, "cannot remove temporary recording file " + path, error);
        }
    }

    private String readVideoLog() {
        if (mVideoLogPath == null) {
            return "no screenrecord log";
        }
        try {
            final byte[] bytes = Files.readAllBytes(
                    new File(mVideoLogPath).toPath());
            final int length = Math.min(bytes.length, 2_048);
            return new String(bytes, 0, length, StandardCharsets.UTF_8).trim();
        } catch (IOException error) {
            return usefulMessage(error);
        }
    }

    private int awaitVideoPid() throws IOException {
        final long deadline = SystemClock.uptimeMillis() + 2_000L;
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                final String value = new String(
                        Files.readAllBytes(new File(mVideoPidPath).toPath()),
                        StandardCharsets.UTF_8).trim();
                final int pid = Integer.parseInt(value);
                if (pid > 0) {
                    return pid;
                }
            } catch (IOException | NumberFormatException ignored) {
                if (mVideoProcess != null && !mVideoProcess.isAlive()) {
                    throw new IOException("screenrecord stopped during startup: "
                            + readVideoLog());
                }
            }
            SystemClock.sleep(10L);
        }
        throw new IOException("screenrecord process id was not published");
    }

    private long awaitVideoOutput() throws IOException {
        final File output = new File(mVideoPath);
        final long deadline = SystemClock.uptimeMillis()
                + VIDEO_START_TIMEOUT_MILLIS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (output.length() > 0L) {
                return SystemClock.elapsedRealtimeNanos();
            }
            if (mVideoProcess != null && !mVideoProcess.isAlive()) {
                throw new IOException("screenrecord stopped during startup: "
                        + readVideoLog());
            }
            SystemClock.sleep(10L);
        }
        throw new IOException("screenrecord produced no video during startup");
    }

    private void forceStopVideo() {
        if (mVideoPid <= 0) {
            return;
        }
        try {
            Os.kill(mVideoPid, OsConstants.SIGKILL);
        } catch (ErrnoException | RuntimeException ignored) {
            // The child may already have exited between the liveness checks.
        }
    }

    private static String shellQuote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String validateOutputPath(final String outputPath) {
        if (outputPath == null || !outputPath.endsWith(".mp4")) {
            throw new IllegalArgumentException("invalid recording output path");
        }
        try {
            final String canonical = new File(outputPath).getCanonicalPath();
            if (!canonical.startsWith(OUTPUT_ROOT)) {
                throw new IllegalArgumentException(
                        "recording output must be under " + OUTPUT_ROOT);
            }
            return canonical;
        } catch (IOException error) {
            throw new IllegalArgumentException(
                    "invalid recording output path", error);
        }
    }

    private static void validateVideoOptions(
            final int width,
            final int height,
            final int bitrateMbps) {
        final boolean nativeSize = width == 0 && height == 0;
        final boolean validScaledSize = width >= 2
                && height >= 2
                && width <= MAX_CAPTURE_DIMENSION
                && height <= MAX_CAPTURE_DIMENSION
                && (width & 1) == 0
                && (height & 1) == 0;
        if (!nativeSize && !validScaledSize) {
            throw new IllegalArgumentException("invalid recording size");
        }
        if (bitrateMbps < MIN_BITRATE_MBPS
                || bitrateMbps > MAX_BITRATE_MBPS) {
            throw new IllegalArgumentException("invalid recording bitrate");
        }
    }

    private static String usefulMessage(final Throwable error) {
        final String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
