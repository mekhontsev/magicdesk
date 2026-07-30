package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public final class BoundedProcessRunnerTest {
    @Test
    public void truncatesCommandOutput() throws Exception {
        final FakeProcess process = new FakeProcess("abcdefgh", true, 7);

        final BoundedProcessRunner.Result result =
                BoundedProcessRunner.run(process, 1000L, 4);

        assertEquals(7, result.exitCode);
        assertTrue(result.truncated);
        assertTrue(result.output.startsWith("abcd"));
        assertTrue(result.output.contains("output truncated"));
    }

    @Test
    public void terminatesCommandAtDeadline() throws Exception {
        final FakeProcess process = new FakeProcess("", false, 0);

        try {
            BoundedProcessRunner.run(process, 1L, 1024);
            fail("Expected command timeout");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("timed out"));
        }
        assertTrue(process.destroyed);
    }

    private static final class FakeProcess extends Process {
        private final InputStream mOutput;
        private final OutputStream mInput = new ByteArrayOutputStream();
        private final int mExitCode;
        private final boolean mCompletes;
        private boolean mAlive = true;
        boolean destroyed;

        FakeProcess(
                final String output,
                final boolean completes,
                final int exitCode) {
            mOutput = new ByteArrayInputStream(
                    output.getBytes(StandardCharsets.UTF_8));
            mCompletes = completes;
            mExitCode = exitCode;
        }

        @Override
        public OutputStream getOutputStream() {
            return mInput;
        }

        @Override
        public InputStream getInputStream() {
            return mOutput;
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() throws InterruptedException {
            mAlive = false;
            return mExitCode;
        }

        @Override
        public boolean waitFor(
                final long timeout,
                final TimeUnit unit) {
            if (!mAlive) {
                return true;
            }
            if (mCompletes) {
                mAlive = false;
                return true;
            }
            return false;
        }

        @Override
        public int exitValue() {
            if (mAlive) {
                throw new IllegalThreadStateException();
            }
            return mExitCode;
        }

        @Override
        public void destroy() {
            destroyed = true;
            mAlive = false;
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return mAlive;
        }
    }
}
