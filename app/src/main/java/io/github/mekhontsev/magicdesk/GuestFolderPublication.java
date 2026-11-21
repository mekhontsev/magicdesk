package io.github.mekhontsev.magicdesk;

import android.content.Context;
import io.github.mekhontsev.magicdesk.documents.FolderMountLease;
import io.github.mekhontsev.magicdesk.documents.FolderMounts;
import java.io.IOException;
import java.util.HashMap;

/** Android owns tree grants; an executor sees only arguments in its private runtime directory. */
final class GuestFolderPublication {
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
        var settings=new GuestLaunchSettings(app,scope);
        var execution=settings.execution;
        FolderMountLease lease=null;
        try (var session=execution.session("",false)) {
            if (new FolderMounts(app).list(scope).isEmpty()) {
                settings.write(session,".folders",null);
            } else {
                // Fail closed during reconfiguration or if a provider is unavailable.
                settings.write(session,".folders","");
                lease=FolderMountLease.open(app,scope,execution.uid,execution.endpointFactory());
                String args=String.join("\n",lease.arguments())+"\nend\n";
                settings.write(session,".folders",args);
                active.put(scope,lease); lease=null;
            }
            pending.edit().remove(scope).apply();
        } finally { if (lease!=null) lease.close(); }
    }
    static String folderArguments() {
        return """
                md_folder_file="$md_launch_file.folders"
                if [ -f "$md_folder_file" ]; then
                    md_end=
                    while IFS= read -r md_opt; do
                        case "$md_opt" in end) md_end=1; break;; --bind-fuse|--bind-fuse-ro) ;; *) break;; esac
                        IFS= read -r md_peer && IFS= read -r md_target || break
                        set -- "$md_opt" "$md_peer" "$md_target" "$@"
                    done < "$md_folder_file"
                    [ "$md_end" = 1 ] || { echo 'Android folder mounts are unavailable' >&2; exit 1; }
                fi
                """;
    }
}
