package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class DesktopFileRepository {
    private static final int THUMBNAIL_SIZE = 192;

    private final Context mContext;

    DesktopFileRepository(final Context context) {
        mContext = context.getApplicationContext();
    }

    List<DesktopFile> load(final int thumbnailLimit) throws IOException {
        final DesktopFileInfo[] records = ShellAccess.listDesktopFiles();
        Arrays.sort(records, new Comparator<DesktopFileInfo>() {
            @Override
            public int compare(
                    final DesktopFileInfo left,
                    final DesktopFileInfo right) {
                if (left.directory != right.directory) {
                    return left.directory ? -1 : 1;
                }
                return left.name.compareToIgnoreCase(right.name);
            }
        });
        final List<DesktopFile> files = new ArrayList<>(records.length);
        int previewsRemaining = Math.max(0, thumbnailLimit);
        for (final DesktopFileInfo record : records) {
            Bitmap thumbnail = null;
            if (!record.directory
                    && previewsRemaining > 0
                    && record.mimeType.startsWith("image/")) {
                thumbnail = loadImageThumbnail(record.relativePath);
                previewsRemaining--;
            }
            files.add(new DesktopFile(
                    record.relativePath,
                    DesktopFileUri.create(mContext, record.relativePath),
                    record.name,
                    record.mimeType,
                    record.modified,
                    record.size,
                    record.directory,
                    thumbnail));
        }
        return files;
    }

    private Bitmap loadImageThumbnail(final String relativePath) {
        final BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (ParcelFileDescriptor descriptor =
                     ShellAccess.openDesktopFile(relativePath)) {
            BitmapFactory.decodeFileDescriptor(
                    descriptor.getFileDescriptor(), null, bounds);
        } catch (IOException | RuntimeException error) {
            return null;
        }
        final int largest = Math.max(bounds.outWidth, bounds.outHeight);
        if (largest <= 0) {
            return null;
        }
        final BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = 1;
        while (largest / (decode.inSampleSize * 2) >= THUMBNAIL_SIZE) {
            decode.inSampleSize *= 2;
        }
        try (ParcelFileDescriptor descriptor =
                     ShellAccess.openDesktopFile(relativePath)) {
            return BitmapFactory.decodeFileDescriptor(
                    descriptor.getFileDescriptor(), null, decode);
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }
}
