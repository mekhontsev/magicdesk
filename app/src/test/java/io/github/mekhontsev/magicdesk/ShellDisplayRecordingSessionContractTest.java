package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class ShellDisplayRecordingSessionContractTest {
    private static String source() throws Exception {
        return Files.readString(Path.of("src/main/java/io/github/mekhontsev/magicdesk/"
                + "ShellDisplayRecordingSession.java"));
    }

    @Test
    public void interruptedStopTerminatesNativeChildBeforeDiscardingSupervisor() throws Exception {
        final String source = source();
        final String stop = source.substring(source.indexOf("private void stopCapture()"),
                source.indexOf("private void validateCaptureFiles()"));
        final String interrupted = stop.substring(stop.indexOf("catch (InterruptedException"));
        assertTrue(interrupted.indexOf("forceStopVideo();") >= 0);
        assertTrue(interrupted.indexOf("forceStopVideo();")
                < interrupted.indexOf("process.destroyForcibly();"));
    }

    @Test
    public void startupUsesBoundedStateObservation() throws Exception {
        final String source = source();
        assertTrue(source.contains("BoundedStateAwaiter.awaitIo("));
        assertTrue(source.contains("BoundedStateAwaiter.Reason.RECORDING_STARTUP"));
        assertFalse(source.contains("RuntimeDelays.pause("));
    }

    @Test
    public void diagnosticFileReadsAreBoundedBeforeAllocation() throws Exception {
        final String source = source();
        assertFalse(source.contains("Files.readAllBytes("));
        assertTrue(source.contains("input.readNBytes(2_048)"));
        assertTrue(source.contains("input.readNBytes(64)"));
    }
}
