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
import java.time.Instant;

public final class DiagnosticsActivityTest {
    @Test
    public void successfulCollectionPreservesReport() {
        final DiagnosticsReport report = report("current report");
        final DiagnosticsReportResult result =
                DiagnosticsReportResult.collect(() -> report);

        assertTrue(result.successful());
        assertSame(report, result.report);
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
        assertNull(result.report);
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
        assertTrue(DiagnosticsReportResult.collect(() -> report("new report")).successful());
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
        assertTrue(source.contains("UiToolLayout.page(this, UiColor.BACKGROUND, true)"));
        assertTrue(RuntimeSourceFixture.methods("UiToolLayout", "page")
                .contains("SystemBarInsets.addToPadding(page, ime)"));
        assertFalse("The Activity must not apply the shared page insets twice", source.contains("SystemBarInsets.addToPadding("));
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
                "mReportView.setText(report.successful() ? mReport.text : failure);"));
        assertTrue(source.contains("setButtonEnabled(mCopy, enabled && mReport != null && !mReport.text.isEmpty());"));
        assertTrue(source.contains("setButtonEnabled(mShare, enabled && mReport != null && !mReport.text.isEmpty() && !mSharing);"));
        assertFalse(source.contains("private int getDisplayId()"));
    }

    @Test
    public void sharingExportsTheCapturedReportOffTheUiThread() throws IOException {
        final String share = between(source(), "private void shareReport()",
                "private void finishReportShare(");
        assertTrue(share.contains("final DiagnosticsReport report = mReport;"));
        assertTrue(share.contains("mSharing = true;"));
        assertTrue(share.indexOf("new Thread(() ->") < share.indexOf("GeneratedContentProvider.publish("));
        assertTrue(share.contains("report.fileName, report::writeZip"));
        assertTrue(share.contains("runOnUiThread(() -> finishReportShare(null, error))"));
        assertTrue(share.contains("runOnUiThread(() -> finishReportShare(uri, null))"));
        assertFalse(share.contains("collectReport("));
        final String completion = between(source(), "private void finishReportShare(",
                "private void reportShareFailure(");
        assertTrue(completion.contains("mSharing = false;"));
        assertTrue(completion.indexOf("isFinishing() || isDestroyed()")
                < completion.indexOf("shareReportArchive(uri)"));
        assertTrue(completion.contains("DesktopSelfTestRunState.isActive()"));
    }

    @Test
    public void archiveUsesSharedContentRoutingWhileCopyKeepsText() throws IOException {
        final String share = between(source(), "private void shareReportArchive(",
                "private void confirmVendorProbe()");
        assertTrue(share.contains("new AndroidContentPayload.UriItem(uri, DiagnosticsReport.MIME_TYPE)"));
        assertTrue(share.contains("AndroidDesktopActionDispatcher.shareContent("));
        assertTrue(share.contains("AndroidContentIntentAdapter.share(content)"));
        assertFalse(share.contains("mReport.text"));
        assertFalse(share.contains("text/plain"));
        assertTrue(between(source(), "private void copyReport()", "private void shareReport()")
                .contains("\"MagicDesk compatibility report\", mReport.text, false"));
    }

    @Test
    public void reportHeaderAndArchiveUseOneGenerationTimestamp() throws IOException {
        final String collector = Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/CompatibilityDiagnostics.java"));
        final String build = between(collector, "static DiagnosticsReport buildReport(",
                "static void clearEvents(");
        assertTrue(build.contains(".append(\"Generated UTC: \").append(generatedAt)"));
        assertTrue(build.contains("BuildConfig.VERSION_NAME, generatedAt)"));
        assertEquals(1, occurrences(build, "System.currentTimeMillis()"));
        assertTrue(Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/DesktopAutomationStateReader.java"))
                .contains("CompatibilityDiagnostics.buildReport(mContext).text"));
    }

    private static DiagnosticsReport report(final String text) {
        return new DiagnosticsReport(text, "Brand", "Model", "1.0", Instant.EPOCH);
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
