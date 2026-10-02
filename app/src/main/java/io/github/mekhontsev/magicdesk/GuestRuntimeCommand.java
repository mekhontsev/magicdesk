package io.github.mekhontsev.magicdesk;

/** Lazy CLI adapter. Preparation is a child command, never the owner of application FDs. */
public final class GuestRuntimeCommand {
    public static final String LIBRARIES_ENV = "MAGICDESK_GUEST_LIBRARIES";
    public static final String SCRIPT = "#!/system/bin/sh\n"
            + "unset LD_PRELOAD LD_LIBRARY_PATH\n"
            + "md_guest=$(CLASSPATH=\"${MAGICDESK_COMMAND_APK:?Open a new MagicDesk console}\" "
            + "/system/bin/app_process / io.github.mekhontsev.magicdesk.GuestRuntimeMain "
            + "\"${" + LIBRARIES_ENV + ":?Guest runtime is unavailable}\" "
            + "\"${MAGICDESK_RUNTIME:?Missing shell runtime}/guest-runtime\") || exit $?\n"
            + "md_library() { CLASSPATH=\"$MAGICDESK_COMMAND_APK\" /system/bin/app_process / "
            + "io.github.mekhontsev.magicdesk.GuestEnvironmentMain \"$md_guest\" "
            + "\"${MAGICDESK_GUEST_HOME:-$MAGICDESK_RUNTIME/guest-environments}\" \"$@\"; }\n"
            + "case \"${1-}\" in\n"
            + "  run|exec|login) md_action=$1; shift\n"
            + "     md_store=$(md_library resolve \"${1-}\") || exit $?\n"
            + "     shift\n"
            + "     \"$md_guest/libmagicdesk_guest_supervisor.so\" \"$md_guest/libmagicdesk_guest_bootstrap.so\" --probe >/dev/null || exit $?\n"
            + "     exec \"$md_guest/libmagicdesk_guest_image.so\" \"$md_action\" \"$md_store\" \"$@\" ;;\n"
            + "  install|list|inspect|path|backup|restore|remove|prune|dns|launches|stop|applications) md_library \"$@\"; exit $? ;;\n"
            + "  ''|-h|--help|help) md_library help; exit $? ;;\n"
            + "  image) shift\n"
            + "     if [ \"${1-}\" = run ] || [ \"${1-}\" = exec ] || [ \"${1-}\" = login ]; then\n"
            + "       \"$md_guest/libmagicdesk_guest_supervisor.so\" \"$md_guest/libmagicdesk_guest_bootstrap.so\" --probe >/dev/null || exit $?\n"
            + "     fi\n"
            + "     exec \"$md_guest/libmagicdesk_guest_image.so\" \"$@\" ;;\n"
            + "  --import) exec \"$md_guest/libmagicdesk_guest_service.so\" \"$@\" ;;\n"
            + "  --probe) exec \"$md_guest/libmagicdesk_guest_supervisor.so\" \"$md_guest/libmagicdesk_guest_bootstrap.so\" \"$@\" ;;\n"
            + "  *) \"$md_guest/libmagicdesk_guest_supervisor.so\" \"$md_guest/libmagicdesk_guest_bootstrap.so\" --probe >/dev/null || exit $?\n"
            + "     exec \"$md_guest/libmagicdesk_guest_run.so\" \"$@\" ;;\n"
            + "esac\n";
    private GuestRuntimeCommand() { }
}
