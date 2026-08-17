package io.github.mekhontsev.magicdesk;

import android.os.Parcel;
import android.os.Parcelable;

/** Resource counters for one Linux process name. */
public final class SystemProcessSnapshot implements Parcelable {
    public static final Creator<SystemProcessSnapshot> CREATOR =
            new Creator<SystemProcessSnapshot>() {
                @Override
                public SystemProcessSnapshot createFromParcel(
                        final Parcel source) {
                    return new SystemProcessSnapshot(source);
                }

                @Override
                public SystemProcessSnapshot[] newArray(final int size) {
                    return new SystemProcessSnapshot[size];
                }
            };

    public final String processName;
    public final float cpuPercent;
    public final long pssKb;

    SystemProcessSnapshot(
            final String processName,
            final float cpuPercent,
            final long pssKb) {
        this.processName = processName == null ? "" : processName;
        this.cpuPercent = cpuPercent;
        this.pssKb = pssKb;
    }

    private SystemProcessSnapshot(final Parcel source) {
        final String readName = source.readString();
        processName = readName == null ? "" : readName;
        cpuPercent = source.readFloat();
        pssKb = source.readLong();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel destination, final int flags) {
        destination.writeString(processName);
        destination.writeFloat(cpuPercent);
        destination.writeLong(pssKb);
    }
}
