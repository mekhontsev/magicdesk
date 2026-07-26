package io.github.mekhontsev.magicdesk.kernel;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class KernelFixesActivity extends Activity {
    private static final int COLOR_BACKGROUND = 0xFF090D14;
    private static final int COLOR_PANEL = 0xFF111827;
    private static final int COLOR_PANEL_ALT = 0xFF172033;
    private static final int COLOR_TEXT = 0xFFE5E7EB;
    private static final int COLOR_MUTED = 0xFF94A3B8;
    private static final int COLOR_CYAN = 0xFF22D3EE;
    private static final int COLOR_AMBER = 0xFFF59E0B;
    private static final int COLOR_RED = 0xFFF43F5E;

    private TextView mXrStatus;
    private Button mXrAction;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContent());
        updateActiveState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateActiveState();
    }

    private View createContent() {
        final ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BACKGROUND);

        final LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(20), dp(20), dp(24));

        final TextView title = text(R.string.screen_title, 26, COLOR_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        page.addView(title);

        final TextView subtitle = text(R.string.screen_subtitle, 14, COLOR_MUTED);
        page.addView(subtitle, marginTop(dp(4)));

        final TextView warning = text(R.string.security_warning, 14, COLOR_TEXT);
        warning.setPadding(dp(14), dp(12), dp(14), dp(12));
        warning.setBackground(panel(COLOR_PANEL_ALT, COLOR_AMBER));
        page.addView(warning, marginTop(dp(18)));

        final LinearLayout fix = new LinearLayout(this);
        fix.setOrientation(LinearLayout.VERTICAL);
        fix.setPadding(dp(16), dp(15), dp(16), dp(16));
        fix.setBackground(panel(COLOR_PANEL, COLOR_PANEL_ALT));

        final TextView fixTitle = text(R.string.xr_fix_title, 18, COLOR_TEXT);
        fixTitle.setTypeface(Typeface.DEFAULT_BOLD);
        fix.addView(fixTitle);

        final TextView device = text(R.string.xr_fix_device, 13, COLOR_MUTED);
        fix.addView(device, marginTop(dp(6)));

        mXrStatus = text(R.string.xr_fix_ready, 13, COLOR_AMBER);
        mXrStatus.setTypeface(Typeface.DEFAULT_BOLD);
        fix.addView(mXrStatus, marginTop(dp(14)));

        mXrAction = new Button(this);
        mXrAction.setAllCaps(false);
        mXrAction.setText(R.string.action_activate);
        mXrAction.setTextColor(Color.BLACK);
        mXrAction.setTextSize(14);
        mXrAction.setTypeface(Typeface.DEFAULT_BOLD);
        mXrAction.setGravity(Gravity.CENTER);
        mXrAction.setBackground(panel(COLOR_CYAN, COLOR_CYAN));
        mXrAction.setOnClickListener(view -> confirmActivation());
        final LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        actionParams.setMargins(0, dp(14), 0, 0);
        fix.addView(mXrAction, actionParams);

        page.addView(fix, marginTop(dp(18)));
        scroll.addView(page, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private void confirmActivation() {
        if (XrResolutionFix.isActive()) {
            updateActiveState();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_title)
                .setMessage(R.string.confirm_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_continue,
                        (dialog, which) -> activateFix())
                .show();
    }

    private void activateFix() {
        mXrAction.setEnabled(false);
        mXrAction.setText(R.string.xr_fix_working);
        mXrStatus.setText(R.string.xr_fix_working);
        mXrStatus.setTextColor(COLOR_AMBER);
        XrResolutionFix.activate(this, result -> runOnUiThread(() -> {
            mXrAction.setEnabled(true);
            switch (result.code) {
                case ACTIVE:
                    showActive(R.string.xr_fix_active);
                    break;
                case ACTIVATED:
                    showActive(R.string.xr_fix_activated);
                    break;
                case UNSUPPORTED_KERNEL:
                    showFailure(getString(
                            R.string.xr_fix_unsupported_kernel, result.detail));
                    break;
                case UNSUPPORTED_DRIVER:
                    showFailure(getString(
                            R.string.xr_fix_unsupported_driver, result.detail));
                    break;
                case INVALID_MODULE:
                    showFailure(getString(
                            R.string.xr_fix_invalid_module, result.detail));
                    break;
                case FAILED:
                default:
                    showFailure(getString(R.string.xr_fix_failed, result.detail));
                    break;
            }
        }));
    }

    private void updateActiveState() {
        if (mXrStatus == null || mXrAction == null) {
            return;
        }
        if (XrResolutionFix.isActive()) {
            showActive(R.string.xr_fix_active);
        } else {
            mXrStatus.setText(R.string.xr_fix_ready);
            mXrStatus.setTextColor(COLOR_AMBER);
            mXrAction.setEnabled(true);
            mXrAction.setText(R.string.action_activate);
            mXrAction.setBackground(panel(COLOR_CYAN, COLOR_CYAN));
        }
    }

    private void showActive(final int statusResId) {
        mXrStatus.setText(statusResId);
        mXrStatus.setTextColor(COLOR_CYAN);
        mXrAction.setText(R.string.action_active);
        mXrAction.setEnabled(false);
        mXrAction.setBackground(panel(COLOR_PANEL_ALT, COLOR_CYAN));
    }

    private void showFailure(final String status) {
        mXrStatus.setText(status);
        mXrStatus.setTextColor(COLOR_RED);
        mXrAction.setText(R.string.action_activate);
        mXrAction.setBackground(panel(COLOR_PANEL_ALT, COLOR_RED));
    }

    private TextView text(final int textResId, final int sizeSp, final int color) {
        final TextView view = new TextView(this);
        view.setText(textResId);
        view.setTextColor(color);
        view.setTextSize(sizeSp);
        view.setGravity(Gravity.START);
        return view;
    }

    private LinearLayout.LayoutParams marginTop(final int margin) {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, margin, 0, 0);
        return params;
    }

    private GradientDrawable panel(final int fill, final int stroke) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(6));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
