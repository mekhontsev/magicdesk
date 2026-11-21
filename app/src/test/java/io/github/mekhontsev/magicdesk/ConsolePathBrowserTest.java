package io.github.mekhontsev.magicdesk;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConsolePathBrowserTest {
    @Test public void pathIsOneLiteralArgumentAndDirectoriesPrecedeFiles() throws Exception {
        var root=Files.createTempDirectory("console paths ' ");
        String name="a' ; echo WRONG";
        try {
            Files.createDirectory(root.resolve("sub")); Files.createFile(root.resolve(name)); Files.createFile(root.resolve(".hidden"));
            var shell=new ProcessBuilder("sh","-c",ConsolePathBrowser.LIST,"sh",root.toString()).start();
            var listing=ConsolePathBrowser.parse(new String(shell.getInputStream().readAllBytes(),StandardCharsets.UTF_8));
            assertEquals(0,shell.waitFor());
            assertTrue(listing.directory());
            assertEquals(root.toString(),listing.path());
            assertEquals(java.util.List.of(new ConsolePathBrowser.Entry("sub",true),
                    new ConsolePathBrowser.Entry(".hidden",false),new ConsolePathBrowser.Entry(name,false)),listing.children());
        } finally {
            Files.deleteIfExists(root.resolve(".hidden")); Files.deleteIfExists(root.resolve(name));
            Files.deleteIfExists(root.resolve("sub")); Files.delete(root);
        }
    }
    @Test public void incompleteAndEscapingEntriesAreNeverNavigable() {
        for (var bad:java.util.List.of("D/\0F..\0","D/\0D/a\0","D/\0Ffile","banner\nD/\0"))
            assertThrows(java.io.IOException.class,()->ConsolePathBrowser.parse(bad));
    }
    @Test public void narrowLayoutsAlwaysReserveSessionAndOverflow() {
        assertEquals(0,ConsoleTerminalWindow.primarySlots(130,120,48));
        assertEquals(3,ConsoleTerminalWindow.primarySlots(320,120,48));
        assertEquals(5,ConsoleTerminalWindow.primarySlots(410,120,48));
    }
}
