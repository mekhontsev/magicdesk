package io.github.mekhontsev.magicdesk.hosted;

import android.hardware.HardwareBuffer;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ParcelFileDescriptor;
import java.io.IOException;

/** Owns one immutable frame and its acquire fence across Binder. */
public final class HostedFrame implements Parcelable, AutoCloseable {
    public final HardwareBuffer buffer;
    public final ParcelFileDescriptor pixels;
    public final ParcelFileDescriptor fence;
    public final int width, height;

    public HostedFrame(HardwareBuffer buffer, ParcelFileDescriptor pixels, ParcelFileDescriptor fence, int width, int height) {
        if ((buffer == null) == (pixels == null) || width < 1 || height < 1 || width > 4096 || height > 4096
                || (buffer != null && (buffer.getWidth() != width || buffer.getHeight() != height)))
            throw new IllegalArgumentException("Invalid hosted frame");
        this.buffer = buffer; this.pixels = pixels; this.fence = fence;
        this.width = width; this.height = height;
    }

    @Override public void close() {
        if (buffer != null) buffer.close();
        close(pixels); close(fence);
    }
    public static void close(HostedFrame frame) { if (frame != null) frame.close(); }
    private static void close(ParcelFileDescriptor descriptor) {
        if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) { }
    }
    @Override public int describeContents() { return CONTENTS_FILE_DESCRIPTOR; }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeTypedObject(buffer, flags); out.writeTypedObject(pixels, flags); out.writeTypedObject(fence, flags);
        out.writeInt(width); out.writeInt(height);
    }
    public static final Creator<HostedFrame> CREATOR = new Creator<>() {
        @Override public HostedFrame createFromParcel(Parcel in) {
            HardwareBuffer buffer = null;
            ParcelFileDescriptor pixels = null, fence = null;
            try {
                buffer = in.readTypedObject(HardwareBuffer.CREATOR);
                pixels = in.readTypedObject(ParcelFileDescriptor.CREATOR);
                fence = in.readTypedObject(ParcelFileDescriptor.CREATOR);
                return new HostedFrame(buffer, pixels, fence, in.readInt(), in.readInt());
            }
            catch (RuntimeException error) {
                if (buffer != null) buffer.close(); close(pixels); close(fence); throw error;
            }
        }
        @Override public HostedFrame[] newArray(int size) { return new HostedFrame[size]; }
    };
}
