package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.Set;

public final class DesktopFileRepositoryTest {
    @Test
    public void importNamePreservesExtensionAndSkipsExistingSuffixes() {
        final Set<String> occupied = new LinkedHashSet<>();
        occupied.add("Report.pdf");
        occupied.add("report (2).PDF");

        assertEquals(
                "report (3).pdf",
                DesktopFileRepository.uniqueImportName(
                        "report.pdf", occupied));
    }

    @Test
    public void invalidImportNameUsesAvailableFallback() {
        final Set<String> occupied = new LinkedHashSet<>();
        occupied.add("Dropped file");

        assertEquals(
                "Dropped file (2)",
                DesktopFileRepository.uniqueImportName(
                        "../outside.txt", occupied));
    }
}
