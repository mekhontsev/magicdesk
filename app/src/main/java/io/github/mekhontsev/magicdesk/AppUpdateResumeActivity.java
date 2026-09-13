package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/** Installer-only process entry. Theme.NoDisplay creates no window or launcher surface.
 * Do not use noHistory: loss of visibility during cold startup can remove this
 * Activity before onCreate. This entry, not visibility, owns its completion. */
public final class AppUpdateResumeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            MagicDeskRuntime.startAutomation(this);
        } catch (RuntimeException error) {
            Log.e("MagicDeskUpdate", "Could not resume automation", error);
        } finally {
            finish();
        }
    }
}
