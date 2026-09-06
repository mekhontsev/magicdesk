package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DiagnosticsActivityTest {
    @Test
    public void successfulCollectionPreservesReport() {
        final DiagnosticsReportResult result =
                DiagnosticsReportResult.collect(() -> "current report");

        assertTrue(result.successful());
        assertEquals("current report", result.report);
        assertEquals("", result.failure);
        assertNull(result.cause);
    }

    @Test
    public void runtimeFailureHasNoReportToCopyOrShare() {
        final RuntimeException error = new SecurityException("observation unavailable");
        final DiagnosticsReportResult result =
                DiagnosticsReportResult.collect(() -> {
                    throw error;
                });

        assertFalse(result.successful());
        assertEquals("", result.report);
        assertEquals("observation unavailable", result.failure);
        assertSame(error, result.cause);
    }

    @Test
    public void exceptionsWithoutMessagesStillDescribeTheFailure() {
        for (final String message : new String[] {null, "", " "}) {
            final DiagnosticsReportResult result =
                    DiagnosticsReportResult.collect(() -> {
                        throw new IllegalStateException(message);
                    });
            assertFalse(result.successful());
            assertEquals("IllegalStateException", result.failure);
        }
    }

    @Test
    public void nullReportIsAFailureAndLaterCollectionCanRecover() {
        assertFalse(DiagnosticsReportResult.collect(() -> null).successful());
        assertTrue(DiagnosticsReportResult.collect(() -> "new report").successful());
    }

    @Test
    public void everyReportWorkerUsesTheGuardedCollector() throws IOException {
        final String source = source();
        assertEquals(1, occurrences(source, "CompatibilityDiagnostics.buildReport("));
        assertEquals(3, occurrences(source,
                "final DiagnosticsReportResult report = collectReport();"));
        assertTrue(source.contains("DiagnosticsReportResult.collect(() ->"));
        final String collection = between(source,
                "private DiagnosticsReportResult collectReport()", "private void showReport(");
        assertTrue(collection.contains("if (!result.successful())"));
        assertTrue(collection.contains(
                "Log.w(\"MagicDeskDiagnostics\", \"Could not collect compatibility report\", result.cause);"));
    }

    @Test
    public void collectionCompletionReleasesLoadingAndPendingSelfTests()
            throws IOException {
        final String source = source();
        final String completion = between(source,
                "private void finishReportCollection(",
                "private void runPendingAutomatedSelfTest()");
        assertTrue(completion.contains("showReport(report, status);"));
        assertTrue(completion.contains("mLoading = false;"));
        assertTrue(completion.contains("setActionsEnabled(true);"));
        assertTrue(completion.contains("runPendingAutomatedSelfTest();"));
        assertTrue(between(source, "private void refreshReport()",
                "private DiagnosticsReportResult collectReport()")
                .contains("finishReportCollection(report,"));
        assertTrue(between(source, "private void collectVendorProbe()",
                "private void setActionsEnabled(")
                .contains("finishReportCollection(report,"));
    }

    @Test
    public void selfTestOutcomeAndCleanupSurviveReportFailure() throws IOException {
        final String completion = between(source(),
                "private void runDesktopSelfTest(", "private void copyReport()");
        assertTrue(completion.contains("showReport(report, result.isCancelled()"));
        assertTrue(completion.contains("R.string.diagnostics_self_test_cancelled"));
        assertTrue(completion.contains("result.summary()"));
        assertTrue(completion.contains("finishSelfTestPreparation(runId);"));
    }

    @Test
    public void failedReportReplacesStaleContentAndCannotBeExported()
            throws IOException {
        final String source = source();
        final String presentation = between(source,
                "private void showReport(", "private void finishReportCollection(");
        assertTrue(presentation.contains("mReport = report.report;"));
        assertTrue(presentation.contains("R.string.diagnostics_report_failed"));
        assertTrue(presentation.contains(
                "mReportView.setText(report.successful() ? mReport : failure);"));
        assertTrue(source.contains("mCopy.setEnabled(enabled && !mReport.isEmpty());"));
        assertTrue(source.contains("mShare.setEnabled(enabled && !mReport.isEmpty());"));
        assertFalse(source.contains("private int getDisplayId()"));
    }

    private static String source() throws IOException {
        return Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/DiagnosticsActivity.java"));
    }

    private static String between(
            final String source, final String start, final String end) {
        final int first = source.indexOf(start);
        final int last = source.indexOf(end, first + start.length());
        assertTrue("method boundaries exist", first >= 0 && last > first);
        return source.substring(first, last);
    }

    private static int occurrences(final String source, final String text) {
        return source.split(java.util.regex.Pattern.quote(text), -1).length - 1;
    }
}
