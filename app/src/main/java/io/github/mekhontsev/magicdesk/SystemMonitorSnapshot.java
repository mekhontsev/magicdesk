package io.github.mekhontsev.magicdesk;

import android.os.Parcel;
import android.os.Parcelable;

/** Bounded system-monitor snapshot produced by the shell service. */
public final class SystemMonitorSnapshot implements Parcelable {
    public static final Creator<SystemMonitorSnapshot> CREATOR =
            new Creator<SystemMonitorSnapshot>() {
                @Override
                public SystemMonitorSnapshot createFromParcel(
                        final Parcel source) {
                    return new SystemMonitorSnapshot(source);
                }

                @Override
                public SystemMonitorSnapshot[] newArray(final int size) {
                    return new SystemMonitorSnapshot[size];
                }
            };

    public final boolean available;
    public final long totalMemoryKb;
    public final long availableMemoryKb;
    public final long cpuTotal;
    public final long cpuIdle;
    public final float loadAverage;
    public final SystemProcessSnapshot[] processes;
    public final String error;

    SystemMonitorSnapshot(
            final boolean available,
            final long totalMemoryKb,
            final long availableMemoryKb,
            final long cpuTotal,
            final long cpuIdle,
            final float loadAverage,
            final SystemProcessSnapshot[] processes,
            final String error) {
        this.available = available;
        this.totalMemoryKb = totalMemoryKb;
        this.availableMemoryKb = availableMemoryKb;
        this.cpuTotal = cpuTotal;
        this.cpuIdle = cpuIdle;
        this.loadAverage = loadAverage;
        this.processes = processes == null
                ? new SystemProcessSnapshot[0]
                : processes.clone();
        this.error = error == null ? "" : error;
    }

    private SystemMonitorSnapshot(final Parcel source) {
        available = source.readBoolean();
        totalMemoryKb = source.readLong();
        availableMemoryKb = source.readLong();
        cpuTotal = source.readLong();
        cpuIdle = source.readLong();
        loadAverage = source.readFloat();
        final SystemProcessSnapshot[] readProcesses =
                source.createTypedArray(SystemProcessSnapshot.CREATOR);
        processes = readProcesses == null
                ? new SystemProcessSnapshot[0]
                : readProcesses;
        final String readError = source.readString();
        error = readError == null ? "" : readError;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel destination, final int flags) {
        destination.writeBoolean(available);
        destination.writeLong(totalMemoryKb);
        destination.writeLong(availableMemoryKb);
        destination.writeLong(cpuTotal);
        destination.writeLong(cpuIdle);
        destination.writeFloat(loadAverage);
        destination.writeTypedArray(processes, flags);
        destination.writeString(error);
    }

    static SystemMonitorSnapshot unavailable(final String error) {
        return new SystemMonitorSnapshot(
                false,
                -1L,
                -1L,
                -1L,
                -1L,
                -1f,
                new SystemProcessSnapshot[0],
                error);
    }
}
