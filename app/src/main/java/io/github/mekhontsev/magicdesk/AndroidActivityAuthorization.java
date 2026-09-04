package io.github.mekhontsev.magicdesk;

import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Parcel;
import android.os.Parcelable;

/** App-identity authorization required before shell places an Activity task. */
public final class AndroidActivityAuthorization implements Parcelable {
    public static final int ALLOWED = 0;
    public static final int COMPONENT_DISABLED = 1;
    public static final int COMPONENT_NOT_EXPORTED = 2;
    public static final int REQUIRED_PERMISSION_DENIED = 3;

    public static final Creator<AndroidActivityAuthorization> CREATOR =
            new Creator<AndroidActivityAuthorization>() {
                @Override
                public AndroidActivityAuthorization createFromParcel(
                        final Parcel source) {
                    return new AndroidActivityAuthorization(source);
                }

                @Override
                public AndroidActivityAuthorization[] newArray(
                        final int size) {
                    return new AndroidActivityAuthorization[size];
                }
            };

    public final boolean exported;
    public final boolean enabled;
    public final String requiredPermission;
    public final boolean permissionGranted;
    public final boolean samePackage;
    public final int decision;

    private AndroidActivityAuthorization(
            final boolean exported,
            final boolean enabled,
            final String requiredPermission,
            final boolean permissionGranted,
            final boolean samePackage,
            final int decision) {
        this.exported = exported;
        this.enabled = enabled;
        this.requiredPermission = value(requiredPermission);
        this.permissionGranted = permissionGranted;
        this.samePackage = samePackage;
        this.decision = decision;
        validate();
    }

    private AndroidActivityAuthorization(final Parcel source) {
        exported = source.readBoolean();
        enabled = source.readBoolean();
        requiredPermission = value(source.readString());
        permissionGranted = source.readBoolean();
        samePackage = source.readBoolean();
        decision = source.readInt();
        validate();
    }

    static AndroidActivityAuthorization inspect(
            final PackageManager packageManager,
            final ActivityInfo activityInfo,
            final String requestingPackage) {
        if (packageManager == null || activityInfo == null
                || requestingPackage == null || requestingPackage.isEmpty()) {
            throw new IllegalArgumentException(
                    "activity authorization inputs are required");
        }
        final String permission = value(activityInfo.permission);
        final boolean permissionGranted = permission.isEmpty()
                || packageManager.checkPermission(
                        permission, requestingPackage)
                        == PackageManager.PERMISSION_GRANTED;
        final boolean enabled = activityInfo.enabled
                && activityInfo.applicationInfo != null
                && activityInfo.applicationInfo.enabled;
        final boolean samePackage = requestingPackage.equals(
                activityInfo.packageName);
        return evaluate(
                activityInfo.exported,
                enabled,
                permission,
                permissionGranted,
                samePackage);
    }

    static AndroidActivityAuthorization evaluate(
            final boolean exported,
            final boolean enabled,
            final String requiredPermission,
            final boolean permissionGranted) {
        return evaluate(
                exported,
                enabled,
                requiredPermission,
                permissionGranted,
                false);
    }

    static AndroidActivityAuthorization evaluate(
            final boolean exported,
            final boolean enabled,
            final String requiredPermission,
            final boolean permissionGranted,
            final boolean samePackage) {
        final int decision;
        decision = decision(
                exported, enabled, permissionGranted, samePackage);
        return new AndroidActivityAuthorization(
                exported,
                enabled,
                requiredPermission,
                permissionGranted,
                samePackage,
                decision);
    }

    public boolean allowed() {
        return decision == ALLOWED;
    }

    public boolean requiresAppIdentity() {
        return !requiredPermission.isEmpty()
                || (samePackage && !exported);
    }

    public String decisionName() {
        switch (decision) {
            case COMPONENT_DISABLED:
                return "component-disabled";
            case COMPONENT_NOT_EXPORTED:
                return "component-not-exported";
            case REQUIRED_PERMISSION_DENIED:
                return "required-permission-denied";
            default:
                return "allowed";
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel destination, final int flags) {
        destination.writeBoolean(exported);
        destination.writeBoolean(enabled);
        destination.writeString(requiredPermission);
        destination.writeBoolean(permissionGranted);
        destination.writeBoolean(samePackage);
        destination.writeInt(decision);
    }

    private void validate() {
        if (decision < ALLOWED || decision > REQUIRED_PERMISSION_DENIED
                || decision != decision(
                        exported,
                        enabled,
                        permissionGranted,
                        samePackage)) {
            throw new IllegalArgumentException(
                    "invalid Android Activity authorization");
        }
    }

    private static int decision(
            final boolean exported,
            final boolean enabled,
            final boolean permissionGranted,
            final boolean samePackage) {
        if (!enabled) {
            return COMPONENT_DISABLED;
        }
        if (!exported && !samePackage) {
            return COMPONENT_NOT_EXPORTED;
        }
        return permissionGranted
                ? ALLOWED : REQUIRED_PERMISSION_DENIED;
    }

    private static String value(final String source) {
        return source == null ? "" : source;
    }
}
