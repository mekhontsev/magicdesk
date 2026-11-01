package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** One collected snapshot, shared unchanged as text or an archive. */
final class DiagnosticsReport {
    static final String MIME_TYPE = "application/zip";
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    final String text;
    final String fileName;
    private final Instant generatedAt;

    DiagnosticsReport(final String text, final String brand, final String model,
            final String version, final Instant generatedAt) {
        this.text = Objects.requireNonNull(text);
        this.generatedAt = Objects.requireNonNull(generatedAt);
        fileName = "MagicDesk_" + filePart(brand) + "-" + filePart(model)
                + "_" + filePart(version) + "_" + FILE_TIME.format(generatedAt) + ".zip";
    }

    void writeZip(final OutputStream output) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            final ZipEntry entry = new ZipEntry("report.md");
            entry.setTime(generatedAt.toEpochMilli());
            zip.putNextEntry(entry);
            zip.write(text.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static String filePart(final String value) {
        final String safe = value == null ? "" : value
                .replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("^[._-]+|[._-]+$", "");
        return safe.isEmpty() ? "unknown" : safe.substring(0, Math.min(safe.length(), 64));
    }
}
