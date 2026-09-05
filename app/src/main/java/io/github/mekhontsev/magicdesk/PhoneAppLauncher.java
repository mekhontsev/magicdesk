package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.view.Display;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Ordinary phone launches, with desktop tasks returned before Intent reuse. */
final class PhoneAppLauncher {
    private PhoneAppLauncher() {
    }

    static void launch(
            final Activity activity,
            final AppItem app,
            final int desktopDisplayId,
            final BooleanSupplier canLaunch,
            final Runnable onStarted,
            final Consumer<Throwable> onFailure) {
        final Intent intent;
        final LaunchActivityIdentity identity;
        try {
            intent = app.launchTarget.resolve(activity.getPackageManager());
            if (intent == null) {
                throw new IllegalStateException("launcher activity is unavailable");
            }
            identity = LaunchActivityIdentity.resolve(
                    activity.getPackageManager(), app.launchTarget);
        } catch (RuntimeException error) {
            onFailure.accept(error);
            return;
        }
        final Runnable start = () -> {
            if (!canLaunch.getAsBoolean()) {
                return;
            }
            try {
                final ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                activity.startActivity(intent, options.toBundle());
                onStarted.run();
            } catch (RuntimeException error) {
                onFailure.accept(error);
            }
        };
        // An ordinary Intent cannot safely reuse a task across organizer areas.
        // Reuse the existing transfer protocol instead of launching duplicates
        // or teaching this HOME about desktop window topology.
        TaskRepository.load(desktopDisplayId, snapshot -> activity.runOnUiThread(() -> {
            if (!canLaunch.getAsBoolean()) {
                return;
            }
            if (!snapshot.available) {
                onFailure.accept(new IllegalStateException(snapshot.error));
                return;
            }
            final TaskRepository.TaskEntry transfer = selectTransfer(identity, snapshot);
            if (transfer == null) {
                start.run();
                return;
            }
            TaskRepository.moveTaskToDisplay(transfer, Display.DEFAULT_DISPLAY, null,
                    result -> activity.runOnUiThread(() -> {
                        if (!canLaunch.getAsBoolean()) {
                            return;
                        }
                        if (result.success) {
                            start.run();
                        } else {
                            onFailure.accept(new IllegalStateException(result.message));
                        }
                    }));
        }));
    }

    static TaskRepository.TaskEntry selectTransfer(
            final LaunchActivityIdentity identity,
            final TaskRepository.Snapshot snapshot) {
        // A phone instance takes precedence; leave independent desktop tasks alone.
        for (final TaskRepository.TaskEntry task : snapshot.phoneTasks) {
            if (!task.home && identity.matchesTask(task)) {
                return null;
            }
        }
        for (final TaskRepository.TaskEntry task : snapshot.tasks) {
            if (task.displayId != Display.DEFAULT_DISPLAY
                    && !task.home && identity.matchesTask(task)) {
                return task;
            }
        }
        return null;
    }
}
