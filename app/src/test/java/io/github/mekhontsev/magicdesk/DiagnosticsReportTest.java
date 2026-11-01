package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class DiagnosticsReportTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final Instant GENERATED = Instant.parse("2026-10-31T17:06:56.129Z");

    @Test
    public void fileNameIdentifiesDeviceVersionAndCollectionTime() {
        final DiagnosticsReport report = report("text");
        assertEquals("MagicDesk_POCO-2510EPC8BG_2.2.0.1_20261031-170656Z.zip", report.fileName);
        assertEquals(report.fileName, ShellFileNamePolicy.validate(report.fileName));
        assertEquals("application/zip", DiagnosticsReport.MIME_TYPE);
    }

    @Test
    public void fileNameDoesNotDependOnLocalTimeZoneOrLocale() {
        final Locale locale = Locale.getDefault();
        final TimeZone zone = TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"));
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            assertEquals("MagicDesk_POCO-2510EPC8BG_2.2.0.1_20261031-170656Z.zip", report("text").fileName);
        } finally {
            Locale.setDefault(locale);
            TimeZone.setDefault(zone);
        }
    }

    @Test
    public void fileNameSanitizesMetadataAndRetainsDevelopmentVersions() {
        final DiagnosticsReport report = new DiagnosticsReport("text", "Brand / Name",
                "../Model\\\n:*?\"<>|", "2.2.4-dev.123+abc", GENERATED);
        assertEquals("MagicDesk_Brand-Name-Model_2.2.4-dev.123-abc_20261031-170656Z.zip", report.fileName);
        assertTrue(report.fileName.matches("[A-Za-z0-9._-]+"));
    }

    @Test
    public void fileNameHandlesMissingAndLongMetadata() {
        assertEquals("MagicDesk_unknown-unknown_unknown_20261031-170656Z.zip",
                new DiagnosticsReport("text", null, "...", "\u0000/", GENERATED).fileName);
        final DiagnosticsReport longName = new DiagnosticsReport("text", "b".repeat(400),
                "m".repeat(400), "v".repeat(400), GENERATED);
        assertTrue(longName.fileName.length() <= 255);
        assertTrue(longName.fileName.endsWith("_20261031-170656Z.zip"));
    }

    @Test
    public void zipContainsOnlyTheExactDisplayedUtf8Report() throws Exception {
        final String text = "# Report\n\nGenerated UTC: " + GENERATED
                + "\nDevice: \u65e5\u672c \uD83D\uDE00\n```json\n{\"value\":1}\n```\n";
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        report(text).writeZip(output);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()),
                StandardCharsets.UTF_8)) {
            final var entry = zip.getNextEntry();
            assertEquals("report.md", entry.getName());
            assertFalse(entry.isDirectory());
            assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), zip.readAllBytes());
            assertNull(zip.getNextEntry());
        }
    }

    @Test
    public void publishedArchiveHasACentralDirectoryAndIndependentRepeatedExports() throws Exception {
        final var directory = temporary.newFolder();
        final DiagnosticsReport report = report("current report\n".repeat(3000));
        final long now = GENERATED.plusSeconds(600).toEpochMilli();
        final String first = GeneratedContentStore.publish(directory, report::writeZip, now);
        final String second = GeneratedContentStore.publish(directory, report::writeZip, now);
        assertNotEquals(first, second);
        for (final String id : new String[] { first, second }) {
            final var file = GeneratedContentStore.resolve(directory, id, now + 1);
            assertTrue(file.length() < report.text.getBytes(StandardCharsets.UTF_8).length);
            try (ZipFile zip = new ZipFile(file, StandardCharsets.UTF_8)) {
                assertEquals(1, zip.size());
                assertArrayEquals(report.text.getBytes(StandardCharsets.UTF_8),
                        zip.getInputStream(zip.getEntry("report.md")).readAllBytes());
            }
        }
        assertEquals("MagicDesk_POCO-2510EPC8BG_2.2.0.1_20261031-170656Z.zip", report.fileName);
    }

    @Test
    public void failedArchiveWritePropagatesAndLeavesNoPartialPublishedFile() throws Exception {
        final var directory = temporary.newFolder();
        assertThrows(IOException.class, () -> GeneratedContentStore.publish(directory,
                output -> report("text").writeZip(new OutputStream() {
                    @Override public void write(final int value) throws IOException {
                        throw new IOException("disk unavailable");
                    }
                }), GENERATED.toEpochMilli()));
        try (var files = Files.list(directory.toPath())) {
            assertEquals(0, files.count());
        }
    }

    private static DiagnosticsReport report(final String text) {
        return new DiagnosticsReport(text, "POCO", "2510EPC8BG", "2.2.0.1", GENERATED);
    }
}
