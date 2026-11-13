package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import org.junit.Test;

public final class HomeRoleCommandTest {
    @Test public void logSelectionRetainsOnlyRoleServicesDuringTheOperation() {
        final String output = "--------- beginning of system\n"
                + "10.100 123 456 E RoleControllerServiceImpl: too old\n"
                + "10.500 123 456 E OtherApp: private message\n"
                + "10.500 123 456 E RoleControllerServiceImpl: Package does not qualify\n"
                + "10.501 123 456 E RoleControllerManager: at android.app.role.Manager.run(Manager.java:1)\n"
                + "11.001 123 456 E RoleManagerService: too new\n"
                + "99999999999999999999.123 1 1 E RoleManagerService: invalid\n";
        final String selected = HomeRoleCommand.selectLogs(output, 10_500, 11_000);
        assertTrue(selected.contains("Package does not qualify"));
        assertTrue(selected.contains("Manager.run"));
        assertFalse(selected.contains("too old"));
        assertFalse(selected.contains("too new"));
        assertFalse(selected.contains("private message"));
        assertFalse(selected.contains("invalid"));
        assertTrue(HomeRoleCommand.selectLogs("no readable logs", 10_500, 11_000)
                .contains("no matching"));
    }

    @Test public void captureIsBoundedAndReadOnly() {
        final String command = HomeRoleCommand.logCommand(1793466377649L);
        assertTrue(command.startsWith("/system/bin/toybox timeout 3 /system/bin/logcat "));
        assertTrue(command.contains("-d -v epoch -T 1793466377.649 -m 128 -s"));
        assertTrue(command.contains("RoleControllerServiceImpl:V"));
        assertTrue(command.endsWith("'*:S'"));
        assertFalse(command.contains("-c"));
        final String lines = "10.500 123 456 E RoleControllerServiceImpl: denied\n".repeat(200);
        assertTrue(HomeRoleCommand.selectLogs(lines, 10_000, 11_000).endsWith("[role log truncated]"));
        final String large = "10.500 123 456 E RoleControllerServiceImpl: " + "x".repeat(20_000);
        assertTrue(HomeRoleCommand.selectLogs(large, 10_000, 11_000).length() < 8100);
    }

    @Test public void successfulChangesDoNotReadLogsOrChangeCommands() throws Exception {
        verify("""
                HomeRoleCommand.run(10, "example.launcher", true);
                HomeRoleCommand.run(10, "example.launcher", false);
                check(commands.equals(List.of(
                        "/system/bin/cmd role add-role-holder --user 10 android.app.role.HOME 'example.launcher' 1",
                        "/system/bin/cmd role remove-role-holder --user 10 android.app.role.HOME 'example.launcher' 1")), commands.toString());
                check(records == 0, "successful change collected diagnostics");
                """);
    }

    @Test public void bothMutationsPreserveFailureAndRecordUnavailableLogs() throws Exception {
        verify("""
                mutationFailure = new IOException("Shell command failed 255: Failed");
                logFailure = new IOException("logcat denied");
                for (boolean add : new boolean[] {true, false}) {
                    try { HomeRoleCommand.run(0, "example.launcher", add); throw new AssertionError("failure swallowed"); }
                    catch (IOException expected) { check(expected == mutationFailure, "failure replaced"); }
                    check(recordedError == mutationFailure, "original error not recorded");
                    check(detail.contains("unavailable: java.io.IOException: logcat denied"), detail);
                    check(detail.contains("operation=" + (add ? "add-role-holder" : "remove-role-holder")), detail);
                }
                check(records == 2 && commands.size() == 4, "unexpected retry or missing evidence");
                """);
    }

    @Test public void diagnosticFailureCannotInterruptRecovery() throws Exception {
        verify("""
                mutationFailure = new IOException("rejected");
                recordFailure = true;
                try { HomeRoleCommand.run(0, "example.launcher", true); throw new AssertionError("failure swallowed"); }
                catch (IOException expected) { check(expected == mutationFailure, "journal error replaced rejection"); }
                check(commands.size() == 2, "diagnostics retried mutation");
                """);
    }

    @Test public void invalidRequestsCannotRunCommands() throws Exception {
        verify("""
                try { HomeRoleCommand.run(-1, "example.launcher", true); throw new AssertionError("negative user"); }
                catch (IOException expected) { }
                try { HomeRoleCommand.run(0, "bad;command", false); throw new AssertionError("unsafe package"); }
                catch (IOException expected) { }
                check(commands.isEmpty() && records == 0, "invalid request caused side effects");
                """);
    }

    private static void verify(final String scenario) throws Exception {
        final String owner = RuntimeSourceFixture.nestedClass("HomeRoleCommand", "HomeRoleCommand")
                .replace("Pattern ", "java.util.regex.Pattern ")
                .replace("Pattern.compile", "java.util.regex.Pattern.compile")
                .replace("Matcher ", "java.util.regex.Matcher ");
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static final List<String> commands = new ArrayList<>();
                static IOException mutationFailure, logFailure;
                static Throwable recordedError;
                static String detail;
                static int records;
                static boolean recordFailure;
                static class ShellAccess {
                    static String run(String command) throws IOException {
                        commands.add(command);
                        if (command.startsWith("/system/bin/cmd role ")) {
                            if (mutationFailure != null) throw mutationFailure;
                            return "";
                        }
                        if (logFailure != null) throw logFailure;
                        return "";
                    }
                }
                static class CompatibilityDiagnostics {
                    static void recordSystemOperationFailure(String code, String message, String value, Throwable error) {
                        if (recordFailure) throw new IllegalStateException("journal unavailable");
                        check(code.equals("HOME-ROLE-002"), code);
                        records++; detail = value; recordedError = error;
                    }
                }
                static
                """ + owner + "\npublic static void verify() throws Exception {\n" + scenario + "}\n",
                "FailureDiagnostics", "BoundedText", "PackageNameValidator", "ShellCommandLine");
    }
}
