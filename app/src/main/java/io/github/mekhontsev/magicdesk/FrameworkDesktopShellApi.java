package io.github.mekhontsev.magicdesk;

import java.util.regex.Pattern;

/** Advertised WMShell command signatures, not Android desk ownership. */
final class FrameworkDesktopShellApi {
    private FrameworkDesktopShellApi() { }

    static String moveAction(String help) {
        if (hasCommand(help, "moveTaskToDesk", "<taskId>")) return "moveTaskToDesk";
        if (hasCommand(help, "moveToDesktop", "<taskId>")) return "moveToDesktop";
        return null;
    }

    static boolean canExitDesk(String help) {
        return hasCommand(help, "moveTaskOutOfDesk", "<taskId>");
    }

    private static boolean hasCommand(String help, String command, String arguments) {
        if (help == null || !help.contains("desktopmode")) return false;
        // Match the complete signature: an additional required deskId is not optional.
        return Pattern.compile("(?m)^\\h*(?:desktopmode\\h+)?" + Pattern.quote(command)
                + "\\h+" + Pattern.quote(arguments) + "\\h*$").matcher(help).find();
    }
}
