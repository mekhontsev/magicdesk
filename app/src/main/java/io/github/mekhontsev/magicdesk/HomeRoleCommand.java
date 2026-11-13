package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HOME mutations retain failure evidence before lease recovery changes the state. */
final class HomeRoleCommand {
    private static final int MAX_LOG_LINES = 128;
    private static final int MAX_LOG_CHARS = 8000;
    private static final Set<String> LOG_TAGS = Set.of("RoleManagerService",
            "RoleControllerManager", "RoleControllerService", "RoleControllerServiceImpl",
            "RemoteRoleControllerService", "Role", "HomeRoleBehavior");
    private static final Pattern LOG_LINE = Pattern.compile(
            "^\\s*(\\d+)\\.(\\d{3})\\d*\\s+\\d+\\s+\\d+\\s+[VDIWEF]\\s+([^:]+):.*$");

    private HomeRoleCommand() { }

    static void run(final int userId, final String packageName, final boolean add)
            throws IOException {
        if (userId < 0 || !PackageNameValidator.isSafe(packageName)) {
            throw new IOException("invalid HOME selection request");
        }
        final String operation = add ? "add-role-holder" : "remove-role-holder";
        final long startedAt = System.currentTimeMillis();
        try {
            ShellAccess.run("/system/bin/cmd role " + operation + " --user " + userId
                    + " android.app.role.HOME " + ShellCommandLine.quote(packageName)
                    + " 1"); // MANAGE_HOLDERS_FLAG_DONT_KILL_APP
        } catch (IOException | RuntimeException error) {
            recordFailure(operation, userId, packageName, startedAt, error);
            throw error;
        }
    }

    private static void recordFailure(final String operation, final int userId,
            final String packageName, final long startedAt, final Throwable error) {
        final long endedAt = System.currentTimeMillis();
        try {
            String logs;
            try {
                logs = selectLogs(ShellAccess.run(logCommand(startedAt)), startedAt, endedAt);
            } catch (IOException | RuntimeException unavailable) {
                logs = "unavailable: " + FailureDiagnostics.describe(unavailable, 500);
            }
            CompatibilityDiagnostics.recordSystemOperationFailure("HOME-ROLE-002",
                    "Android HOME role change failed",
                    "operation=" + operation + " user=" + userId + " package=" + packageName
                            + " startedAtMillis=" + startedAt + " endedAtMillis=" + endedAt
                            + "\nroleServiceLog (time-correlated, not exclusive to this request):\n"
                            + logs, error);
        } catch (RuntimeException ignored) {
            // Diagnostics must never replace the rejection or interrupt lease recovery.
        }
    }

    static String logCommand(final long startedAt) {
        final StringBuilder command = new StringBuilder(
                "/system/bin/toybox timeout 3 /system/bin/logcat -b main -b system -d -v epoch -T ")
                .append(String.format(Locale.ROOT, "%d.%03d", startedAt / 1000, startedAt % 1000))
                .append(" -m ").append(MAX_LOG_LINES).append(" -s");
        for (final String tag : new java.util.TreeSet<>(LOG_TAGS)) {
            command.append(' ').append(tag).append(":V");
        }
        return command.append(" '*:S'").toString();
    }

    static String selectLogs(final String output, final long startedAt, final long endedAt) {
        final StringBuilder result = new StringBuilder();
        int count = 0;
        for (final String line : output.split("\\r?\\n")) {
            final Matcher match = LOG_LINE.matcher(line);
            if (!match.matches() || !LOG_TAGS.contains(match.group(3).trim())) continue;
            final long timestamp;
            try {
                timestamp = Math.addExact(Math.multiplyExact(Long.parseLong(match.group(1)), 1000),
                        Long.parseLong(match.group(2)));
            } catch (NumberFormatException | ArithmeticException ignored) {
                continue;
            }
            if (timestamp < startedAt || timestamp > endedAt) continue;
            result.append(FailureDiagnostics.bounded(line, MAX_LOG_CHARS)).append('\n');
            if (++count >= MAX_LOG_LINES || result.length() >= MAX_LOG_CHARS) {
                return FailureDiagnostics.bounded(result.toString(), MAX_LOG_CHARS)
                        + "\n[role log truncated]";
            }
        }
        return result.length() == 0 ? "no matching role-service log entries" : result.toString();
    }
}
