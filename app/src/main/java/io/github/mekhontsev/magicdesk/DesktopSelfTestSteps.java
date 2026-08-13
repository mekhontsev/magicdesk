package io.github.mekhontsev.magicdesk;

/** Records self-test steps and controls required-step aborts. */
final class DesktopSelfTestSteps {
    private DesktopSelfTestSteps() {
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
        DesktopSelfTestHostObserver.stage(code);
        try {
            final T value = operation.run();
            result.add(DesktopSelfTestResult.State.PASS,
                    code, label,
                    successDetail == null
                            ? String.valueOf(value) : successDetail);
            return value;
        } catch (Exception error) {
            failAndAbort(result, code, label, usefulMessage(error));
            throw new AssertionError("unreachable");
        }
    }

    static <T> void check(
            final DesktopSelfTestResult result,
            final String code,
            final String label,
            final CheckedSupplier<T> operation) {
        DesktopSelfTestHostObserver.stage(code);
        try {
            result.add(DesktopSelfTestResult.State.PASS,
                    code, label, String.valueOf(operation.run()));
        } catch (Exception error) {
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
        throw new AbortSelfTest();
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

    static final class AbortSelfTest extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
