package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.function.IntConsumer;

/** Linux scale controls shared by Start and the retained-session manager. */
final class X11ScaleDialog {
    static void show(Activity activity, String title, String desktopFile) {
        String key = X11PresentationPreferences.key(IntegrationPackage.TERMUX.selected(), desktopFile);
        show(activity, title, X11PresentationPreferences.load(activity, key),
                scale -> X11PresentationPreferences.save(activity, key, scale));
    }

    static void show(Activity activity, X11Sessions.Session session) {
        show(activity, session.name, session.scalePercent(), scale -> {
            X11PresentationPreferences.save(activity, session.presentationKey, scale);
            session.setScale(scale);
        });
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
        new AlertDialog.Builder(activity).setTitle(title).setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.app_presentation_system, (dialog, which) -> save.accept(100))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> save.accept(slider.getProgress())).show();
    }

    private X11ScaleDialog() { }
}
