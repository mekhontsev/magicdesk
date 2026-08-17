package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.graphics.Rect;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Owns a one-shot shell observer for the initial state of a launched task. */
final class DesktopTaskLaunchProbe implements Closeable {
    private final ShellStreamHandle mStream;
    private final BufferedReader mReader;

    private DesktopTaskLaunchProbe(
            final ShellStreamHandle stream,
            final BufferedReader reader) {
        mStream = stream;
        mReader = reader;
    }

    static DesktopTaskLaunchProbe open(
            final int expectedTaskId,
            final ComponentName expectedComponent) throws IOException {
        return open(expectedTaskId, expectedComponent, -1);
    }

    static DesktopTaskLaunchProbe open(
            final int expectedTaskId,
            final ComponentName expectedComponent,
            final int expectedDisplayId) throws IOException {
        if (expectedTaskId < -1 || expectedComponent == null
                || expectedDisplayId < -1) {
            throw new IOException("invalid task launch observation target");
        }
        final String packageName = expectedComponent.getPackageName();
        final String className = expectedComponent.getClassName();
        if (!PackageNameValidator.isSafe(packageName)
                || !AppLaunchTarget.isSafeClassName(className)
                || className.isEmpty()) {
            throw new IOException("invalid task launch component");
        }
        final String arguments = expectedTaskId
                + " " + ShellCommandLine.quote(packageName)
                + " " + ShellCommandLine.quote(className)
                + " " + expectedDisplayId;
        final ShellStreamHandle stream = ShellAccess.openOwnedStream(
                AppProcessCommand.run(
                        "io.github.mekhontsev.magicdesk."
                                + "DesktopTaskLaunchObserverCommand",
                        arguments));
        final BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        stream.inputStream(), StandardCharsets.UTF_8));
        try {
            final String ready = reader.readLine();
            if (!DesktopTaskLaunchObserverCommand.READY.equals(ready)) {
                throw new IOException(
                        "task launch observer did not become ready: " + ready);
            }
            return new DesktopTaskLaunchProbe(stream, reader);
        } catch (IOException error) {
            closeQuietly(reader);
            closeQuietly(stream);
            throw error;
        }
    }

    Observation awaitObservation() throws IOException {
        String line;
        while ((line = mReader.readLine()) != null) {
            if (line.startsWith(
                    DesktopTaskLaunchObserverCommand.OBSERVED + "\t")) {
                return parseObservation(line);
            }
            if (DesktopTaskLaunchObserverCommand.TIMEOUT.equals(line)) {
                throw new IOException(
                        "task did not produce a front-state callback");
            }
        }
        throw new IOException("task launch observer disconnected");
    }

    @Override
    public void close() {
        closeQuietly(mReader);
        closeQuietly(mStream);
    }

    static Observation parseObservation(final String line) throws IOException {
        final String[] fields = line == null ? new String[0] : line.split("\\t");
        if (fields.length == 3
                && DesktopTaskLaunchObserverCommand.OBSERVED.equals(fields[0])
                && "error".equals(fields[1])) {
            throw new IOException("task launch observation failed: " + fields[2]);
        }
        if (fields.length != 8
                || !DesktopTaskLaunchObserverCommand.OBSERVED.equals(fields[0])) {
            throw new IOException("invalid task launch observation: " + line);
        }
        try {
            return new Observation(
                    Integer.parseInt(fields[1]),
                    Integer.parseInt(fields[2]),
                    Integer.parseInt(fields[3]),
                    Integer.parseInt(fields[4]),
                    Integer.parseInt(fields[5]),
                    Integer.parseInt(fields[6]),
                    Integer.parseInt(fields[7]));
        } catch (NumberFormatException error) {
            throw new IOException("invalid task launch observation: " + line,
                    error);
        }
    }

    private static void closeQuietly(final Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // The owned process exits after its one observation.
        }
    }

    static final class Observation {
        final int taskId;
        final int displayId;
        final int windowingMode;
        final int left;
        final int top;
        final int right;
        final int bottom;

        Observation(
                final int taskId,
                final int displayId,
                final int windowingMode,
                final int left,
                final int top,
                final int right,
                final int bottom) {
            this.taskId = taskId;
            this.displayId = displayId;
            this.windowingMode = windowingMode;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        Rect bounds() {
            return new Rect(left, top, right, bottom);
        }

        @Override
        public String toString() {
            return "task=" + taskId
                    + "/display=" + displayId
                    + "/mode=" + windowingMode
                    + "/bounds=[" + left + "," + top + "]["
                    + right + "," + bottom + "]";
        }
    }
}
