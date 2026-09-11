package io.github.mekhontsev.magicdesk;

/** Owned shell startup, independent of transport. Never edits the user's shell configuration. */
final class TerminalShellIntegration {
    private TerminalShellIntegration() { }

    static String androidPrompt(final boolean root) {
        // mksh's SOH/CR prefix declares zero-width prompt delimiters. It has no
        // pre-execution hook, so A/B are honest prompt marks, not inferred C/D.
        return """
                _magicdesk_prompt() {
                    local status=$? title=${PWD//[[:cntrl:]]/}
                    REPLY=$'\\001\\e]0;'"${title:0:1024}"$'\\a\\e]133;A\\a\\001'
                    (( status )) && REPLY+="[exit $status] "
                    REPLY+="$title "'PROMPT'$'\\001\\e]133;B\\a\\001'
                    return "$status"
                }
                PS1=$'\\001\\r''${| _magicdesk_prompt; }'
                """.replace("PROMPT", root ? "# " : "$ ");
    }

    static final String BASH_RC = """
            # The service loaded the login environment; Bash loads its compiled-in
            # global rc itself. Read the user rc before adding OSC hooks.
            if [[ -r ~/.bashrc ]]; then source ~/.bashrc; fi
            _magicdesk_prompt_start() {
                local status=$? title=${PWD//[[:cntrl:]]/}
                printf '\\033]133;D;%s\\007\\033]0;%s\\007' "$status" "${title:0:1024}"
                return "$status"
            }
            _magicdesk_prompt_end() {
                local status=$?
                if [[ $PS1 != "${_magicdesk_wrapped_prompt-}" ]]; then _magicdesk_user_prompt=$PS1; fi
                _magicdesk_wrapped_prompt='\\[\\e]133;A\\a\\]'"${_magicdesk_user_prompt-}"'\\[\\e]133;B\\a\\]'
                PS1=$_magicdesk_wrapped_prompt
                return "$status"
            }
            PROMPT_COMMAND=(_magicdesk_prompt_start "${PROMPT_COMMAND[@]}" _magicdesk_prompt_end)
            PS0='\\e]133;C\\a'"${PS0-}"
            """;

    static String termuxBootstrap() {
        return termuxShellSelection() + "rc=\"${HOME:?}/.local/share/magicdesk/bashrc\"\n"
                + "mkdir -p \"${rc%/*}\"\n"
                + "rc_tmp=\"${rc}.$$\"\n"
                + "trap 'rm -f \"$rc_tmp\"' EXIT HUP INT TERM\n"
                + "printf '%s' " + ShellCommandLine.quote(BASH_RC) + " > \"$rc_tmp\"\n"
                + "chmod 600 \"$rc_tmp\"\n"
                + "mv -f \"$rc_tmp\" \"$rc\"\n"
                + "trap - EXIT HUP INT TERM\n"
                + "export MAGICDESK_BASH_RC=\"$rc\"\n";
    }

    static String termuxShellSelection() {
        // RUN_COMMAND can inherit the login dispatcher rather than its final shell.
        // Resolve only that standard entry point, honoring Termux's user selection.
        return """
                shell=${SHELL:-${PREFIX:?}/bin/bash}
                if [ "$shell" = "${PREFIX:?}/bin/login" ]; then
                    if [ -G "${HOME:?}/.termux/shell" ]; then
                        shell=$(realpath "${HOME:?}/.termux/shell")
                    else
                        shell="${PREFIX:?}/bin/bash"
                    fi
                fi
                export SHELL="$shell"
                """;
    }
}
