package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.function.IntConsumer;

/** Linux scale controls shared by Start and the retained-session manager. */
final class GraphicalScaleDialog {
    static void show(Activity activity, String title, String executor, String desktopFile) {
        String key = GraphicalPresentationPreferences.key(executor, desktopFile);
        show(activity, title, GraphicalPresentationPreferences.load(activity, key),
                scale -> GraphicalPresentationPreferences.save(activity, key, scale));
    }

    static void show(Activity activity, GraphicalSessions.Session session) {
        show(activity, session.name(), session.scalePercent(),
                scale -> GraphicalPresentationPreferences.save(activity, session, scale));
    }

    private static void show(Activity activity, String title, int scale, IntConsumer save) {
        var ui = new DesktopUiFactory(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12));
        TextView label = new TextView(activity);
        content.addView(label);
        SeekBar slider = new SeekBar(activity);
        slider.setMin(AppPresentationProfile.MIN_SCALE_PERCENT);
        slider.setMax(AppPresentationProfile.MAX_SCALE_PERCENT);
        slider.setKeyProgressIncrement(5);
        slider.setProgress(scale);
        slider.setContentDescription(activity.getString(R.string.app_presentation_scale));
        content.addView(slider, new LinearLayout.LayoutParams(-1, ui.dp(48)));
        Runnable update = () -> label.setText(activity.getString(R.string.app_presentation_scale)
                + ": " + activity.getString(R.string.app_presentation_scale_value, slider.getProgress()));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser) bar.setProgress(Math.round(value / 5f) * 5);
                update.run();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        update.run();
        IntConsumer apply = value -> {
            try { save.accept(value); }
            catch (RuntimeException error) {
                new AlertDialog.Builder(activity).setMessage(ShellAccess.usefulMessage(error))
                        .setPositiveButton(android.R.string.ok, null).show();
            }
        };
        new AlertDialog.Builder(activity).setTitle(title).setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.app_presentation_system, (dialog, which) -> apply.accept(100))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> apply.accept(slider.getProgress())).show();
    }

    private GraphicalScaleDialog() { }
}
