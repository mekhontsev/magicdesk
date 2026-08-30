package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;

/** Typed task state shared by the framework observer and app process. */
public final class FrameworkTaskSnapshot implements Parcelable {
    public static final int WINDOWING_MODE_FULLSCREEN = 1;
    public static final int WINDOWING_MODE_FREEFORM = 5;
    public static final int ACTIVITY_TYPE_HOME = 2;

    public static final Creator<FrameworkTaskSnapshot> CREATOR =
            new Creator<FrameworkTaskSnapshot>() {
                @Override
                public FrameworkTaskSnapshot createFromParcel(
                        final Parcel source) {
                    return new FrameworkTaskSnapshot(source);
                }

                @Override
                public FrameworkTaskSnapshot[] newArray(final int size) {
                    return new FrameworkTaskSnapshot[size];
                }
            };

    final Object task;
    final ComponentName rootComponent;
    final ComponentName topComponent;
    final Integer requestedVisibleTypes;
    public final int rootTaskId;
    public final int taskId;
    public final int displayId;
    public final int displayAreaFeatureId;
    public final int windowingMode;
    public final int activityType;
    public final String componentName;
    public final String topActivityName;
    public final String packageName;
    public final String topPackage;
    public final int topUid;
    public final String topProcessName;
    public final Rect bounds;
    public final boolean visible;
    public final boolean focused;
    public final boolean requestedVisibleTypesKnown;
    public final int requestedVisibleTypesValue;
    public final boolean taskConfigurationKnown;
    public final int densityDpi;
    public final int screenWidthDp;
    public final int screenHeightDp;
    public final int smallestScreenWidthDp;

    FrameworkTaskSnapshot(
            final Object rawTask,
            final int rootTaskId,
            final int taskId,
            final int displayId,
            final int displayAreaFeatureId,
            final int windowingMode,
            final int activityType,
            final ComponentName component,
            final ComponentName topActivity,
            final String componentName,
            final String topActivityName,
            final String packageName,
            final String topPackage,
            final int topUid,
            final String topProcessName,
            final Rect bounds,
            final boolean visible,
            final boolean focused,
            final Integer requestedVisibleTypes) {
        this(
                rawTask,
                rootTaskId,
                taskId,
                displayId,
                displayAreaFeatureId,
                windowingMode,
                activityType,
                component,
                topActivity,
                componentName,
                topActivityName,
                packageName,
                topPackage,
                topUid,
                topProcessName,
                bounds,
                visible,
                focused,
                requestedVisibleTypes,
                null);
    }

    FrameworkTaskSnapshot(
            final Object rawTask,
            final int rootTaskId,
            final int taskId,
            final int displayId,
            final int displayAreaFeatureId,
            final int windowingMode,
            final int activityType,
            final ComponentName component,
            final ComponentName topActivity,
            final String componentName,
            final String topActivityName,
            final String packageName,
            final String topPackage,
            final int topUid,
            final String topProcessName,
            final Rect bounds,
            final boolean visible,
            final boolean focused,
            final Integer requestedVisibleTypes,
            final TaskConfiguration taskConfiguration) {
        task = rawTask;
        rootComponent = component;
        topComponent = topActivity;
        this.rootTaskId = rootTaskId;
        this.taskId = taskId;
        this.displayId = displayId;
        this.displayAreaFeatureId = displayAreaFeatureId;
        this.windowingMode = windowingMode;
        this.activityType = activityType;
        this.componentName = nullToEmpty(componentName);
        this.topActivityName = nullToEmpty(topActivityName);
        this.packageName = nullToEmpty(packageName);
        this.topPackage = topPackage;
        this.topUid = topUid;
        this.topProcessName = topProcessName;
        this.bounds = bounds == null ? new Rect() : new Rect(bounds);
        this.visible = visible;
        this.focused = focused;
        requestedVisibleTypesKnown = requestedVisibleTypes != null;
        requestedVisibleTypesValue = requestedVisibleTypes == null
                ? 0 : requestedVisibleTypes.intValue();
        this.requestedVisibleTypes = requestedVisibleTypes;
        taskConfigurationKnown = taskConfiguration != null;
        densityDpi = taskConfiguration == null
                ? -1 : taskConfiguration.densityDpi;
        screenWidthDp = taskConfiguration == null
                ? -1 : taskConfiguration.screenWidthDp;
        screenHeightDp = taskConfiguration == null
                ? -1 : taskConfiguration.screenHeightDp;
        smallestScreenWidthDp = taskConfiguration == null
                ? -1 : taskConfiguration.smallestScreenWidthDp;
    }

    private FrameworkTaskSnapshot(final Parcel source) {
        task = null;
        rootTaskId = source.readInt();
        taskId = source.readInt();
        displayId = source.readInt();
        displayAreaFeatureId = source.readInt();
        windowingMode = source.readInt();
        activityType = source.readInt();
        componentName = nullToEmpty(source.readString());
        topActivityName = nullToEmpty(source.readString());
        rootComponent = ComponentName.unflattenFromString(componentName);
        topComponent = ComponentName.unflattenFromString(topActivityName);
        packageName = nullToEmpty(source.readString());
        topPackage = source.readString();
        topUid = source.readInt();
        topProcessName = source.readString();
        bounds = source.readTypedObject(Rect.CREATOR);
        visible = source.readBoolean();
        focused = source.readBoolean();
        requestedVisibleTypesKnown = source.readBoolean();
        requestedVisibleTypesValue = source.readInt();
        requestedVisibleTypes = requestedVisibleTypesKnown
                ? Integer.valueOf(requestedVisibleTypesValue) : null;
        taskConfigurationKnown = source.readBoolean();
        densityDpi = source.readInt();
        screenWidthDp = source.readInt();
        screenHeightDp = source.readInt();
        smallestScreenWidthDp = source.readInt();
    }

    Boolean requestingImmersive() {
        return requestedVisibleTypesKnown
                ? Boolean.valueOf(
                        FrameworkTaskObservationSource.isRequestingImmersive(
                                requestedVisibleTypes))
                : null;
    }

    boolean isHome() {
        return activityType == ACTIVITY_TYPE_HOME;
    }

    String windowingModeName() {
        if (windowingMode == WINDOWING_MODE_FULLSCREEN) {
            return "fullscreen";
        }
        if (windowingMode == WINDOWING_MODE_FREEFORM) {
            return "freeform";
        }
        return Integer.toString(windowingMode);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel destination, final int flags) {
        destination.writeInt(rootTaskId);
        destination.writeInt(taskId);
        destination.writeInt(displayId);
        destination.writeInt(displayAreaFeatureId);
        destination.writeInt(windowingMode);
        destination.writeInt(activityType);
        destination.writeString(componentName);
        destination.writeString(topActivityName);
        destination.writeString(packageName);
        destination.writeString(topPackage);
        destination.writeInt(topUid);
        destination.writeString(topProcessName);
        destination.writeTypedObject(bounds, flags);
        destination.writeBoolean(visible);
        destination.writeBoolean(focused);
        destination.writeBoolean(requestedVisibleTypesKnown);
        destination.writeInt(requestedVisibleTypesValue);
        destination.writeBoolean(taskConfigurationKnown);
        destination.writeInt(densityDpi);
        destination.writeInt(screenWidthDp);
        destination.writeInt(screenHeightDp);
        destination.writeInt(smallestScreenWidthDp);
    }

    static final class TaskConfiguration {
        final int densityDpi;
        final int screenWidthDp;
        final int screenHeightDp;
        final int smallestScreenWidthDp;

        TaskConfiguration(
                final int density,
                final int widthDp,
                final int heightDp,
                final int smallestWidthDp) {
            densityDpi = density;
            screenWidthDp = widthDp;
            screenHeightDp = heightDp;
            smallestScreenWidthDp = smallestWidthDp;
        }
    }

    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }
}
