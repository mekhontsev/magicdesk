package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Display preparation controls; these do not acquire a desktop session. */
final class DisplaySelectionView {
    interface Actions {
        void selectDisplay(DesktopDisplayInfo display);
        void startSelectedDesktop();
        void createDisplay(VirtualDisplaySpec spec, boolean preview);
        void removeDisplay(DesktopDisplayInfo display);
        void setExternalOutputTiming(String outputTiming);
    }

    private final Activity mActivity;
    private final DesktopUiFactory mUi;
    private final Actions mActions;
    private final Spinner mSelector;
    private final Button mStart;
    private final ImageButton mCreate;
    private final ImageButton mDelete;
    private final ImageButton mCopy;
    private final TextView mCommand;
    private final ArrayAdapter<String> mLabels;
    private final Button mOutput;
    private PlatformProjectionDriver.ModeSelection mOutputSelection;
    private boolean mCanConfigureOutput;
    private AlertDialog mOutputDialog;
    private DesktopDisplayInfo[] mDisplays = new DesktopDisplayInfo[0];
    private DesktopDisplayInfo mSelected;
    private int mRenderGeneration;
    private boolean mRendering;

    DisplaySelectionView(final Activity activity, final DesktopUiFactory ui,
            final Actions actions, final LinearLayout parent) {
        mActivity = activity;
        mUi = ui;
        mActions = actions;
        final LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        mSelector = new Spinner(activity, Spinner.MODE_DROPDOWN);
        mSelector.setContentDescription(activity.getString(R.string.display_selector));
        mLabels = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item,
                new ArrayList<>());
        mLabels.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSelector.setAdapter(mLabels);
        mSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(final AdapterView<?> p, final View v,
                    final int position, final long id) {
                if (!mRendering && position >= 0 && position < mDisplays.length) {
                    mActions.selectDisplay(mDisplays[position]);
                }
            }
            @Override public void onNothingSelected(final AdapterView<?> p) { }
        });
        row.addView(mSelector, new LinearLayout.LayoutParams(0, dp(52), 1));
        mCreate = ui.menuIconButton(R.drawable.ic_add, R.string.display_create);
        mCreate.setOnClickListener(v -> showCreateDialog());
        row.addView(mCreate, new LinearLayout.LayoutParams(dp(48), dp(52)));
        mOutput = ui.controlAction(R.string.external_display_resolution,
                R.drawable.ic_show_desktop, DesktopUiFactory.COLOR_TEXT);
        mOutput.setOnClickListener(v -> showOutputModeDialog());
        mDelete = ui.menuIconButton(R.drawable.ic_file_delete,
                R.string.display_remove);
        mDelete.setOnClickListener(v -> {
            final DesktopDisplayInfo selected = mSelected;
            if (selected == null) { return; }
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.display_remove)
                    .setMessage(label(selected))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.action_delete,
                            (dialog, which) -> mActions.removeDisplay(selected))
                    .show();
        });
        row.addView(mDelete, new LinearLayout.LayoutParams(dp(48), dp(52)));
        final LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.topMargin = dp(12);
        parent.addView(row, rowParams);
        mStart = ui.actionButton(R.string.display_start, DesktopUiFactory.COLOR_CYAN);
        mStart.setTextSize(15);
        mStart.setSingleLine(false);
        mStart.setMaxLines(2);
        mStart.setOnClickListener(v -> mActions.startSelectedDesktop());
        parent.addView(mStart, new LinearLayout.LayoutParams(-1, dp(52)));

        final LinearLayout commandRow = new LinearLayout(activity);
        commandRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        mCommand = new TextView(activity);
        mCommand.setTextColor(DesktopUiFactory.COLOR_MUTED);
        mCommand.setTextSize(12);
        mCommand.setTextIsSelectable(true);
        commandRow.addView(mCommand, new LinearLayout.LayoutParams(0, -2, 1));
        mCopy = ui.menuIconButton(R.drawable.ic_file_copy,
                R.string.display_copy_command);
        mCopy.setOnClickListener(v -> {
            if (mSelected != null) {
                final AndroidClipboardGateway.OperationResult result =
                        AndroidClipboardGateway.get(activity).writeText("scrcpy",
                        DesktopDisplayCatalog.scrcpyCommand(mSelected), false);
                if (!result.successful) {
                    android.widget.Toast.makeText(activity, result.error,
                            android.widget.Toast.LENGTH_LONG).show();
                }
            }
        });
        commandRow.addView(mCopy, new LinearLayout.LayoutParams(dp(48), dp(48)));
        parent.addView(commandRow);
    }

    Button outputControl() { return mOutput; }

    void render(final DesktopDisplayInfo[] displays, final String selectedUniqueId,
            final java.util.Set<Integer> desktopDisplays, final boolean shellReady, final boolean busy,
            final boolean outputControlAvailable, final PlatformProjectionDriver.ModeSelection outputSelection) {
        final int generation = ++mRenderGeneration;
        mRendering = true;
        mDisplays = orderedDisplays(displays);
        final List<String> labels = new ArrayList<>();
        int selectedIndex = -1;
        for (int i = 0; i < mDisplays.length; i++) {
            labels.add(label(mDisplays[i]) + (desktopDisplays.contains(mDisplays[i].id)
                    ? " - " + mActivity.getString(R.string.display_desktop_active) : ""));
            if (mDisplays[i].uniqueId.equals(selectedUniqueId)) {
                selectedIndex = i;
            }
        }
        boolean changed = labels.size() != mLabels.getCount();
        for (int i = 0; !changed && i < labels.size(); i++) {
            changed = !labels.get(i).equals(mLabels.getItem(i));
        }
        if (changed) {
            mLabels.clear();
            mLabels.addAll(labels);
        }
        final DesktopDisplayInfo previousDisplay = mSelected;
        mSelected = selectedIndex >= 0 ? mDisplays[selectedIndex] : null;
        mSelector.setSelection(selectedIndex);
        mSelector.setEnabled(shellReady && !busy);
        mOutput.setEnabled(hasOutputControls(mSelected, outputControlAvailable)
                && shellReady && !busy && outputSelection != null);
        final boolean canConfigure = canConfigureOutput(mSelected, outputControlAvailable,
                outputSelection, desktopDisplays, shellReady, busy);
        // A dialog describes one display/mode snapshot, never a replacement output.
        if (mOutputDialog != null && (!mOutput.isEnabled() || previousDisplay == null
                || mSelected == null || !previousDisplay.uniqueId.equals(mSelected.uniqueId)
                || mOutputSelection != outputSelection || mCanConfigureOutput != canConfigure)) {
            mOutputDialog.dismiss();
        }
        mOutputSelection = outputSelection;
        mCanConfigureOutput = canConfigure;
        mStart.setText(!RuntimeCapabilities.supportsDesktop(android.os.Build.VERSION.SDK_INT)
                ? R.string.desktop_android_requirement
                : mSelected != null && desktopDisplays.contains(mSelected.id)
                    ? R.string.display_show : R.string.display_start);
        mStart.setEnabled(canStart(mSelected, shellReady, busy,
                android.os.Build.VERSION.SDK_INT));
        mCreate.setEnabled(shellReady && !busy);
        mDelete.setEnabled(shellReady && !busy && mSelected != null && mSelected.canRemove());
        final boolean remote = mSelected != null
                && ("virtual".equals(mSelected.source) || "overlay".equals(mSelected.source));
        mCommand.setText(remote ? DesktopDisplayCatalog.scrcpyCommand(mSelected) : "");
        ((View) mCommand.getParent()).setVisibility(remote ? View.VISIBLE : View.GONE);
        mCopy.setEnabled(remote);
        // Spinner callbacks may be posted after setSelection; rendering is not a user choice.
        mSelector.post(() -> {
            if (generation == mRenderGeneration) { mRendering = false; }
        });
    }

    static DesktopDisplayInfo[] orderedDisplays(final DesktopDisplayInfo[] displays) {
        final DesktopDisplayInfo[] ordered = displays.clone();
        Arrays.sort(ordered, Comparator.comparingInt((DesktopDisplayInfo display) -> display.id).reversed());
        return ordered;
    }

    static boolean hasOutputControls(final DesktopDisplayInfo display, final boolean available) {
        return available && display != null && "wired".equals(display.source);
    }

    static boolean canConfigureOutput(final DesktopDisplayInfo display, final boolean available,
            final PlatformProjectionDriver.ModeSelection selection, final java.util.Set<Integer> desktopDisplays,
            final boolean shellReady, final boolean busy) {
        return hasOutputControls(display, available) && shellReady && !busy && !desktopDisplays.contains(display.id)
                && selection != null && selection.configurable
                && (selection.systemDefaultAvailable || !selection.availableModes.isEmpty());
    }

    static boolean canStart(final DesktopDisplayInfo display,
            final boolean shellReady, final boolean busy, final int sdk) {
        return RuntimeCapabilities.supportsDesktop(sdk)
                && shellReady && !busy && display != null && display.canHostDesktop;
    }

    private String label(final DesktopDisplayInfo display) {
        return display.name + " [" + display.id + "]"
                + (display.canHostDesktop ? "" : " (" + mActivity.getString(R.string.display_unavailable) + ")");
    }

    private void showOutputModeDialog() {
        final PlatformProjectionDriver.ModeSelection selection = mOutputSelection;
        if (!mOutput.isEnabled() || selection == null || mOutputDialog != null) { return; }
        final LinearLayout options = new LinearLayout(mActivity);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(dp(24), dp(8), dp(24), dp(8));
        final TextView current = new TextView(mActivity);
        current.setText(mActivity.getString(R.string.external_display_current_mode,
                selection.current == null ? mActivity.getString(R.string.state_unavailable)
                        : selection.current.displayLabel));
        current.setTextSize(14);
        options.addView(current);
        final List<PlatformProjectionDriver.Mode> modes = new ArrayList<>();
        if (selection.systemDefaultAvailable) {
            modes.add(new PlatformProjectionDriver.Mode("",
                    mActivity.getString(R.string.external_display_system_native)));
        }
        modes.addAll(selection.availableModes);
        final String[] labels = modes.isEmpty()
                ? new String[] {mActivity.getString(R.string.external_display_no_modes)}
                : modes.stream().map(mode -> mode.displayLabel).toArray(String[]::new);
        final Spinner mode = spinner(options, labels);
        mode.setContentDescription(mActivity.getString(R.string.external_display_resolution));
        mode.setSelection(outputModeIndex(selection, modes));
        mode.setEnabled(mCanConfigureOutput);
        final AlertDialog.Builder builder = new AlertDialog.Builder(mActivity)
                .setTitle(R.string.external_display_resolution).setView(options)
                .setNegativeButton(android.R.string.cancel, null);
        if (mCanConfigureOutput) {
            builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
                final int index = mode.getSelectedItemPosition();
                if (mCanConfigureOutput && mOutputSelection == selection
                        && index >= 0 && index < modes.size()) {
                    mActions.setExternalOutputTiming(modes.get(index).timingKey);
                }
            });
        }
        mOutputDialog = builder.create();
        mOutputDialog.setOnDismissListener(dialog -> mOutputDialog = null);
        mOutputDialog.show();
    }

    static int outputModeIndex(final PlatformProjectionDriver.ModeSelection selection,
            final List<PlatformProjectionDriver.Mode> modes) {
        final String timing = selection.systemDefaultSelected ? ""
                : selection.target == null ? null : selection.target.timingKey;
        for (int i = 0; i < modes.size(); i++) {
            if (modes.get(i).timingKey.equals(timing)) { return i; }
        }
        return -1;
    }

    private void showCreateDialog() {
        final VirtualDisplaySpec defaults = VirtualDisplayPreferences.load(mActivity);
        final LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(8));
        final Spinner kind = spinner(content, new String[] {
                mActivity.getString(R.string.display_virtual),
                mActivity.getString(R.string.display_preview)});
        kind.setContentDescription(mActivity.getString(R.string.display_type));
        final int[][] sizes = {{1920, 1080}, {1280, 720}, {2560, 1440}, {2560, 1080}, {3840, 2160}};
        final Spinner preset = spinner(content, new String[] {
                "1920 x 1080", "1280 x 720", "2560 x 1440", "2560 x 1080", "3840 x 2160",
                mActivity.getString(R.string.display_custom)});
        preset.setContentDescription(mActivity.getString(R.string.display_resolution));
        final EditText width = number(content, R.string.display_width, defaults.width);
        final EditText height = number(content, R.string.display_height, defaults.height);
        final EditText scale = number(content, R.string.display_scale, defaults.densityDpi * 100 / 160);
        int selected = sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            if (sizes[i][0] == defaults.width && sizes[i][1] == defaults.height) { selected = i; }
        }
        preset.setSelection(selected);
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
        dialog.setOnShowListener(unused -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    try {
                        final int percent = Integer.parseInt(scale.getText().toString());
                        if (percent < 50 || percent > 400) { throw new IllegalArgumentException(); }
                        final VirtualDisplaySpec spec = new VirtualDisplaySpec(
                                Integer.parseInt(width.getText().toString()),
                                Integer.parseInt(height.getText().toString()), percent * 160 / 100);
                        final boolean preview = kind.getSelectedItemPosition() == 1;
                        if (preview) { spec.requireOverlayCompatible(); }
                        mActions.createDisplay(spec, preview);
                        dialog.dismiss();
                    } catch (IllegalArgumentException error) {
                        width.setError(mActivity.getString(R.string.display_invalid_parameters));
                    }
                }));
        dialog.show();
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
