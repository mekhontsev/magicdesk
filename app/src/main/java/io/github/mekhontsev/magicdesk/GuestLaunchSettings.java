package io.github.mekhontsev.magicdesk;

import android.content.Context;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/** Each executor owns its launch files; their identity follows the store, not its display name. */
final class GuestLaunchSettings {
    static final String DIR=".magicdesk-launch";
    final CommandExecution execution;
    private final String directory,base;
    static String scope(Context context,GuestEnvironmentCatalog.Entry entry) {
        return new CommandExecution(context,entry.backend()).scope+":"+entry.store();
    }
    GuestLaunchSettings(Context context,String scope) throws IOException {
        String[] parts=scope.split(":",3);
        if (parts.length!=3) throw new IOException("Invalid Linux instance");
        Path store=Path.of(parts[2]);
        if (!store.isAbsolute() || !store.normalize().toString().equals(parts[2]) || store.getFileName()==null
                || !UUID.fromString(store.getFileName().toString()).toString().equals(store.getFileName().toString()))
            throw new IOException("Invalid Linux instance store");
        execution=new CommandExecution(context,switch(parts[0]) {
            case "app" -> DesktopExecBackend.APP; case "shell" -> DesktopExecBackend.SHELL;
            default -> throw new IOException("Unsupported Linux executor");
        });
        if (!execution.scope.equals(parts[0]+":"+parts[1])) throw new IOException("Linux executor identity changed");
        directory=ShellCommandLine.quote(execution.home+"/"+DIR+store.getParent());
        base=directory+"/"+store.getFileName();
    }
    private static String run(ShellCommandSession session,String command) throws IOException {
        var r=session.execute(command);
        if (r.exitCode()!=0) throw new IOException("Cannot access Linux launch settings: "+r.output());
        return r.output();
    }
    void write(ShellCommandSession session,String suffix,String data) throws IOException {
        if (data==null) run(session,"rm -f -- "+base+suffix);
        else run(session,"(umask 077; mkdir -p -- "+directory+" && t=$(mktemp "+directory+
                "/.new.XXXXXXXX) || exit 1; trap 'rm -f -- \"$t\"' EXIT; printf '%s' "+
                ShellCommandLine.quote(data)+" > \"$t\" && mv -f -- \"$t\" "+base+suffix+")");
    }
    GuestNetworkProfile network() throws IOException {
        try (var session=execution.session("",false)) {
            String value=run(session,"if [ -f "+base+".network ]; then cat -- "+base+".network; else printf 'host\\nend\\n'; fi");
            return GuestNetworkProfile.fromSettings(value);
        }
    }
    void network(GuestNetworkProfile profile) throws IOException {
        try (var session=execution.session("",false)) {
            write(session,".network",profile.equals(GuestNetworkProfile.HOST) ? null : profile.settings());
        }
    }
    static String arguments() {
        return "md_launch_root=\"$HOME/"+DIR+"\"\n"+"""
                if [ -d "$md_launch_root" ]; then
                    md_launch_store=${md_store%/}
                    case "$md_launch_store/" in /*/../*|*/./*|*//*|[!/]*) md_launch_store=$(realpath -- "$md_store") || exit 1;; esac
                    md_launch_file="$md_launch_root$md_launch_store"
                """+GuestFolderPublication.folderArguments()+"""
                    if [ -f "$md_launch_file.network" ]; then
                        md_end=
                        {
                            IFS= read -r md_mode || exit 1
                            case "$md_mode" in none|bridge) ;; *) echo 'Invalid Linux network mode' >&2; exit 1;; esac
                            set -- --magicdesk-default-network "$md_mode" "$@"
                            while IFS= read -r md_rule; do
                                case "$md_rule" in end) md_end=1; break;; esac
                                set -- --magicdesk-default-publish "$md_rule" "$@"
                            done
                        } < "$md_launch_file.network"
                        [ "$md_end" = 1 ] || { echo 'Incomplete Linux network settings' >&2; exit 1; }
                    fi
                fi
                """;
    }
}
