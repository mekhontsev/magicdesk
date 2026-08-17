package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.provider.DocumentsContract;

import org.junit.Test;

import java.util.Arrays;

public final class ConsolePathTextTest {
    @Test
    public void quotesAndInsertsDroppedPaths() {
        final String paths = ConsolePathText.quotePaths(Arrays.asList(
                "/storage/emulated/0/My file.txt",
                "/storage/emulated/0/it's here"));
        assertEquals(
                "'/storage/emulated/0/My file.txt' "
                        + "'/storage/emulated/0/it'\"'\"'s here'",
                paths);
        assertEquals("cat '/tmp/a b' tail",
                ConsolePathText.insert("cat tail", 3, 3, "'/tmp/a b'"));
    }

    @Test
    public void resolvesRelativeSelectedPath() {
        assertEquals("/storage/emulated/0/Download/report.txt",
                ConsolePathText.resolveSelectedPath(
                        "/storage/emulated/0/Desktop",
                        "../Download/report.txt"));
    }

    @Test
    public void completesRelativeDirectory() {
        final ConsolePathText.CompletionRequest request =
                ConsolePathText.completionRequest(
                        "cd Dow", 6, "/storage/emulated/0");
        assertEquals("/storage/emulated/0", request.parentPath);
        assertEquals("Dow", request.namePrefix);
        final ConsolePathText.CompletionResult result =
                ConsolePathText.complete(request, Arrays.asList(
                        info("Download", true), info("Documents", true)));
        assertEquals("'Download/'", result.replacement);
    }

    @Test
    public void completesExecutableFromFirstCommandWord() {
        final ConsolePathText.CompletionRequest request =
                ConsolePathText.completionRequest(
                        "prin", 4, "/storage/emulated/0");
        assertTrue(request.commandName);
        final ConsolePathText.CompletionResult result =
                ConsolePathText.complete(request, Arrays.asList(
                        info("printenv", false, true),
                        info("private", false, false)));
        assertEquals("printenv ", result.replacement);
    }

    @Test
    public void quotedWordKeepsSpacesInsideCompletionBounds() {
        final ConsolePathText.CompletionRequest request =
                ConsolePathText.completionRequest(
                        "cat 'My fol' tail", 11, "/storage/emulated/0");
        assertEquals(4, request.tokenStart);
        assertEquals(12, request.tokenEnd);
        assertEquals("My fol", request.namePrefix);
    }

    private static ShellFileInfo info(
            final String name, final boolean directory) {
        return info(name, directory, false);
    }

    private static ShellFileInfo info(
            final String name,
            final boolean directory,
            final boolean executable) {
        return new ShellFileInfo(
                "/storage/emulated/0/" + name,
                name,
                directory
                        ? DocumentsContract.Document.MIME_TYPE_DIR
                        : "text/plain",
                "",
                0L,
                0L,
                1L,
                1L,
                2000,
                2000,
                0,
                directory,
                false,
                true,
                true,
                executable,
                false);
    }
}
