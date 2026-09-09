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
import java.util.Collections;
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
    private final View mOutputOptions;
    private Spinner mOutputMode;
    private ArrayAdapter<String> mOutputModeAdapter;
    private List<PlatformProjectionDriver.Mode> mOutputModes = Collections.emptyList();
    private boolean mOutputModesConfigurable;
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
        parent.addView(row);
        mOutputOptions = createOutputModeView();
        parent.addView(mOutputOptions);
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

    void render(final DesktopDisplayInfo[] displays, final String selectedUniqueId,
            final int activeId, final boolean shellReady, final boolean busy,
            final boolean outputControlAvailable, final PlatformProjectionDriver.ModeSelection outputSelection) {
        final int generation = ++mRenderGeneration;
        mRendering = true;
        mDisplays = orderedDisplays(displays);
        final List<String> labels = new ArrayList<>();
        int selectedIndex = -1;
        for (int i = 0; i < mDisplays.length; i++) {
            labels.add(label(mDisplays[i]));
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
        mSelected = selectedIndex >= 0 ? mDisplays[selectedIndex] : null;
        mSelector.setSelection(selectedIndex);
        mSelector.setEnabled(shellReady && !busy);
        mOutputOptions.setVisibility(hasOutputControls(mSelected, outputControlAvailable)
                ? View.VISIBLE : View.GONE);
        renderOutputModes(outputSelection);
        mOutputMode.setEnabled(canConfigureOutput(mSelected, outputControlAvailable,
                outputSelection, activeId, shellReady, busy));
        mStart.setText(!RuntimeCapabilities.supportsDesktop(android.os.Build.VERSION.SDK_INT)
                ? R.string.desktop_android_requirement
                : mSelected != null && mSelected.id == activeId
                    ? R.string.display_show : R.string.display_start);
        mStart.setEnabled(canStart(mSelected, activeId, shellReady, busy,
                android.os.Build.VERSION.SDK_INT));
        mCreate.setEnabled(shellReady && !busy);
        mDelete.setEnabled(shellReady && !busy && mSelected != null && mSelected.owned);
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
            final PlatformProjectionDriver.ModeSelection selection, final int activeId,
            final boolean shellReady, final boolean busy) {
        return hasOutputControls(display, available) && shellReady && !busy && activeId < 0
                && selection != null && selection.configurable
                && (selection.systemDefaultAvailable || !selection.availableModes.isEmpty());
    }

    static boolean canStart(final DesktopDisplayInfo display, final int activeId,
            final boolean shellReady, final boolean busy, final int sdk) {
        return RuntimeCapabilities.supportsDesktop(sdk)
                && shellReady && !busy && display != null && display.canHostDesktop
                && (activeId < 0 || activeId == display.id);
    }

    private String label(final DesktopDisplayInfo display) {
        return display.name + " [" + display.id + "]"
                + (display.canHostDesktop ? "" : " (" + mActivity.getString(R.string.display_unavailable) + ")");
    }

    private View createOutputModeView() {
        final LinearLayout options = new LinearLayout(mActivity);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(dp(6), dp(4), dp(3), dp(4));
        final TextView label = new TextView(mActivity);
        label.setText(R.string.external_display_resolution);
        label.setTextColor(DesktopUiFactory.COLOR_TEXT);
        label.setTextSize(14);
        options.addView(label, new LinearLayout.LayoutParams(-1, -2));
        mOutputMode = new Spinner(mActivity, Spinner.MODE_DROPDOWN);
        mOutputMode.setContentDescription(mActivity.getString(R.string.external_display_resolution));
        mOutputModeAdapter = new ArrayAdapter<>(mActivity,
                android.R.layout.simple_spinner_item, new ArrayList<>());
        mOutputModeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mOutputMode.setAdapter(mOutputModeAdapter);
        mOutputMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(final AdapterView<?> parent, final View view,
                    final int position, final long id) {
                if (!mRendering && mOutputMode.isEnabled()
                        && position >= 0 && position < mOutputModes.size()) {
                    mActions.setExternalOutputTiming(mOutputModes.get(position).timingKey);
                }
            }
            @Override public void onNothingSelected(final AdapterView<?> parent) { }
        });
        options.addView(mOutputMode, new LinearLayout.LayoutParams(-1, dp(48)));
        return options;
    }

    private void renderOutputModes(final PlatformProjectionDriver.ModeSelection selection) {
        final List<PlatformProjectionDriver.Mode> modes = new ArrayList<>();
        if (selection != null && selection.systemDefaultAvailable) {
            modes.add(new PlatformProjectionDriver.Mode("",
                    mActivity.getString(R.string.external_display_system_native)));
        }
        if (selection != null) { modes.addAll(selection.availableModes); }
        final boolean configurable = selection != null && selection.configurable;
        if (mOutputModeAdapter.getCount() == 0 || !sameModes(mOutputModes, modes)
                || mOutputModesConfigurable != configurable) {
            mOutputModes = modes;
            mOutputModesConfigurable = configurable;
            mOutputModeAdapter.clear();
            if (modes.isEmpty()) {
                mOutputModeAdapter.add(mActivity.getString(R.string.external_display_no_modes));
            } else {
                for (final PlatformProjectionDriver.Mode mode : modes) {
                    mOutputModeAdapter.add(configurable ? mode.displayLabel
                            : mActivity.getString(R.string.external_display_system_mode, mode.displayLabel));
                }
            }
            mOutputModeAdapter.notifyDataSetChanged();
        }
        if (selection == null || selection.target == null) {
            mOutputMode.setSelection(0, false);
            return;
        }
        final String selectedTiming = selection.systemDefaultSelected ? "" : selection.target.timingKey;
        for (int index = 0; index < mOutputModes.size(); index++) {
            if (selectedTiming.equals(mOutputModes.get(index).timingKey)) {
                mOutputMode.setSelection(index, false);
                return;
            }
        }
    }

    private static boolean sameModes(final List<PlatformProjectionDriver.Mode> left,
            final List<PlatformProjectionDriver.Mode> right) {
        if (left.size() != right.size()) { return false; }
        for (int index = 0; index < left.size(); index++) {
            if (!left.get(index).timingKey.equals(right.get(index).timingKey)
                    || !left.get(index).displayLabel.equals(right.get(index).displayLabel)) { return false; }
        }
        return true;
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
