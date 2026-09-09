package io.github.mekhontsev.magicdesk;

/** Records self-test steps and controls required-step aborts. */
final class DesktopSelfTestSteps {
    private DesktopSelfTestSteps() {
    }

    static void scenario(
            final DesktopSelfTestResult result,
            final String code,
            final String label,
            final Scenario operation,
            final CheckedSupplier<String> cleanup) throws AbortSelfTest {
        try {
            operation.run();
        } catch (AbortSelfTest failed) {
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    code + "-REMAINING", label + " dependent checks",
                    "not reached after " + failed.code);
        }
        // Only a verified local cleanup permits another scenario. Cancellation
        // and fail-fast go directly to the controller's unconditional finalizer.
        require(result, code + "-CLEANUP", label + " cleanup", cleanup);
    }

    static <T> T require(
            final DesktopSelfTestResult result,
            final String code,
            final String label,
            final CheckedSupplier<T> operation) throws AbortSelfTest {
        return require(result, code, label, operation, null);
    }

    static <T> T require(
            final DesktopSelfTestResult result,
            final String code,
            final String label,
            final CheckedSupplier<T> operation,
            final String successDetail) throws AbortSelfTest {
        DesktopSelfTestRunState.checkpoint();
        DesktopSelfTestHostObserver.stage(code);
        DesktopSelfTestRunState.stage(result.runId(), code, label);
        try {
            final T value = operation.run();
            DesktopSelfTestRunState.checkpoint();
            result.add(DesktopSelfTestResult.State.PASS,
                    code, label,
                    successDetail == null
                            ? String.valueOf(value) : successDetail);
            return value;
        } catch (DesktopSelfTestRunState.Cancelled cancelled) {
            throw cancelled;
        } catch (Exception error) {
            DesktopSelfTestRunState.checkpoint();
            failAndAbort(result, code, label, usefulMessage(error));
            throw new AssertionError("unreachable");
        }
    }

    static <T> void check(
            final DesktopSelfTestResult result,
            final String code,
            final String label,
            final CheckedSupplier<T> operation) {
        DesktopSelfTestRunState.checkpoint();
        DesktopSelfTestHostObserver.stage(code);
        DesktopSelfTestRunState.stage(result.runId(), code, label);
        try {
            final T value = operation.run();
            DesktopSelfTestRunState.checkpoint();
            result.add(DesktopSelfTestResult.State.PASS,
                    code, label, String.valueOf(value));
        } catch (DesktopSelfTestRunState.Cancelled cancelled) {
            throw cancelled;
        } catch (Exception error) {
            DesktopSelfTestRunState.checkpoint();
            result.add(DesktopSelfTestResult.State.FAIL,
                    code, label, usefulMessage(error));
        }
    }

    static void failAndAbort(
            final DesktopSelfTestResult result,
            final String code,
            final String label,
            final String detail) throws AbortSelfTest {
        result.add(DesktopSelfTestResult.State.FAIL, code, label, detail);
        throw new AbortSelfTest(code);
    }

    static String usefulMessage(final Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        final String message = current.getMessage();
        return message == null || message.isEmpty()
                ? current.getClass().getSimpleName() : message;
    }

    interface CheckedSupplier<T> {
        T run() throws Exception;
    }

    interface Scenario {
        void run() throws AbortSelfTest;
    }

    static final class AbortSelfTest extends Exception {
        private static final long serialVersionUID = 1L;
        final String code;

        AbortSelfTest() {
            this("required prerequisite");
        }

        AbortSelfTest(final String code) {
            super(code);
            this.code = code;
        }
    }
}
