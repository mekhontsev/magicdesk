package io.github.mekhontsev.magicdesk;

import java.util.Objects;
import java.util.function.Supplier;

/** Report collection failure is independent of a completed probe or self-test. */
final class DiagnosticsReportResult {
    final DiagnosticsReport report;
    final String failure;
    final RuntimeException cause;

    private DiagnosticsReportResult(
            final DiagnosticsReport report, final String failure, final RuntimeException cause) {
        this.report = report;
        this.failure = failure;
        this.cause = cause;
    }

    static DiagnosticsReportResult collect(final Supplier<DiagnosticsReport> collector) {
        try {
            return new DiagnosticsReportResult(
                    Objects.requireNonNull(collector.get(), "report is unavailable"),
                    "", null);
        } catch (RuntimeException error) {
            final String message = error.getMessage();
            return new DiagnosticsReportResult(
                    null,
                    message == null || message.isBlank()
                            ? error.getClass().getSimpleName() : message,
                    error);
        }
    }

    boolean successful() {
        return cause == null;
    }
}
