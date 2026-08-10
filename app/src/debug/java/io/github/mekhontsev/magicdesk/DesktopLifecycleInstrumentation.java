package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

/** Runs the production simulated-display lifecycle as a device regression. */
public final class DesktopLifecycleInstrumentation extends Instrumentation {
    @Override
    public void onCreate(final Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        final Thread thread = new Thread(
                this::runLifecycle,
                "MagicDeskLifecycleInstrumentation");
        thread.setDaemon(true);
        thread.start();
    }

    private void runLifecycle() {
        final Bundle output = new Bundle();
        final DesktopSelfTestResult result =
                DesktopSelfTestController.run(getTargetContext());
        output.putString("summary", result.summary());
        output.putString("report", result.format());
        finish(result.hasFailures()
                        ? Activity.RESULT_CANCELED : Activity.RESULT_OK,
                output);
    }
}
