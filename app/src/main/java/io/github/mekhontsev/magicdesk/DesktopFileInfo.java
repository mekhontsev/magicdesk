package io.github.mekhontsev.magicdesk;

import android.os.Parcel;
import android.os.Parcelable;

public final class DesktopFileInfo implements Parcelable {
    public static final Creator<DesktopFileInfo> CREATOR =
            new Creator<DesktopFileInfo>() {
                @Override
                public DesktopFileInfo createFromParcel(final Parcel source) {
                    return new DesktopFileInfo(source);
                }

                @Override
                public DesktopFileInfo[] newArray(final int size) {
                    return new DesktopFileInfo[size];
                }
            };

    final String relativePath;
    final String name;
    final String mimeType;
    final long modified;
    final long size;
    final boolean directory;

    DesktopFileInfo(
            final String relativePath,
            final String name,
            final String mimeType,
            final long modified,
            final long size,
            final boolean directory) {
        this.relativePath = requireText(relativePath, "relative path");
        this.name = requireText(name, "name");
        this.mimeType = requireText(mimeType, "MIME type");
        this.modified = modified;
        this.size = size;
        this.directory = directory;
    }

    private DesktopFileInfo(final Parcel source) {
        relativePath = requireText(source.readString(), "relative path");
        name = requireText(source.readString(), "name");
        mimeType = requireText(source.readString(), "MIME type");
        modified = source.readLong();
        size = source.readLong();
        directory = source.readBoolean();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel destination, final int flags) {
        destination.writeString(relativePath);
        destination.writeString(name);
        destination.writeString(mimeType);
        destination.writeLong(modified);
        destination.writeLong(size);
        destination.writeBoolean(directory);
    }

    private static String requireText(
            final String value, final String label) {
        if (value == null || value.length() == 0) {
            throw new IllegalArgumentException("missing " + label);
        }
        return value;
    }
}
