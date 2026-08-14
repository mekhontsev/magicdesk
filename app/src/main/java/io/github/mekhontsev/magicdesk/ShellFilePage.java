package io.github.mekhontsev.magicdesk;

import android.os.Parcel;
import android.os.Parcelable;

public final class ShellFilePage implements Parcelable {
    public static final Creator<ShellFilePage> CREATOR =
            new Creator<ShellFilePage>() {
                @Override
                public ShellFilePage createFromParcel(final Parcel source) {
                    return new ShellFilePage(source);
                }

                @Override
                public ShellFilePage[] newArray(final int size) {
                    return new ShellFilePage[size];
                }
            };

    public final String directoryPath;
    public final String parentPath;
    public final ShellFileInfo[] entries;
    public final int nextOffset;
    public final boolean complete;

    ShellFilePage(
            final String directoryPath,
            final String parentPath,
            final ShellFileInfo[] entries,
            final int nextOffset,
            final boolean complete) {
        this.directoryPath = directoryPath;
        this.parentPath = parentPath == null ? "" : parentPath;
        this.entries = entries == null ? new ShellFileInfo[0] : entries;
        this.nextOffset = nextOffset;
        this.complete = complete;
    }

    private ShellFilePage(final Parcel source) {
        directoryPath = source.readString();
        final String parent = source.readString();
        parentPath = parent == null ? "" : parent;
        final ShellFileInfo[] parcelEntries = source.createTypedArray(
                ShellFileInfo.CREATOR);
        entries = parcelEntries == null
                ? new ShellFileInfo[0] : parcelEntries;
        nextOffset = source.readInt();
        complete = source.readBoolean();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel destination, final int flags) {
        destination.writeString(directoryPath);
        destination.writeString(parentPath);
        destination.writeTypedArray(entries, flags);
        destination.writeInt(nextOffset);
        destination.writeBoolean(complete);
    }
}
