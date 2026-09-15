package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Apps is an ordinary screen of the control panel; it never acquires HOME. */
public final class StartActivity extends Activity {
    private FullscreenStartController mStart;

    static void open(Activity activity, DesktopDisplayInfo display) {
        activity.startActivity(new Intent(activity, StartActivity.class)
                .putExtra("start.display_id", display.id)
                .putExtra("start.display_unique_id", display.uniqueId)
                .putExtra("start.display_name", display.name));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        mStart = new FullscreenStartController(this, false);
    }
    @Override protected void onStart() { super.onStart(); mStart.start(); }
    @Override protected void onResume() { super.onResume(); mStart.resume(); }
    @Override protected void onStop() { mStart.stop(); super.onStop(); }
    @Override protected void onDestroy() { mStart.destroy(); super.onDestroy(); }
}
