package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.List;

public final class ConsolePathTextTest {
    @Test
    public void quotesDroppedPathsAsIndividualShellArguments() {
        assertEquals(
                "'/storage/emulated/0/My file.txt' "
                        + "'/storage/emulated/0/it'\"'\"'s here'",
                ConsolePathText.quotePaths(List.of(
                        "/storage/emulated/0/My file.txt",
                        "/storage/emulated/0/it's here")));
    }

    @Test
    public void droppedPathsKeepSupplementaryCharactersAndShellSyntaxLiteral() {
        assertEquals("'/tmp/\ud83d\ude80;$(id)'",
                ConsolePathText.quotePaths(List.of("/tmp/./\ud83d\ude80;$(id)")));
    }

    @Test
    public void droppedPathsRejectMissingRelativeAndNulValues() {
        assertThrows(IllegalArgumentException.class, () -> ConsolePathText.quotePaths(null));
        assertThrows(IllegalArgumentException.class, () -> ConsolePathText.quotePaths(List.of()));
        for (final String path : List.of("relative", "/tmp\0other")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ConsolePathText.quotePaths(List.of(path)));
        }
    }

    @Test
    public void resolvesRelativeSelectedPath() {
        assertEquals("/storage/emulated/0/Download/report.txt",
                ConsolePathText.resolveSelectedPath(
                        "/storage/emulated/0/Desktop", "../Download/report.txt"));
    }

    @Test
    public void selectedAbsoluteAndQuotedPathsDoNotDependOnWorkingDirectory() {
        assertEquals("/tmp/space name",
                ConsolePathText.resolveSelectedPath("/elsewhere", "'/tmp/space name'"));
        assertEquals("/tmp/file",
                ConsolePathText.resolveSelectedPath("/elsewhere", " \"/tmp/file\" "));
        assertEquals("/tmp/\ud83d\ude80",
                ConsolePathText.resolveSelectedPath("/tmp", "\ud83d\ude80"));
    }

    @Test
    public void selectedPathRejectsEmptyMultilineAndNulText() {
        for (final String text : new String[]{null, "", "  ", "''", "x\ny", "x\ry", "x\0y"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> ConsolePathText.resolveSelectedPath("/tmp", text));
        }
    }
}
