package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_BACKGROUND;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_CYAN;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_MUTED;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_PANEL;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_PANEL_ALT;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_TEXT;

import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

final class PhoneLauncherController {
    private final MainActivity mActivity;
    private final DesktopUiFactory mUi;

    private LinearLayout mContent;
    private TextView mStatus;
    private boolean mForceFullscreen;

    PhoneLauncherController(
            final MainActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
    }

    View createView() {
        final LinearLayout root = new LinearLayout(mActivity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BACKGROUND);
        final int horizontalPadding = dp(18);
        final int topPadding = dp(16);
        final int bottomPadding = dp(12);
        root.setPadding(
                horizontalPadding,
                topPadding,
                horizontalPadding,
                bottomPadding);
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            final Insets systemBars = windowInsets.getInsets(
                    WindowInsets.Type.systemBars());
            view.setPadding(
                    horizontalPadding,
                    topPadding + systemBars.top,
                    horizontalPadding,
                    bottomPadding + systemBars.bottom);
            return windowInsets;
        });

        final LinearLayout brand = new LinearLayout(mActivity);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setOrientation(LinearLayout.HORIZONTAL);

        final ImageView appIcon = new ImageView(mActivity);
        appIcon.setImageResource(R.drawable.ic_magicdesk);
        brand.addView(
                appIcon,
                new LinearLayout.LayoutParams(dp(48), dp(48)));

        final LinearLayout titleBlock = new LinearLayout(mActivity);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setPadding(dp(12), 0, 0, 0);
        final TextView title = new TextView(mActivity);
        title.setText(R.string.app_name);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleBlock.addView(
                title,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        mStatus = new TextView(mActivity);
        mStatus.setText(R.string.status_loading);
        mStatus.setTextColor(COLOR_MUTED);
        mStatus.setTextSize(13);
        titleBlock.addView(
                mStatus,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        brand.addView(
                titleBlock,
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1));
        root.addView(
                brand,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        final LinearLayout actions = new LinearLayout(mActivity);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        final Button desktopMode = new Button(mActivity);
        desktopMode.setText(R.string.action_layout_desktop);
        desktopMode.setAllCaps(false);
        desktopMode.setTextColor(Color.WHITE);
        desktopMode.setSingleLine(true);
        desktopMode.setEllipsize(TextUtils.TruncateAt.END);
        desktopMode.setBackground(
                mUi.rounded(COLOR_PANEL_ALT, dp(10), COLOR_CYAN));
        desktopMode.setOnClickListener(
                view -> mActivity.setLayoutMode(
                        DesktopPreferences.LAYOUT_DESKTOP));
        final LinearLayout.LayoutParams desktopModeParams =
                new LinearLayout.LayoutParams(
                        0,
                        dp(48),
                        1);
        desktopModeParams.setMargins(0, 0, dp(8), 0);
        actions.addView(desktopMode, desktopModeParams);

        final Button refresh = new Button(mActivity);
        refresh.setText(R.string.action_refresh);
        refresh.setAllCaps(false);
        refresh.setTextColor(Color.WHITE);
        refresh.setBackground(
                mUi.rounded(COLOR_PANEL_ALT, dp(10), COLOR_CYAN));
        refresh.setOnClickListener(view -> mActivity.renderApps());
        actions.addView(
                refresh,
                new LinearLayout.LayoutParams(
                        0,
                        dp(48),
                        1));
        final LinearLayout.LayoutParams actionParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        actionParams.setMargins(0, dp(12), 0, 0);
        root.addView(actions, actionParams);

        final ScrollView scrollView = new ScrollView(mActivity);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setPadding(0, dp(16), 0, dp(12));

        mContent = new LinearLayout(mActivity);
        mContent.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(
                mContent,
                new ScrollView.LayoutParams(
                        ScrollView.LayoutParams.MATCH_PARENT,
                        ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(
                scrollView,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1));
        return root;
    }

    void render(final List<AppItem> apps) {
        if (mContent == null) {
            return;
        }
        mContent.removeAllViews();
        addDock(apps);
        addTools();
        addLaunchMode();
        addSection(apps);
    }

    void setStatus(final String text) {
        if (mStatus != null) {
            mStatus.setText(text);
        }
    }

    void release() {
        if (mContent != null) {
            mContent.removeAllViews();
            mContent = null;
        }
        mStatus = null;
    }

    private void addDock(final List<AppItem> apps) {
        final List<AppItem> favorites = new ArrayList<>();
        for (final String packageName :
                DesktopPreferences.favoritePackages()) {
            final AppItem app =
                    LauncherAppRepository.find(apps, packageName);
            if (app != null) {
                favorites.add(app);
            }
        }
        if (favorites.isEmpty()) {
            return;
        }

        final TextView title = mUi.sectionTitle(R.string.section_dock);
        final LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, 0, 0, dp(8));
        mContent.addView(title, titleParams);

        final LinearLayout dock = new LinearLayout(mActivity);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setPadding(dp(8), dp(8), dp(8), dp(8));
        dock.setBackground(
                mUi.rounded(COLOR_PANEL, dp(16), COLOR_PANEL_ALT));
        for (final AppItem app : favorites) {
            dock.addView(
                    createDockItem(app),
                    new LinearLayout.LayoutParams(0, dp(82), 1));
        }
        mContent.addView(
                dock,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private View createDockItem(final AppItem app) {
        final LinearLayout item = new LinearLayout(mActivity);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(6), dp(6), dp(6), dp(4));
        item.setClickable(true);
        item.setFocusable(true);
        item.setOnClickListener(view -> mActivity.launchDefault(app));

        final ImageView icon = new ImageView(mActivity);
        icon.setImageDrawable(app.icon);
        item.addView(
                icon,
                new LinearLayout.LayoutParams(dp(38), dp(38)));

        final TextView label = new TextView(mActivity);
        label.setText(app.label);
        label.setTextColor(COLOR_TEXT);
        label.setTextSize(11);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        final LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(0, dp(6), 0, 0);
        item.addView(label, labelParams);
        return item;
    }

    private void addTools() {
        final TextView title = mUi.sectionTitle(R.string.section_tools);
        final LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, dp(16), 0, dp(8));
        mContent.addView(title, titleParams);

        final LinearLayout panel = new LinearLayout(mActivity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(12), dp(12), dp(12));
        panel.setBackground(
                mUi.rounded(COLOR_PANEL, dp(16), COLOR_PANEL_ALT));
        mActivity.populateToolsControls(panel, dp(8));
        mContent.addView(
                panel,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void addLaunchMode() {
        final CheckBox fullscreen = new CheckBox(mActivity);
        fullscreen.setText(R.string.section_fullscreen);
        fullscreen.setTextColor(COLOR_TEXT);
        fullscreen.setTextSize(13);
        fullscreen.setGravity(Gravity.CENTER_VERTICAL);
        fullscreen.setChecked(mForceFullscreen);
        fullscreen.setOnCheckedChangeListener((button, checked) ->
                mForceFullscreen = checked);
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(16), 0, 0);
        mContent.addView(fullscreen, params);
    }

    private void addSection(final List<AppItem> apps) {
        if (apps.isEmpty()) {
            return;
        }

        final TextView title = mUi.sectionTitle(R.string.section_apps);
        final LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, dp(12), 0, dp(8));
        mContent.addView(title, titleParams);

        final GridLayout grid = new GridLayout(mActivity);
        grid.setColumnCount(getColumnCount());
        grid.setUseDefaultMargins(false);
        final int tileWidth = getTileWidth(grid.getColumnCount());
        for (final AppItem app : apps) {
            grid.addView(
                    createAppTile(app),
                    createTileParams(tileWidth));
        }
        mContent.addView(
                grid,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private View createAppTile(final AppItem app) {
        final LinearLayout tile = new LinearLayout(mActivity);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(10), dp(12), dp(10), dp(10));
        tile.setBackground(
                mUi.rounded(
                        COLOR_PANEL,
                        dp(14),
                        COLOR_PANEL_ALT));
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setOnClickListener(view -> launch(app));

        final ImageView icon = new ImageView(mActivity);
        icon.setImageDrawable(app.icon);
        tile.addView(
                icon,
                new LinearLayout.LayoutParams(dp(46), dp(46)));

        final TextView label = new TextView(mActivity);
        label.setText(app.label);
        label.setTextColor(COLOR_TEXT);
        label.setTextSize(13);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        final LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(0, dp(8), 0, 0);
        tile.addView(label, labelParams);

        final LinearLayout actions = new LinearLayout(mActivity);
        actions.setGravity(Gravity.CENTER);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        final Button primary = mUi.smallButton(
                R.string.action_open,
                COLOR_PANEL_ALT);
        primary.setOnClickListener(view -> launch(app));
        actions.addView(
                primary,
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1));
        final LinearLayout.LayoutParams actionParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        actionParams.setMargins(0, dp(8), 0, 0);
        tile.addView(actions, actionParams);
        return tile;
    }

    private void launch(final AppItem app) {
        if (mForceFullscreen) {
            mActivity.launchFullscreen(app);
        } else {
            mActivity.launchDefault(app);
        }
    }

    private GridLayout.LayoutParams createTileParams(final int width) {
        final GridLayout.LayoutParams params =
                new GridLayout.LayoutParams();
        params.width = width;
        params.height = dp(154);
        params.setMargins(dp(5), dp(5), dp(5), dp(5));
        return params;
    }

    private int getColumnCount() {
        final int widthDp =
                mActivity.getResources().getConfiguration().screenWidthDp;
        if (widthDp >= 1100) {
            return 7;
        }
        if (widthDp >= 840) {
            return 5;
        }
        if (widthDp >= 600) {
            return 4;
        }
        return 2;
    }

    private int getTileWidth(final int columns) {
        final int available =
                mActivity.getResources().getDisplayMetrics().widthPixels
                        - dp(36)
                        - columns * dp(10);
        return Math.max(
                dp(128),
                available / Math.max(columns, 1));
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }
}
