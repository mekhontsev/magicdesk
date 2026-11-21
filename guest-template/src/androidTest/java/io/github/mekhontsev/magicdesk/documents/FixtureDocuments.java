package io.github.mekhontsev.magicdesk.documents;

import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;

/** Test-APK provider: two disposable trees and one file each, never exposes user storage. */
public final class FixtureDocuments extends DocumentsProvider {
    static final String AUTH="io.github.mekhontsev.magicdesk.guesttemplate.test.documents";
    static final String TARGET="io.github.mekhontsev.magicdesk.guesttemplate";
    @Override public boolean onCreate() { return true; }
    static Bundle grants(android.content.Context ctx,boolean revokeRead) {
        int caller=Binder.getCallingUid();
        try {
            if (caller!=ctx.getPackageManager().getPackageUid(TARGET,0) ||
                    ctx.getPackageManager().checkSignatures(caller,android.os.Process.myUid())!=android.content.pm.PackageManager.SIGNATURE_MATCH)
                throw new SecurityException("Fixture target only");
        } catch (android.content.pm.PackageManager.NameNotFoundException e) { throw new IllegalStateException(e); }
        long token=Binder.clearCallingIdentity();
        try {
            for (String dir : new String[]{"read","write"}) {
                int flags=Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION|
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                if (revokeRead) {
                    if (dir.equals("read")) ctx.revokeUriPermission(DocumentsContract.buildTreeDocumentUri(AUTH,dir),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    continue;
                }
                ctx.grantUriPermission(TARGET,DocumentsContract.buildTreeDocumentUri(AUTH,dir),flags);
                var root=java.nio.file.Files.createDirectories(ctx.getCacheDir().toPath().resolve("trees").resolve(dir));
                java.nio.file.Files.write(root.resolve("token"),"read-only".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return Bundle.EMPTY;
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        finally { Binder.restoreCallingIdentity(token); }
    }
    @Override public boolean isChildDocument(String parent,String child) { return child.startsWith(parent+"/"); }
    @Override public Cursor queryRoots(String[] projection) { return new MatrixCursor(projection); }
    @Override public Cursor queryDocument(String id,String[] projection) throws FileNotFoundException {
        var c=new MatrixCursor(projection);
        add(c,id); return c;
    }
    private File file(String id) throws FileNotFoundException {
        if (!id.matches("(read|write)(/[A-Za-z0-9_-]+)*")) throw new FileNotFoundException(id);
        return new File(new File(getContext().getCacheDir(),"trees"),id);
    }
    private void add(MatrixCursor c,String id) throws FileNotFoundException {
        File file=file(id); if (!file.exists()) throw new FileNotFoundException(id);
        var row=c.newRow(); boolean dir=file.isDirectory();
        row.add(Document.COLUMN_DOCUMENT_ID,id).add(Document.COLUMN_DISPLAY_NAME,file.getName())
                .add(Document.COLUMN_MIME_TYPE,dir ? Document.MIME_TYPE_DIR : "text/plain")
                .add(Document.COLUMN_FLAGS,Document.FLAG_SUPPORTS_RENAME|Document.FLAG_SUPPORTS_DELETE|
                        (dir ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE))
                .add(Document.COLUMN_SIZE,dir ? 0 : file.length())
                .add(Document.COLUMN_LAST_MODIFIED,0);
    }
    @Override public Cursor queryChildDocuments(String id,String[] projection,String order) throws FileNotFoundException {
        var c=new MatrixCursor(projection); var names=file(id).list();
        if (names==null) throw new FileNotFoundException(id);
        for (String name:names) add(c,id+"/"+name);
        return c;
    }
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal cancel) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(id),ParcelFileDescriptor.parseMode(mode));
    }
    @Override public String createDocument(String parent,String mime,String name) throws FileNotFoundException {
        String id=parent+"/"+name;
        try { if (!(Document.MIME_TYPE_DIR.equals(mime) ? file(id).mkdir() : file(id).createNewFile())) throw new FileNotFoundException(id); }
        catch (java.io.IOException e) { throw new FileNotFoundException(e.toString()); }
        return id;
    }
    @Override public String renameDocument(String old,String name) throws FileNotFoundException {
        String id=old.substring(0,old.lastIndexOf('/')+1)+name;
        if (!file(old).renameTo(file(id))) throw new FileNotFoundException(old);
        return id;
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        if (!file(id).delete()) throw new FileNotFoundException(id);
    }
}
