package io.github.mekhontsev.magicdesk.documents;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/** The Android UID owns the grant. An environment owns only its explicitly selected view. */
public final class FolderMounts {
    public static final String INDEPENDENT = "local";
    static final int READ = Intent.FLAG_GRANT_READ_URI_PERMISSION;
    static final int WRITE = Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    public record Mount(String scope, Uri tree, String name, boolean writable) {
        public String target() { return "/mnt/android/" + name; }
    }
    private static final Object LOCK = new Object();
    private static final java.util.Map<Uri,Integer> temporary = new java.util.HashMap<>();
    private final Context app;
    public FolderMounts(Context context) { app = context.getApplicationContext(); }
    private android.content.SharedPreferences prefs() {
        return app.getSharedPreferences("android-folder-mounts", Context.MODE_PRIVATE);
    }
    private static String key(String scope, Uri tree) { return scope + "\n" + tree; }
    private List<Mount> all() throws IOException {
        var mounts = new ArrayList<Mount>();
        try {
            for (var entry : prefs().getAll().entrySet()) {
                String[] parts = entry.getKey().split("\n", 2);
                var value = new JSONObject((String) entry.getValue());
                mounts.add(new Mount(parts[0], Uri.parse(parts[1]), value.getString("name"), value.getBoolean("write")));
            }
        } catch (RuntimeException | JSONException e) { throw new IOException("Invalid folder configuration", e); }
        mounts.sort(Comparator.comparing(Mount::name));
        return mounts;
    }
    public List<Mount> list(String scope) throws IOException {
        synchronized (LOCK) { return all().stream().filter(m -> m.scope.equals(scope)).toList(); }
    }
    public List<String> scopes() throws IOException {
        synchronized (LOCK) { return all().stream().map(Mount::scope).distinct().toList(); }
    }
    int held(Uri tree) {
        for (var grant : app.getContentResolver().getPersistedUriPermissions())
            if (grant.getUri().equals(tree)) return flags(grant);
        return 0;
    }
    private static int flags(android.content.UriPermission grant) {
        return (grant.isReadPermission() ? READ : 0)|(grant.isWritePermission() ? WRITE : 0);
    }
    /** Reconcile once after process death. Parse all durable claims before releasing any grant. */
    public int releaseOrphans() throws IOException {
        synchronized (LOCK) {
            var claims=new java.util.HashMap<>(temporary);
            for (var row:all()) claims.merge(row.tree,READ|(row.writable ? WRITE : 0),(a,b) -> a|b);
            int released=0;
            for (var grant:app.getContentResolver().getPersistedUriPermissions()) {
                int unused=flags(grant)&~claims.getOrDefault(grant.getUri(),0);
                if (unused!=0) try { release(grant.getUri(),unused); ++released; }
                catch (SecurityException | IllegalArgumentException ignored) { /* Concurrent provider revocation. */ }
            }
            return released;
        }
    }
    public boolean available(Mount m) {
        int needed = READ | (m.writable ? WRITE : 0);
        return (held(m.tree) & needed) == needed;
    }
    static String alias(String label) {
        String clean = label == null ? "" : label.replaceAll("[/\\\\\\p{Cntrl}]", "_").trim();
        if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) return "Folder";
        // No 180-byte UTF-8 alias can contain more than 180 code points.
        if (clean.codePointCount(0,clean.length())>180)
            clean=clean.substring(0,clean.offsetByCodePoints(0,180));
        while (clean.getBytes(StandardCharsets.UTF_8).length > 180)
            clean = clean.substring(0, clean.offsetByCodePoints(clean.length(), -1));
        return clean;
    }
    public Mount remember(String scope, Uri tree, String label, int offered, boolean writable) throws IOException {
        if (!DocumentsContract.isTreeUri(tree) || !"content".equals(tree.getScheme()) || scope.isBlank() || scope.contains("\n"))
            throw new IOException("Invalid folder");
        int needed = READ | (writable ? WRITE : 0);
        if ((offered & needed) != needed) throw new IOException("The provider did not grant the requested access");
        synchronized (LOCK) {
            var rows = list(scope);
            Mount old = rows.stream().filter(m -> m.tree.equals(tree)).findFirst().orElse(null);
            String base = alias(label), name = old == null ? base : old.name;
            if (old == null) {
                var names = rows.stream().map(Mount::name).collect(java.util.stream.Collectors.toSet());
                for (int n = 2; names.contains(name); ++n) name = base + " (" + n + ")";
            }
            int previous = held(tree);
            app.getContentResolver().takePersistableUriPermission(tree, needed);
            try {
                var value = new JSONObject().put("name", name).put("write", writable);
                if (!prefs().edit().putString(key(scope, tree), value.toString()).commit()) throw new IOException("Cannot save folder");
            } catch (IOException | JSONException e) {
                release(tree, needed & ~previous);
                throw new IOException("Cannot save folder", e);
            }
            trimGrant(tree);
            return new Mount(scope, tree, name, writable);
        }
    }
    public void forget(Mount mount) throws IOException {
        synchronized (LOCK) {
            if (!prefs().edit().remove(key(mount.scope, mount.tree)).commit()) throw new IOException("Cannot forget folder");
            trimGrant(mount.tree);
        }
    }
    private void trimGrant(Uri tree) throws IOException {
        int needed = temporary.getOrDefault(tree, 0);
        for (var row : all()) if (row.tree.equals(tree)) needed |= READ | (row.writable ? WRITE : 0);
        release(tree, held(tree) & ~needed);
    }
    /** The activity-result owner already reference-counts its readers and writers. */
    public void takeTemporary(Uri uri, int flags) {
        synchronized (LOCK) {
            app.getContentResolver().takePersistableUriPermission(uri, flags);
            temporary.merge(uri, flags, (old, added) -> old | added);
        }
    }
    public void releaseTemporary(Uri uri, int flags) {
        synchronized (LOCK) {
            temporary.computeIfPresent(uri, (key, old) -> (old & ~flags) == 0 ? null : old & ~flags);
            try { trimGrant(uri); }
            catch (IOException e) { throw new IllegalStateException("Cannot check retained folder grants", e); }
        }
    }
    private void release(Uri tree, int flags) {
        if (flags != 0) app.getContentResolver().releasePersistableUriPermission(tree, flags);
    }
}
