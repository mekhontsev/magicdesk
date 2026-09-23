package io.github.mekhontsev.magicdesk.wayland;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Objects;

/** Committed protocol intent in surface coordinates, not an Android focus grant. */
public record WaylandShellSurface(long id, long revision, String name, boolean mapped, boolean configureNeeded,
        Layer layer, Keyboard keyboard, int anchors, long width, long height,
        int marginLeft, int marginTop, int marginRight, int marginBottom,
        int exclusiveZone) implements Parcelable {
    public enum Layer { BACKGROUND, BOTTOM, TOP, OVERLAY }
    public enum Keyboard { NONE, ON_DEMAND, EXCLUSIVE }
    public static final int LEFT = 1, TOP = 2, RIGHT = 4, BOTTOM = 8;
    private static final Layer[] LAYERS = Layer.values();
    private static final Keyboard[] KEYBOARDS = Keyboard.values();

    public WaylandShellSurface {
        Objects.requireNonNull(name);
        Objects.requireNonNull(layer);
        Objects.requireNonNull(keyboard);
        if (id <= 0 || revision <= 0 || (anchors & ~15) != 0
                || width < 0 || width > 0xffffffffL || height < 0 || height > 0xffffffffL
                || (width == 0 && (anchors & (LEFT | RIGHT)) != (LEFT | RIGHT))
                || (height == 0 && (anchors & (TOP | BOTTOM)) != (TOP | BOTTOM))) {
            throw new IllegalArgumentException("Invalid committed Wayland shell surface");
        }
    }

    static Layer layer(int code) {
        if (code < 0 || code >= LAYERS.length) throw new IllegalArgumentException("Invalid shell layer");
        return LAYERS[code];
    }

    static Keyboard keyboard(int code) {
        if (code < 0 || code >= KEYBOARDS.length) throw new IllegalArgumentException("Invalid shell keyboard intent");
        return KEYBOARDS[code];
    }

    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeLong(id); out.writeLong(revision); out.writeString(name); out.writeBoolean(mapped);
        out.writeBoolean(configureNeeded);
        out.writeInt(layer.ordinal()); out.writeInt(keyboard.ordinal()); out.writeInt(anchors);
        out.writeLong(width); out.writeLong(height);
        out.writeInt(marginLeft); out.writeInt(marginTop); out.writeInt(marginRight); out.writeInt(marginBottom);
        out.writeInt(exclusiveZone);
    }

    public static final Creator<WaylandShellSurface> CREATOR = new Creator<>() {
        @Override public WaylandShellSurface createFromParcel(Parcel in) {
            return new WaylandShellSurface(in.readLong(), in.readLong(), in.readString(), in.readBoolean(), in.readBoolean(),
                    layer(in.readInt()), keyboard(in.readInt()), in.readInt(), in.readLong(), in.readLong(),
                    in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
        }
        @Override public WaylandShellSurface[] newArray(int size) { return new WaylandShellSurface[size]; }
    };
}
