package io.github.mekhontsev.magicdesk.documents;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** The fixture can offer a persistable grant without granting blanket MANAGE_DOCUMENTS. */
public final class FixtureGrantOffer extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method,String arg,Bundle data) {
        if ("ready".equals(method)) return Bundle.EMPTY;
        if (!"offerTestTrees".equals(method) && !"revokeReadTree".equals(method)) throw new UnsupportedOperationException();
        return FixtureDocuments.grants(getContext(),"revokeReadTree".equals(method));
    }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri u,ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u,String s,String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u,ContentValues v,String s,String[] a) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri u) { return null; }
}
