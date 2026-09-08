package io.github.mekhontsev.magicdesk;

import android.os.Parcel;
import android.os.Parcelable;

/** Immutable selection of optional shared mechanisms for one desktop session. */
public final class DesktopCompatibilityPolicy implements Parcelable {
    public enum Option {
        FOCUS_REPAIR("focusRepair"),
        CAPTION_REFRESH("captionRefresh"),
        PHONE_TASK_ISOLATION("phoneTaskIsolation"),
        PHONE_TASK_RECOVERY("phoneTaskRecovery"),
        STALE_RECENTS_CLEANUP("staleRecentsCleanup"),
        RECENTS_TO_HOME("recentsToHome");

        final String key;

        Option(final String key) {
            this.key = key;
        }
    }

    public static final DesktopCompatibilityPolicy NONE = new DesktopCompatibilityPolicy(0);
    private final int mBits;

    private DesktopCompatibilityPolicy(final int bits) {
        if ((bits & ~((1 << Option.values().length) - 1)) != 0) {
            throw new IllegalArgumentException("unknown desktop compatibility option");
        }
        mBits = bits;
    }

    public boolean enabled(final Option option) {
        return (mBits & (1 << option.ordinal())) != 0;
    }

    public DesktopCompatibilityPolicy with(final Option option, final boolean enabled) {
        final int bit = 1 << option.ordinal();
        return new DesktopCompatibilityPolicy(enabled ? mBits | bit : mBits & ~bit);
    }

    int bits() {
        return mBits;
    }

    static DesktopCompatibilityPolicy fromBits(final int bits) {
        return new DesktopCompatibilityPolicy(bits);
    }

    @Override
    public String toString() {
        final StringBuilder result = new StringBuilder("{");
        for (final Option option : Option.values()) {
            if (result.length() > 1) {
                result.append(", ");
            }
            result.append(option.key).append('=').append(enabled(option));
        }
        return result.append('}').toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel destination, final int flags) {
        destination.writeInt(mBits);
    }

    public static final Creator<DesktopCompatibilityPolicy> CREATOR =
            new Creator<DesktopCompatibilityPolicy>() {
                @Override
                public DesktopCompatibilityPolicy createFromParcel(final Parcel source) {
                    return fromBits(source.readInt());
                }

                @Override
                public DesktopCompatibilityPolicy[] newArray(final int size) {
                    return new DesktopCompatibilityPolicy[size];
                }
            };
}
