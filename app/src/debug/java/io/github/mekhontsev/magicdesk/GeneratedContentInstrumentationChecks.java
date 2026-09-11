package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.termux.terminal.AndroidTerminalImages;
import com.termux.terminal.TerminalImage;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/** Android codecs, provider metadata and Intent grants, independent of Desktop and privileged services. */
public final class GeneratedContentInstrumentationChecks {
    private GeneratedContentInstrumentationChecks() { }

    public static void verify(Context context) throws Exception {
        for (TerminalImage.Factory factory : new TerminalImage.Factory[]{AndroidTerminalImages.FACTORY, TerminalImage.JAVA_RASTER}) {
            TerminalImage image = factory.fromArgb(2, 2, new int[]{0xffff0000, 0xff00ff00, 0x800000ff, 0});
            Uri uri = GeneratedContentProvider.publish(context, "Terminal image.png", out -> AndroidTerminalImages.writePng(image, out));
            try {
                require("image/png".equals(context.getContentResolver().getType(uri)), "PNG MIME");
                try (var cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                    require(cursor != null && cursor.moveToFirst(), "export metadata");
                    require("Terminal image.png".equals(cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))), "name");
                    require(cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)) > 0, "size");
                }
                try (var input = context.getContentResolver().openInputStream(uri)) {
                    Bitmap png = BitmapFactory.decodeStream(input);
                    require(png != null && png.getWidth() == 2 && png.getHeight() == 2, "original dimensions");
                    try {
                        require(png.getPixel(0, 0) == 0xffff0000 && png.getPixel(1, 0) == 0xff00ff00
                                && png.getPixel(0, 1) == 0x800000ff && png.getPixel(1, 1) == 0, "PNG pixels/alpha");
                    } finally { png.recycle(); }
                }
                try (var ignored = context.getContentResolver().openFileDescriptor(uri, "rw")) {
                    throw new AssertionError("write grant accepted");
                } catch (FileNotFoundException expected) { }
                final var payload = AndroidContentPayload.uris("Image", List.of(new AndroidContentPayload.UriItem(uri, "image/png")),
                        List.of(), AndroidContentPayload.Origin.APPLICATION);
                for (Intent intent : new Intent[]{AndroidContentIntentAdapter.open(payload),
                        AndroidContentIntentAdapter.share(payload), FileManagerActivity.createSaveIntent(context, payload)}) {
                    require((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0, "read grant missing");
                    require((intent.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0, "unexpected write grant");
                    require(uri.equals(intent.getClipData().getItemAt(0).getUri()), "ClipData URI");
                }
                require(AndroidContentPayload.fromSendIntent(FileManagerActivity.createSaveIntent(context, payload))
                        .uris().equals(List.of(uri)), "Files save payload");
            } finally {
                java.nio.file.Files.deleteIfExists(new File(new File(context.getCacheDir(), "generated-content"),
                        uri.getPathSegments().get(0)).toPath());
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
