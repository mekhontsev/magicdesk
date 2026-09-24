package io.github.mekhontsev.magicdesk.wayland;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Rendered family coordinates, independent of shell placement and exclusive zones. */
public record WaylandViewGeometry(long id, long revision, boolean mapped, Rect paint,
        boolean inputComplete, List<Rect> input, boolean dependents) implements Parcelable {
    public static final int MAX_INPUT_RECTS = 512;
    public WaylandViewGeometry(long id, long revision, boolean mapped, Rect paint, boolean inputComplete, List<Rect> input) {
        this(id, revision, mapped, paint, inputComplete, input, false);
    }

    public record Rect(int left, int top, int right, int bottom) {
        public Rect {
            if (right < left || bottom < top) throw new IllegalArgumentException("Invalid Wayland rectangle");
        }
        public boolean contains(int x, int y) { return x >= left && x < right && y >= top && y < bottom; }
    }

    public WaylandViewGeometry {
        Objects.requireNonNull(paint);
        if (id <= 0 || revision <= 0 || input.size() > MAX_INPUT_RECTS || (!inputComplete && !input.isEmpty()))
            throw new IllegalArgumentException("Invalid Wayland view geometry");
        input = List.copyOf(input);
    }

    public boolean acceptsInput(int x, int y) {
        if (!mapped || !inputComplete) return false;
        for (Rect rect : input) if (rect.contains(x, y)) return true;
        return false;
    }

    static WaylandViewGeometry fromNative(long id, long revision, boolean mapped,
            int left, int top, int right, int bottom, boolean complete, int[] coordinates, boolean dependents) {
        if (coordinates.length % 4 != 0 || coordinates.length > MAX_INPUT_RECTS * 4)
            throw new IllegalArgumentException("Invalid Wayland input region");
        List<Rect> input = new ArrayList<>(coordinates.length / 4);
        for (int i = 0; i < coordinates.length; i += 4)
            input.add(new Rect(coordinates[i], coordinates[i + 1], coordinates[i + 2], coordinates[i + 3]));
        return new WaylandViewGeometry(id, revision, mapped, new Rect(left, top, right, bottom), complete, input, dependents);
    }

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeLong(id); out.writeLong(revision); out.writeBoolean(mapped);
        writeRect(out, paint);
        out.writeBoolean(inputComplete); out.writeInt(input.size());
        for (Rect rect : input) writeRect(out, rect);
        out.writeBoolean(dependents);
    }

    private static void writeRect(Parcel out, Rect rect) {
        out.writeInt(rect.left); out.writeInt(rect.top); out.writeInt(rect.right); out.writeInt(rect.bottom);
    }
    private static Rect readRect(Parcel in) { return new Rect(in.readInt(), in.readInt(), in.readInt(), in.readInt()); }

    public static final Creator<WaylandViewGeometry> CREATOR = new Creator<>() {
        @Override public WaylandViewGeometry createFromParcel(Parcel in) {
            long id = in.readLong(), revision = in.readLong();
            boolean mapped = in.readBoolean();
            Rect paint = readRect(in);
            boolean complete = in.readBoolean();
            int count = in.readInt();
            if (count < 0 || count > MAX_INPUT_RECTS) throw new IllegalArgumentException("Wayland input region too large");
            List<Rect> input = new ArrayList<>(count);
            for (int i = 0; i < count; ++i) input.add(readRect(in));
            return new WaylandViewGeometry(id, revision, mapped, paint, complete, input, in.readBoolean());
        }
        @Override public WaylandViewGeometry[] newArray(int size) { return new WaylandViewGeometry[size]; }
    };
}
