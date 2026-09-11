package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.function.IntConsumer;

/** The same size picker serves the window override and the default for new windows. */
final class ConsoleFontSizeDialog {
    private ConsoleFontSizeDialog() { }

    static void show(final Activity activity, final int title, final int current,
            final int reset, final IntConsumer apply) {
        final DesktopUiFactory ui = new DesktopUiFactory(activity);
        final LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8));
        final TextView preview = new TextView(activity);
        preview.setText(R.string.console_font_size_preview);
        preview.setTypeface(activity.getResources().getFont(R.font.console_mono));
        preview.setGravity(Gravity.CENTER);
        content.addView(preview, new LinearLayout.LayoutParams(-1, ui.dp(112)));
        final TextView value = new TextView(activity);
        value.setGravity(Gravity.CENTER);
        content.addView(value, new LinearLayout.LayoutParams(-1, -2));
        final SeekBar slider = new SeekBar(activity);
        slider.setMin(ConsolePreferences.MIN_FONT_SIZE_SP);
        slider.setMax(ConsolePreferences.MAX_FONT_SIZE_SP);
        slider.setKeyProgressIncrement(1);
        slider.setContentDescription(activity.getString(R.string.console_font_size));
        content.addView(slider, new LinearLayout.LayoutParams(-1, ui.dp(48)));
        final IntConsumer render = size -> {
            value.setText(activity.getString(R.string.console_font_size_value, size));
            preview.setTextSize(size);
        };
        slider.setProgress(ConsolePreferences.clampFontSize(current));
        render.accept(slider.getProgress());
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(final SeekBar bar, final int progress, final boolean fromUser) {
                render.accept(progress);
            }
            @Override public void onStartTrackingTouch(final SeekBar bar) { }
            @Override public void onStopTrackingTouch(final SeekBar bar) { }
        });
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title).setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.action_reset, null)
                .setPositiveButton(android.R.string.ok, (ignored, which) -> apply.accept(slider.getProgress()))
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(view -> slider.setProgress(ConsolePreferences.clampFontSize(reset))));
        dialog.show();
    }
}
