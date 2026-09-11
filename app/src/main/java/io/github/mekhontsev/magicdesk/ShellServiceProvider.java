package io.github.mekhontsev.magicdesk;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/** A one-shot inbound Binder handoff, never a public command endpoint. */
public final class ShellServiceProvider extends ContentProvider {
    static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".privileged-service";

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String token, Bundle extras) {
        if (!ShellAccess.isSupportedServiceUid(Binder.getCallingUid())) throw new SecurityException("privileged caller required");
        if (!"attach".equals(method) || extras == null) throw new IllegalArgumentException("invalid service handoff");
        return ShellProcessLauncher.attach(token, extras, Binder.getCallingPid(), Binder.getCallingUid());
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        throw new UnsupportedOperationException();
    }
    @Override public String getType(Uri uri) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
