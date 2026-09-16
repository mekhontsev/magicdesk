package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;

/** View-local display selection; commands capture its identity without claiming input. */
final class DisplayTableView {
    interface Actions {
        void startDesktop(DesktopDisplayInfo display);
        void closeDesktop(DesktopDisplayInfo display);
        void controlDisplay(DesktopDisplayInfo display);
        void openApplications(DesktopDisplayInfo display);
        void openIndependentApplications(DesktopDisplayInfo display);
        void openOutputSettings(DesktopDisplayInfo display);
        void createDisplay(VirtualDisplaySpec spec, boolean preview);
        void removeDisplay(DesktopDisplayInfo display);
        void startPortableDesktop(DesktopDisplayInfo output);
    }

    private final Activity mActivity;
    private final DesktopUiFactory mUi;
    private final Actions mActions;
    private final LinearLayout mRoot;
    private final RadioGroup mRows;
    private final LinearLayout mCommands;
    private final DisplayCreationDialog mCreationDialog;
    private final Button mStart, mClose, mApps, mIndependent, mInput, mOutput, mRemove;
    private final Button mShowDisplay, mStopShowing, mPortable;
    private DesktopDisplayInfo[] mDisplays = new DesktopDisplayInfo[0];
    private DesktopDisplayInfo mSelectedDisplay;
    private Set<Integer> mDesktops = Set.of();
    private boolean mShellReady, mBusy, mOutputAvailable, mRendering;

    DisplayTableView(Activity activity, DesktopUiFactory ui, Actions actions, LinearLayout parent) {
        mActivity = activity;
        mUi = ui;
        mActions = actions;
        mRoot = new LinearLayout(activity);
        mRoot.setOrientation(LinearLayout.VERTICAL);
        mRoot.setVisibility(View.GONE);
        parent.addView(mRoot, new LinearLayout.LayoutParams(-1, -2));
        mCreationDialog = new DisplayCreationDialog(activity, ui, actions);
        mRows = new RadioGroup(activity);
        mRows.setOrientation(LinearLayout.VERTICAL);
        final GradientDrawable divider = new GradientDrawable();
        divider.setColor(DesktopUiFactory.COLOR_MUTED);
        divider.setSize(1, dp(1));
        mRows.setDividerDrawable(divider);
        mRows.setShowDividers(LinearLayout.SHOW_DIVIDER_BEGINNING | LinearLayout.SHOW_DIVIDER_MIDDLE);
        mRows.setOnCheckedChangeListener((group, checkedId) -> selectDisplay(checkedId));
        mRoot.addView(mRows, new LinearLayout.LayoutParams(-1, -2));
        mCommands = new LinearLayout(activity);
        mCommands.setOrientation(LinearLayout.VERTICAL);
        mRoot.addView(mCommands, new LinearLayout.LayoutParams(-1, -2));
        separator(mCommands);
        final GridLayout commands = new GridLayout(mActivity);
        commands.setColumnCount(2);
        mStart = button(commands, R.drawable.ic_play, R.string.display_start);
        mPortable = button(commands, R.drawable.ic_file_new_window, R.string.display_start_portable);
        mApps = button(commands, R.drawable.ic_sections, R.string.section_apps);
        mIndependent = button(commands, R.drawable.ic_history, R.string.display_independent_apps);
        mShowDisplay = button(commands, R.drawable.ic_eye, R.string.display_show_another);
        mStopShowing = button(commands, R.drawable.ic_close, R.string.display_stop_showing);
        mInput = button(commands, R.drawable.ic_touchpad, R.string.display_control);
        mOutput = button(commands, R.drawable.ic_show_desktop, R.string.external_display_resolution);
        mClose = button(commands, R.drawable.ic_close, R.string.action_close_desktop);
        mRemove = button(commands, R.drawable.ic_file_delete, R.string.display_remove);
        mCommands.addView(commands, new LinearLayout.LayoutParams(-1, -2));
        separator(mCommands);
    }

    void render(DesktopDisplayInfo[] displays, Set<Integer> desktops, boolean shellReady,
            boolean busy, boolean outputAvailable, TaskRepository.Snapshot tasks) {
        mDesktops = desktops;
        mShellReady = shellReady;
        mBusy = busy;
        mOutputAvailable = outputAvailable;
        mRoot.setVisibility(shellReady ? View.VISIBLE : View.GONE);
        if (!shellReady) { return; }
        final DesktopDisplayInfo[] next = orderedDisplays(displays);
        final DesktopDisplayInfo added = newlyAvailableDisplay(mDisplays, next);
        mDisplays = next;
        mSelectedDisplay = added != null ? added : selectedDisplay(mDisplays,
                mSelectedDisplay == null ? null : mSelectedDisplay.uniqueId, mActivity.getDisplay().getDisplayId());
        mRendering = true;
        try {
            reconcileRows();
            int checkedId = View.NO_ID;
            for (int index = 0; index < mDisplays.length; ++index) {
                final DesktopDisplayInfo display = mDisplays[index];
                final RadioButton row = (RadioButton) mRows.getChildAt(index);
                final CharSequence label = rowLabel(display, desktops, tasks);
                if (!TextUtils.equals(row.getText(), label)) { row.setText(label); }
                if (display == mSelectedDisplay) { checkedId = row.getId(); }
            }
            mRows.check(checkedId);
        } finally {
            mRendering = false;
        }
        renderCommands(mSelectedDisplay, desktops, shellReady, busy, outputAvailable);
    }

    private void selectDisplay(int checkedId) {
        if (mRendering || !mShellReady) { return; }
        final View row = mRows.findViewById(checkedId);
        if (row == null) { return; }
        mSelectedDisplay = (DesktopDisplayInfo) row.getTag();
        renderCommands(mSelectedDisplay, mDesktops, mShellReady, mBusy, mOutputAvailable);
    }

    void showCreationDialog() {
        mCreationDialog.show(mSelectedDisplay);
    }

    private void reconcileRows() {
        // Catalogs stay in descending ID order, so surviving rows never need detaching.
        for (int index = mRows.getChildCount() - 1; index >= 0; --index) {
            final DesktopDisplayInfo old = (DesktopDisplayInfo) mRows.getChildAt(index).getTag();
            if (Arrays.stream(mDisplays).noneMatch(display -> sameDisplay(old, display))) {
                mRows.removeViewAt(index);
            }
        }
        for (int index = 0; index < mDisplays.length; ++index) {
            final DesktopDisplayInfo display = mDisplays[index];
            RadioButton row = (RadioButton) mRows.getChildAt(index);
            if (row == null || !sameDisplay((DesktopDisplayInfo) row.getTag(), display)) {
                row = createRow();
                mRows.addView(row, index, new RadioGroup.LayoutParams(-1, -2));
            }
            row.setTag(display);
        }
    }

    private RadioButton createRow() {
        final RadioButton row = new RadioButton(mActivity);
        row.setId(View.generateViewId());
        row.setTextSize(14);
        row.setTextColor(DesktopUiFactory.COLOR_TEXT);
        row.setButtonTintList(new ColorStateList(
                new int[][] {new int[] {android.R.attr.state_checked}, new int[0]},
                new int[] {DesktopUiFactory.COLOR_CYAN, DesktopUiFactory.COLOR_MUTED}));
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        row.setCompoundDrawablePadding(dp(8));
        row.setMinHeight(dp(64));
        return row;
    }

    private CharSequence rowLabel(DesktopDisplayInfo display, Set<Integer> desktops, TaskRepository.Snapshot tasks) {
        final boolean active = desktops.contains(display.id);
        final SpannableStringBuilder label = new SpannableStringBuilder(display.name + " [" + display.id + "]");
        final int titleEnd = label.length();
        final String state = mActivity.getString(active ? R.string.display_desktop_active : R.string.display_no_desktop);
        label.append('\n').append(state).append("  |  ").append(display.width + " x " + display.height);
        final var output = DisplayPresentations.forOutput(display.id);
        if (output != null) {
            label.append('\n').append(mActivity.getString(R.string.display_viewing,
                    output.source.name, output.source.id));
        }
        final var source = DisplayPresentations.forSource(display.id);
        if (source != null) {
            label.append('\n').append(mActivity.getString(R.string.display_presented_on,
                    source.output.name, source.output.id));
        }
        final var independent = ApplicationTaskPlacement.independentSnapshot(tasks, display.id, active);
        label.append('\n').append(independent.available
                ? mActivity.getString(R.string.display_independent_count, independent.tasks.size())
                : mActivity.getString(R.string.display_tasks_unknown));
        if (MagicDeskRuntime.inputDisplayId() == display.id) {
            label.append("  |  ").append(mActivity.getString(R.string.display_input_active));
        }
        label.setSpan(new ForegroundColorSpan(DesktopUiFactory.COLOR_MUTED), titleEnd, label.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(new RelativeSizeSpan(12f / 14f), titleEnd, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return label;
    }

    private void renderCommands(DesktopDisplayInfo display, Set<Integer> desktops,
            boolean shellReady, boolean busy, boolean outputAvailable) {
        final boolean active = display != null && desktops.contains(display.id);
        final boolean enabled = display != null && shellReady && !busy;
        updateButton(mStart, active ? R.drawable.ic_eye : R.drawable.ic_play,
                active ? R.string.display_show : R.string.display_start,
                canStart(display, shellReady, busy, android.os.Build.VERSION.SDK_INT),
                () -> mActions.startDesktop(display));
        updateButton(mClose, R.drawable.ic_close, R.string.action_close_desktop,
                enabled && active, () -> mActions.closeDesktop(display));
        updateButton(mApps, R.drawable.ic_sections, R.string.section_apps, enabled,
                () -> mActions.openApplications(display));
        updateButton(mIndependent, R.drawable.ic_history, R.string.display_independent_apps, enabled,
                () -> mActions.openIndependentApplications(display));
        final boolean input = display != null && MagicDeskRuntime.inputDisplayId() == display.id;
        updateButton(mInput, R.drawable.ic_touchpad,
                input ? R.string.display_input_active : R.string.display_control, enabled && !input,
                () -> mActions.controlDisplay(display));
        updateButton(mOutput, R.drawable.ic_show_desktop, R.string.external_display_resolution,
                enabled && hasOutputControls(display, outputAvailable), () -> mActions.openOutputSettings(display));
        updateButton(mRemove, R.drawable.ic_file_delete, R.string.display_remove,
                enabled && display.canRemove(), () -> new AlertDialog.Builder(mActivity)
                        .setTitle(R.string.display_remove).setMessage(display.name + " [" + display.id + "]")
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.action_delete, (dialog, which) -> mActions.removeDisplay(display)).show());
        renderPresentationActions(display, enabled, android.os.Build.VERSION.SDK_INT);
    }

    private void renderPresentationActions(DesktopDisplayInfo display, boolean enabled, int sdk) {
        final DisplayPresentations.Session session = display == null ? null : DisplayPresentations.forOutput(display.id);
        updateButton(mShowDisplay, R.drawable.ic_eye, R.string.display_show_another,
                enabled && Arrays.stream(mDisplays).anyMatch(d -> d.id != display.id),
                () -> DisplaySourceDialog.show(mActivity, display, mDisplays));
        updateButton(mStopShowing, R.drawable.ic_close, R.string.display_stop_showing,
                enabled && session != null, () -> DisplayPresentations.detach(session));
        updateButton(mPortable, R.drawable.ic_file_new_window, R.string.display_start_portable,
                enabled && RuntimeCapabilities.supportsDesktop(sdk)
                        && DisplayPresentationMode.forSource(display) != DisplayPresentationMode.DIRECT,
                () -> mActions.startPortableDesktop(display));
    }

    private void separator(LinearLayout parent) {
        final View separator = new View(mActivity);
        separator.setBackgroundColor(DesktopUiFactory.COLOR_MUTED);
        parent.addView(separator, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private Button button(GridLayout toolbar, int icon, int label) {
        final Button button = mUi.controlAction(label, icon, DesktopUiFactory.COLOR_TEXT);
        button.setEnabled(false);
        final GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(52);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        toolbar.addView(button, params);
        return button;
    }

    private void updateButton(Button button, int icon, int label, boolean enabled, Runnable action) {
        mUi.setControlIcon(button, icon);
        button.setText(label);
        button.setContentDescription(mActivity.getString(label));
        button.setTooltipText(mActivity.getString(label));
        button.setEnabled(enabled);
        button.setOnClickListener(view -> action.run());
    }

    static DesktopDisplayInfo[] orderedDisplays(DesktopDisplayInfo[] displays) {
        final DesktopDisplayInfo[] ordered = displays.clone();
        Arrays.sort(ordered, Comparator.comparingInt((DesktopDisplayInfo display) -> display.id).reversed());
        return ordered;
    }

    static DesktopDisplayInfo newlyAvailableDisplay(DesktopDisplayInfo[] previous, DesktopDisplayInfo[] current) {
        // The initial catalog establishes a baseline, not a connection event.
        if (previous.length == 0) { return null; }
        for (final DesktopDisplayInfo display : current) {
            final boolean existed = Arrays.stream(previous).anyMatch(old -> sameDisplay(old, display));
            if (!existed) { return display; }
        }
        return null;
    }

    static boolean sameDisplay(DesktopDisplayInfo first, DesktopDisplayInfo second) {
        return first.id == second.id && java.util.Objects.equals(first.uniqueId, second.uniqueId);
    }

    static DesktopDisplayInfo selectedDisplay(DesktopDisplayInfo[] displays, String uniqueId, int hostDisplayId) {
        DesktopDisplayInfo fallback = null;
        for (final DesktopDisplayInfo display : displays) {
            if (uniqueId != null && !uniqueId.isEmpty() && uniqueId.equals(display.uniqueId)) { return display; }
            if (fallback == null || (fallback.isBuiltIn()
                    && (!display.isBuiltIn() || display.id == hostDisplayId))) { fallback = display; }
        }
        return fallback;
    }

    static boolean hasOutputControls(DesktopDisplayInfo display, boolean available) {
        return available && display != null && "wired".equals(display.source);
    }

    static boolean canConfigureOutput(DesktopDisplayInfo display, boolean available,
            PlatformProjectionDriver.ModeSelection selection, Set<Integer> desktopDisplays,
            boolean shellReady, boolean busy) {
        return hasOutputControls(display, available) && shellReady && !busy && !desktopDisplays.contains(display.id)
                && selection != null && selection.configurable
                && (selection.systemDefaultAvailable || !selection.availableModes.isEmpty());
    }

    static boolean canStart(DesktopDisplayInfo display, boolean shellReady, boolean busy, int sdk) {
        return RuntimeCapabilities.supportsDesktop(sdk) && shellReady && !busy
                && display != null && (display.canHostDesktop || display.requiresPortableDesktop);
    }

    private int dp(int value) { return mUi.dp(value); }
}
