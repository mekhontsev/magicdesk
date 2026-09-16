package io.github.mekhontsev.magicdesk;

import android.os.Parcel;
import android.os.Parcelable;

/** One process incarnation. Names are labels, never identity or authority. */
public final class SystemProcessSnapshot implements Parcelable {
    public record Identity(int pid, int uid, long startTicks) { }
    public final int pid, uid, parentPid;
    public final long startTicks, cpuTicks, rssKb;
    public final String name, state;

    SystemProcessSnapshot(int pid, int uid, int parentPid, long startTicks, long cpuTicks,
            long rssKb, String name, String state) {
        this.pid = pid; this.uid = uid; this.parentPid = parentPid;
        this.startTicks = startTicks; this.cpuTicks = cpuTicks; this.rssKb = rssKb;
        this.name = name; this.state = state;
    }
    public Identity identity() { return new Identity(pid, uid, startTicks); }
    private SystemProcessSnapshot(Parcel in) {
        this(in.readInt(), in.readInt(), in.readInt(), in.readLong(), in.readLong(),
                in.readLong(), in.readString(), in.readString());
    }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(pid); out.writeInt(uid); out.writeInt(parentPid);
        out.writeLong(startTicks); out.writeLong(cpuTicks); out.writeLong(rssKb);
        out.writeString(name); out.writeString(state);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<SystemProcessSnapshot> CREATOR = new Creator<>() {
        @Override public SystemProcessSnapshot createFromParcel(Parcel in) { return new SystemProcessSnapshot(in); }
        @Override public SystemProcessSnapshot[] newArray(int size) { return new SystemProcessSnapshot[size]; }
    };
}
