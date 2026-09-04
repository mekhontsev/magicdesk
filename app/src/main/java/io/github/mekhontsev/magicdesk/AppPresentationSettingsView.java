package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Flat desktop settings UI for per-application presentation profiles. */
final class AppPresentationSettingsView {
    interface Actions {
        void useSystemScale(String packageName);

        void setCustomScale(String packageName, int scalePercent);

        void openProfile(String packageName);
    }

    private static final int CONTENT_MAX_WIDTH_DP = 540;
    private static final int SCALE_STEP = 5;

    private final Activity mActivity;
    private final DesktopUiFactory mUi;
    private final Actions mActions;

    private RadioButton mSystemMode;
    private RadioButton mCustomMode;
    private SeekBar mScaleSlider;
    private TextView mScaleValue;
    private Button mDecrease;
    private Button mIncrease;
    private String mPackageName;
    private boolean mRendering;
    private boolean mEnabled = true;

    AppPresentationSettingsView(
            final Activity activity,
            final Actions actions) {
        mActivity = activity;
        mUi = new DesktopUiFactory(activity);
        mActions = actions;
    }

    View createList() {
        clearDetailControls();
        final LinearLayout content = pageContent(
                R.string.app_presentation_profiles_title,
                android.R.drawable.ic_menu_manage);
        final Map<String, AppPresentationProfile> profiles =
                AppPresentationProfileStore.loadAll();
        final List<ProfileRow> rows = new ArrayList<>();
        for (final Map.Entry<String, AppPresentationProfile> entry
                : profiles.entrySet()) {
            rows.add(loadRow(entry.getKey(), entry.getValue()));
        }
        rows.sort(Comparator
                .comparing((ProfileRow row) ->
                        row.label.toLowerCase(Locale.ROOT))
                .thenComparing(row -> row.packageName));
        if (rows.isEmpty()) {
            final TextView empty = new TextView(mActivity);
            empty.setText(R.string.app_presentation_profiles_empty);
            empty.setTextColor(DesktopUiFactory.COLOR_MUTED);
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(36), dp(12), dp(36));
            content.addView(empty, matchWrap());
        } else {
            for (final ProfileRow row : rows) {
                content.addView(createProfileRow(row), matchWrap());
                addDivider(content);
            }
        }
        return wrapPage(content);
    }

    View createDetail(final String packageName) {
        AppPresentationProfileManager.requireUserApplication(packageName);
        mPackageName = packageName;
        final ProfileRow app = loadRow(
                packageName, AppPresentationProfileStore.load(packageName));
        final LinearLayout content = pageContent(
                R.string.app_presentation_title,
                android.R.drawable.ic_menu_manage);
        content.addView(createAppHeader(app), matchWrap());
        addSection(content, R.string.app_presentation_scale_section);

        final RadioGroup modes = new RadioGroup(mActivity);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.setGravity(Gravity.CENTER_VERTICAL);
        modes.setPadding(dp(8), dp(4), dp(8), dp(4));
        mSystemMode = modeButton(R.string.app_presentation_system);
        mCustomMode = modeButton(R.string.app_presentation_custom);
        modes.addView(mSystemMode, new RadioGroup.LayoutParams(0, dp(46), 1));
        modes.addView(mCustomMode, new RadioGroup.LayoutParams(0, dp(46), 1));
        content.addView(modes, matchWrap());

        final LinearLayout scaleHeader = new LinearLayout(mActivity);
        scaleHeader.setOrientation(LinearLayout.HORIZONTAL);
        scaleHeader.setGravity(Gravity.CENTER_VERTICAL);
        scaleHeader.setPadding(dp(8), dp(12), dp(8), 0);
        final TextView scaleLabel = text(
                mActivity.getString(R.string.app_presentation_scale),
                DesktopUiFactory.COLOR_TEXT,
                14);
        scaleHeader.addView(scaleLabel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        mScaleValue = text("", DesktopUiFactory.COLOR_TEXT, 14);
        mScaleValue.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        scaleHeader.addView(mScaleValue, new LinearLayout.LayoutParams(
                dp(74), LinearLayout.LayoutParams.WRAP_CONTENT));
        content.addView(scaleHeader, matchWrap());

        final LinearLayout adjustment = new LinearLayout(mActivity);
        adjustment.setOrientation(LinearLayout.HORIZONTAL);
        adjustment.setGravity(Gravity.CENTER_VERTICAL);
        adjustment.setPadding(dp(8), 0, dp(8), dp(8));
        mDecrease = stepButton(
                "-", R.string.app_presentation_decrease);
        adjustment.addView(mDecrease, new LinearLayout.LayoutParams(
                dp(42), dp(42)));
        mScaleSlider = new SeekBar(mActivity);
        mScaleSlider.setMin(AppPresentationProfile.MIN_SCALE_PERCENT);
        mScaleSlider.setMax(AppPresentationProfile.MAX_SCALE_PERCENT);
        mScaleSlider.setKeyProgressIncrement(SCALE_STEP);
        mScaleSlider.setSplitTrack(false);
        adjustment.addView(mScaleSlider, new LinearLayout.LayoutParams(
                0, dp(42), 1));
        mIncrease = stepButton(
                "+", R.string.app_presentation_increase);
        adjustment.addView(mIncrease, new LinearLayout.LayoutParams(
                dp(42), dp(42)));
        content.addView(adjustment, matchWrap());

        mScaleSlider.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            final SeekBar seekBar,
                            final int progress,
                            final boolean fromUser) {
                        final int scale = snapScale(progress);
                        if (fromUser && progress != scale) {
                            seekBar.setProgress(scale);
                            return;
                        }
                        updateScaleValue(scale);
                    }

                    @Override
                    public void onStartTrackingTouch(final SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(final SeekBar seekBar) {
                        if (!mRendering && mCustomMode.isChecked()) {
                            mActions.setCustomScale(
                                    mPackageName,
                                    snapScale(seekBar.getProgress()));
                        }
                    }
                });
        mDecrease.setOnClickListener(view -> adjustScale(-SCALE_STEP));
        mIncrease.setOnClickListener(view -> adjustScale(SCALE_STEP));
        modes.setOnCheckedChangeListener((group, checkedId) -> {
            if (mRendering) {
                return;
            }
            if (checkedId == mSystemMode.getId()) {
                mActions.useSystemScale(mPackageName);
            } else if (checkedId == mCustomMode.getId()) {
                mActions.setCustomScale(
                        mPackageName, mScaleSlider.getProgress());
            }
            updateEnabledState();
        });
        renderDetail(app.profile);
        return wrapPage(content);
    }

    void setEnabled(final boolean enabled) {
        mEnabled = enabled;
        updateEnabledState();
    }

    private void renderDetail(final AppPresentationProfile profile) {
        if (mSystemMode == null || mCustomMode == null
                || mScaleSlider == null) {
            return;
        }
        mRendering = true;
        final boolean custom = profile != null;
        final int scale = custom
                ? profile.scalePercent
                : AppPresentationProfile.SYSTEM_SCALE_PERCENT;
        mSystemMode.setChecked(!custom);
        mCustomMode.setChecked(custom);
        mScaleSlider.setProgress(scale);
        updateScaleValue(scale);
        mRendering = false;
        updateEnabledState();
    }

    private void adjustScale(final int delta) {
        if (mScaleSlider == null || !mScaleSlider.isEnabled()) {
            return;
        }
        final int scale = snapScale(mScaleSlider.getProgress() + delta);
        mScaleSlider.setProgress(scale);
        mActions.setCustomScale(mPackageName, scale);
    }

    private void updateScaleValue(final int scale) {
        if (mScaleValue != null) {
            mScaleValue.setText(mActivity.getString(
                    R.string.app_presentation_scale_value, scale));
        }
    }

    private void updateEnabledState() {
        final boolean custom = mEnabled
                && mCustomMode != null
                && mCustomMode.isChecked();
        if (mSystemMode != null) {
            mSystemMode.setEnabled(mEnabled);
        }
        if (mCustomMode != null) {
            mCustomMode.setEnabled(mEnabled);
        }
        if (mScaleSlider != null) {
            mScaleSlider.setEnabled(custom);
            mScaleSlider.setAlpha(custom ? 1f : 0.5f);
        }
        setControlEnabled(mDecrease, custom);
        setControlEnabled(mIncrease, custom);
    }

    private RadioButton modeButton(final int labelResId) {
        final RadioButton button = new RadioButton(mActivity);
        button.setId(View.generateViewId());
        button.setText(labelResId);
        button.setTextColor(DesktopUiFactory.COLOR_TEXT);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setButtonTintList(new ColorStateList(
                new int[][]{
                    new int[]{android.R.attr.state_checked},
                    new int[0]
                },
                new int[]{
                    DesktopUiFactory.COLOR_CYAN,
                    DesktopUiFactory.COLOR_MUTED
                }));
        return button;
    }

    private Button stepButton(
            final String text,
            final int descriptionResId) {
        final Button button = mUi.actionButton(
                text, DesktopUiFactory.COLOR_CYAN);
        button.setTextSize(18);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(0, 0, 0, 0);
        button.setContentDescription(mActivity.getString(descriptionResId));
        button.setTooltipText(mActivity.getString(descriptionResId));
        return button;
    }

    private View createProfileRow(final ProfileRow profile) {
        final LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(62));
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(mUi.interactiveRounded(
                Color.TRANSPARENT, dp(6), DesktopUiFactory.COLOR_PANEL_ALT));
        row.setOnClickListener(view ->
                mActions.openProfile(profile.packageName));
        final ImageView icon = new ImageView(mActivity);
        icon.setImageDrawable(profile.icon);
        row.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
        final LinearLayout labels = new LinearLayout(mActivity);
        labels.setOrientation(LinearLayout.VERTICAL);
        final TextView title = text(
                profile.label, DesktopUiFactory.COLOR_TEXT, 14);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(title, matchWrap());
        final TextView packageLabel = text(
                profile.packageName, DesktopUiFactory.COLOR_MUTED, 11);
        packageLabel.setSingleLine(true);
        packageLabel.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        labels.addView(packageLabel, matchWrap());
        final LinearLayout.LayoutParams labelsParams =
                new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        labelsParams.setMargins(dp(12), 0, dp(12), 0);
        row.addView(labels, labelsParams);
        final TextView scale = text(
                mActivity.getString(
                        R.string.app_presentation_scale_value,
                        profile.profile.scalePercent),
                DesktopUiFactory.COLOR_CYAN,
                13);
        scale.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(scale, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private View createAppHeader(final ProfileRow profile) {
        final LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(14), dp(8), dp(10));
        final ImageView icon = new ImageView(mActivity);
        icon.setImageDrawable(profile.icon);
        row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        final LinearLayout labels = new LinearLayout(mActivity);
        labels.setOrientation(LinearLayout.VERTICAL);
        final TextView title = text(
                profile.label, DesktopUiFactory.COLOR_TEXT, 17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(title, matchWrap());
        final TextView packageLabel = text(
                profile.packageName, DesktopUiFactory.COLOR_MUTED, 12);
        packageLabel.setSingleLine(true);
        packageLabel.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        labels.addView(packageLabel, matchWrap());
        final LinearLayout.LayoutParams labelsParams =
                new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        labelsParams.setMargins(dp(14), 0, 0, 0);
        row.addView(labels, labelsParams);
        return row;
    }

    private LinearLayout pageContent(
            final int titleResId,
            final int iconResId) {
        final LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(46));
        final ImageView icon = new ImageView(mActivity);
        icon.setImageResource(iconResId);
        icon.setColorFilter(DesktopUiFactory.COLOR_CYAN);
        header.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        final TextView title = text(
                mActivity.getString(titleResId),
                DesktopUiFactory.COLOR_TEXT,
                18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        final LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        titleParams.setMargins(dp(10), 0, 0, 0);
        header.addView(title, titleParams);
        content.addView(header, matchWrap());
        final View divider = new View(mActivity);
        divider.setBackgroundColor(DesktopUiFactory.COLOR_CYAN);
        content.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        return content;
    }

    private View wrapPage(final LinearLayout content) {
        final LinearLayout page = new LinearLayout(mActivity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(DesktopUiFactory.COLOR_PANEL);
        page.setPadding(dp(14), dp(10), dp(14), dp(14));
        SystemBarInsets.addToPadding(page);
        final ScrollView scroll = new ScrollView(mActivity);
        scroll.setFillViewport(true);
        final FrameLayout host = new FrameLayout(mActivity);
        final int availableWidthDp = Math.max(
                1,
                mActivity.getResources().getConfiguration().screenWidthDp
                        - 32);
        host.addView(content, new FrameLayout.LayoutParams(
                dp(Math.min(CONTENT_MAX_WIDTH_DP, availableWidthDp)),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        scroll.addView(host, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        return page;
    }

    private void addSection(
            final LinearLayout content,
            final int titleResId) {
        final TextView title = mUi.sectionTitle(titleResId);
        title.setTextSize(16);
        final LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(dp(8), dp(14), dp(8), dp(5));
        content.addView(title, params);
    }

    private void addDivider(final LinearLayout parent) {
        final View divider = new View(mActivity);
        divider.setBackgroundColor(DesktopUiFactory.COLOR_PANEL_ALT);
        parent.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private ProfileRow loadRow(
            final String packageName,
            final AppPresentationProfile profile) {
        final PackageManager packages = mActivity.getPackageManager();
        try {
            final ApplicationInfo info = packages.getApplicationInfo(
                    packageName, 0);
            final CharSequence label = packages.getApplicationLabel(info);
            return new ProfileRow(
                    packageName,
                    label == null ? packageName : label.toString(),
                    packages.getApplicationIcon(info),
                    profile);
        } catch (PackageManager.NameNotFoundException error) {
            return new ProfileRow(
                    packageName,
                    packageName,
                    mActivity.getDrawable(android.R.drawable.sym_def_app_icon),
                    profile);
        }
    }

    private void clearDetailControls() {
        mPackageName = null;
        mSystemMode = null;
        mCustomMode = null;
        mScaleSlider = null;
        mScaleValue = null;
        mDecrease = null;
        mIncrease = null;
    }

    private static void setControlEnabled(
            final View control,
            final boolean enabled) {
        if (control != null) {
            control.setEnabled(enabled);
            control.setAlpha(enabled ? 1f : 0.5f);
        }
    }

    private int snapScale(final int value) {
        final int bounded = Math.max(
                AppPresentationProfile.MIN_SCALE_PERCENT,
                Math.min(AppPresentationProfile.MAX_SCALE_PERCENT, value));
        return Math.round(bounded / (float) SCALE_STEP) * SCALE_STEP;
    }

    private TextView text(
            final String value,
            final int color,
            final int sizeSp) {
        final TextView text = new TextView(mActivity);
        text.setText(value);
        text.setTextColor(color);
        text.setTextSize(sizeSp);
        return text;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }

    private static final class ProfileRow {
        final String packageName;
        final String label;
        final Drawable icon;
        final AppPresentationProfile profile;

        ProfileRow(
                final String packageName,
                final String label,
                final Drawable icon,
                final AppPresentationProfile profile) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
            this.profile = profile;
        }
    }
}
