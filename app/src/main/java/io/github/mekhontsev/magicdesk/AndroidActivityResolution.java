package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.List;

/** Typed Activity resolution that distinguishes real handlers from Android's resolver. */
public final class AndroidActivityResolution implements Parcelable {
    public static final int NONE = 0;
    public static final int CONCRETE = 1;
    public static final int RESOLVER = 2;

    public static final Creator<AndroidActivityResolution> CREATOR =
            new Creator<AndroidActivityResolution>() {
                @Override
                public AndroidActivityResolution createFromParcel(
                        final Parcel source) {
                    return new AndroidActivityResolution(source);
                }

                @Override
                public AndroidActivityResolution[] newArray(final int size) {
                    return new AndroidActivityResolution[size];
                }
            };

    public final int state;
    public final ComponentName component;
    public final int handlerCount;

    private AndroidActivityResolution(
            final int state,
            final ComponentName component,
            final int handlerCount) {
        this.state = state;
        this.component = component;
        this.handlerCount = Math.max(0, handlerCount);
        validate();
    }

    private AndroidActivityResolution(final Parcel source) {
        state = source.readInt();
        component = source.readTypedObject(ComponentName.CREATOR);
        handlerCount = source.readInt();
        validate();
    }

    static AndroidActivityResolution resolve(
            final PackageManager packageManager,
            final Intent intent) {
        if (packageManager == null || intent == null) {
            throw new IllegalArgumentException(
                    "PackageManager and Intent are required");
        }
        final List<ResolveInfo> handlers = packageManager.queryIntentActivities(
                intent, PackageManager.MATCH_DEFAULT_ONLY);
        final int handlerCount = handlers == null ? 0 : handlers.size();
        if (handlerCount == 0) {
            return new AndroidActivityResolution(NONE, null, 0);
        }
        final ResolveInfo resolved = packageManager.resolveActivity(
                intent, PackageManager.MATCH_DEFAULT_ONLY);
        final ComponentName resolvedComponent = componentOf(resolved);
        if (resolvedComponent != null && contains(handlers, resolvedComponent)) {
            return new AndroidActivityResolution(
                    CONCRETE, resolvedComponent, handlerCount);
        }
        // PackageManager returns an internal ResolverActivity when the user
        // must choose. It is not one of the actual handlers and must remain an
        // implicit app-owned launch rather than an explicit shell target.
        return new AndroidActivityResolution(RESOLVER, null, handlerCount);
    }

    boolean requiresResolver() {
        return state == RESOLVER;
    }

    boolean hasHandlers() {
        return state != NONE;
    }

    String stateName() {
        switch (state) {
            case CONCRETE:
                return "concrete";
            case RESOLVER:
                return "resolver";
            default:
                return "none";
        }
    }

    private static boolean contains(
            final List<ResolveInfo> handlers,
            final ComponentName component) {
        for (final ResolveInfo handler : handlers) {
            if (component.equals(componentOf(handler))) {
                return true;
            }
        }
        return false;
    }

    private static ComponentName componentOf(final ResolveInfo resolveInfo) {
        final ActivityInfo activityInfo = resolveInfo == null
                ? null : resolveInfo.activityInfo;
        return activityInfo == null ? null : new ComponentName(
                activityInfo.packageName, activityInfo.name);
    }

    private void validate() {
        if (state < NONE || state > RESOLVER || handlerCount < 0
                || (state == CONCRETE) != (component != null)
                || (state == NONE && handlerCount != 0)) {
            throw new IllegalArgumentException(
                    "invalid Android Activity resolution");
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel destination, final int flags) {
        destination.writeInt(state);
        destination.writeTypedObject(component, flags);
        destination.writeInt(handlerCount);
    }
}
