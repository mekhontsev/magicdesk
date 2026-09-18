package io.github.mekhontsev.magicdesk;

/** Explicit user-run setup; never dispatched through RUN_COMMAND or privileged access. */
final class TermuxSetupCommand {
    static final String SCRIPT = """
            (
              set -eu
              dir="${HOME:?}/.termux"
              mkdir -p "$dir"
              file="$dir/termux.properties"
              touch "$file"
              tmp="$(mktemp "$dir/termux.properties.XXXXXX")"
              trap 'rm -f "$tmp"' EXIT
              awk '
                /^[ \\t]*allow-external-apps([ \\t=:]|$)/ {
                  if (!written++) print "allow-external-apps = true"
                  next
                }
                { print }
                END { if (!written) print "allow-external-apps = true" }
              ' "$file" > "$tmp"
              cat "$tmp" > "$file"
              termux-reload-settings
            )""";

    private TermuxSetupCommand() { }
}
