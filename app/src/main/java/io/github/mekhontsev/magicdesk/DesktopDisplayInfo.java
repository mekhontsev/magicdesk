package io.github.mekhontsev.magicdesk;

import android.os.Parcel;
import android.os.Parcelable;

/** A live display identity, distinct from a desktop session and its ownership. */
public final class DesktopDisplayInfo implements Parcelable {
    public final int id;
    public final String uniqueId;
    public final String systemName;
    public final String name;
    public final String source;
    public final int width;
    public final int height;
    public final int densityDpi;
    public final boolean canHostDesktop;
    public final boolean requiresPortableDesktop;
    public final boolean owned;
    public final boolean secure;

    DesktopDisplayInfo(final int id, final String uniqueId, final String systemName, final String name,
            final String source, final int width, final int height, final int densityDpi,
            final boolean canHostDesktop, final boolean requiresPortableDesktop,
            final boolean owned, final boolean secure) {
        this.id = id;
        this.uniqueId = uniqueId;
        this.systemName = systemName;
        this.name = name;
        this.source = source;
        this.width = width;
        this.height = height;
        this.densityDpi = densityDpi;
        this.canHostDesktop = canHostDesktop;
        this.requiresPortableDesktop = requiresPortableDesktop;
        this.owned = owned;
        this.secure = secure;
    }

    /** An output capability is not a protection policy for ordinary mirrored screens. */
    boolean protectedContent() { return owned && "virtual".equals(source) && secure; }

    void requirePresentationOutput(final DesktopDisplayInfo output) {
        if (protectedContent() && !output.secure) {
            throw new IllegalArgumentException("Protected content requires a secure output display: " + output.id);
        }
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
        // Android requires a trusted display for organizer-created task areas.
        // Ordinary Viewer output does not acquire those areas on its display.
        // Additional built-in panels are catalogued, not admitted through the
        // external-display path before their HOME/input lifecycle is verified.
        return !"unknown".equals(source) && !"internal".equals(source)
                && (id == android.view.Display.DEFAULT_DISPLAY || (publicDisplay && trusted));
    }

    static boolean requiresPortableDesktop(final int id, final String source,
            final boolean publicDisplay, final boolean trusted) {
        return id != android.view.Display.DEFAULT_DISPLAY && publicDisplay && !trusted
                && !"internal".equals(source) && !"unknown".equals(source);
    }

    private DesktopDisplayInfo(final Parcel in) {
        this(in.readInt(), in.readString(), in.readString(), in.readString(), in.readString(),
                in.readInt(), in.readInt(), in.readInt(),
                in.readInt() != 0, in.readInt() != 0, in.readInt() != 0, in.readInt() != 0);
    }

    @Override public void writeToParcel(final Parcel out, final int flags) {
        out.writeInt(id);
        out.writeString(uniqueId);
        out.writeString(systemName);
        out.writeString(name);
        out.writeString(source);
        out.writeInt(width);
        out.writeInt(height);
        out.writeInt(densityDpi);
        out.writeInt(canHostDesktop ? 1 : 0);
        out.writeInt(requiresPortableDesktop ? 1 : 0);
        out.writeInt(owned ? 1 : 0);
        out.writeInt(secure ? 1 : 0);
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
