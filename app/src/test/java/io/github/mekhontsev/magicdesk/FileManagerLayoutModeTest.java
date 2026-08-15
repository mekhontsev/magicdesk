package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class FileManagerLayoutModeTest {
    @Test
    public void preferenceUsesListAsSafeDefault() {
        assertEquals(FileManagerLayoutMode.LIST,
                FileManagerLayoutMode.fromPreference(null));
        assertEquals(FileManagerLayoutMode.LIST,
                FileManagerLayoutMode.fromPreference("unknown"));
    }

    @Test
    public void preferenceRestoresGrid() {
        assertEquals(FileManagerLayoutMode.GRID,
                FileManagerLayoutMode.fromPreference("GRID"));
    }

    @Test
    public void directoryUsesDesktopFolderIcon() {
        assertEquals(R.drawable.ic_desktop_folder,
                FileIconResolver.forFile(true, "application/octet-stream"));
    }

    @Test
    public void regularFileUsesDesktopMimeIcon() {
        assertEquals(R.drawable.ic_desktop_file_pdf,
                FileIconResolver.forFile(false, "application/pdf"));
    }
}
