package io.github.mekhontsev.magicdesk;

import android.content.Context;
import io.github.mekhontsev.magicdesk.documents.FolderMountLease;
import io.github.mekhontsev.magicdesk.documents.FolderMounts;
import java.io.IOException;
import java.util.HashMap;
import java.util.UUID;

/** Android owns tree grants; an executor sees only arguments in its private runtime directory. */
final class GuestFolderPublication {
    private static final String DIR=".magicdesk-folder-mounts";
    private static final HashMap<String,FolderMountLease> active=new HashMap<>();
    private static final java.util.concurrent.ExecutorService restore=java.util.concurrent.Executors.newSingleThreadExecutor();

    static void initialize(Context app) {
        restore.execute(() -> restore(app,"app:"));
        ShellAccess.addStateListener(snapshot -> {
            if (snapshot.isReady()) restore.execute(() -> restore(app,"shell:"+snapshot.uid+":"));
        });
    }
    private static void restore(Context app,String prefix) {
        try {
            var scopes=new java.util.HashSet<>(new FolderMounts(app).scopes());
            scopes.addAll(app.getSharedPreferences("folder-publication",0).getAll().keySet());
            for (String scope : scopes) if (scope.startsWith(prefix)) {
                try { publish(app,scope,false); }
                catch (Exception e) { android.util.Log.w("AndroidFolders","Cannot restore "+scope,e); }
            }
        } catch (IOException e) { android.util.Log.w("AndroidFolders","Cannot read folder catalog",e); }
    }
    static synchronized void publish(Context app,String scope,boolean changed) throws Exception {
        var old=active.get(scope);
        if (!changed && old!=null && old.healthy()) return;
        var pending=app.getSharedPreferences("folder-publication",0);
        if (!pending.edit().putBoolean(scope,true).commit()) throw new IOException("Cannot save publication state");
        if (old!=null) { active.remove(scope); old.retire(); }
        String[] words=scope.split(":",3);
        if (words.length!=3) throw new IOException("Invalid Linux instance");
        var store=java.nio.file.Path.of(words[2]);
        if (!store.isAbsolute() || !store.normalize().toString().equals(words[2]) || store.getFileName()==null
                || !UUID.fromString(store.getFileName().toString()).toString().equals(store.getFileName().toString()))
            throw new IOException("Invalid Linux instance store");
        var execution=new CommandExecution(app,switch(words[0]) {
            case "app" -> DesktopExecBackend.APP; case "shell" -> DesktopExecBackend.SHELL;
            default -> throw new IOException("Unsupported folder executor");
        });
        if (!execution.scope.equals(words[0]+":"+words[1])) throw new IOException("The Linux executor identity changed");
        var dir=ShellCommandLine.quote(execution.home+"/"+DIR+store.getParent());
        String path=dir+"/"+store.getFileName();
        FolderMountLease lease=null;
        try (var session=execution.session("",false)) {
            if (new FolderMounts(app).list(scope).isEmpty()) {
                check(session,"rm -f -- "+path);
            } else {
                // Fail closed during reconfiguration or if a provider is unavailable.
                check(session,"umask 077; mkdir -p -- "+dir+" && : > "+path);
                lease=FolderMountLease.open(app,scope,execution.uid,execution.endpointFactory());
                String args=String.join("\n",lease.arguments())+"\nend\n";
                check(session,"(umask 077; t=$(mktemp "+dir+"/.new.XXXXXXXX) || exit 1; "
                        +"trap 'rm -f -- \"$t\"' EXIT; printf '%s' "+ShellCommandLine.quote(args)
                        +" > \"$t\" && mv -f -- \"$t\" "+path+")");
                active.put(scope,lease); lease=null;
            }
            pending.edit().remove(scope).apply();
        } finally { if (lease!=null) lease.close(); }
    }
    private static void check(ShellCommandSession session,String cmd) throws IOException {
        var result=session.execute(cmd);
        if (result.exitCode()!=0) throw new IOException("Cannot publish Android folder mounts: "+result.output());
    }
    static String arguments() {
        return """
                md_folder_root="$HOME/.magicdesk-folder-mounts"
                if [ -d "$md_folder_root" ]; then
                    md_folder_store=${md_store%/}
                    case "$md_folder_store/" in /*/../*|*/./*|*//*|[!/]*) md_folder_store=$(realpath -- "$md_store") || exit 1;; esac
                    md_folder_file="$md_folder_root$md_folder_store"
                if [ -f "$md_folder_file" ]; then
                    md_end=
                    while IFS= read -r md_opt; do
                        case "$md_opt" in end) md_end=1; break;; --bind-fuse|--bind-fuse-ro) ;; *) break;; esac
                        IFS= read -r md_peer && IFS= read -r md_target || break
                        set -- "$md_opt" "$md_peer" "$md_target" "$@"
                    done < "$md_folder_file"
                    [ "$md_end" = 1 ] || { echo 'Android folder mounts are unavailable' >&2; exit 1; }
                fi
                fi
                """;
    }
}
