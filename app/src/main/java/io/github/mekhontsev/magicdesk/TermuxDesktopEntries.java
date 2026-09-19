package io.github.mekhontsev.magicdesk;

import android.content.Context;

/** User launcher storage through the captured Termux endpoint, without privileged filesystem access. */
final class TermuxDesktopEntries {
    static void create(Context context, TermuxIntegration.Endpoint endpoint,
            DesktopApplicationShortcut shortcut, TermuxIntegration.ResultCallback callback) {
        TermuxIntegration.runBackgroundShellCommandForResult(context, endpoint,
                createCommand(DesktopEntryFile.shortcutFileName(shortcut.name)),
                "MagicDesk application shortcut", endpoint.homeDirectory, 15_000,
                DesktopEntryFile.encodeApplication(shortcut), callback);
    }

    static String createCommand(String fileName) {
        String checked = DesktopPathPolicy.validateName(fileName);
        // Publish only a complete file. A repeated identical request succeeds without another entry.
        return "set -eu; umask 077; directory=\"${XDG_DATA_HOME:-$HOME/.local/share}/applications\"; "
                + "mkdir -p -- \"$directory\"; destination=\"$directory/\"" + ShellCommandLine.quote("magicdesk-" + checked) + "; "
                + "temporary=$(mktemp \"$directory/.magicdesk.XXXXXX\"); "
                + "trap 'rm -f -- \"$temporary\"' EXIT; cat > \"$temporary\"; "
                // Some coreutils versions report a skipped no-clobber move as failure.
                + "if mv -nT -- \"$temporary\" \"$destination\"; then "
                + "[ -e \"$temporary\" ] || exit 0; "
                + "else [ -e \"$destination\" ] || exit 1; fi; "
                + "if ! cmp -s -- \"$temporary\" \"$destination\"; then "
                + "printf '%s\\n' 'An application with this name already exists' >&2; exit 1; fi";
    }
    static void delete(Context context, TermuxIntegration.Endpoint endpoint, String path,
            TermuxIntegration.ResultCallback callback) {
        TermuxIntegration.runBackgroundShellCommandForResult(context, endpoint, deleteCommand(path),
                "Delete MagicDesk shortcut", endpoint.homeDirectory, 15_000, callback);
    }

    static String deleteCommand(String path) {
        if (path == null || !path.startsWith("/") || path.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid shortcut path");
        String name = DesktopPathPolicy.validateName(path.substring(path.lastIndexOf('/') + 1));
        if (!name.startsWith("magicdesk-") || !name.endsWith(".desktop"))
            throw new IllegalArgumentException("Not a MagicDesk user shortcut");
        return "set -eu; directory=\"${XDG_DATA_HOME:-$HOME/.local/share}/applications\"; "
                + "target=" + ShellCommandLine.quote(path) + "; "
                + "[ \"$target\" = \"$directory/\"" + ShellCommandLine.quote(name)
                + " ] || { printf '%s\\n' 'Shortcut is outside the user applications directory' >&2; exit 1; }; "
                + "case \"$(realpath -m -- \"$directory\")/\" in \"$(realpath -m -- \"$PREFIX\")/\"*) "
                + "printf '%s\\n' 'Package-managed applications cannot be deleted here' >&2; exit 1;; esac; "
                + "[ ! -L \"$target\" ] || { printf '%s\\n' 'Shortcut is a symbolic link' >&2; exit 1; }; "
                + "[ -e \"$target\" ] || exit 0; "
                + "[ -f \"$target\" ] || { printf '%s\\n' 'Shortcut is not a regular file' >&2; exit 1; }; "
                + "rm -- \"$target\"";
    }

    private TermuxDesktopEntries() { }
}
