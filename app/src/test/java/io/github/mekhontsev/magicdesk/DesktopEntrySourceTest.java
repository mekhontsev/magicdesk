package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public class DesktopEntrySourceTest {
    @Test public void authorityIsExplicitAndUnknownSourcesAreRejected() {
        assertEquals(DesktopEntrySource.TERMUX, DesktopEntrySource.parse("termux"));
        assertEquals(DesktopEntrySource.DESKTOP, DesktopEntrySource.parse("desktop"));
        assertThrows(IllegalArgumentException.class, () -> DesktopEntrySource.parse("auto"));
        assertThrows(IllegalArgumentException.class, () -> DesktopEntrySource.parse(""));
    }

    @Test public void exactCatalogPathIsRequiredWithoutShellFallback() {
        var shortcut = DesktopEntryFile.parseTermuxApplication(
                "[Desktop Entry]\nType=Application\nName=GIMP\nExec=gimp %U\n");
        var entry = new DesktopApplicationRepository.Entry(shortcut, "/prefix/share/applications/gimp.desktop", null);
        assertSame(entry, DesktopEntrySource.find(List.of(entry), entry.desktopFilePath));
        assertThrows(IllegalArgumentException.class, () -> DesktopEntrySource.find(List.of(entry), "/other/gimp.desktop"));
        assertThrows(IllegalArgumentException.class, () -> DesktopEntrySource.find(List.of(), entry.desktopFilePath));
    }

    @Test public void termuxFileArgumentsStayInTheTermuxCommand() {
        var shortcut = DesktopEntryFile.parseTermuxApplication(
                "[Desktop Entry]\nType=Application\nName=GIMP\nExec=gimp %U\n");
        var request = DesktopLaunchRequest.from(shortcut, DesktopLaunchArguments.files(
                List.of("/data/user/10/termux/files/home/a b.png")), "/prefix/share/applications/gimp.desktop").prepareExec();
        assertEquals(DesktopExecBackend.X11, request.exec.backend);
        assertEquals("'gimp' 'file:///data/user/10/termux/files/home/a%20b.png'", request.exec.command);
    }
}
