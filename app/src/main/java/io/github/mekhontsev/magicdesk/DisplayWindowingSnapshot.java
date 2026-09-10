package io.github.mekhontsev.magicdesk;

import android.os.Parcel;
import android.os.Parcelable;

/** A display identity and effective workspace defaults, not a task mode. */
public final class DisplayWindowingSnapshot implements Parcelable {
    public static final Creator<DisplayWindowingSnapshot> CREATOR =
            new Creator<DisplayWindowingSnapshot>() {
                @Override
                public DisplayWindowingSnapshot createFromParcel(final Parcel source) {
                    return new DisplayWindowingSnapshot(source.readInt(),
                            source.readString(), source.readInt(), source.readBoolean(),
                            source.readBoolean());
                }

                @Override
                public DisplayWindowingSnapshot[] newArray(final int size) {
                    return new DisplayWindowingSnapshot[size];
                }
            };

    public final int displayId;
    public final String uniqueId;
    public final int mode;
    public final boolean virtual;
    public final boolean systemDecorations;

    DisplayWindowingSnapshot(final int displayId, final String uniqueId,
            final int mode, final boolean virtual, final boolean systemDecorations) {
        this.displayId = displayId;
        this.uniqueId = uniqueId;
        this.mode = mode;
        this.virtual = virtual;
        this.systemDecorations = systemDecorations;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel destination, final int flags) {
        destination.writeInt(displayId);
        destination.writeString(uniqueId);
        destination.writeInt(mode);
        destination.writeBoolean(virtual);
        destination.writeBoolean(systemDecorations);
    }
}
