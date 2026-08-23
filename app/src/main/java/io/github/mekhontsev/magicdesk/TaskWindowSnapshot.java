package io.github.mekhontsev.magicdesk;

import android.os.Parcel;
import android.os.Parcelable;

/** Exact task state read by the privileged desktop task observer. */
public final class TaskWindowSnapshot implements Parcelable {
    public static final int WINDOWING_MODE_FULLSCREEN = 1;
    public static final int WINDOWING_MODE_FREEFORM = 5;

    public static final Creator<TaskWindowSnapshot> CREATOR =
            new Creator<TaskWindowSnapshot>() {
                @Override
                public TaskWindowSnapshot createFromParcel(
                        final Parcel source) {
                    return new TaskWindowSnapshot(source);
                }

                @Override
                public TaskWindowSnapshot[] newArray(final int size) {
                    return new TaskWindowSnapshot[size];
                }
            };

    public final int taskId;
    public final int displayId;
    public final int windowingMode;
    public final boolean visible;
    public final boolean visibilityKnown;
    public final boolean focused;
    public final boolean focusKnown;

    TaskWindowSnapshot(
            final int taskId,
            final int displayId,
            final int windowingMode,
            final boolean visible,
            final boolean visibilityKnown,
            final boolean focused,
            final boolean focusKnown) {
        this.taskId = taskId;
        this.displayId = displayId;
        this.windowingMode = windowingMode;
        this.visible = visible;
        this.visibilityKnown = visibilityKnown;
        this.focused = focused;
        this.focusKnown = focusKnown;
    }

    private TaskWindowSnapshot(final Parcel source) {
        taskId = source.readInt();
        displayId = source.readInt();
        windowingMode = source.readInt();
        visible = source.readBoolean();
        visibilityKnown = source.readBoolean();
        focused = source.readBoolean();
        focusKnown = source.readBoolean();
    }

    boolean isFullscreen() {
        return windowingMode == WINDOWING_MODE_FULLSCREEN;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel destination, final int flags) {
        destination.writeInt(taskId);
        destination.writeInt(displayId);
        destination.writeInt(windowingMode);
        destination.writeBoolean(visible);
        destination.writeBoolean(visibilityKnown);
        destination.writeBoolean(focused);
        destination.writeBoolean(focusKnown);
    }
}
