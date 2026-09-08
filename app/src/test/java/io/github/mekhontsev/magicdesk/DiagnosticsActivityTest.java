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
        assertEquals(2, occurrences(source,
                "final DiagnosticsReportResult report = collectReport();"));
        assertTrue(source.contains("DiagnosticsReportResult.collect(() ->"));
        final String collection = between(source,
                "private DiagnosticsReportResult collectReport()", "private void showReport(");
        assertTrue(collection.contains("if (!result.successful())"));
        assertTrue(collection.contains(
                "Log.w(\"MagicDeskDiagnostics\", \"Could not collect compatibility report\", result.cause);"));
    }

    @Test
    public void collectionCompletionReleasesLoadingWithoutReplacingLiveProgress()
            throws IOException {
        final String source = source();
        final String completion = between(source,
                "private void finishReportCollection(",
                "private void chooseDesktopSelfTestTarget()");
        assertTrue(completion.contains("showReport(report, resultStatus);"));
        assertTrue(completion.contains("mLoading = false;"));
        assertTrue(completion.contains("setActionsEnabled(true);"));
        assertTrue(completion.contains("if (DesktopSelfTestRunState.isActive())"));
        assertTrue(completion.contains("renderRunState();"));
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
                "private void finishReportCollection(", "private void chooseDesktopSelfTestTarget()");
        assertTrue(completion.contains("R.string.diagnostics_self_test_cancelled"));
        assertTrue(completion.contains("state.terminal()"));
        assertTrue(completion.contains("state.detail"));
        final String launcher = Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/DesktopSelfTestLauncher.java"));
        assertTrue(launcher.contains("DesktopSelfTestController.run(mContext,"));
        assertFalse(launcher.contains("collectReport("));
        assertFalse(source().contains("DesktopSelfTestController.run("));
    }

    @Test
    public void diagnosticsObservesProgressOnlyWhileVisible() throws IOException {
        final String source = source();
        assertTrue(source.contains("DesktopSelfTestRunState.addListener(mRunChanged)"));
        assertTrue(source.contains("DesktopSelfTestRunState.removeListener(mRunChanged)"));
        assertTrue(source.contains("DesktopSelfTestGuardWindow.resumed(this, mGuardRunId)"));
        assertTrue(source.contains("DesktopSelfTestPhoneInputGuard.recordTouch(event)"));
        assertTrue(source.contains("DesktopSelfTestPhoneInputGuard.recordKey(event)"));
        assertTrue(source.contains("DesktopSelfTestRunState.requestCancellation(state.runId)"));
        assertFalse(source.contains("postDelayed("));
        assertTrue(source.contains("SystemBarInsets.addToPadding(page, true)"));
        assertFalse(between(source, "protected void onDestroy()", "void releaseSelfTestGuard()")
                .contains("requestCancellation("));
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
        assertTrue(source.contains("setButtonEnabled(mCopy, enabled && !mReport.isEmpty());"));
        assertTrue(source.contains("setButtonEnabled(mShare, enabled && !mReport.isEmpty());"));
        assertFalse(source.contains("private int getDisplayId()"));
    }

    @Test
    public void cancellationKeepsTheWorkerCleanupBoundary() throws IOException {
        final String worker = Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/DesktopSelfTestController.java"));
        assertFalse(between(worker, "final Context appContext", "result.arm(policy);")
                .contains("return finish("));
        assertTrue(between(worker, "result.arm(policy);", "SELFTEST-TARGET-001")
                .contains("DesktopSelfTestRunState.checkpoint();"));
        assertTrue(between(worker, "DesktopSelfTestWindowSuite.run(",
                "DesktopSelfTestDisplayRemovalSuite.run(")
                .contains("DesktopSelfTestRunState.checkpoint();"));
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
