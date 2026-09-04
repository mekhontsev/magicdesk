package io.github.mekhontsev.magicdesk;

import android.appwidget.AppWidgetProviderInfo;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class DesktopWidgetPickerController {
    interface Listener {
        void onWidgetSelected(AppWidgetProviderInfo info);
    }

    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;

    DesktopWidgetPickerController(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
    }

    void show(
            final List<AppWidgetProviderInfo> providers,
            final Listener listener) {
        final DesktopPanelWindowController panels = mActivity.panels();
        if (panels == null || listener == null) {
            return;
        }
        final List<WidgetChoice> choices = choices(providers);
        final LinearLayout panel = new LinearLayout(mActivity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(12));
        panel.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_PANEL,
                dp(12),
                DesktopUiFactory.COLOR_CYAN));
        panel.setClickable(true);
        panel.addView(header(panels, panel), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final ScrollView scroll = new ScrollView(mActivity);
        final LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        populate(content, choices, panels, panel, listener);
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        final LinearLayout.LayoutParams scrollParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        scrollParams.setMargins(0, dp(10), 0, 0);
        panel.addView(scroll, scrollParams);

        final int margin = dp(16);
        final int width = Math.min(
                dp(mActivity.isCompactDesktopPreview() ? 390 : 560),
                Math.max(dp(280), mActivity.getDesktopAreaWidth() - 2 * margin));
        final int availableHeight = Math.max(
                dp(240),
                mActivity.getDesktopAreaHeight()
                        - mActivity.getTaskbarHeight() - 2 * margin);
        final int height = Math.min(
                dp(mActivity.isCompactDesktopPreview() ? 560 : 760),
                availableHeight);
        final int left = mActivity.getDesktopAreaLeft()
                + Math.max(0, (mActivity.getDesktopAreaWidth() - width) / 2);
        final int top = mActivity.getDesktopAreaTop()
                + Math.max(0, (availableHeight - height) / 2);
        panels.show(
                panel, left, top, width, height,
                false, "MagicDesk widgets");
    }

    private View header(
            final DesktopPanelWindowController panels,
            final View panel) {
        final LinearLayout header = new LinearLayout(mActivity);
        header.setGravity(Gravity.CENTER_VERTICAL);

        final TextView title = mUi.sectionTitle(R.string.action_add_widget);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        final Button close = mUi.smallButton(
                R.string.action_close,
                DesktopUiFactory.COLOR_PANEL_ALT);
        close.setOnClickListener(view -> panels.hide(panel));
        header.addView(close, new LinearLayout.LayoutParams(
                dp(84), dp(34)));
        return header;
    }

    private void populate(
            final LinearLayout content,
            final List<WidgetChoice> choices,
            final DesktopPanelWindowController panels,
            final View panel,
            final Listener listener) {
        if (choices.isEmpty()) {
            final TextView empty = text(
                    mActivity.getString(R.string.status_no_widgets),
                    14,
                    DesktopUiFactory.COLOR_MUTED);
            empty.setGravity(Gravity.CENTER);
            content.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(80)));
            return;
        }

        String previousApp = null;
        for (final WidgetChoice choice : choices) {
            if (!choice.appLabel.equals(previousApp)) {
                final TextView app = text(
                        choice.appLabel,
                        13,
                        DesktopUiFactory.COLOR_CYAN);
                app.setTypeface(Typeface.DEFAULT_BOLD);
                final LinearLayout.LayoutParams appParams =
                        new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT);
                appParams.setMargins(dp(4), dp(previousApp == null ? 2 : 12),
                        dp(4), dp(4));
                content.addView(app, appParams);
                previousApp = choice.appLabel;
            }
            final View row = row(choice);
            row.setOnClickListener(view -> {
                panels.hide(panel);
                listener.onWidgetSelected(choice.info);
            });
            final LinearLayout.LayoutParams rowParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(64));
            rowParams.setMargins(0, dp(2), 0, dp(2));
            content.addView(row, rowParams);
        }
    }

    private View row(final WidgetChoice choice) {
        final LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(6), dp(10), dp(6));
        row.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_PANEL_ALT,
                dp(8),
                DesktopUiFactory.COLOR_PANEL_ALT));

        final ImageView icon = new ImageView(mActivity);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        if (choice.icon != null) {
            icon.setImageDrawable(choice.icon);
        }
        row.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));

        final LinearLayout labels = new LinearLayout(mActivity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(10), 0, 0, 0);
        final TextView title = text(
                choice.title, 14, DesktopUiFactory.COLOR_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        final TextView detail = text(
                choice.detail, 12, DesktopUiFactory.COLOR_MUTED);
        detail.setSingleLine(true);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(detail, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(labels, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private List<WidgetChoice> choices(
            final List<AppWidgetProviderInfo> providers) {
        final List<WidgetChoice> choices = new ArrayList<>();
        if (providers == null) {
            return choices;
        }
        final PackageManager packageManager = mActivity.getPackageManager();
        final int density = mActivity.getResources()
                .getDisplayMetrics().densityDpi;
        for (final AppWidgetProviderInfo info : providers) {
            if (info == null || info.provider == null
                    || (info.widgetFeatures
                            & AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER)
                            != 0) {
                continue;
            }
            final String appLabel = appLabel(info, packageManager);
            final String providerLabel = providerLabel(info, packageManager);
            final String description = description(info);
            final String title;
            if (!description.isEmpty()) {
                title = description;
            } else if (!providerLabel.isEmpty()
                    && !providerLabel.equalsIgnoreCase(appLabel)) {
                title = providerLabel;
            } else {
                title = humanize(info.provider.getClassName());
            }
            Drawable icon = null;
            try {
                icon = info.loadIcon(mActivity, density);
            } catch (RuntimeException ignored) {
                // A missing provider resource must not hide an otherwise usable widget.
            }
            choices.add(new WidgetChoice(
                    info,
                    appLabel,
                    title,
                    size(info),
                    icon));
        }
        choices.sort(Comparator
                .comparing((WidgetChoice choice) -> choice.appLabel,
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(choice -> choice.title,
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(choice -> choice.info.provider.flattenToString()));
        return choices;
    }

    private String appLabel(
            final AppWidgetProviderInfo info,
            final PackageManager packageManager) {
        try {
            final ActivityInfo activityInfo = info.getActivityInfo();
            if (activityInfo != null && activityInfo.applicationInfo != null) {
                final CharSequence label = activityInfo.applicationInfo
                        .loadLabel(packageManager);
                if (label != null && label.length() > 0) {
                    return label.toString();
                }
            }
        } catch (RuntimeException ignored) {
            // Fall back to the package when provider resources are incomplete.
        }
        return info.provider.getPackageName();
    }

    private String providerLabel(
            final AppWidgetProviderInfo info,
            final PackageManager packageManager) {
        try {
            return safe(info.loadLabel(packageManager));
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private String description(final AppWidgetProviderInfo info) {
        if (info.descriptionRes == 0) {
            return "";
        }
        try {
            return safe(info.loadDescription(mActivity));
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private String size(final AppWidgetProviderInfo info) {
        final int columns = Math.max(1, info.targetCellWidth);
        final int rows = Math.max(1, info.targetCellHeight);
        return mActivity.getString(R.string.widget_recommended_size, columns, rows);
    }

    private static String humanize(final String className) {
        final int separator = Math.max(
                className.lastIndexOf('.'), className.lastIndexOf('$'));
        String name = separator >= 0
                ? className.substring(separator + 1) : className;
        name = name.replaceAll(
                "(?i)(AppWidgetProvider|WidgetProvider|Provider|AppWidget)$", "");
        name = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        name = name.replace('_', ' ').trim();
        return name.isEmpty() ? "Widget" : name;
    }

    private static String safe(final CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private TextView text(
            final String value,
            final float size,
            final int color) {
        final TextView text = new TextView(mActivity);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }

    private static final class WidgetChoice {
        final AppWidgetProviderInfo info;
        final String appLabel;
        final String title;
        final String detail;
        final Drawable icon;

        WidgetChoice(
                final AppWidgetProviderInfo info,
                final String appLabel,
                final String title,
                final String detail,
                final Drawable icon) {
            this.info = info;
            this.appLabel = appLabel;
            this.title = title;
            this.detail = detail;
            this.icon = icon;
        }
    }
}
