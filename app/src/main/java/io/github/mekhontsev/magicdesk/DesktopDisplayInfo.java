package io.github.mekhontsev.magicdesk;

import android.os.Parcel;
import android.os.Parcelable;

/** A live display identity, distinct from a desktop session and its ownership. */
public final class DesktopDisplayInfo implements Parcelable {
    public final int id;
    public final String uniqueId;
    public final String name;
    public final String source;
    public final int width;
    public final int height;
    public final int densityDpi;
    public final boolean canHostDesktop;
    public final boolean owned;

    DesktopDisplayInfo(final int id, final String uniqueId, final String name,
            final String source, final int width, final int height, final int densityDpi,
            final boolean canHostDesktop, final boolean owned) {
        this.id = id;
        this.uniqueId = uniqueId;
        this.name = name;
        this.source = source;
        this.width = width;
        this.height = height;
        this.densityDpi = densityDpi;
        this.canHostDesktop = canHostDesktop;
        this.owned = owned;
    }

    DesktopDisplayTarget target() {
        if (!canHostDesktop) {
            throw new IllegalArgumentException("display cannot host a desktop: " + id);
        }
        switch (source) {
            case "phone":
            case "internal": return DesktopDisplayTarget.builtIn(id);
            case "wired": return DesktopDisplayTarget.wired(id);
            case "wireless": return DesktopDisplayTarget.wireless(id);
            case "virtual":
            case "overlay":
                return DesktopDisplayTarget.simulated(id).withActivationSource(owned
                        ? DesktopDisplayOutput.ActivationSource.MAGICDESK_REQUESTED
                        : DesktopDisplayOutput.ActivationSource.ADOPTED_EXISTING);
            default: throw new IllegalArgumentException("unsupported display source: " + source);
        }
    }

    public boolean isDefaultDisplay() {
        return id == android.view.Display.DEFAULT_DISPLAY;
    }

    public boolean isBuiltIn() {
        return "phone".equals(source) || "internal".equals(source);
    }

    public boolean canRemove() {
        return owned && !isDefaultDisplay() && !isBuiltIn();
    }

    static boolean supportsDesktop(final int id, final String source,
            final boolean publicDisplay, final boolean trusted) {
        // Additional built-in panels are catalogued, not admitted through the
        // external-display path before their HOME/input lifecycle is verified.
        return !"unknown".equals(source) && !"internal".equals(source)
                && (id == android.view.Display.DEFAULT_DISPLAY || (publicDisplay && trusted));
    }

    private DesktopDisplayInfo(final Parcel in) {
        this(in.readInt(), in.readString(), in.readString(), in.readString(),
                in.readInt(), in.readInt(), in.readInt(),
                in.readInt() != 0, in.readInt() != 0);
    }

    @Override public void writeToParcel(final Parcel out, final int flags) {
        out.writeInt(id);
        out.writeString(uniqueId);
        out.writeString(name);
        out.writeString(source);
        out.writeInt(width);
        out.writeInt(height);
        out.writeInt(densityDpi);
        out.writeInt(canHostDesktop ? 1 : 0);
        out.writeInt(owned ? 1 : 0);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<DesktopDisplayInfo> CREATOR = new Creator<>() {
        @Override public DesktopDisplayInfo createFromParcel(final Parcel in) {
            return new DesktopDisplayInfo(in);
        }
        @Override public DesktopDisplayInfo[] newArray(final int size) {
            return new DesktopDisplayInfo[size];
        }
    };
}
