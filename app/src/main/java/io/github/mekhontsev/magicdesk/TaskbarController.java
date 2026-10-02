package io.github.mekhontsev.magicdesk;

import android.content.Intent;
import android.os.BatteryManager;
import android.provider.Settings;
import android.view.DragAndDropPermissions;
import android.view.DragEvent;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import static io.github.mekhontsev.magicdesk.ShellComposition.Kind;

final class TaskbarController {
    enum ContextArea {
        NONE,
        START,
        BLANK,
        ACTION
    }

    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;
    private final ContentRequestScope mContentRequests =
            AndroidDesktopActionDispatcher.createContentScope();

    private final LinkedHashMap<String, PanelView> mPanels = new LinkedHashMap<>();
    private boolean mCreated;
    private final EnumMap<Kind, View> mComponents = new EnumMap<>(Kind.class);
    private final EnumMap<Kind, Integer> mNaturalWidths = new EnumMap<>(Kind.class);
    private TextClock mClock;
    private boolean mPhoneActionVisible;
    private Button mStartButton;
    private LinearLayout mPins;
    private FrameLayout mTaskViewport;
    private boolean mTasksVertical;
    private TextView mKeyboardLayout;
    private final InputMethodMenuController mInputMethodMenu;
    private final TaskbarOverflowController mOverflow;
    private TextView mBatteryStatus;
    private final UiBatteryDrawable mBatteryIcon = new UiBatteryDrawable();
    private ImageButton mSystemButton;
    private ImageButton mPhoneScreenButton;
    private Intent mLastBatteryIntent;
    private boolean mChargeSeparationEnabled;
    private final List<Integer> mTaskOrder = new ArrayList<>();
    private int mItemCount;
    private final Runnable mAppearanceChanged = this::applyAppearance;

    TaskbarController(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
        mInputMethodMenu = new InputMethodMenuController(activity, ui);
        mOverflow = new TaskbarOverflowController(
                activity, ui, this::activate);
    }

    private LinearLayout createPanelContainer() {
        final LinearLayout taskbar = new LinearLayout(mActivity) {
            private final int mTouchSlop = ViewConfiguration.get(
                    mActivity).getScaledTouchSlop();
            private float mBlankDownX;
            private float mBlankDownY;
            private boolean mBlankLongPressPending;
            private final Runnable mBlankLongPress = () -> {
                if (!mBlankLongPressPending) {
                    return;
                }
                mBlankLongPressPending = false;
                mActivity.captureInteractionStackForPanel();
                mActivity.showTaskbarContextMenu(mBlankDownX, mBlankDownY);
            };

            @Override
            public boolean dispatchTouchEvent(final MotionEvent event) {
                final int action = event.getActionMasked();
                if (mActivity.handleDesktopMouseTouchEvent(event, true)) {
                    cancelBlankLongPress();
                    return true;
                }
                if (action == MotionEvent.ACTION_DOWN) {
                    cancelBlankLongPress();
                    if (!isActionAt(this, event.getX(), event.getY())) {
                        mActivity.hideAllPanels();
                        mActivity.clearInteractionVisibleTasks();
                        mBlankDownX = event.getRawX();
                        mBlankDownY = event.getRawY();
                        mBlankLongPressPending = true;
                        postDelayed(
                                mBlankLongPress,
                                ViewConfiguration.getLongPressTimeout());
                    }
                } else if (action == MotionEvent.ACTION_MOVE
                        && mBlankLongPressPending
                        && (Math.abs(event.getRawX() - mBlankDownX) > mTouchSlop
                                || Math.abs(event.getRawY() - mBlankDownY)
                                        > mTouchSlop)) {
                    cancelBlankLongPress();
                } else if (action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_CANCEL) {
                    cancelBlankLongPress();
                }
                return super.dispatchTouchEvent(event);
            }

            @Override
            public boolean dispatchGenericMotionEvent(
                    final MotionEvent event) {
                if (mActivity.handleDesktopMouseGenericEvent(event, true)) {
                    return true;
                }
                return super.dispatchGenericMotionEvent(event);
            }

            @Override
            protected void onDetachedFromWindow() {
                cancelBlankLongPress();
                super.onDetachedFromWindow();
            }

            private void cancelBlankLongPress() {
                mBlankLongPressPending = false;
                removeCallbacks(mBlankLongPress);
            }
        };
        taskbar.setOrientation(LinearLayout.HORIZONTAL);
        taskbar.setGravity(Gravity.CENTER_VERTICAL);
        taskbar.setPadding(
                desktopDp(10, 4),
                desktopDp(8, 4),
                desktopDp(10, 4),
                desktopDp(8, 4));
        return taskbar;
    }

    void create() {
        final LinearLayout taskbar = new LinearLayout(mActivity);

        final Button start = mUi.actionButton(
                R.string.action_start,
                UiColor.ACCENT);
        start.setTextSize(14);
        UiAppearance.component(start, ShellControls.Role.PANEL_BUTTON);
        start.setPadding(mUi.dp(4), 0, mUi.dp(4), 0);
        start.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        start.setOnClickListener(view -> mActivity.toggleStartMenu());
        start.setOnLongClickListener(view -> {
            final int[] location = new int[2];
            view.getLocationOnScreen(location);
            mActivity.captureInteractionStackForPanel();
            mActivity.showStartButtonContextMenu(
                    location[0] + view.getWidth() / 2f,
                    location[1] + view.getHeight() / 2f);
            return true;
        });
        mActivity.registerAutomationUiElement(
                start, "taskbar.start", "button",
                mActivity.getString(R.string.action_start));
        mStartButton = start;
        start.setSingleLine(true);
        start.setEllipsize(android.text.TextUtils.TruncateAt.END);
        start.setAutoSizeTextTypeUniformWithConfiguration(8, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        addComponent(taskbar, Kind.START, start, new LinearLayout.LayoutParams(
                desktopDp(108, 72),
                LinearLayout.LayoutParams.MATCH_PARENT));

        final FrameLayout taskScroll = new FrameLayout(mActivity);
        taskScroll.addOnLayoutChangeListener((view,
                left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if ((right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) && mPins != null) {
                renderPins(mActivity.getLauncherApps());
            }
        });
        mTaskViewport = taskScroll;
        mPins = new LinearLayout(mActivity);
        mPins.setOrientation(LinearLayout.HORIZONTAL);
        mPins.setGravity(Gravity.CENTER_VERTICAL);
        taskScroll.addView(mPins, new FrameLayout.LayoutParams(-1, -1));
        final LinearLayout.LayoutParams pinsParams =
                new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        pinsParams.setMargins(
                desktopDp(10, 4), 0, desktopDp(10, 4), 0);
        addComponent(taskbar, Kind.TASKS, taskScroll, pinsParams);

        final ImageButton showDesktop = taskbarButton(
                R.drawable.ic_show_desktop,
                R.string.action_show_desktop);
        showDesktop.setOnClickListener(view ->
                mActivity.toggleDesktopWorkspace());
        mActivity.registerAutomationUiElement(
                showDesktop, "taskbar.show_desktop", "button",
                mActivity.getString(R.string.action_show_desktop));
        addButton(taskbar, Kind.SHOW_DESKTOP, showDesktop);

        final ImageButton taskOverview = taskbarButton(
                R.drawable.ic_file_new_window,
                R.string.action_open_tasks);
        taskOverview.setOnClickListener(view ->
                mActivity.toggleTaskOverview());
        mActivity.registerAutomationUiElement(
                taskOverview, "taskbar.open_tasks", "button",
                mActivity.getString(R.string.action_open_tasks));
        addButton(taskbar, Kind.OPEN_TASKS, taskOverview);

        final View notifications =
                mActivity.notifications().createTaskbarButton(
                        mActivity.isCompactDesktopPreview());
        addComponent(taskbar, Kind.NOTIFICATIONS, notifications,
                new LinearLayout.LayoutParams(
                        desktopDp(46, 38),
                        LinearLayout.LayoutParams.MATCH_PARENT));

        mKeyboardLayout = new TextView(mActivity);
        UiAppearance.text(mKeyboardLayout, UiColor.TEXT);
        mKeyboardLayout.setTextSize(
                mActivity.isCompactDesktopPreview() ? 11 : 13);
        mKeyboardLayout.setAutoSizeTextTypeUniformWithConfiguration(
                8,
                mActivity.isCompactDesktopPreview() ? 11 : 13,
                1,
                android.util.TypedValue.COMPLEX_UNIT_SP);
        mKeyboardLayout.setTypeface(
                android.graphics.Typeface.DEFAULT_BOLD);
        mKeyboardLayout.setGravity(Gravity.CENTER);
        mKeyboardLayout.setClickable(true);
        mKeyboardLayout.setFocusable(true);
        UiAppearance.component(mKeyboardLayout, ShellControls.Role.PANEL_BUTTON);
        mKeyboardLayout.setOnClickListener(mInputMethodMenu::toggle);
        mKeyboardLayout.setEnabled(
                ShellAccess.isReady());
        addComponent(taskbar, Kind.KEYBOARD_LAYOUT, mKeyboardLayout, new LinearLayout.LayoutParams(
                desktopDp(48, 38),
                LinearLayout.LayoutParams.MATCH_PARENT));
        if (mActivity.isCompactDesktopPreview()) {
            mKeyboardLayout.setVisibility(View.GONE);
        }
        mActivity.registerAutomationUiElement(
                mKeyboardLayout, "taskbar.keyboard_layout", "button",
                mActivity.getString(R.string.keyboard_layout_description,
                        ""));

        mPhoneScreenButton = taskbarButton(
                R.drawable.ic_phone_screen_off,
                R.string.tooltip_phone_screen);
        mPhoneScreenButton.setOnClickListener(view ->
                mActivity.togglePhoneScreen());
        mPhoneScreenButton.setEnabled(false);
        addButton(taskbar, Kind.PHONE_SCREEN, mPhoneScreenButton);
        if (mActivity.isCompactDesktopPreview()
                || mActivity.getCurrentDisplayId() == Display.DEFAULT_DISPLAY) {
            mPhoneScreenButton.setVisibility(View.GONE);
        }
        mActivity.registerAutomationUiElement(
                mPhoneScreenButton, "taskbar.phone_screen", "button",
                mActivity.getString(R.string.tooltip_phone_screen));

        mSystemButton = taskbarButton(
                R.drawable.ic_quick_controls,
                R.string.section_quick_controls);
        mSystemButton.setOnClickListener(view ->
                mActivity.toggleSystemPanel());
        mActivity.registerAutomationUiElement(
                mSystemButton, "taskbar.quick_controls", "button",
                mActivity.getString(R.string.section_quick_controls));
        addButton(taskbar, Kind.QUICK_CONTROLS, mSystemButton);

        mBatteryStatus = new TextView(mActivity);
        UiAppearance.text(mBatteryStatus, UiColor.MUTED);
        mBatteryStatus.setTextSize(
                mActivity.isCompactDesktopPreview() ? 10 : 12);
        mBatteryStatus.setGravity(Gravity.CENTER);
        mBatteryStatus.setSingleLine(true);
        mBatteryStatus.setClickable(true);
        mBatteryStatus.setFocusable(true);
        UiAppearance.component(mBatteryStatus, ShellControls.Role.PANEL_BUTTON);
        mBatteryStatus.setOnClickListener(view ->
                mActivity.toggleSystemPanel());
        mActivity.registerAutomationUiElement(
                mBatteryStatus, "taskbar.battery", "button",
                mActivity.getString(R.string.battery_status_unknown));
        addComponent(taskbar, Kind.BATTERY, mBatteryStatus, new LinearLayout.LayoutParams(
                desktopDp(58, 44),
                LinearLayout.LayoutParams.MATCH_PARENT));

        final TextClock clock = new TextClock(mActivity);
        mClock = clock;
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        UiAppearance.text(clock, UiColor.TEXT);
        clock.setTextSize(mActivity.isCompactDesktopPreview() ? 12 : 16);
        clock.setSingleLine(true);
        clock.setEllipsize(android.text.TextUtils.TruncateAt.END);
        clock.setAutoSizeTextTypeUniformWithConfiguration(8, mActivity.isCompactDesktopPreview() ? 12 : 16,
                1, android.util.TypedValue.COMPLEX_UNIT_SP);
        clock.setGravity(Gravity.CENTER);
        clock.setClickable(true);
        clock.setFocusable(true);
        UiAppearance.component(clock, ShellControls.Role.PANEL_BUTTON);
        clock.setContentDescription(
                mActivity.getString(R.string.action_calendar));
        clock.setTooltipText(mActivity.getString(R.string.action_calendar));
        clock.setOnClickListener(view -> mActivity.toggleCalendarPanel());
        mActivity.registerAutomationUiElement(
                clock, "taskbar.clock", "button",
                mActivity.getString(R.string.action_calendar));
        addComponent(taskbar, Kind.CLOCK, clock, new LinearLayout.LayoutParams(
                desktopDp(72, 50),
                LinearLayout.LayoutParams.MATCH_PARENT));
        taskbar.removeAllViews();
        mCreated = true;
        AppearanceStore.listen(mAppearanceChanged);
        applyAppearance();
    }

    void release() {
        AppearanceStore.unlisten(mAppearanceChanged);
        mContentRequests.close();
        mCreated = false;
        mPanels.clear();
        mComponents.clear(); mNaturalWidths.clear(); mClock = null;
        mStartButton = null;
        mPins = null;
        mTaskViewport = null;
        mOverflow.release();
        mKeyboardLayout = null;
        mInputMethodMenu.release();
        mBatteryStatus = null;
        mSystemButton = null;
        mPhoneScreenButton = null;
    }

    void setVisible(final boolean visible) {
        final DesktopTaskbarHost taskbarHost = mActivity.taskbarHost();
        if (taskbarHost != null && mCreated) {
            taskbarHost.setPresented(visible);
        }
    }

    void setPhoneScreenActionEnabled(final boolean enabled) {
        if (mPhoneScreenButton != null) {
            mPhoneScreenButton.setEnabled(enabled);
        }
    }

    void renderPins(final List<AppItem> apps) {
        if (mPins == null) {
            return;
        }
        mPins.removeAllViews();
        final List<TaskbarOverflowController.Entry> items =
                collectTaskbarItems(apps);
        if (mItemCount != items.size()) {
            mItemCount = items.size();
            for (var panel : mPanels.values()) panel.allocate();
            mActivity.onTaskbarContentChanged();
        }
        final int itemWidth = taskItemExtent();
        final int availableWidth = mTaskViewport == null
                ? 0 : mTasksVertical ? mTaskViewport.getHeight() : mTaskViewport.getWidth();
        final int visibleCount = TaskbarOverflowPolicy.visibleItemCount(
                items.size(), availableWidth, itemWidth);
        mOverflow.setItems(items.subList(visibleCount, items.size()));
        for (int index = 0; index < visibleCount; index++) {
            addPin(items.get(index));
        }
        if (visibleCount < items.size()) {
            addOverflowButton();
        }
    }

    View panelView(String id) { final var panel = mPanels.get(id); return panel == null ? null : panel.root; }
    int minimumLength(ShellPanel panel) { return contentLength(panel, false); }
    int preferredLength(ShellPanel panel) { return contentLength(panel, true); }

    private int contentLength(ShellPanel panel, boolean preferred) {
        int width = 2 * panelPadding(panel);
        for (var item : panel.components()) {
            if (!componentVisible(item)) continue;
            width += componentLength(item, panel);
            if (preferred && item.type() == Kind.TASKS && item.widthDp() == 0) {
                width += dp(metrics(panel).itemExtent()) * Math.max(0, mItemCount - 1);
            }
        }
        return width;
    }

    private void applyAppearance() {
        if (!mCreated) return;
        var definitions = AppearanceStore.current(mActivity).composition().panels();
        var ids = definitions.stream().map(ShellPanel::id).toList();
        mPanels.keySet().removeIf(id -> !ids.contains(id));
        // Detach only moved/removed components, preserving live controls and their service bindings.
        for (var entry : mComponents.entrySet()) {
            var destination = definitions.stream().filter(p -> p.components().stream().anyMatch(c -> c.type() == entry.getKey()))
                    .findFirst().orElse(null);
            var host = destination == null ? null : mPanels.get(destination.id());
            if (entry.getValue().getParent() instanceof ViewGroup current && (host == null || current != host.row)) {
                current.removeView(entry.getValue());
            }
        }
        for (int i = 0; i < definitions.size(); i++) {
            ShellPanel definition = definitions.get(i);
            PanelView panel = mPanels.computeIfAbsent(definition.id(), id -> new PanelView());
            panel.update(definition);
            mActivity.registerAutomationUiElement(panel.root, i == 0 ? "taskbar" : "shell.panel." + definition.id(),
                    "taskbar", definition.id());
        }
        renderPins(mActivity.getLauncherApps());
        if (mLastBatteryIntent != null) updateBattery(mLastBatteryIntent);
        mActivity.onTaskbarContentChanged();
    }

    private ShellPanelMetrics metrics(ShellPanel panel) { return ShellPanelMetrics.resolve(panel.style(), mActivity.isCompactDesktopPreview()); }
    private int panelPadding(ShellPanel panel) { return dp(metrics(panel).padding()); }
    private ShellPanel taskPanel() { return AppearanceStore.current(mActivity).composition().panelFor(Kind.TASKS); }
    private int taskItemExtent() { return dp(metrics(taskPanel()).itemExtent()); }
    private ShellComposition.Component component(Kind kind) {
        return AppearanceStore.current(mActivity).composition().panels().stream().flatMap(p -> p.components().stream())
                .filter(c -> c.type() == kind).findFirst().orElse(ShellComposition.Component.of(kind));
    }

    private boolean componentVisible(ShellComposition.Component item) {
        final var metrics = mActivity.getResources().getDisplayMetrics();
        return item.visible(mActivity.isCompactDesktopPreview(), mActivity.getCurrentDisplayId() != Display.DEFAULT_DISPLAY,
                Math.round(metrics.widthPixels / metrics.density))
                && (item.type() != Kind.PHONE_SCREEN || mPhoneActionVisible);
    }

    private int componentLength(ShellComposition.Component item, ShellPanel panel) {
        if (item.widthDp() != 0) return dp(item.widthDp());
        var metrics = metrics(panel);
        if (item.type() == Kind.TASKS) return dp(metrics.itemExtent());
        if (item.type() == Kind.SPACER) return 0;
        if (panel.edge().vertical()) return dp(metrics.itemExtent());
        if (item.type() == Kind.CLOCK && item.clock() != ShellComposition.Clock.TIME) {
            return dp(metrics.scaled(item.clock() == ShellComposition.Clock.DATE ? 110 : 170));
        }
        if (item.type() == Kind.BATTERY) return switch (item.battery()) {
            case ICON -> dp(metrics.itemExtent());
            case BOTH -> dp(metrics.scaled(mActivity.isCompactDesktopPreview() ? 76 : 92));
            case PERCENT -> Math.round(mNaturalWidths.getOrDefault(item.type(), 0) * metrics.contentScale());
        };
        if (mComponents.get(item.type()) instanceof ImageButton || item.type() == Kind.NOTIFICATIONS) return dp(metrics.itemExtent());
        return Math.round(mNaturalWidths.getOrDefault(item.type(), 0) * metrics.contentScale());
    }

    private final class PanelView {
        final LinearLayout root = createPanelContainer();
        final FrameLayout row = new FrameLayout(mActivity);
        final List<View> spacers = new ArrayList<>();
        ShellPanel definition;
        Boolean vertical;
        PanelView() {
            root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l != or - ol || b - t != ob - ot) allocate();
            });
        }
        void update(ShellPanel value) {
            boolean axis = value.edge().vertical();
            if (vertical == null || vertical != axis) {
                if (row.getParent() instanceof ViewGroup parent) parent.removeView(row);
                root.removeAllViews();
                if (axis) {
                    ScrollView scroll = new ScrollView(mActivity); scroll.setVerticalScrollBarEnabled(false); scroll.setFillViewport(true);
                    scroll.addView(row, new FrameLayout.LayoutParams(-1, -2)); root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
                } else {
                    HorizontalScrollView scroll = new HorizontalScrollView(mActivity); scroll.setHorizontalScrollBarEnabled(false); scroll.setFillViewport(true);
                    scroll.addView(row, new FrameLayout.LayoutParams(-2, -1)); root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
                }
                vertical = axis;
            }
            definition = value;
            int pad = panelPadding(value); root.setPadding(pad, pad, pad, pad);
            final List<View> ordered = new ArrayList<>();
            int spacer = 0;
            for (var item : value.components()) {
                final View view;
                if (item.type() == Kind.SPACER) {
                    if (spacer == spacers.size()) spacers.add(new View(mActivity));
                    view = spacers.get(spacer++);
                } else view = mComponents.get(item.type());
                ordered.add(view);
                if (view instanceof ImageButton icon) {
                    int inset = dp(metrics(value).iconInset());
                    UiAppearance.componentPadding(icon, inset, inset, inset, inset);
                }
                if (item.type() == Kind.START) mStartButton.setText(item.label().isEmpty()
                        ? mActivity.getString(R.string.action_start) : item.label());
                if (item.type() == Kind.CLOCK) {
                    String format = switch (item.clock()) { case TIME -> "HH:mm"; case DATE -> "EEE, d MMM"; case DATE_TIME -> "d MMM  HH:mm"; };
                    mClock.setFormat12Hour(format); mClock.setFormat24Hour(format);
                }
                if (item.type() == Kind.TASKS && mTasksVertical != axis) {
                    mTasksVertical = axis;
                    mPins.setOrientation(axis ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
                    renderPins(mActivity.getLauncherApps());
                }
            }
            // Retain Views and their service bindings; reorder only changed positions.
            for (int i = row.getChildCount() - 1; i >= 0; i--) {
                if (!ordered.contains(row.getChildAt(i))) row.removeViewAt(i);
            }
            for (int i = 0; i < ordered.size(); i++) {
                View view = ordered.get(i);
                if (row.indexOfChild(view) == i) continue;
                if (view.getParent() instanceof ViewGroup parent) parent.removeView(view);
                row.addView(view, i, new FrameLayout.LayoutParams(axis ? -1 : 0, axis ? 0 : -1));
            }
            while (spacers.size() > spacer) spacers.remove(spacers.size() - 1);
            allocate();
        }
        void allocate() {
            if (definition == null) return;
            final List<ShellComponentLayout.Slot> slots = new ArrayList<>();
            for (var item : definition.components()) {
                boolean visible = componentVisible(item);
                int minimum = visible ? componentLength(item, definition) : 0;
                int preferred = minimum + (visible && item.type() == Kind.TASKS && item.widthDp() == 0
                        ? dp(metrics(definition).itemExtent()) * Math.max(0, mItemCount - 1) : 0);
                slots.add(new ShellComponentLayout.Slot(minimum, preferred,
                        visible && item.widthDp() == 0 && (item.type() == Kind.TASKS || item.type() == Kind.SPACER), item.group()));
            }
            var layout = ShellComponentLayout.resolve(slots, (vertical ? root.getHeight() : root.getWidth()) - 2 * panelPadding(definition));
            var rowParams = row.getLayoutParams();
            int rowWidth = vertical ? -1 : layout.extent(), rowHeight = vertical ? layout.extent() : -1;
            if (rowParams.width != rowWidth || rowParams.height != rowHeight) {
                rowParams.width = rowWidth; rowParams.height = rowHeight; row.setLayoutParams(rowParams);
            }
            for (int i = 0; i < definition.components().size(); i++) {
                View view = row.getChildAt(i);
                view.setVisibility(componentVisible(definition.components().get(i)) ? View.VISIBLE : View.GONE);
                var params = (FrameLayout.LayoutParams) view.getLayoutParams();
                int width = vertical ? -1 : layout.lengths()[i], height = vertical ? layout.lengths()[i] : -1;
                int left = vertical ? 0 : row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                        ? layout.extent() - layout.offsets()[i] - width : layout.offsets()[i];
                int top = vertical ? layout.offsets()[i] : 0;
                if (params.width != width || params.height != height || params.leftMargin != left || params.topMargin != top) {
                    params.width = width; params.height = height; params.gravity = Gravity.TOP | Gravity.LEFT;
                    params.leftMargin = left; params.topMargin = top; view.setLayoutParams(params);
                }
            }
        }
    }

    private List<TaskbarOverflowController.Entry> collectTaskbarItems(
            final List<AppItem> apps) {
        final List<TaskbarOverflowController.Entry> items = new ArrayList<>();
        final List<AppItem> availableApps = apps == null
                ? new ArrayList<>() : apps;
        final List<AppReference> pinnedApps = mActivity.getPinnedApps();
        final Set<Integer> renderedTaskIds = new HashSet<>();
        final List<TaskRepository.TaskEntry> orderedTasks =
                getOrderedTaskbarTasks();

        for (final AppReference reference : pinnedApps) {
            final AppItem app = LauncherAppRepository.find(
                    availableApps, reference);
            if (app == null) {
                continue;
            }
            final List<TaskRepository.TaskEntry> packageTasks =
                    findTasks(orderedTasks, app);
            if (packageTasks.isEmpty()) {
                items.add(new TaskbarOverflowController.Entry(app, null));
                continue;
            }
            for (final TaskRepository.TaskEntry task : packageTasks) {
                items.add(new TaskbarOverflowController.Entry(BuiltInWindowRegistry.present(mActivity, app, task), task));
                renderedTaskIds.add(Integer.valueOf(task.taskId));
            }
        }

        for (final TaskRepository.TaskEntry task : orderedTasks) {
            if (renderedTaskIds.contains(
                    Integer.valueOf(task.taskId))) {
                continue;
            }
            final AppItem app = mActivity.findOrLoadApp(
                    availableApps, task);
            if (app != null) {
                items.add(new TaskbarOverflowController.Entry(app, task));
            }
        }
        return items;
    }

    List<AppReference> getPinnedApps() {
        return DesktopPreferences.taskbarApps();
    }

    void togglePinned(final AppItem app) {
        final List<AppReference> pinned = getPinnedApps();
        final boolean nowPinned;
        if (pinned.remove(app.reference)) {
            nowPinned = false;
        } else {
            pinned.add(app.reference);
            nowPinned = true;
        }
        DesktopPreferences.saveTaskbarApps(pinned);
        renderPins(mActivity.getLauncherApps());
        mActivity.renderStartMenuContent();
        mActivity.setStatus(mActivity.getString(
                nowPinned
                        ? R.string.status_app_pinned
                        : R.string.status_app_unpinned,
                app.label));
    }

    private List<TaskRepository.TaskEntry> getOrderedTaskbarTasks() {
        final List<TaskRepository.TaskEntry> liveTasks = new ArrayList<>();
        final Set<Integer> liveTaskIds = new HashSet<>();
        for (final TaskRepository.TaskEntry task :
                mActivity.getTaskSnapshot().tasks) {
            if (!mActivity.isTaskbarTask(task)) {
                continue;
            }
            liveTasks.add(task);
            liveTaskIds.add(Integer.valueOf(task.taskId));
        }

        for (int index = mTaskOrder.size() - 1; index >= 0; index--) {
            if (!liveTaskIds.contains(mTaskOrder.get(index))) {
                mTaskOrder.remove(index);
            }
        }
        for (final TaskRepository.TaskEntry task : liveTasks) {
            final Integer taskId = Integer.valueOf(task.taskId);
            if (!mTaskOrder.contains(taskId)) {
                mTaskOrder.add(taskId);
            }
        }

        final List<TaskRepository.TaskEntry> orderedTasks = new ArrayList<>();
        for (final Integer taskId : mTaskOrder) {
            for (final TaskRepository.TaskEntry task : liveTasks) {
                if (task.taskId == taskId.intValue()) {
                    orderedTasks.add(task);
                    break;
                }
            }
        }
        return orderedTasks;
    }

    private static List<TaskRepository.TaskEntry> findTasks(
            final List<TaskRepository.TaskEntry> tasks,
            final AppItem app) {
        final List<TaskRepository.TaskEntry> result = new ArrayList<>();
        for (final TaskRepository.TaskEntry task : tasks) {
            if (app.matchesTask(task)) {
                result.add(task);
            }
        }
        return result;
    }

    void updateKeyboardLayout() {
        if (mKeyboardLayout == null) {
            return;
        }
        String layoutLabel = Settings.Global.getString(
                mActivity.getContentResolver(),
                DesktopShellActivity.HARDWARE_LAYOUT_LABEL_STATE);
        if (layoutLabel == null || layoutLabel.isEmpty()) {
            layoutLabel = "??";
        }
        final String layoutName = Settings.Global.getString(
                mActivity.getContentResolver(),
                DesktopShellActivity.HARDWARE_LAYOUT_NAME_STATE);
        mKeyboardLayout.setText(layoutLabel);
        final String description = mActivity.getString(
                R.string.keyboard_layout_description,
                layoutName == null || layoutName.isEmpty()
                        ? layoutLabel
                        : layoutName);
        mKeyboardLayout.setContentDescription(description);
        mKeyboardLayout.setTooltipText(description);
    }

    void updatePhoneScreen(
            final boolean phoneScreenOff,
            final boolean visible,
            final boolean phoneScreenControl) {
        if (mPhoneScreenButton == null) {
            return;
        }
        final int actionResId = phoneScreenOff
                ? R.string.action_phone_screen_on
                : R.string.action_phone_screen_off;
        mPhoneScreenButton.setImageResource(phoneScreenOff
                ? R.drawable.ic_phone_screen_on
                : R.drawable.ic_phone_screen_off);
        UiAppearance.image(mPhoneScreenButton,
                phoneScreenOff
                        ? UiColor.ACCENT
                        : UiColor.TEXT);
        mPhoneScreenButton.setContentDescription(
                mActivity.getString(actionResId));
        mPhoneScreenButton.setTooltipText(
                mActivity.getString(actionResId));
        mPhoneScreenButton.setEnabled(phoneScreenControl);
        mPhoneScreenButton.setAlpha(phoneScreenControl ? 1f : 0.45f);
        if (mPhoneActionVisible != visible) {
            mPhoneActionVisible = visible;
            applyAppearance();
            mActivity.onTaskbarContentChanged();
        }
    }

    void updateSystemStatus(final boolean shortcutsReady) {
        if (mSystemButton == null) {
            return;
        }
        final boolean taskControl =
                ShellAccess.isReady();
        final UiColor color = taskControl && shortcutsReady
                ? UiColor.ACCENT
                : (taskControl
                        ? UiColor.ATTENTION
                        : UiColor.MUTED);
        final String description = mActivity.getString(
                R.string.system_status_description,
                ShellAccess.statusLabel(),
                mActivity.getString(R.string.state_ready),
                mActivity.getString(shortcutsReady
                        ? R.string.state_ready
                        : R.string.state_unavailable));
        UiAppearance.image(mSystemButton, color);
        mSystemButton.setContentDescription(description);
        mSystemButton.setTooltipText(description);
    }

    void updateBattery(final Intent battery) {
        if (mBatteryStatus == null || battery == null) {
            return;
        }
        mLastBatteryIntent = battery;
        final int level =
                battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        final int scale =
                battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        final int percent = level < 0 || scale <= 0
                ? -1
                : Math.max(
                        0,
                        Math.min(
                                100,
                                Math.round(level * 100f / scale)));
        final int status = battery.getIntExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN);
        final boolean charging =
                status == BatteryManager.BATTERY_STATUS_CHARGING;
        final boolean full =
                status == BatteryManager.BATTERY_STATUS_FULL;
        var batteryStyle = component(Kind.BATTERY).battery();
        var panel = AppearanceStore.current(mActivity).composition().panelFor(Kind.BATTERY);
        var size = metrics(panel);
        mBatteryIcon.setPercent(percent);
        mBatteryIcon.setBounds(0, 0, dp(size.scaled(28)), dp(size.scaled(16)));
        boolean aboveText = panel.edge().vertical() && batteryStyle == ShellComposition.Battery.BOTH;
        mBatteryStatus.setCompoundDrawablesRelative(batteryStyle != ShellComposition.Battery.PERCENT && !aboveText ? mBatteryIcon : null,
                aboveText ? mBatteryIcon : null, null, null);
        mBatteryStatus.setCompoundDrawablePadding(dp(4));
        mBatteryStatus.setText(batteryStyle == ShellComposition.Battery.ICON ? "" : percent < 0
                ? mActivity.getString(R.string.battery_compact_unknown)
                : mActivity.getString(
                        R.string.battery_compact,
                        Integer.valueOf(percent)));
        UiAppearance.text(mBatteryStatus,
                charging || mChargeSeparationEnabled
                        ? UiColor.ACCENT
                        : UiColor.TEXT);
        final String state = mActivity.getString(
                charging
                        ? R.string.battery_state_charging
                        : (full
                                ? R.string.battery_state_full
                                : R.string.battery_state_discharging));
        final String description = percent < 0
                ? mActivity.getString(R.string.battery_status_unknown)
                : (mChargeSeparationEnabled
                        ? mActivity.getString(
                                R.string.battery_status_bypass_description,
                                Integer.valueOf(percent))
                        : mActivity.getString(
                        R.string.battery_status_description,
                        Integer.valueOf(percent),
                        state));
        mBatteryStatus.setContentDescription(description);
        mBatteryStatus.setTooltipText(description);
    }

    void updateChargeSeparation(final boolean enabled) {
        mChargeSeparationEnabled = enabled;
        if (mLastBatteryIntent != null) {
            updateBattery(mLastBatteryIntent);
        }
    }

    private void addPin(final TaskbarOverflowController.Entry taskbarItem) {
        mPins.addView(
                createPin(taskbarItem),
                new LinearLayout.LayoutParams(
                        mTasksVertical ? -1 : taskItemExtent(),
                        mTasksVertical ? taskItemExtent() : -1));
    }

    private View createPin(
            final TaskbarOverflowController.Entry taskbarItem) {
        final AppItem app = taskbarItem.app;
        final TaskRepository.TaskEntry task = taskbarItem.task;
        final FrameLayout item = new FrameLayout(mActivity);
        UiAppearance.component(item, ShellControls.Role.PANEL_BUTTON);
        item.setClickable(true);
        item.setFocusable(true);

        final ImageView icon = new ImageView(mActivity);
        icon.setImageDrawable(app.icon);
        int inset = dp(metrics(taskPanel()).iconInset());
        icon.setPadding(inset, inset, inset, inset);
        item.addView(icon, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        var indicator = component(Kind.TASKS).indicator();
        if (task != null && indicator != ShellComposition.Indicator.NONE) {
            final View running = new View(mActivity);
            var presentation = BuiltInWindowRegistry.presentation(task);
            UiColor color = task.active || presentation != null && presentation.attention()
                    ? UiColor.ATTENTION
                    : UiColor.ACCENT;
            int length = dp(indicator == ShellComposition.Indicator.DOT ? (task.active ? 6 : 4)
                    : metrics(taskPanel()).scaled(task.active ? 26 : 20));
            int thickness = dp(indicator == ShellComposition.Indicator.DOT ? (task.active ? 6 : 4) : (task.active ? 3 : 2));
            int gravity = switch (taskPanel().edge()) {
                case BOTTOM -> Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                case TOP -> Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                case LEFT -> Gravity.LEFT | Gravity.CENTER_VERTICAL;
                case RIGHT -> Gravity.RIGHT | Gravity.CENTER_VERTICAL;
            };
            if (indicator == ShellComposition.Indicator.DOT) running.setBackground(mUi.rounded(
                    color, dp(3), UiColor.TRANSPARENT));
            else UiAppearance.background(running, color);
            final FrameLayout.LayoutParams runningParams = new FrameLayout.LayoutParams(
                    mTasksVertical ? thickness : length, mTasksVertical ? length : thickness, gravity);
            runningParams.setMargins(dp(2), dp(2), dp(2), dp(2));
            item.addView(running, runningParams);
        }

        final String description = task == null
                ? app.label
                : mActivity.getString(
                        R.string.taskbar_running_description,
                        app.label,
                        Integer.valueOf(task.taskId));
        item.setContentDescription(description);
        item.setTooltipText(description);
        item.setOnClickListener(view -> activate(taskbarItem));
        enableContentDrop(item, app, task);
        mActivity.registerContextTarget(item, app, task);
        mActivity.registerAutomationUiElement(
                item,
                task == null
                        ? "taskbar.app."
                                + DesktopAutomationUiRegistry.identitySegment(
                                        app.packageName)
                        : "taskbar.task." + task.taskId,
                "application",
                app.label,
                app.packageName,
                task == null ? -1 : task.taskId);
        return item;
    }

    private void enableContentDrop(
            final View item,
            final AppItem app,
            final TaskRepository.TaskEntry task) {
        final float restingAlpha = item.getAlpha();
        item.setOnDragListener((target, event) -> {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return !(event.getLocalState()
                                    instanceof DesktopGridLayout.DragToken)
                            && event.getClipDescription() != null;
                case DragEvent.ACTION_DRAG_ENTERED:
                    target.setAlpha(0.72f);
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                case DragEvent.ACTION_DRAG_ENDED:
                    target.setAlpha(restingAlpha);
                    return true;
                case DragEvent.ACTION_DROP:
                    target.setAlpha(restingAlpha);
                    final AndroidContentPayload content =
                            AndroidContentPayload.fromClipData(
                                    event.getClipData(),
                                    AndroidContentPayload.Origin.DRAG);
                    if (content.isEmpty()) {
                        return false;
                    }
                    final DragAndDropPermissions permissions =
                            mActivity.requestDragAndDropPermissions(event);
                    deliverContent(app, task, content, permissions);
                    return true;
                default:
                    return true;
            }
        });
    }

    private void deliverContent(
            final AppItem app,
            final TaskRepository.TaskEntry task,
            final AndroidContentPayload content,
            final DragAndDropPermissions permissions) {
        final int displayId = mActivity.getCurrentDisplayId();
        if (task != null && !task.isFreeform() && !task.isFullscreen()) {
            if (permissions != null) {
                permissions.release();
            }
            mActivity.setErrorStatus(
                    "CONTENT-DROP-001",
                    "target task has no supported desktop window mode",
                    "package=" + app.packageName,
                    null);
            return;
        }
        final DesktopLaunchPresentation presentation = task == null
                ? DesktopLaunchPresentation.automatic()
                : DesktopLaunchPresentation.forMode(
                        task.isFreeform()
                                ? DesktopLaunchMode.WINDOWED
                                : DesktopLaunchMode.FULLSCREEN)
                        .withPreferredTask(task.taskId);
        AndroidDesktopActionDispatcher.deliverContent(
                mContentRequests,
                mActivity,
                content,
                app.launchTarget,
                presentation,
                displayId,
                () -> {
                    if (permissions != null) {
                        permissions.release();
                    }
                },
                result -> {
                    if (!result.success) {
                        mActivity.setErrorStatus(
                                "CONTENT-DROP-001",
                                result.message,
                                "package=" + app.packageName,
                                null);
                    }
                });
    }

    private void addOverflowButton() {
        mPins.addView(mOverflow.createButton(metrics(taskPanel())),
                new LinearLayout.LayoutParams(
                mTasksVertical ? -1 : taskItemExtent(),
                mTasksVertical ? taskItemExtent() : -1));
    }

    private void activate(final TaskbarOverflowController.Entry taskbarItem) {
        if (taskbarItem.task != null) {
            mActivity.captureInteractionStackForPanel();
        }
        mActivity.hideAllPanels();
        if (taskbarItem.task == null) {
            mActivity.launchDefault(taskbarItem.app);
        } else {
            mActivity.toggleTaskbarTask(
                    taskbarItem.app,
                    taskbarItem.task);
        }
    }

    private void addButton(
            final LinearLayout taskbar,
            final Kind kind,
            final ImageButton button) {
        addComponent(taskbar, kind, button, new LinearLayout.LayoutParams(
                desktopDp(46, 38),
                LinearLayout.LayoutParams.MATCH_PARENT));
    }

    private void addComponent(LinearLayout parent, Kind kind, View view, LinearLayout.LayoutParams params) {
        mComponents.put(kind, view); mNaturalWidths.put(kind, Math.max(0, params.width));
        parent.addView(view, params);
    }

    private ImageButton taskbarButton(
            final int drawableResId,
            final int descriptionResId) {
        return mUi.taskbarIconButton(
                drawableResId,
                descriptionResId,
                mActivity.isCompactDesktopPreview());
    }

    private boolean isActionAt(final LinearLayout panel, final float localX, final float localY) {
        for (int index = 0; index < panel.getChildCount(); index++) {
            if (isActionViewAt(
                    panel,
                    panel.getChildAt(index),
                    localX,
                    localY)) {
                return true;
            }
        }
        return false;
    }

    ContextArea contextAreaAt(final float screenX, final float screenY) {
        if (containsOnScreen(mStartButton, screenX, screenY)) {
            return ContextArea.START;
        }
        final int[] location = new int[2];
        for (var panel : mPanels.values()) {
            if (!containsOnScreen(panel.root, screenX, screenY)) continue;
            panel.root.getLocationOnScreen(location);
            return isActionAt(panel.root, screenX - location[0], screenY - location[1]) ? ContextArea.ACTION : ContextArea.BLANK;
        }
        return ContextArea.NONE;
    }

    private static boolean containsOnScreen(
            final View view,
            final float screenX,
            final float screenY) {
        if (view == null || !view.isShown()) {
            return false;
        }
        final int[] location = new int[2];
        view.getLocationOnScreen(location);
        return screenX >= location[0]
                && screenY >= location[1]
                && screenX < location[0] + view.getWidth()
                && screenY < location[1] + view.getHeight();
    }

    private boolean isActionViewAt(
            final ViewGroup parent,
            final View view,
            final float parentX,
            final float parentY) {
        if (view == null || !view.isShown() || !view.isEnabled()) {
            return false;
        }
        final float localX =
                parentX + parent.getScrollX() - view.getLeft();
        final float localY =
                parentY + parent.getScrollY() - view.getTop();
        if (localX < 0
                || localY < 0
                || localX >= view.getWidth()
                || localY >= view.getHeight()) {
            return false;
        }
        if (view.hasOnClickListeners()) {
            return true;
        }
        if (!(view instanceof ViewGroup)) {
            return false;
        }
        final ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            if (isActionViewAt(
                    group,
                    group.getChildAt(index),
                    localX,
                    localY)) {
                return true;
            }
        }
        return false;
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }

    private int desktopDp(
            final int normalValue,
            final int compactValue) {
        return mUi.desktopDp(
                normalValue,
                compactValue,
                mActivity.isCompactDesktopPreview());
    }

}
