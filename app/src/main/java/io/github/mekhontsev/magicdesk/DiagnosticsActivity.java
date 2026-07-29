package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Display;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class DiagnosticsActivity extends Activity {
    private static final int COLOR_BACKGROUND = 0xFF090D14;
    private static final int COLOR_PANEL_ALT = 0xFF172033;
    private static final int COLOR_TEXT = 0xFFE5E7EB;
    private static final int COLOR_MUTED = 0xFF94A3B8;
    private static final int COLOR_CYAN = 0xFF22D3EE;
    private static final int COLOR_AMBER = 0xFFF59E0B;

    private TextView mStatus;
    private TextView mReportView;
    private Button mRefresh;
    private Button mCopy;
    private Button mShare;
    private String mReport = "";
    private boolean mLoading;

    static Intent createIntent(final Context context) {
        return new Intent(context, DiagnosticsActivity.class);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContentView());
        refreshReport();
    }

    private View createContentView() {
        final LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        final int bottomPadding = dp(16)
                + (getDisplayId() == Display.DEFAULT_DISPLAY
                        ? 0 : dp(DesktopShellActivity.TASKBAR_HEIGHT_DP));
        page.setPadding(dp(18), dp(16), dp(18), bottomPadding);
        page.setBackgroundColor(COLOR_BACKGROUND);

        final LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        final TextView title = new TextView(this);
        title.setText(R.string.diagnostics_title);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        final Button close = createButton(R.string.action_close, COLOR_MUTED);
        close.setOnClickListener(view -> finish());
        header.addView(close, new LinearLayout.LayoutParams(
                dp(92), dp(46)));
        page.addView(header);

        final TextView description = new TextView(this);
        description.setText(R.string.diagnostics_description);
        description.setTextColor(COLOR_MUTED);
        description.setTextSize(13);
        final LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        descriptionParams.setMargins(0, dp(6), 0, dp(10));
        page.addView(description, descriptionParams);

        mStatus = new TextView(this);
        mStatus.setTextColor(COLOR_CYAN);
        mStatus.setTextSize(14);
        mStatus.setTypeface(Typeface.DEFAULT_BOLD);
        page.addView(mStatus);

        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        mReportView = new TextView(this);
        mReportView.setTextColor(COLOR_TEXT);
        mReportView.setTextSize(11);
        mReportView.setTypeface(Typeface.MONOSPACE);
        mReportView.setTextIsSelectable(true);
        mReportView.setPadding(dp(12), dp(10), dp(12), dp(10));
        mReportView.setBackground(rounded(COLOR_PANEL_ALT, dp(6), COLOR_PANEL_ALT));
        scroll.addView(mReportView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        final LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        scrollParams.setMargins(0, dp(8), 0, dp(10));
        page.addView(scroll, scrollParams);

        final LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        mRefresh = createButton(R.string.diagnostics_refresh, COLOR_CYAN);
        mRefresh.setOnClickListener(view -> refreshReport());
        actions.addView(mRefresh, weightedButtonParams(0));
        mCopy = createButton(R.string.diagnostics_copy, COLOR_CYAN);
        mCopy.setOnClickListener(view -> copyReport());
        actions.addView(mCopy, weightedButtonParams(dp(8)));
        mShare = createButton(R.string.diagnostics_share, COLOR_AMBER);
        mShare.setOnClickListener(view -> shareReport());
        actions.addView(mShare, weightedButtonParams(dp(8)));
        page.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));
        return page;
    }

    private LinearLayout.LayoutParams weightedButtonParams(final int leftMargin) {
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.setMargins(leftMargin, 0, 0, 0);
        return params;
    }

    private void refreshReport() {
        if (mLoading) {
            return;
        }
        mLoading = true;
        setActionsEnabled(false);
        mStatus.setText(R.string.diagnostics_collecting);
        new Thread(() -> {
            final String report =
                    CompatibilityDiagnostics.buildReport(getApplicationContext());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                mReport = report;
                mReportView.setText(report);
                mStatus.setText(R.string.diagnostics_ready);
                mLoading = false;
                setActionsEnabled(true);
            });
        }, "MagicDeskDiagnostics").start();
    }

    private void copyReport() {
        if (mReport.isEmpty()) {
            return;
        }
        final ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) {
            Toast.makeText(this, R.string.diagnostics_copy_failed,
                    Toast.LENGTH_LONG).show();
            return;
        }
        clipboard.setPrimaryClip(
                ClipData.newPlainText("MagicDesk compatibility report", mReport));
        Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show();
    }

    private void shareReport() {
        if (mReport.isEmpty()) {
            return;
        }
        final Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "MagicDesk compatibility report")
                .putExtra(Intent.EXTRA_TEXT, mReport);
        startActivity(Intent.createChooser(share, getString(R.string.diagnostics_share)));
    }

    private void setActionsEnabled(final boolean enabled) {
        mRefresh.setEnabled(enabled);
        mCopy.setEnabled(enabled);
        mShare.setEnabled(enabled);
    }

    private Button createButton(final int textResId, final int accentColor) {
        final Button button = new Button(this);
        button.setText(textResId);
        button.setAllCaps(false);
        button.setSingleLine(true);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setPadding(dp(6), dp(4), dp(6), dp(4));
        button.setBackground(rounded(COLOR_PANEL_ALT, dp(6), accentColor));
        return button;
    }

    private GradientDrawable rounded(
            final int color, final int radius, final int strokeColor) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int getDisplayId() {
        final Display display = getDisplay();
        return display == null ? Display.DEFAULT_DISPLAY : display.getDisplayId();
    }
}
