package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_MUTED;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_TEXT;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Displays project identity without introducing a separate activity or task. */
final class AboutDialog {
    private static final String PROJECT_URL =
            "https://github.com/mekhontsev/magicdesk";
    private static final String LICENSE_URL = PROJECT_URL + "/blob/main/LICENSE";

    private AboutDialog() {
    }

    static void show(final Activity activity) {
        final DesktopUiFactory ui = new DesktopUiFactory(activity);
        final LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(8));

        final ImageView icon = new ImageView(activity);
        icon.setImageResource(R.drawable.ic_magicdesk);
        icon.setContentDescription(activity.getString(R.string.app_name));
        content.addView(icon, new LinearLayout.LayoutParams(
                ui.dp(80), ui.dp(80)));

        final TextView name = text(activity, R.string.app_name, 24, true);
        final LinearLayout.LayoutParams nameParams = wrapContent();
        nameParams.topMargin = ui.dp(10);
        content.addView(name, nameParams);

        final TextView version = text(
                activity,
                activity.getString(
                        R.string.about_version,
                        BuildConfig.VERSION_NAME,
                        Integer.valueOf(BuildConfig.VERSION_CODE)),
                14,
                false,
                COLOR_MUTED);
        content.addView(version, wrapContent());

        final TextView author = text(
                activity, R.string.about_author, 15, false);
        final LinearLayout.LayoutParams authorParams = wrapContent();
        authorParams.topMargin = ui.dp(16);
        content.addView(author, authorParams);

        content.addView(
                text(activity, R.string.about_copyright, 13, false,
                        COLOR_MUTED),
                wrapContent());
        final TextView license = text(
                activity, R.string.about_license, 13, false, COLOR_MUTED);
        final LinearLayout.LayoutParams licenseParams = wrapContent();
        licenseParams.topMargin = ui.dp(8);
        content.addView(license, licenseParams);

        new AlertDialog.Builder(activity)
                .setView(content)
                .setNeutralButton(
                        R.string.about_github,
                        (dialog, which) -> open(activity, PROJECT_URL))
                .setNegativeButton(
                        R.string.about_mit_license,
                        (dialog, which) -> open(activity, LICENSE_URL))
                .setPositiveButton(R.string.action_close, null)
                .show();
    }

    private static TextView text(
            final Activity activity,
            final int textResId,
            final int size,
            final boolean bold) {
        return text(activity, textResId, size, bold, COLOR_TEXT);
    }

    private static TextView text(
            final Activity activity,
            final int textResId,
            final int size,
            final boolean bold,
            final int color) {
        return text(activity, activity.getString(textResId), size, bold,
                color);
    }

    private static TextView text(
            final Activity activity,
            final CharSequence value,
            final int size,
            final boolean bold,
            final int color) {
        final TextView text = new TextView(activity);
        text.setText(value);
        text.setTextColor(color);
        text.setTextSize(size);
        text.setGravity(Gravity.CENTER);
        if (bold) {
            text.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return text;
    }

    private static LinearLayout.LayoutParams wrapContent() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static void open(
            final Activity activity,
            final String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(
                    activity,
                    R.string.about_link_unavailable,
                    Toast.LENGTH_SHORT).show();
        }
    }
}
