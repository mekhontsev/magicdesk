package io.github.mekhontsev.magicdesk.displayfixes;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** One-screen launcher for an explicit, user-authorized root display fix. */
public final class DisplayFixesActivity extends Activity {
    private static final int COLOR_BACKGROUND = 0xFF090D14;
    private static final int COLOR_PANEL = 0xFF111827;
    private static final int COLOR_PANEL_ALT = 0xFF172033;
    private static final int COLOR_TEXT = 0xFFE5E7EB;
    private static final int COLOR_MUTED = 0xFF94A3B8;
    private static final int COLOR_CYAN = 0xFF22D3EE;
    private static final int COLOR_GREEN = 0xFF34D399;
    private static final int COLOR_AMBER = 0xFFF59E0B;
    private static final int COLOR_RED = 0xFFF43F5E;

    private LinearLayout mPage;
    private TextView mStatus;
    private TextView mDetail;
    private Button mRetry;
    private int mOperationGeneration;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContent());
        mPage.post(this::applyNativeMode);
    }

    @Override
    protected void onDestroy() {
        mOperationGeneration++;
        super.onDestroy();
    }

    private View createContent() {
        final ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BACKGROUND);

        mPage = new LinearLayout(this);
        mPage.setOrientation(LinearLayout.VERTICAL);
        applyInsets(mPage);

        final TextView title = text(R.string.screen_title, 26, COLOR_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        mPage.addView(title);

        final TextView subtitle = text(
                R.string.screen_subtitle, 14, COLOR_MUTED);
        mPage.addView(subtitle, marginTop(dp(4)));

        final TextView rootNotice = text(
                R.string.root_notice, 14, COLOR_TEXT);
        rootNotice.setPadding(dp(14), dp(12), dp(14), dp(12));
        rootNotice.setBackground(panel(COLOR_PANEL_ALT, COLOR_AMBER));
        mPage.addView(rootNotice, marginTop(dp(18)));

        final LinearLayout operation = new LinearLayout(this);
        operation.setOrientation(LinearLayout.VERTICAL);
        operation.setPadding(dp(16), dp(16), dp(16), dp(16));
        operation.setBackground(panel(COLOR_PANEL, COLOR_PANEL_ALT));

        mStatus = text(R.string.status_working, 18, COLOR_AMBER);
        mStatus.setTypeface(Typeface.DEFAULT_BOLD);
        operation.addView(mStatus);

        mDetail = text("", 14, COLOR_MUTED);
        mDetail.setTextIsSelectable(true);
        operation.addView(mDetail, marginTop(dp(8)));

        mRetry = new Button(this);
        mRetry.setAllCaps(false);
        mRetry.setText(R.string.action_retry);
        mRetry.setTextColor(Color.BLACK);
        mRetry.setTextSize(14);
        mRetry.setTypeface(Typeface.DEFAULT_BOLD);
        mRetry.setGravity(Gravity.CENTER);
        mRetry.setBackground(panel(COLOR_CYAN, COLOR_CYAN));
        mRetry.setOnClickListener(view -> applyNativeMode());
        final LinearLayout.LayoutParams retryParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        retryParams.setMargins(0, dp(16), 0, 0);
        operation.addView(mRetry, retryParams);

        mPage.addView(operation, marginTop(dp(18)));
        scroll.addView(mPage, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private void applyInsets(final LinearLayout page) {
        page.setPadding(dp(20), dp(20), dp(20), dp(24));
        page.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            final Insets bars = windowInsets.getInsets(
                    WindowInsets.Type.systemBars());
            view.setPadding(
                    dp(20) + bars.left,
                    dp(20) + bars.top,
                    dp(20) + bars.right,
                    dp(24) + bars.bottom);
            return windowInsets;
        });
    }

    private void applyNativeMode() {
        final int generation = ++mOperationGeneration;
        mStatus.setText(R.string.status_working);
        mStatus.setTextColor(COLOR_AMBER);
        mDetail.setText("");
        mRetry.setEnabled(false);
        NativeDisplayModeFix.apply(this, result -> runOnUiThread(() -> {
            if (generation != mOperationGeneration || isDestroyed()) {
                return;
            }
            renderResult(result);
        }));
    }

    private void renderResult(final NativeDisplayModeFix.Result result) {
        mRetry.setEnabled(true);
        switch (result.code) {
            case APPLIED:
                showResult(
                        R.string.status_applied,
                        COLOR_GREEN,
                        getString(R.string.detail_applied, result.timing));
                break;
            case ALREADY_ACTIVE:
                showResult(
                        R.string.status_active,
                        COLOR_CYAN,
                        getString(R.string.detail_active, result.timing));
                break;
            case ROOT_DENIED:
                showResult(
                        R.string.status_no_root, COLOR_RED, result.detail);
                break;
            case NO_DISPLAY:
                showResult(
                        R.string.status_no_display, COLOR_AMBER, result.detail);
                break;
            case UNSUPPORTED:
                showResult(
                        R.string.status_unsupported,
                        COLOR_AMBER,
                        result.detail);
                break;
            case FAILED:
            default:
                showResult(
                        R.string.status_failed, COLOR_RED, result.detail);
                break;
        }
    }

    private void showResult(
            final int statusId,
            final int color,
            final String detail) {
        mStatus.setText(statusId);
        mStatus.setTextColor(color);
        mDetail.setText(detail == null ? "" : detail);
    }

    private TextView text(
            final int textId,
            final int sizeSp,
            final int color) {
        return text(getString(textId), sizeSp, color);
    }

    private TextView text(
            final String value,
            final int sizeSp,
            final int color) {
        final TextView view = new TextView(this);
        view.setText(value);
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
