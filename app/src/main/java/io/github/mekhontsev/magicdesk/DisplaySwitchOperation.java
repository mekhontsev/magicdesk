package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/** Explicit image/input handoff. Ordinary Viewer selection remains view-only. */
final class DisplaySwitchOperation {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private final Context context;
    private final DesktopDisplayInfo output;
    private final DesktopDisplayInfo source;
    private final BuiltInWindowLauncher.Callback completion;
    private final DisplayPresentations.Session previous;
    private final DesktopDisplayInfo previousSource;
    private final int previousInput = MagicDeskRuntime.inputDisplayId();
    private final boolean touchpad = PhoneTouchpadController.shouldRemainVisible(previousInput);
    private long inputVersion = MagicDeskRuntime.inputSelectionVersion();
    private DisplayInputRequests.Request request;
    private DisplayPresentations.Session applied;
    private long appliedBinding;
    private boolean changed;
    private boolean finished;

    DisplaySwitchOperation(Context context, DesktopDisplayInfo output, DesktopDisplayInfo source,
            BuiltInWindowLauncher.Callback completion) {
        this.context = context.getApplicationContext();
        this.output = output;
        this.source = source;
        this.completion = completion;
        previous = DisplayPresentations.forOutput(output.id);
        previousSource = previous == null ? output : previous.source;
    }

    void start() {
        TaskCommandQueue.execute(() -> {
            try {
                DesktopDisplayCatalog.require(output.id, output.uniqueId);
                DesktopDisplayCatalog.require(source.id, source.uniqueId);
                MAIN.post(() -> {
                    if (DisplayPresentations.forOutput(output.id) != previous
                            || !DisplayPresentations.canSwitchOutput(source, output)) {
                        finish(new IllegalStateException("Display presentation changed before switching"));
                        return;
                    }
                    route(-1, error -> {
                        if (error != null) { rollback(error); return; }
                        present();
                    });
                });
            } catch (Exception error) { MAIN.post(() -> finish(error)); }
        });
    }

    private void route(int displayId, BuiltInWindowLauncher.Callback callback) {
        request = MagicDeskRuntime.selectInputDisplay(displayId, inputVersion, result -> MAIN.post(() ->
                callback.onComplete(result.success ? null : new IllegalStateException(result.message))));
        if (request != null) inputVersion = request.version;
    }

    private boolean ownsInput() {
        return request != null && request.isCurrent() && MagicDeskRuntime.inputSelectionVersion() == inputVersion;
    }

    private void present() {
        if (!ownsInput()) { rollback(new IllegalStateException("Input selection changed")); return; }
        if (DisplayPresentations.forOutput(output.id) != previous
                || previous != null && !previous.source.uniqueId.equals(previousSource.uniqueId)) {
            rollback(new IllegalStateException("Display presentation changed"));
            return;
        }
        changed = true;
        final BuiltInWindowLauncher.Callback shown = error -> {
            applied = DisplayPresentations.forOutput(output.id);
            appliedBinding = applied == null ? -1 : applied.bindingGeneration;
            if (error != null) { rollback(error); return; }
            if (source.id != output.id && (applied == null || !applied.ready
                    || !applied.source.uniqueId.equals(source.uniqueId))) {
                rollback(new IllegalStateException("Display presentation is not ready"));
                return;
            }
            route(source.id, failure -> {
                if (failure != null || !ownsInput()) {
                    rollback(failure != null ? failure : new IllegalStateException("Input selection changed"));
                } else {
                    if (applied != null) applied.inputRequest = request;
                    final Throwable touchpadError = restoreTouchpad(source.id);
                    if (touchpadError == null) finish(null);
                    else rollback(touchpadError);
                }
            });
        };
        if (source.id == output.id) {
            if (previous == null) shown.onComplete(null);
            else DisplayPresentations.detach(previous, shown);
        } else DisplayPresentations.attachForSwitch(context, source, output, shown);
    }

    private void rollback(Throwable failure) {
        if (finished) return;
        // A later explicit selection owns input, including a repeated choice of
        // the same display. Never undo it while restoring our presentation.
        final boolean restoreInput = ownsInput();
        final BuiltInWindowLauncher.Callback restored = error -> {
            if (error != null) failure.addSuppressed(error);
            if (restoreInput && ownsInput()) route(previousInput, inputError -> {
                if (inputError != null) failure.addSuppressed(inputError);
                else {
                    final Throwable touchpadError = restoreTouchpad(previousInput);
                    if (touchpadError != null) failure.addSuppressed(touchpadError);
                }
                finish(failure);
            });
            else finish(failure);
        };
        if (restoreInput && changed) {
            route(-1, error -> {
                if (error != null) { failure.addSuppressed(error); finish(failure); }
                else restorePresentation(restored);
            });
        } else restorePresentation(restored);
    }

    private void restorePresentation(BuiltInWindowLauncher.Callback restored) {
        final var current = DisplayPresentations.forOutput(output.id);
        if (!changed || current != applied || current != null
                && (current.bindingGeneration != appliedBinding || !current.source.uniqueId.equals(source.uniqueId))) {
            restored.onComplete(null);
        } else if (previousSource.id == output.id) {
            if (current == null) restored.onComplete(null);
            else DisplayPresentations.detach(current, restored);
        } else DisplayPresentations.attachForSwitch(context, previousSource, output, restored);
    }

    private Throwable restoreTouchpad(int displayId) {
        try {
            if (touchpad && displayId > 0 && output.id != 0) PhoneTouchpadController.open(displayId);
            return null;
        } catch (RuntimeException error) { return error; }
    }

    private void finish(Throwable error) {
        if (finished) return;
        finished = true;
        DesktopAutomationEventJournal.record("display", "switch_completed", error == null,
                "output=" + output.id + " source=" + source.id
                        + (error == null ? "" : " error=" + ShellAccess.usefulMessage(error)));
        completion.onComplete(error);
    }
}
