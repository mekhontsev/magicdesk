package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_CYAN;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_PANEL;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_PANEL_ALT;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_TEXT;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CalendarView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class CalendarPanelController {
    private final Context mContext;
    private final DesktopUiFactory mUi;
    private final Runnable mHidePanels;
    private final Runnable mCaptureInteractionStack;
    private final Runnable mOpenCalendar;
    private final Runnable mPanelUnavailable;

    private LinearLayout mPanel;
    private CalendarView mCalendarView;

    CalendarPanelController(
            final Context context,
            final DesktopUiFactory ui,
            final Runnable hidePanels,
            final Runnable captureInteractionStack,
            final Runnable openCalendar,
            final Runnable panelUnavailable) {
        mContext = context;
        mUi = ui;
        mHidePanels = hidePanels;
        mCaptureInteractionStack = captureInteractionStack;
        mOpenCalendar = openCalendar;
        mPanelUnavailable = panelUnavailable;
    }

    LinearLayout createPanel() {
        final LinearLayout panel = new LinearLayout(mContext);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(14), dp(14), dp(12));
        panel.setBackground(mUi.rounded(COLOR_PANEL, dp(8), COLOR_CYAN));
        panel.setVisibility(View.GONE);
        panel.setClickable(true);

        final LinearLayout header = new LinearLayout(mContext);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        final TextView title = new TextView(mContext);
        title.setText(R.string.calendar_title);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        final Button close =
                mUi.smallButton(R.string.action_close, COLOR_PANEL_ALT);
        close.setOnClickListener(view -> mHidePanels.run());
        header.addView(close, new LinearLayout.LayoutParams(
                dp(86), LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        mCalendarView = new CalendarView(mContext);
        mCalendarView.setDate(System.currentTimeMillis(), false, true);
        final LinearLayout.LayoutParams calendarParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        calendarParams.setMargins(0, dp(8), 0, dp(8));
        panel.addView(mCalendarView, calendarParams);

        final LinearLayout actions = new LinearLayout(mContext);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        final Button today =
                mUi.smallButton(R.string.action_today, COLOR_PANEL_ALT);
        today.setOnClickListener(view -> mCalendarView.setDate(
                System.currentTimeMillis(), true, true));
        actions.addView(today, new LinearLayout.LayoutParams(0, dp(42), 1));
        final Button open =
                mUi.smallButton(R.string.action_open_calendar, COLOR_CYAN);
        open.setOnClickListener(view -> mOpenCalendar.run());
        final LinearLayout.LayoutParams openParams =
                new LinearLayout.LayoutParams(0, dp(42), 1);
        openParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(open, openParams);
        panel.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        mPanel = panel;
        return panel;
    }

    void toggle(
            final DesktopPanelWindowController panels,
            final Rect contentBounds,
            final int taskbarHeight) {
        if (panels == null || mPanel == null) {
            return;
        }
        if (panels.isRequested(mPanel)) {
            mHidePanels.run();
            return;
        }
        mCaptureInteractionStack.run();
        final int areaWidth = contentBounds.width();
        final int areaHeight = contentBounds.height();
        final int width = Math.max(1, Math.min(dp(380), areaWidth - dp(16)));
        final int availableHeight =
                Math.max(1, areaHeight - taskbarHeight - dp(16));
        final int height = Math.min(dp(430), availableHeight);
        final int left = contentBounds.left
                + Math.max(0, areaWidth - width - dp(8));
        final int top = contentBounds.top
                + Math.max(0, areaHeight - taskbarHeight - height);
        if (!panels.show(
                mPanel, left, top, width, height,
                false, "MagicDesk calendar")) {
            mPanelUnavailable.run();
        }
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }
}
