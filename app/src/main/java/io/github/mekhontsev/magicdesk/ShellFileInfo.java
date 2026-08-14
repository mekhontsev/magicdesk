package io.github.mekhontsev.magicdesk;

import android.os.Parcel;
import android.os.Parcelable;

public final class ShellFileInfo implements Parcelable {
    public static final Creator<ShellFileInfo> CREATOR =
            new Creator<ShellFileInfo>() {
                @Override
                public ShellFileInfo createFromParcel(final Parcel source) {
                    return new ShellFileInfo(source);
                }

                @Override
                public ShellFileInfo[] newArray(final int size) {
                    return new ShellFileInfo[size];
                }
            };

    public final String absolutePath;
    public final String name;
    public final String mimeType;
    public final String linkTarget;
    public final long modified;
    public final long size;
    public final long deviceId;
    public final long inode;
    public final int ownerUid;
    public final int ownerGid;
    public final int mode;
    public final boolean directory;
    public final boolean symbolicLink;
    public final boolean readable;
    public final boolean writable;
    public final boolean executable;
    public final boolean hidden;

    ShellFileInfo(
            final String absolutePath,
            final String name,
            final String mimeType,
            final String linkTarget,
            final long modified,
            final long size,
            final long deviceId,
            final long inode,
            final int ownerUid,
            final int ownerGid,
            final int mode,
            final boolean directory,
            final boolean symbolicLink,
            final boolean readable,
            final boolean writable,
            final boolean executable,
            final boolean hidden) {
        this.absolutePath = requireText(absolutePath, "absolute path");
        this.name = requireText(name, "name");
        this.mimeType = requireText(mimeType, "MIME type");
        this.linkTarget = linkTarget == null ? "" : linkTarget;
        this.modified = modified;
        this.size = size;
        this.deviceId = deviceId;
        this.inode = inode;
        this.ownerUid = ownerUid;
        this.ownerGid = ownerGid;
        this.mode = mode;
        this.directory = directory;
        this.symbolicLink = symbolicLink;
        this.readable = readable;
        this.writable = writable;
        this.executable = executable;
        this.hidden = hidden;
    }

    private ShellFileInfo(final Parcel source) {
        absolutePath = requireText(source.readString(), "absolute path");
        name = requireText(source.readString(), "name");
        mimeType = requireText(source.readString(), "MIME type");
        final String parcelLinkTarget = source.readString();
        linkTarget = parcelLinkTarget == null ? "" : parcelLinkTarget;
        modified = source.readLong();
        size = source.readLong();
        deviceId = source.readLong();
        inode = source.readLong();
        ownerUid = source.readInt();
        ownerGid = source.readInt();
        mode = source.readInt();
        directory = source.readBoolean();
        symbolicLink = source.readBoolean();
        readable = source.readBoolean();
        writable = source.readBoolean();
        executable = source.readBoolean();
        hidden = source.readBoolean();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel destination, final int flags) {
        destination.writeString(absolutePath);
        destination.writeString(name);
        destination.writeString(mimeType);
        destination.writeString(linkTarget);
        destination.writeLong(modified);
        destination.writeLong(size);
        destination.writeLong(deviceId);
        destination.writeLong(inode);
        destination.writeInt(ownerUid);
        destination.writeInt(ownerGid);
        destination.writeInt(mode);
        destination.writeBoolean(directory);
        destination.writeBoolean(symbolicLink);
        destination.writeBoolean(readable);
        destination.writeBoolean(writable);
        destination.writeBoolean(executable);
        destination.writeBoolean(hidden);
    }

    private static String requireText(final String value, final String label) {
        if (value == null || value.length() == 0) {
            throw new IllegalArgumentException("missing " + label);
        }
        return value;
    }
}
