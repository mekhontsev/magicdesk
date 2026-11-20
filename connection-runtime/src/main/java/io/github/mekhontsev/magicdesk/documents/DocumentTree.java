package io.github.mekhontsev.magicdesk.documents;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.system.ErrnoException;
import static android.system.OsConstants.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Only document IDs discovered underneath the granted tree reach the provider. */
final class DocumentTree {
    record Item(String id, String name, boolean directory, long size, long modified, int flags) { }
    private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS};
    private final ContentResolver resolver;
    private final FolderMounts.Mount mount;
    private final boolean available;
    private final android.os.CancellationSignal cancel;
    DocumentTree(ContentResolver resolver, FolderMounts.Mount mount) { this(resolver,mount,true,null); }
    DocumentTree(ContentResolver resolver, FolderMounts.Mount mount,boolean available,android.os.CancellationSignal cancel) {
        this.resolver=resolver; this.mount=mount; this.available=available; this.cancel=cancel;
    }
    boolean available() { return available; }
    String root() { return DocumentsContract.getTreeDocumentId(mount.tree()); }
    boolean writable() { return mount.writable(); }
    boolean canWrite(Item item) {
        int flag=item.directory() ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE;
        return writable() && (item.flags()&flag)!=0;
    }
    void requireWrite() throws ErrnoException { if (!writable()) throw error(EROFS); }
    static ErrnoException error(int errno) { return new ErrnoException("Document tree", errno); }
    private Uri uri(String id) { return DocumentsContract.buildDocumentUriUsingTree(mount.tree(), id); }
    private Cursor query(Uri uri) throws IOException {
        Cursor cursor = resolver.query(uri, COLUMNS, null, null, null, cancel);
        if (cursor == null) throw new IOException("Document provider unavailable");
        if (cursor.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING)
                || cursor.getExtras().getString(DocumentsContract.EXTRA_ERROR) != null) {
            cursor.close(); throw new IOException("Document provider has not supplied the directory");
        }
        return cursor;
    }
    static boolean validName(String name) {
        return name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && name.indexOf('/') < 0 && name.indexOf(0) < 0
                && name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 255;
    }
    private Item item(Cursor c) throws IOException {
        String id = c.getString(0);
        if (id == null || id.isEmpty()) throw new IOException("Missing document identity");
        return new Item(id, c.getString(1), Document.MIME_TYPE_DIR.equals(c.getString(2)), c.isNull(3) ? -1 : c.getLong(3),
                Math.max(0,c.getLong(4)), c.getInt(5));
    }
    Item stat(String id) throws Exception {
        try (var c = query(uri(id))) {
            if (!c.moveToFirst()) throw error(ENOENT);
            Item result = item(c);
            if (!id.equals(result.id())) throw new IOException("Document identity changed");
            return result;
        }
    }
    List<Item> children(String dir) throws Exception {
        var result = new ArrayList<Item>();
        var names = new HashSet<String>();
        try (var c = query(DocumentsContract.buildChildDocumentsUriUsingTree(mount.tree(), dir))) {
            while (c.moveToNext()) {
                var item = item(c);
                if (!validName(item.name()) || !names.add(item.name())) throw error(EIO);
                result.add(item);
            }
        }
        return result;
    }
    Item child(String dir, String name) throws Exception {
        if (!validName(name)) throw error(EINVAL);
        Item selected=null;
        try (var c=query(DocumentsContract.buildChildDocumentsUriUsingTree(mount.tree(),dir))) {
            while (c.moveToNext()) if (name.equals(c.getString(1))) {
                if (selected!=null) throw error(EIO);
                selected=item(c);
            }
        }
        if (selected==null) throw error(ENOENT);
        return selected;
    }
    private void absent(String dir, String name) throws Exception {
        try { child(dir, name); }
        catch (ErrnoException e) { if (e.errno == ENOENT) return; throw e; }
        throw error(EEXIST);
    }
    ParcelFileDescriptor open(String id, int flags) throws Exception {
        int access = flags & O_ACCMODE;
        if (access != O_RDONLY && access != O_WRONLY && access != O_RDWR) throw error(EINVAL);
        if (access != O_RDONLY || (flags & O_TRUNC) != 0) requireWrite();
        if (access == O_RDONLY && (flags & O_TRUNC) != 0) throw error(EINVAL);
        // SAF "w" may truncate on open. Request the non-truncating "rw" and
        // enforce the guest descriptor's access mode in the FUSE handle.
        String mode = access == O_RDONLY ? "r" : (flags & O_TRUNC) != 0 ? "rwt" : "rw";
        var fd = resolver.openFileDescriptor(uri(id), mode,cancel);
        if (fd == null) throw error(EIO);
        return fd;
    }
    Item create(String dir, String name, boolean directory) throws Exception {
        requireWrite(); absent(dir, name);
        Uri made = DocumentsContract.createDocument(resolver, uri(dir), directory ? Document.MIME_TYPE_DIR : "application/octet-stream", name);
        if (made == null) throw error(EIO);
        Item result = stat(DocumentsContract.getDocumentId(made));
        if (!name.equals(result.name()) || directory != result.directory()) throw error(EEXIST);
        return result;
    }
    record Renamed(String oldId, String newId) { }
    Renamed rename(String from, String fromName, String to, String toName, boolean noReplace) throws Exception {
        requireWrite();
        Item src=child(from,fromName);
        if (from.equals(to) && fromName.equals(toName)) return new Renamed(src.id(),src.id());
        try { absent(to,toName); }
        catch (ErrnoException e) {
            // SAF has no atomic replace. Never delete the destination to fake POSIX rename.
            if (e.errno==EEXIST && !noReplace) throw error(EOPNOTSUPP);
            throw e;
        }
        Uri result;
        if (from.equals(to) && (src.flags()&Document.FLAG_SUPPORTS_RENAME)!=0)
            result=DocumentsContract.renameDocument(resolver,uri(src.id()),toName);
        else if (fromName.equals(toName) && (src.flags()&Document.FLAG_SUPPORTS_MOVE)!=0)
            result=DocumentsContract.moveDocument(resolver,uri(src.id()),uri(from),uri(to));
        else throw error(EOPNOTSUPP);
        if (result==null) throw error(EIO);
        String newId=DocumentsContract.getDocumentId(result);
        if (!toName.equals(stat(newId).name())) throw error(EIO);
        return new Renamed(src.id(),newId);
    }
    void remove(String dir, String name, boolean directory) throws Exception {
        requireWrite();
        Item item = child(dir, name);
        if (item.directory() != directory) throw error(directory ? ENOTDIR : EISDIR);
        // SAF deleteDocument can recursively erase an entire directory. A
        // preflight readdir cannot make that operation POSIX rmdir atomically.
        if (directory) throw error(EOPNOTSUPP);
        boolean removed=(item.flags()&Document.FLAG_SUPPORTS_REMOVE)!=0
                ? DocumentsContract.removeDocument(resolver,uri(item.id()),uri(dir))
                : DocumentsContract.deleteDocument(resolver,uri(item.id()));
        if (!removed) throw error(EIO);
    }
}
