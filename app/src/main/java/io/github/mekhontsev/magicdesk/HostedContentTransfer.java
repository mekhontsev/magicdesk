package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.OpenableColumns;
import io.github.mekhontsev.magicdesk.hosted.HostedDataSource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Streams selected content across Android providers and a selected guest file namespace. */
final class HostedContentTransfer {
    interface FilesAccess {
        ParcelFileDescriptor open(String uri) throws IOException, RemoteException;
        String importFile(ParcelFileDescriptor file, String name) throws IOException, RemoteException;
    }
    private static final int MAX_TEXT = 1024 * 1024;
    private final Context context;
    private final FilesAccess files;
    private final BooleanSupplier stopped;
    private final HostedContentFormats formats;
    private final String label;

    HostedContentTransfer(Context context, FilesAccess files, BooleanSupplier stopped,
            HostedContentFormats formats, String label) {
        this.context = context.getApplicationContext(); this.files = files;
        this.stopped = stopped; this.formats = formats; this.label = label;
    }

    AndroidContentPayload receive(HostedDataSource source) throws IOException {
        List<String> types = source.types();
        String textType = formats.textType(types);
        String text = textType == null ? "" : readText(source, textType);
        String html = types.contains(HostedContentFormats.HTML) ? readText(source, HostedContentFormats.HTML) : "";
        List<AndroidContentPayload.UriItem> items = new ArrayList<>();
        if (types.contains(HostedContentFormats.FILES)) {
            for (String value : HostedContentFormats.files(readText(source, HostedContentFormats.FILES))) {
                try (ParcelFileDescriptor descriptor = files.open(value)) {
                    if (descriptor == null) throw new IOException("Guest file is unavailable");
                    Uri uri = export(HostedContentFormats.fileName(value), descriptor);
                    items.add(new AndroidContentPayload.UriItem(uri, context.getContentResolver().getType(uri)));
                } catch (RemoteException | RuntimeException e) { throw new IOException("Cannot export guest file", e); }
            }
        } else if (types.contains(HostedContentFormats.PNG)) {
            try (ParcelFileDescriptor descriptor = source.open(HostedContentFormats.PNG)) {
                if (descriptor == null) throw new IOException("Guest image is unavailable");
                items.add(new AndroidContentPayload.UriItem(export("image.png", descriptor), HostedContentFormats.PNG));
            }
        }
        return AndroidContentPayload.create(AndroidContentPayload.Origin.APPLICATION, label, "", text, html,
                items, List.of(), false);
    }

    private Uri export(String name, ParcelFileDescriptor descriptor) throws IOException {
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.getFileDescriptor()))) {
            return GeneratedContentProvider.publish(context, name,
                    output -> ContentStreamCopy.copy(input, output, stopped, HostedDataSource.MAX_BYTES));
        }
    }

    private String readText(HostedDataSource source, String type) throws IOException {
        try (ParcelFileDescriptor descriptor = source.open(type)) {
            if (descriptor == null) throw new IOException("Guest owner rejected " + type);
            try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                 var bytes = new java.io.ByteArrayOutputStream()) {
                ContentStreamCopy.copy(input, bytes, stopped, MAX_TEXT);
                return bytes.toString(formats.charset(type));
            }
        }
    }

    HostedDataSource offer(AndroidContentPayload payload) {
        return new HostedDataSource() {
            private String importedFiles;
            @Override public List<String> types() { return formats.formats(payload); }
            @Override public synchronized ParcelFileDescriptor open(String type) throws IOException {
                if (!types().contains(type)) return null;
                if (type.equals(HostedContentFormats.HTML)) return HostedDataSource.bytes(payload.htmlText.getBytes(StandardCharsets.UTF_8));
                if (type.equals(HostedContentFormats.FILES)) {
                    if (importedFiles == null) {
                        List<String> files = new ArrayList<>();
                        for (AndroidContentPayload.UriItem item : payload.uriItems) {
                            try (ParcelFileDescriptor fd = materialize(item.uri)) {
                                files.add(HostedContentTransfer.this.files.importFile(fd, displayName(item.uri)));
                            } catch (RemoteException | RuntimeException e) { throw new IOException("Cannot import guest content", e); }
                        }
                        importedFiles = String.join("\r\n", files) + "\r\n";
                    }
                    return HostedDataSource.bytes(importedFiles.getBytes(StandardCharsets.UTF_8));
                }
                if (type.equals(HostedContentFormats.PNG)) return materialize(payload.uriItems.get(0).uri);
                return HostedDataSource.bytes(payload.text.getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    private String displayName(Uri uri) {
        try (var cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return ShellFileNamePolicy.validate(cursor.getString(0));
        } catch (RuntimeException ignored) { }
        return "content";
    }

    private ParcelFileDescriptor materialize(Uri uri) throws IOException {
        File file = File.createTempFile("hosted-transfer-", ".data", context.getCacheDir());
        try {
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 var output = Files.newOutputStream(file.toPath())) {
                if (input == null) throw new IOException("Content is unavailable");
                ContentStreamCopy.copy(input, output, stopped, HostedDataSource.MAX_BYTES);
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } finally { Files.deleteIfExists(file.toPath()); }
    }
}
