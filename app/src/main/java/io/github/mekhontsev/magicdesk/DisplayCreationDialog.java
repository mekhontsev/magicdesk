package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

/** Virtual display creation, independent of Desktop and input routing. */
final class DisplayCreationDialog {
    private final Activity mActivity;
    private final DesktopUiFactory mUi;
    private final DisplayTableView.Actions mActions;
    private boolean mOpen;

    DisplayCreationDialog(Activity activity, DesktopUiFactory ui, DisplayTableView.Actions actions) {
        mActivity = activity;
        mUi = ui;
        mActions = actions;
    }
    void show(final DesktopDisplayInfo reference) {
        if (mOpen) { return; }
        mOpen = true;
        TaskCommandQueue.execute(() -> {
            try {
                final VirtualDisplaySpec previous = VirtualDisplayPreferences.load(mActivity);
                final DisplayProfiles.CreationDefaults defaults = DisplayProfiles.creationDefaults(reference, previous);
                mActivity.runOnUiThread(() -> {
                    if (mActivity.isDestroyed() || mActivity.isFinishing()) { mOpen = false; return; }
                    showPrepared(defaults);
                });
            } catch (RuntimeException error) {
                mActivity.runOnUiThread(() -> {
                    mOpen = false;
                    if (!mActivity.isDestroyed() && !mActivity.isFinishing()) {
                        android.widget.Toast.makeText(mActivity, ShellAccess.usefulMessage(error),
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void showPrepared(final DisplayProfiles.CreationDefaults defaults) {
        // Snapshot only; validate creation limits on Create, not while opening the dialog.
        final int[] resolution = {defaults.width, defaults.height};
        final LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(8));
        final Spinner kind = spinner(content, new String[] {
                mActivity.getString(R.string.display_virtual),
                mActivity.getString(R.string.display_preview)});
        kind.setContentDescription(mActivity.getString(R.string.display_type));
        final int[][] sizes = {resolution,
                {1920, 1080}, {1280, 720}, {2560, 1440}, {2560, 1080}, {3840, 2160}};
        final Spinner preset = spinner(content, new String[] {
                mActivity.getString(R.string.display_resolution_default, resolution[0], resolution[1]),
                "1920 x 1080", "1280 x 720", "2560 x 1440", "2560 x 1080", "3840 x 2160",
                mActivity.getString(R.string.display_custom)});
        preset.setContentDescription(mActivity.getString(R.string.display_resolution));
        final EditText width = number(content, R.string.display_width, resolution[0]);
        final EditText height = number(content, R.string.display_height, resolution[1]);
        final EditText scale = number(content, R.string.display_scale, defaults.densityDpi * 100 / 160);
        final CheckBox protection = new CheckBox(mActivity);
        protection.setText(R.string.display_protected_content);
        protection.setEnabled(false);
        content.addView(protection);
        final boolean[] protectionAllowed = {false};
        kind.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
                protection.setEnabled(position == 0 && protectionAllowed[0]);
                if (position != 0) protection.setChecked(false);
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        preset.setSelection(0);
        preset.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(final AdapterView<?> p, final View v,
                    final int position, final long id) {
                final boolean custom = position >= sizes.length;
                width.setEnabled(custom);
                height.setEnabled(custom);
                if (!custom) {
                    width.setText(Integer.toString(sizes[position][0]));
                    height.setText(Integer.toString(sizes[position][1]));
                }
            }
            @Override public void onNothingSelected(final AdapterView<?> p) { }
        });
        final ScrollView scroll = new ScrollView(mActivity);
        scroll.addView(content);
        final AlertDialog dialog = new AlertDialog.Builder(mActivity)
                .setTitle(R.string.display_create).setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.action_create, null).create();
        dialog.setOnDismissListener(unused -> mOpen = false);
        dialog.setOnShowListener(unused -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    try {
                        final int percent = Integer.parseInt(scale.getText().toString());
                        if (percent < 50 || percent > 400) { throw new IllegalArgumentException(); }
                        final VirtualDisplaySpec spec = defaults.spec(
                                Integer.parseInt(width.getText().toString()),
                                Integer.parseInt(height.getText().toString()), densityForScale(percent, defaults.densityDpi),
                                protection.isChecked());
                        final boolean preview = kind.getSelectedItemPosition() == 1;
                        if (preview) { spec.requireOverlayCompatible(); }
                        mActions.createDisplay(spec, preview);
                        dialog.dismiss();
                    } catch (IllegalArgumentException error) {
                        width.setError(mActivity.getString(R.string.display_invalid_parameters));
                    }
                }));
        dialog.show();
        TaskCommandQueue.execute(() -> {
            boolean allowed = false;
            String error = null;
            try { allowed = ShellAccess.canCreateProtectedDisplay(); }
            catch (java.io.IOException failure) { error = ShellAccess.usefulMessage(failure); }
            final boolean permission = allowed;
            final String failure = error;
            mActivity.runOnUiThread(() -> {
                if (!dialog.isShowing() || mActivity.isDestroyed()) return;
                protectionAllowed[0] = permission;
                protection.setEnabled(permission && kind.getSelectedItemPosition() == 0);
                protection.setTooltipText(failure != null ? failure : permission ? null
                        : mActivity.getString(R.string.display_protected_content_unavailable));
            });
        });
    }

    static int densityForScale(int percent, int defaultDpi) {
        return percent == defaultDpi * 100 / 160 ? defaultDpi : percent * 160 / 100;
    }

    private Spinner spinner(final LinearLayout parent, final String[] labels) {
        final Spinner spinner = new Spinner(mActivity);
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(mActivity,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        parent.addView(spinner, new LinearLayout.LayoutParams(-1, dp(48)));
        return spinner;
    }

    private EditText number(final LinearLayout parent, final int label, final int value) {
        final TextView title = new TextView(mActivity);
        title.setText(label);
        parent.addView(title);
        final EditText input = new EditText(mActivity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setText(Integer.toString(value));
        parent.addView(input, new LinearLayout.LayoutParams(-1, dp(48)));
        return input;
    }

    private int dp(final int value) { return mUi.dp(value); }
}
