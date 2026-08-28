package io.github.mekhontsev.magicdesk;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Semantic workspace intent plus its already-resolved physical stack plan. */
public final class DesktopWorkspaceCommand implements Parcelable {
    public static final int ACTIVATE = 1;
    public static final int DEMOTE = 2;
    public static final int PRESENT_DESKTOP = 3;
    public static final int RESTORE_WORKSPACE = 4;
    public static final int RESTORE_SESSION = 5;

    public static final Creator<DesktopWorkspaceCommand> CREATOR =
            new Creator<DesktopWorkspaceCommand>() {
                @Override
                public DesktopWorkspaceCommand createFromParcel(
                        final Parcel source) {
                    return new DesktopWorkspaceCommand(source);
                }

                @Override
                public DesktopWorkspaceCommand[] newArray(final int size) {
                    return new DesktopWorkspaceCommand[size];
                }
            };

    public final int operation;
    public final int displayId;
    public final int targetTaskId;
    public final int[] backToFrontTaskIds;

    private DesktopWorkspaceCommand(
            final int operation,
            final int displayId,
            final int targetTaskId,
            final int[] backToFrontTaskIds) {
        this.operation = operation;
        this.displayId = displayId;
        this.targetTaskId = targetTaskId;
        this.backToFrontTaskIds = backToFrontTaskIds == null
                ? new int[0] : backToFrontTaskIds.clone();
    }

    private DesktopWorkspaceCommand(final Parcel source) {
        operation = source.readInt();
        displayId = source.readInt();
        targetTaskId = source.readInt();
        final int[] taskIds = source.createIntArray();
        backToFrontTaskIds = taskIds == null ? new int[0] : taskIds;
    }

    static DesktopWorkspaceCommand create(
            final int operation,
            final int displayId,
            final int targetTaskId,
            final int[] backToFrontTaskIds) {
        final DesktopWorkspaceCommand command = new DesktopWorkspaceCommand(
                operation, displayId, targetTaskId, backToFrontTaskIds);
        command.validate();
        return command;
    }

    void validate() {
        if (!isKnownOperation(operation) || displayId < 0
                || targetTaskId < 0 || backToFrontTaskIds.length == 0
                || backToFrontTaskIds[backToFrontTaskIds.length - 1]
                        != targetTaskId) {
            throw new IllegalArgumentException(
                    "invalid desktop workspace command");
        }
        final Set<Integer> uniqueTaskIds = new HashSet<>();
        for (final int taskId : backToFrontTaskIds) {
            if (taskId < 0
                    || !uniqueTaskIds.add(Integer.valueOf(taskId))) {
                throw new IllegalArgumentException(
                        "invalid or duplicate desktop workspace task");
            }
        }
    }

    String operationName() {
        return operationName(operation);
    }

    static String operationName(final int operation) {
        switch (operation) {
            case ACTIVATE:
                return "activate";
            case DEMOTE:
                return "demote";
            case PRESENT_DESKTOP:
                return "present-desktop";
            case RESTORE_WORKSPACE:
                return "restore-workspace";
            case RESTORE_SESSION:
                return "restore-session";
            default:
                return "unknown(" + operation + ")";
        }
    }

    boolean presentsDesktop() {
        return operation == PRESENT_DESKTOP;
    }

    private static boolean isKnownOperation(final int operation) {
        return operation >= ACTIVATE && operation <= RESTORE_SESSION;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel destination, final int flags) {
        destination.writeInt(operation);
        destination.writeInt(displayId);
        destination.writeInt(targetTaskId);
        destination.writeIntArray(backToFrontTaskIds);
    }

    @Override
    public String toString() {
        return operationName() + " display=" + displayId
                + " target=" + targetTaskId
                + " order=" + Arrays.toString(backToFrontTaskIds);
    }
}
