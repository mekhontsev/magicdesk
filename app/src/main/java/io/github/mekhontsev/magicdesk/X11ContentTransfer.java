package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.OpenableColumns;
import com.termux.x11.X11DataExchange;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Streams selected content across the Android-provider and X11 execution boundaries. */
final class X11ContentTransfer {
    private static final int MAX_TEXT = 1024 * 1024;
    private final Context context;
    private final X11Sessions.Session session;

    X11ContentTransfer(Context context, X11Sessions.Session session) {
        this.context = context.getApplicationContext(); this.session = session;
    }

    AndroidContentPayload receive(X11DataExchange.Source source) throws IOException {
        List<String> types = source.types();
        String textType = X11ContentFormats.textType(types);
        String text = textType == null ? "" : readText(source, textType);
        String html = types.contains(X11ContentFormats.HTML) ? readText(source, X11ContentFormats.HTML) : "";
        List<AndroidContentPayload.UriItem> items = new ArrayList<>();
        if (types.contains(X11ContentFormats.FILES)) {
            for (String value : X11ContentFormats.files(readText(source, X11ContentFormats.FILES))) {
                try (ParcelFileDescriptor descriptor = session.contentFiles().openContentFile(value)) {
                    if (descriptor == null) throw new IOException("X11 file is unavailable");
                    Uri uri = export(X11ContentFormats.fileName(value), descriptor);
                    items.add(new AndroidContentPayload.UriItem(uri, context.getContentResolver().getType(uri)));
                } catch (RemoteException | RuntimeException e) { throw new IOException("Cannot export X11 file", e); }
            }
        } else if (types.contains(X11ContentFormats.PNG)) {
            try (ParcelFileDescriptor descriptor = source.open(X11ContentFormats.PNG)) {
                if (descriptor == null) throw new IOException("X11 image is unavailable");
                items.add(new AndroidContentPayload.UriItem(export("image.png", descriptor), X11ContentFormats.PNG));
            }
        }
        return AndroidContentPayload.create(AndroidContentPayload.Origin.APPLICATION, "X11", "", text, html,
                items, List.of(), false);
    }

    private Uri export(String name, ParcelFileDescriptor descriptor) throws IOException {
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.getFileDescriptor()))) {
            return GeneratedContentProvider.publish(context, name,
                    output -> ContentStreamCopy.copy(input, output, session::stopped, X11DataExchange.MAX_BYTES));
        }
    }

    private String readText(X11DataExchange.Source source, String type) throws IOException {
        try (ParcelFileDescriptor descriptor = source.open(type)) {
            if (descriptor == null) throw new IOException("X11 owner rejected " + type);
            try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                 var bytes = new java.io.ByteArrayOutputStream()) {
                ContentStreamCopy.copy(input, bytes, session::stopped, MAX_TEXT);
                return bytes.toString(type.equals("STRING") ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
            }
        }
    }

    X11DataExchange.Source offer(AndroidContentPayload payload) {
        return new X11DataExchange.Source() {
            private String importedFiles;
            @Override public List<String> types() { return formats(payload); }
            @Override public synchronized ParcelFileDescriptor open(String type) throws IOException {
                if (!types().contains(type)) return null;
                if (type.equals(X11ContentFormats.HTML)) return X11DataExchange.bytes(payload.htmlText.getBytes(StandardCharsets.UTF_8));
                if (type.equals(X11ContentFormats.FILES)) {
                    if (importedFiles == null) {
                        List<String> files = new ArrayList<>();
                        for (AndroidContentPayload.UriItem item : payload.uriItems) {
                            try (ParcelFileDescriptor fd = materialize(item.uri)) {
                                files.add(session.contentFiles().importContentFile(fd, displayName(item.uri)));
                            } catch (RemoteException | RuntimeException e) { throw new IOException("Cannot import content into X11", e); }
                        }
                        importedFiles = String.join("\r\n", files) + "\r\n";
                    }
                    return X11DataExchange.bytes(importedFiles.getBytes(StandardCharsets.UTF_8));
                }
                if (type.equals(X11ContentFormats.PNG)) return materialize(payload.uriItems.get(0).uri);
                return X11DataExchange.bytes(payload.text.getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    static List<String> formats(AndroidContentPayload payload) {
        List<String> result = new ArrayList<>();
        if (!payload.text.isEmpty()) { result.add("UTF8_STRING"); result.add("text/plain;charset=utf-8"); result.add("text/plain"); }
        if (!payload.htmlText.isEmpty()) result.add(X11ContentFormats.HTML);
        if (!payload.uriItems.isEmpty()) result.add(X11ContentFormats.FILES);
        if (payload.uriItems.size() == 1 && X11ContentFormats.PNG.equals(payload.uriItems.get(0).mimeType)) result.add(X11ContentFormats.PNG);
        return List.copyOf(result);
    }

    private String displayName(Uri uri) {
        try (var cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return ShellFileNamePolicy.validate(cursor.getString(0));
        } catch (RuntimeException ignored) { }
        return "content";
    }

    private ParcelFileDescriptor materialize(Uri uri) throws IOException {
        File file = File.createTempFile("x11-transfer-", ".data", context.getCacheDir());
        try {
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 var output = Files.newOutputStream(file.toPath())) {
                if (input == null) throw new IOException("Content is unavailable");
                ContentStreamCopy.copy(input, output, session::stopped, X11DataExchange.MAX_BYTES);
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } finally { Files.deleteIfExists(file.toPath()); }
    }
}
