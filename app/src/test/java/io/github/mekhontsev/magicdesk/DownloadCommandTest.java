package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class DownloadCommandTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void invalidArgumentsNeverOpenNetwork() {
        for (String[] args : new String[][] {
                {"download"}, {"download", "https://host.test"},
                {"download", "--sha256", "bad", "https://host.test", "file"},
                {"download", "--sha256", "0".repeat(64), "--sha256", "0".repeat(64), "https://host.test", "file"},
                {"download", "--sha256"}, {"download", "--bad", "https://host.test", "file"},
                {"download", "file:///etc/passwd", "file"},
                {"download", "https://name:secret@host.test/file", "file"},
                {"download", "https://host.test:70000/file", "file"},
                {"download", "https://host.test:0/file", "file"},
                {"download", "https://host.test/file#fragment", "file"},
                {"download", "https://host.test", ""},
                {"download", "https://host.test", "file", "extra"}}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            assertEquals(java.util.Arrays.toString(args), 2, DownloadCommand.run(args,
                    new PrintStream(out), new PrintStream(err), uri -> { throw new AssertionError("network opened"); }));
            assertEquals(0, out.size());
            assertTrue(err.size() > 0);
        }
    }

    @Test public void progressAndFailuresUseStderrAndDownloadsNeverExecuteContent() throws Exception {
        Path target = temporary.newFolder().toPath().resolve("installer.sh");
        String script = "#!/system/bin/sh\nexit 123\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        AtomicInteger calls = new AtomicInteger();
        String[] args = {"download", "https://host.test/script", target.toString()};
        assertEquals(0, DownloadCommand.run(args, new PrintStream(out), new PrintStream(err), uri -> {
            calls.incrementAndGet();
            return new HttpDownloadTest.Response(uri).data(script);
        }));
        assertEquals(script, Files.readString(target));
        assertEquals(0, out.size());
        assertTrue(err.toString().contains("Saved"));
        assertEquals(1, calls.get());
        err.reset();
        assertEquals(1, DownloadCommand.run(args, new PrintStream(out), new PrintStream(err), uri -> {
            throw new javax.net.ssl.SSLHandshakeException("certificate rejected");
        }));
        assertTrue(err.toString().contains("certificate rejected"));
        assertEquals(script, Files.readString(target));
        assertEquals(0, out.size());
    }
}
