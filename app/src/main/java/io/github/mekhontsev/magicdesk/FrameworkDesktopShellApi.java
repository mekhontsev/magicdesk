package io.github.mekhontsev.magicdesk;

import java.util.regex.Pattern;

/** Advertised WMShell command signatures, not Android desk ownership. */
final class FrameworkDesktopShellApi {
    enum Mode { NATIVE, BASIC, UNKNOWN }

    enum Transport {
        STATUS_BAR("/system/bin/cmd statusbar wmshell-passthrough"),
        WINDOW("/system/bin/cmd window shell");

        private final String command;
        Transport(String command) { this.command = command; }
    }

    private final String mMoveAction;
    private final boolean mCanExit;
    private final Mode mMode;

    private FrameworkDesktopShellApi(String moveAction, boolean canExit, Mode mode) {
        mMoveAction = moveAction;
        mCanExit = canExit;
        mMode = mode;
    }

    static FrameworkDesktopShellApi fromHelp(String help) {
        return new FrameworkDesktopShellApi(moveAction(help),
                hasCommand(help, "moveTaskOutOfDesk", "<taskId>"), mode(help));
    }

    private static Mode mode(String help) {
        if (help == null) return Mode.UNKNOWN;
        if (Pattern.compile("(?m)^\\h*desktopmode(?:\\h|$)").matcher(help).find()) return Mode.NATIVE;
        return Pattern.compile("(?m)^\\h*Window Manager Shell commands:\\h*$").matcher(help).find()
                ? Mode.BASIC : Mode.UNKNOWN;
    }

    Mode mode() { return mMode; }

    static String helpCommand() { return Transport.STATUS_BAR.command + " help"; }

    static String repositoryDumpCommand() {
        return "/system/bin/dumpsys activity service "
                + "com.android.systemui/.SystemUIService"
                + " | /system/bin/awk 'BEGIN { found=0; done=0; base=0 } "
                + "{ line=$0; stripped=line; sub(/^[ ]*/, \"\", stripped); "
                + "indent=length(line)-length(stripped); "
                + "if (!found && !done "
                + "&& stripped == \"DesktopUserRepositories:\") "
                + "{ found=1; base=indent } "
                + "else if (found && stripped != \"\" && indent <= base) "
                + "{ found=0; done=1 } if (found) print line }'";
    }

    boolean canEnterDesktop() { return mMoveAction != null; }
    boolean canExitDesktop() { return mCanExit; }

    String enterDesktopCommand(Transport transport, int taskId) {
        if (!canEnterDesktop()) throw new IllegalStateException("WMShell desktop entry unavailable");
        return taskCommand(transport, mMoveAction, taskId);
    }

    String exitDesktopCommand(Transport transport, int taskId) {
        if (!canExitDesktop()) throw new IllegalStateException("WMShell desktop exit unavailable");
        return taskCommand(transport, "moveTaskOutOfDesk", taskId);
    }

    String entryDescription() {
        return "wmshell-passthrough desktopmode " + (canEnterDesktop() ? mMoveAction : "unavailable");
    }

    private static String taskCommand(Transport transport, String action, int taskId) {
        if (taskId < 0) throw new IllegalArgumentException("invalid task id");
        return transport.command + " desktopmode " + action + " " + taskId;
    }

    private static String moveAction(String help) {
        if (hasCommand(help, "moveTaskToDesk", "<taskId>")) return "moveTaskToDesk";
        if (hasCommand(help, "moveToDesktop", "<taskId>")) return "moveToDesktop";
        return null;
    }

    private static boolean hasCommand(String help, String command, String arguments) {
        if (help == null || !help.contains("desktopmode")) return false;
        // Match the complete signature: an additional required deskId is not optional.
        return Pattern.compile("(?m)^\\h*(?:desktopmode\\h+)?" + Pattern.quote(command)
                + "\\h+" + Pattern.quote(arguments) + "\\h*$").matcher(help).find();
    }
}
