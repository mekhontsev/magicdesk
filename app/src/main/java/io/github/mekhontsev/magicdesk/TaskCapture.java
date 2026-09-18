package io.github.mekhontsev.magicdesk;

import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import java.io.Closeable;
import java.io.IOException;

/** One fresh task frame and its PNG pipe; metadata belongs to that exact capture. */
public final class TaskCapture implements Parcelable, Closeable {
    record Info(int taskId, int width, int height, int taskWidth, int taskHeight,
            int rotation, String topActivity) { }

    final Info info;
    final ParcelFileDescriptor png;

    TaskCapture(Info info, ParcelFileDescriptor png) {
        this.info = info;
        this.png = png;
    }

    private TaskCapture(Parcel parcel) {
        info = new Info(parcel.readInt(), parcel.readInt(), parcel.readInt(), parcel.readInt(),
                parcel.readInt(), parcel.readInt(), parcel.readString());
        png = parcel.readTypedObject(ParcelFileDescriptor.CREATOR);
    }

    @Override public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(info.taskId());
        parcel.writeInt(info.width());
        parcel.writeInt(info.height());
        parcel.writeInt(info.taskWidth());
        parcel.writeInt(info.taskHeight());
        parcel.writeInt(info.rotation());
        parcel.writeString(info.topActivity());
        parcel.writeTypedObject(png, flags);
    }

    @Override public int describeContents() { return CONTENTS_FILE_DESCRIPTOR; }
    @Override public void close() throws IOException { png.close(); }

    public static final Creator<TaskCapture> CREATOR = new Creator<>() {
        @Override public TaskCapture createFromParcel(Parcel parcel) { return new TaskCapture(parcel); }
        @Override public TaskCapture[] newArray(int size) { return new TaskCapture[size]; }
    };
}
