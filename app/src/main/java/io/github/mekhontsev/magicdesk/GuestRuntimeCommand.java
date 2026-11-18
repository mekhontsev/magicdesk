package io.github.mekhontsev.magicdesk;

/** Lazy CLI adapter. Preparation is a child command, never the owner of application FDs. */
public final class GuestRuntimeCommand {
    public static final String LIBRARIES_ENV = "MAGICDESK_GUEST_LIBRARIES";
    public static final String BODY = "unset LD_PRELOAD LD_LIBRARY_PATH\n"
            + "md_root=\"${MAGICDESK_GUEST_HOME:-$HOME/linux}\"; md_selected=\n"
            + "if [ \"${1-}\" = --library ]; then\n"
            + "  [ \"$#\" -ge 3 ] && [ -n \"$2\" ] || { echo 'Usage: shroot --library DIR COMMAND' >&2; exit 2; }\n"
            + "  md_root=$2; md_selected=1; shift 2\n"
            + "fi\n"
            + "if [ \"${1-}\" = --print-library ]; then [ \"$#\" -eq 1 ] || exit 2; printf '%s\\n' \"$md_root\"; exit; fi\n"
            + "md_prepared=$(CLASSPATH=\"${MAGICDESK_COMMAND_APK:?Open a new MagicDesk console}\" "
            + "/system/bin/app_process / io.github.mekhontsev.magicdesk.GuestRuntimeMain "
            + "\"${" + LIBRARIES_ENV + ":?Shroot is unavailable}\" "
            + "\"${MAGICDESK_RUNTIME:?Missing shell runtime}/guest-runtime\") || exit $?\n"
            + "eval \"$md_prepared\"\n"
            + "md_launch() { exec \"${MAGICDESK_COMMAND_BRIDGE:?Missing command bridge}\" --shroot \"$md_commands\" \"$@\"; }\n"
            + "md_library() { CLASSPATH=\"$MAGICDESK_COMMAND_APK\" /system/bin/app_process / "
            + "io.github.mekhontsev.magicdesk.GuestEnvironmentMain \"$md_guest\" "
            + "\"$md_root\" \"$@\"; }\n"
            + "md_image() { md_action=$1; md_store=$2; shift 2\n"
            + "case \"$md_action\" in run|exec|login|backup|remove|resolver) case \"$md_store\" in ''|*/*) ;; *) md_store=./$md_store ;; esac ;; esac\n"
            + "case \"$md_action\" in run|exec|login)\n"
            + LinuxAudioEnvironment.shrootArguments() + GamepadWire.shrootArguments()
            + TerminalFilesEnvironment.shrootArguments() + ";; esac\n"
            + "case \"$md_action\" in run|exec|login) md_launch \"$md_guest/libshroot_cli.so\" \"$md_action\" \"$md_store\" \"$@\" ;;\n"
            + "*) exec \"$md_guest/libshroot_cli.so\" \"$md_action\" \"$md_store\" \"$@\" ;; esac; }\n"
            + "case \"${1-}\" in\n"
            + "  run|exec|login) md_action=$1; shift\n"
            + "     md_store=$(md_library resolve \"${1-}\") || exit $?\n"
            + "     shift\n"
            + "     \"$md_guest/libshroot_guest_supervisor.so\" \"$md_guest/libshroot_guest_bootstrap.so\" --probe >/dev/null || exit $?\n"
            + "     md_image \"$md_action\" \"$md_store\" \"$@\" ;;\n"
            + "  install|list|inspect|path|backup|restore|remove|prune|dns|launches|stop|applications|icons) md_library \"$@\"; exit $? ;;\n"
            + "  ''|-h|--help|help) md_library help; exit $? ;;\n"
            + "esac\n"
            + "[ -z \"$md_selected\" ] || { echo '--library requires an environment command' >&2; exit 2; }\n"
            + "case \"${1-}\" in\n"
            + "  image) shift\n"
            + "     if [ \"${1-}\" = run ] || [ \"${1-}\" = exec ] || [ \"${1-}\" = login ]; then\n"
            + "       \"$md_guest/libshroot_guest_supervisor.so\" \"$md_guest/libshroot_guest_bootstrap.so\" --probe >/dev/null || exit $?\n"
            + "     fi\n"
            + "     if [ \"$#\" -ge 2 ]; then md_image \"$@\"; fi\n"
            + "     exec \"$md_guest/libshroot_cli.so\" \"$@\" ;;\n"
            + "  --import) exec \"$md_guest/libshroot_guest_service.so\" \"$@\" ;;\n"
            + "  --probe) exec \"$md_guest/libshroot_guest_supervisor.so\" \"$md_guest/libshroot_guest_bootstrap.so\" \"$@\" ;;\n"
            + "  *) \"$md_guest/libshroot_guest_supervisor.so\" \"$md_guest/libshroot_guest_bootstrap.so\" --probe >/dev/null || exit $?\n"
            + LinuxAudioEnvironment.shrootArguments()
            + GamepadWire.shrootArguments()
            + TerminalFilesEnvironment.shrootArguments()
            + "     md_launch \"$md_guest/libshroot_guest_run.so\" \"$@\" ;;\n"
            + "esac\n";
    private GuestRuntimeCommand() { }
}
