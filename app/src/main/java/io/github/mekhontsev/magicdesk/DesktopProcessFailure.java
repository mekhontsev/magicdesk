package io.github.mekhontsev.magicdesk;

final class DesktopProcessFailure {
    static final int CRASH = 1;
    static final int ANR = 2;

    private static final int MAX_REASON_LENGTH = 160;

    private DesktopProcessFailure() {
    }

    static String code(final int type) {
        if (type == CRASH) {
            return "DESKTOP-PROCESS-CRASH-001";
        }
        if (type == ANR) {
            return "DESKTOP-PROCESS-ANR-001";
        }
        return "";
    }

    static String message(final int type) {
        if (type == CRASH) {
            return "Desktop application process crashed";
        }
        if (type == ANR) {
            return "Desktop application is not responding";
        }
        return "";
    }

    static String technicalDetail(
            final String processName,
            final int pid,
            final int taskId,
            final int displayId,
            final int windowingMode,
            final String topActivity,
            final String reason) {
        final StringBuilder detail = new StringBuilder()
                .append("process=").append(processName)
                .append(" | pid=").append(pid)
                .append(" | task=").append(taskId)
                .append(" | display=").append(displayId)
                .append(" | windowingMode=").append(windowingMode);
        if (topActivity != null && !topActivity.isEmpty()) {
            detail.append(" | top=").append(topActivity);
        }
        final String compactReason = compactReason(reason);
        if (!compactReason.isEmpty()) {
            detail.append(" | reason=").append(compactReason);
        }
        return detail.toString();
    }

    static String compactReason(final String reason) {
        if (reason == null) {
            return "";
        }
        final StringBuilder compact = new StringBuilder();
        boolean previousWhitespace = false;
        for (int index = 0; index < reason.length(); index++) {
            final char character = reason.charAt(index);
            if (Character.isWhitespace(character)) {
                if (compact.length() > 0 && !previousWhitespace) {
                    compact.append(' ');
                }
                previousWhitespace = true;
            } else {
                compact.append(character);
                previousWhitespace = false;
            }
            if (compact.length() >= MAX_REASON_LENGTH) {
                break;
            }
        }
        final int length = compact.length();
        if (length > 0 && compact.charAt(length - 1) == ' ') {
            compact.setLength(length - 1);
        }
        return compact.toString();
    }
}
