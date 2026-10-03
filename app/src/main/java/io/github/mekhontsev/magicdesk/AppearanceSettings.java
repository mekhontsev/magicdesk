package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.view.View;
import android.widget.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Consumer;

/** UI and SAF adapters to the same validated appearance document used by automation. */
final class AppearanceSettings implements AutoCloseable {
    private static final int IMPORT = 6201, EXPORT = 6202, SCHEMA = 6203, IMPORT_BUNDLE = 6204, EXPORT_BUNDLE = 6205;
    private final Activity mActivity;
    private final DesktopUiFactory mUi;
    private final Consumer<DesktopSystemThemeSession.Preference> mSetSystemTheme;
    private DesktopSystemThemeSession.Preference mSystemTheme = DesktopSystemThemeSession.Preference.UNCHANGED;
    private boolean mSystemThemeAvailable;
    private Spinner mSystemThemeChoice;
    private final ExecutorService mFiles = Executors.newSingleThreadExecutor();
    private final List<Runnable> mRefreshers = new ArrayList<>();
    private final List<AlertDialog> mChildren = new ArrayList<>();
    private final Runnable mChanged = this::refresh;
    private View mPage;
    private AlertDialog mPreviewDialog;
    private boolean mRendering;
    private volatile boolean mClosed;
    private String mPanelId;
    private String mWorkspaceKey;
    private volatile int mGeneration;
    private record EditTarget(String workspaceKey, long revision, int generation) {}
    private record FileRequest(int code, EditTarget target, ShellAppearance appearance, String document) {}
    private FileRequest mPendingFile;
    private boolean mFileBusy;
    private volatile Thread mFileThread;
    private android.os.CancellationSignal mFileCancellation;

    AppearanceSettings(Activity activity, Consumer<DesktopSystemThemeSession.Preference> setSystemTheme) {
        mActivity = activity; mUi = new DesktopUiFactory(activity); mSetSystemTheme = setSystemTheme;
    }

    View createPage(Runnable back) {
        if (mClosed) throw new IllegalStateException("Appearance settings are closed");
        if (mPage != null) return mPage;
        final LinearLayout page = new LinearLayout(mActivity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(mUi.dp(16), mUi.dp(8), mUi.dp(16), mUi.dp(8));
        systemThemeControls(page);
        final String workspace = AppearanceScopeBindings.find(mActivity);
        choice(page, R.string.appearance_scope, workspace == null ? new int[] {R.string.appearance_global}
                        : new int[] {R.string.appearance_global, R.string.appearance_workspace},
                () -> mWorkspaceKey == null ? 0 : 1, index -> {
                    dismissChildren(); invalidateFiles(); mWorkspaceKey = index == 0 ? null : workspace;
                    mPanelId = null; refresh();
                });
        final Button inherit = mUi.menuItem(R.string.appearance_inherit, UiColor.TEXT);
        inherit.setOnClickListener(v -> reset());
        mRefreshers.add(() -> inherit.setVisibility(mWorkspaceKey == null ? View.GONE : View.VISIBLE));
        page.addView(inherit);
        final Button themes = mUi.menuItem(R.string.appearance_choose_theme, UiColor.TEXT);
        themes.setOnClickListener(v -> chooseTheme());
        page.addView(themes);
        heading(page, R.string.appearance_colors);
        final LinearLayout presets = new LinearLayout(mActivity);
        final String[] ids = {"dark", "light", "contrast"};
        final int[] names = {R.string.appearance_dark, R.string.appearance_light, R.string.appearance_contrast};
        for (int i = 0; i < ids.length; i++) {
            final String id = ids[i];
            final Button button = mUi.menuItem(names[i], UiColor.TEXT);
            button.setGravity(android.view.Gravity.CENTER);
            button.setBackground(mUi.flatButtonBackground(mUi.dp(4)));
            mRefreshers.add(() -> {
                var current = current();
                button.setSelected(current.equals(current.withStyle(ShellAppearance.preset(id))));
            });
            button.setOnClickListener(v -> apply(current().withStyle(ShellAppearance.preset(id))));
            presets.addView(button, new LinearLayout.LayoutParams(0, mUi.dp(48), 1));
        }
        page.addView(presets);
        choice(page, R.string.appearance_palette_source, new int[] {R.string.appearance_palette_fixed, R.string.appearance_palette_system},
                () -> current().palette().source().ordinal(), value -> {
                    var t = current(); apply(t.withPalette(t.palette().withSource(ShellAppearance.ColorSource.values()[value], t.palette().mode())));
                });
        Spinner paletteMode = choice(page, R.string.appearance_palette_mode, new int[] {R.string.appearance_light, R.string.appearance_dark, R.string.appearance_palette_follow},
                () -> current().palette().mode().ordinal(), value -> {
                    var t = current(); apply(t.withPalette(t.palette().withSource(t.palette().source(), ShellAppearance.ColorMode.values()[value])));
                });
        mRefreshers.add(() -> paletteMode.setEnabled(current().palette().source() == ShellAppearance.ColorSource.SYSTEM));
        final LinearLayout swatches = new LinearLayout(mActivity);
        for (UiColor role : UiColor.values()) {
            if (role == UiColor.TRANSPARENT) continue;
            final View swatch = new View(mActivity);
            swatch.setBackground(mUi.rounded(role, mUi.dp(2), UiColor.MUTED));
            mRefreshers.add(() -> {
                var palette = SystemAppearancePalette.resolve(current()).palette();
                var paint = (android.graphics.drawable.GradientDrawable) swatch.getBackground();
                paint.setColor(palette.color(role));
                paint.setStroke(mUi.dp(1), palette.color(UiColor.OUTLINE));
            });
            swatch.setContentDescription(mActivity.getString(colorLabel(role)));
            swatch.setTooltipText(swatch.getContentDescription());
            swatch.setFocusable(true);
            swatch.setOnClickListener(v -> editColor(role));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(mUi.dp(44), mUi.dp(44));
            p.setMargins(mUi.dp(2), mUi.dp(4), mUi.dp(2), mUi.dp(4));
            swatches.addView(swatch, p);
        }
        final android.widget.HorizontalScrollView colorScroll = new android.widget.HorizontalScrollView(mActivity);
        colorScroll.addView(swatches);
        page.addView(colorScroll);
        choice(page, R.string.appearance_font, new int[] {R.string.appearance_sans, R.string.appearance_serif, R.string.appearance_mono},
                () -> current().typography().font().ordinal(), index -> {
                    var t = current();
                    apply(t.withTypography(new ShellAppearance.Typography(
                            ShellAppearance.Font.values()[index], t.typography().scale())));
                });
        slider(page, R.string.appearance_text_scale, 80, 130,
                () -> Math.round(current().typography().scale() * 100), value -> {
                    var t = current();
                    apply(t.withTypography(new ShellAppearance.Typography(
                            t.typography().font(), value / 100f)));
                });
        slider(page, R.string.appearance_border, 0, 3, () -> Math.round(current().shape().borderDp()), value -> {
            var t = current();
            apply(t.withShape(new ShellAppearance.Shape(t.shape().radiusScale(), value)));
        });
        slider(page, R.string.appearance_rounding, 0, 200, () -> Math.round(current().shape().radiusScale() * 100), value -> {
            var t = current();
            apply(t.withShape(new ShellAppearance.Shape(value / 100f, t.shape().borderDp())));
        });
        heading(page, R.string.appearance_backdrop);
        slider(page, R.string.appearance_opacity, 15, 100,
                () -> Math.round(current().backdrop().opacity() * 100), value -> {
                    var t = current();
                    apply(t.withBackdrop(new ShellAppearance.Backdrop(value / 100f, t.backdrop().blurRadiusDp())));
                });
        slider(page, R.string.appearance_blur_radius, 0, 64, () -> current().backdrop().blurRadiusDp(), value -> {
            var t = current();
            apply(t.withBackdrop(new ShellAppearance.Backdrop(t.backdrop().opacity(), value)));
        });
        heading(page, R.string.appearance_panels);
        panelSelector(page);
        choice(page, R.string.appearance_edge, new int[] {R.string.appearance_top, R.string.appearance_bottom,
                        R.string.appearance_left, R.string.appearance_right},
                () -> panel().edge().ordinal(), value -> replacePanel(new ShellPanel(panel().id(),
                        ShellPanel.Edge.values()[value], panel().style(), panel().components())));
        choice(page, R.string.appearance_length, new int[] {R.string.appearance_fill, R.string.appearance_content},
                () -> panel().style().length().ordinal(), value -> changeBar("length", ShellAppearance.Width.values()[value].name().toLowerCase(Locale.ROOT)));
        choice(page, R.string.appearance_alignment, new int[] {R.string.appearance_align_start, R.string.appearance_center, R.string.appearance_align_end},
                () -> panel().style().alignment().ordinal(), value -> changeBar("alignment", ShellAppearance.Alignment.values()[value].name().toLowerCase(Locale.ROOT)));
        slider(page, R.string.appearance_max_length, 64, 4096, () -> panel().style().maxLengthDp(), v -> changeBar("maxLengthDp", v));
        slider(page, R.string.appearance_side_gap, 0, 96, () -> panel().style().sideGapDp(), v -> changeBar("sideGapDp", v));
        slider(page, R.string.appearance_edge_gap, 0, 96, () -> panel().style().edgeGapDp(), v -> changeBar("edgeGapDp", v));
        final Switch automatic = new Switch(mActivity);
        automatic.setText(R.string.appearance_auto_thickness); UiAppearance.text(automatic, UiColor.TEXT);
        mRefreshers.add(() -> automatic.setChecked(panel().style().thicknessDp() == 0));
        automatic.setOnCheckedChangeListener((v, checked) -> { if (!mRendering) changeBar("thicknessDp", checked ? 0 : 64); });
        page.addView(automatic);
        slider(page, R.string.appearance_thickness, 40, 160,
                () -> panel().style().thicknessDp() == 0 ? 64 : panel().style().thicknessDp(), v -> changeBar("thicknessDp", v));
        slider(page, R.string.appearance_padding, 0, 16, () -> panel().style().paddingDp(), v -> changeBar("paddingDp", v));
        slider(page, R.string.appearance_radius, 0, 32, () -> panel().style().radiusDp(), v -> changeBar("radiusDp", v));
        slider(page, R.string.appearance_dock_scale, 100, 200, () -> Math.round(panel().style().hover().scale() * 100),
                v -> changeHover("scale", v / 100f));
        slider(page, R.string.appearance_dock_lift, 0, 32, () -> panel().style().hover().liftDp(), v -> changeHover("liftDp", v));
        slider(page, R.string.appearance_dock_radius, 50, 300, () -> Math.round(panel().style().hover().radius() * 100),
                v -> changeHover("radius", v / 100f));
        panelBackdropControls(page);
        final Switch reserve = new Switch(mActivity);
        UiAppearance.button(reserve, UiColor.ACCENT);
        reserve.setText(R.string.appearance_panel_reserve);
        UiAppearance.text(reserve, UiColor.TEXT);
        reserve.setPadding(0, mUi.dp(8), 0, mUi.dp(8));
        mRefreshers.add(() -> reserve.setChecked(panel().style().reserveSpace()));
        reserve.setOnCheckedChangeListener((v, checked) -> { if (!mRendering) changeBar("reserveSpace", checked); });
        page.addView(reserve);
        heading(page, R.string.appearance_composition);
        Button composition = mUi.menuItem(R.string.appearance_panel_components, UiColor.TEXT);
        composition.setOnClickListener(v -> editComponents());
        page.addView(composition);
        choice(page, R.string.appearance_start_presentation, new int[] {R.string.appearance_grid, R.string.appearance_list},
                () -> current().composition().start().presentation().ordinal(),
                v -> change("composition.start", "presentation", v == 0 ? "grid" : "list"));
        choice(page, R.string.appearance_start_navigation, new int[] {R.string.appearance_scroll, R.string.appearance_pages},
                () -> current().composition().start().navigation().ordinal(),
                v -> change("composition.start", "navigation", v == 0 ? "scroll" : "pages"));
        slider(page, R.string.appearance_tile_width, 80, 200, () -> current().composition().start().tileWidthDp(),
                v -> change("composition.start", "tileWidthDp", v));
        slider(page, R.string.appearance_icon_size, 24, 64, () -> current().composition().start().iconSizeDp(),
                v -> change("composition.start", "iconSizeDp", v));
        slider(page, R.string.appearance_entry_gap, 0, 24, () -> current().composition().start().gapDp(),
                v -> change("composition.start", "gapDp", v));
        heading(page, R.string.appearance_motion);
        CheckBox wallpaper = new CheckBox(mActivity);
        wallpaper.setText(R.string.appearance_animate_wallpaper); UiAppearance.text(wallpaper, UiColor.TEXT);
        mRefreshers.add(() -> wallpaper.setChecked(current().motion().wallpaper()));
        wallpaper.setOnCheckedChangeListener((v, checked) -> { if (!mRendering) change("motion", "wallpaper", checked); });
        page.addView(wallpaper);
        final Switch reduced = new Switch(mActivity);
        reduced.setText(R.string.appearance_reduced_motion); UiAppearance.text(reduced, UiColor.TEXT);
        mRefreshers.add(() -> reduced.setChecked(current().motion().reduced()));
        reduced.setOnCheckedChangeListener((v, checked) -> { if (!mRendering) change("motion", "reduced", checked); });
        page.addView(reduced);
        int[] effects = {R.string.appearance_none, R.string.appearance_fade, R.string.appearance_slide,
                R.string.appearance_scale, R.string.appearance_slide_scale};
        choice(page, R.string.appearance_panel_effect, effects, () -> current().motion().panels().ordinal(),
                v -> change("motion", "panels", ShellMotion.Effect.values()[v].name().toLowerCase(java.util.Locale.ROOT)));
        choice(page, R.string.appearance_taskbar_effect, effects, () -> current().motion().taskbar().ordinal(),
                v -> change("motion", "taskbar", ShellMotion.Effect.values()[v].name().toLowerCase(java.util.Locale.ROOT)));
        slider(page, R.string.appearance_motion_distance, 0, 32, () -> current().motion().distanceDp(), v -> change("motion", "distanceDp", v));
        slider(page, R.string.appearance_motion_scale, 85, 100, () -> Math.round(current().motion().scaleFrom() * 100),
                v -> change("motion", "scaleFrom", v / 100f));
        slider(page, R.string.appearance_duration, 0, 400, () -> current().motion().durationMs(), v -> change("motion", "durationMs", v));
        choice(page, R.string.appearance_motion_curve,
                new int[] {R.string.appearance_linear, R.string.appearance_ease_out, R.string.appearance_smooth},
                () -> current().motion().curve().ordinal(),
                v -> change("motion", "curve", ShellMotion.Curve.values()[v].name().toLowerCase(java.util.Locale.ROOT)));
        slider(page, R.string.appearance_feedback_duration, 0, 250, () -> current().motion().feedbackMs(), v -> change("motion", "feedbackMs", v));
        final LinearLayout files = new LinearLayout(mActivity);
        addCommand(files, R.string.appearance_import, R.drawable.ic_folder_open, this::importDocument);
        addCommand(files, R.string.appearance_export, R.drawable.ic_arrow_down, this::exportDocument);
        addCommand(files, R.string.appearance_reset, R.drawable.ic_file_refresh, this::reset);
        page.addView(files);
        final LinearLayout document = new LinearLayout(mActivity);
        addCommand(document, R.string.appearance_edit_document, R.drawable.ic_file_rename, this::editDocument);
        addCommand(document, R.string.appearance_export_schema, R.drawable.ic_file_properties, () -> chooseFile(SCHEMA));
        page.addView(document);
        heading(page, R.string.appearance_resources);
        final LinearLayout bundles = new LinearLayout(mActivity);
        addCommand(bundles, R.string.appearance_import_bundle, R.drawable.ic_folder_open, () -> chooseFile(IMPORT_BUNDLE));
        addCommand(bundles, R.string.appearance_export_bundle, R.drawable.ic_arrow_down, () -> chooseFile(EXPORT_BUNDLE));
        addCommand(bundles, R.string.appearance_prune, R.drawable.ic_file_delete, this::pruneBundles);
        page.addView(bundles);
        final ScrollView scroll = UiToolLayout.scroll(page, 640);
        final LinearLayout root = UiToolLayout.page(mActivity, UiColor.PANEL, true);
        final LinearLayout header = new LinearLayout(mActivity);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final ImageButton previous = mUi.taskbarIconButton(R.drawable.ic_file_back, R.string.action_back, false);
        previous.setOnClickListener(v -> back.run());
        header.addView(previous, new LinearLayout.LayoutParams(mUi.dp(48), mUi.dp(48)));
        final TextView title = mUi.sectionTitle(R.string.appearance_title);
        title.setTextSize(18);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(new UiContentColumn(header, 640));
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        mPage = root;
        AppearanceStore.listen(mChanged);
        refresh();
        return mPage;
    }

    boolean isOpen() { return mPage != null; }

    private void systemThemeControls(LinearLayout page) {
        mSystemThemeChoice = choice(page, R.string.settings_system_theme,
                new int[] {R.string.settings_system_theme_unchanged, R.string.settings_system_theme_light,
                        R.string.settings_system_theme_dark},
                () -> mSystemTheme.ordinal(), index -> {
                    if (mSystemThemeAvailable) mSetSystemTheme.accept(DesktopSystemThemeSession.Preference.values()[index]);
                });
        mSystemThemeChoice.setEnabled(mSystemThemeAvailable);
        final TextView summary = label(page, R.string.settings_system_theme_summary);
        UiAppearance.text(summary, UiColor.MUTED);
        summary.setTextSize(12);
        summary.setPadding(0, 0, 0, mUi.dp(16));
    }

    void renderSystemTheme(DesktopSystemThemeSession.Preference theme, boolean available) {
        mSystemTheme = theme;
        mSystemThemeAvailable = available;
        if (mSystemThemeChoice != null) {
            mSystemThemeChoice.setSelection(theme.ordinal());
            mSystemThemeChoice.setEnabled(available);
        }
    }

    void dismissPage() {
        invalidateFiles();
        dismissChildren();
        AppearanceStore.unlisten(mChanged);
        mRefreshers.clear();
        mPage = null;
        mSystemThemeChoice = null;
    }

    private static int colorLabel(UiColor role) {
        return switch (role) {
            case BACKGROUND -> R.string.appearance_color_background;
            case PANEL -> R.string.appearance_color_panel;
            case SURFACE -> R.string.appearance_color_surface;
            case TEXT -> R.string.appearance_color_text;
            case MUTED -> R.string.appearance_color_muted;
            case ACCENT -> R.string.appearance_color_accent;
            case DANGER -> R.string.appearance_color_danger;
            case ATTENTION -> R.string.appearance_color_attention;
            case HOVER -> R.string.appearance_color_hover;
            case DESKTOP_TEXT -> R.string.appearance_color_desktop_text;
            case TRANSPARENT -> R.string.appearance_color_transparent;
            case SURFACE_LOW -> R.string.appearance_color_surface_low;
            case SURFACE_HIGH -> R.string.appearance_color_surface_high;
            case ON_ACCENT -> R.string.appearance_color_on_accent;
            case ACCENT_CONTAINER -> R.string.appearance_color_accent_container;
            case ON_ACCENT_CONTAINER -> R.string.appearance_color_on_accent_container;
            case OUTLINE -> R.string.appearance_color_outline;
        };
    }

    private void editColor(UiColor role) {
        final EditTarget target = target();
        final EditText text = new EditText(mActivity);
        text.setSingleLine(true);
        text.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(7)});
        text.setText(String.format(Locale.ROOT, "#%06X", SystemAppearancePalette.resolve(current()).palette().color(role) & 0xffffff));
        UiAppearance.text(text, UiColor.TEXT);
        final AlertDialog dialog = UiDialogs.themedBuilder(mActivity).setTitle(colorLabel(role)).setView(text)
                .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!isCurrent(target)) { text.setError(mActivity.getString(R.string.appearance_changed)); return; }
            if (!text.getText().toString().matches("#[0-9a-fA-F]{6}")) { text.setError("#RRGGBB"); return; }
            var t = current();
            apply(t.withPalette(t.palette().withColor(role, android.graphics.Color.parseColor(text.getText().toString()))));
            dialog.dismiss();
        }));
        showChild(dialog);
    }

    private void changeBar(String key, Object value) {
        try {
            var json = ShellAppearanceJson.encode(current());
            var panels = json.getJSONObject("composition").getJSONArray("panels");
            for (int i = 0; i < panels.length(); i++) {
                var item = panels.getJSONObject(i);
                if (item.getString("id").equals(panel().id())) item.getJSONObject("style").put(key, value);
            }
            apply(ShellAppearanceJson.parse(json.toString()));
        } catch (org.json.JSONException error) { throw new IllegalArgumentException(error); }
    }

    private void changeHover(String key, Object value) {
        try {
            var hover = panel().style().hover();
            changeBar("hover", new org.json.JSONObject().put("scale", hover.scale()).put("liftDp", hover.liftDp())
                    .put("radius", hover.radius()).put(key, value));
        } catch (org.json.JSONException error) { throw new IllegalArgumentException(error); }
    }

    private void change(String section, String key, Object value) {
        try {
            var json = ShellAppearanceJson.encode(current());
            var target = json;
            for (String part : section.split("\\.")) target = target.getJSONObject(part);
            target.put(key, value);
            apply(ShellAppearanceJson.parse(json.toString()));
        } catch (org.json.JSONException error) { throw new IllegalArgumentException(error); }
    }

    private void editDocument() {
        final EditTarget target = target();
        final EditText text = new EditText(mActivity);
        text.setTypeface(android.graphics.Typeface.MONOSPACE); text.setTextSize(12);
        text.setGravity(android.view.Gravity.TOP);
        text.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        text.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(ShellAppearanceJson.MAX_BYTES)});
        try { text.setText((mWorkspaceKey == null ? ShellAppearanceJson.encode(current())
                : new org.json.JSONObject(AppearanceStore.snapshot(mWorkspaceKey).patch())).toString(2)); }
        catch (org.json.JSONException error) { throw new IllegalArgumentException(error); }
        UiAppearance.text(text, UiColor.TEXT);
        final ScrollView scroll = new ScrollView(mActivity); scroll.addView(text);
        final AlertDialog editor = UiDialogs.themedBuilder(mActivity).setTitle(R.string.appearance_edit_document).setView(scroll)
                .setPositiveButton(R.string.appearance_preview, null).setNegativeButton(android.R.string.cancel, null).create();
        editor.setOnShowListener(d -> editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { preparePreview(text.getText().toString(), target, editor); }
            catch (Exception error) { text.setError(error.getMessage()); }
        }));
        showChild(editor);
    }

    private void preview(ShellAppearance value, EditTarget target) {
        try {
            String document = mWorkspaceKey == null ? ShellAppearanceJson.encode(value).toString()
                    : changedPatch(AppearanceStore.snapshot(mWorkspaceKey).patch(), current(), value);
            previewDocument(document, target);
        } catch (org.json.JSONException error) { throw new IllegalArgumentException(error); }
    }

    private void previewDocument(String document, EditTarget target) {
        if (mPreviewDialog != null) throw new IllegalStateException(mActivity.getString(R.string.appearance_preview));
        final String scope = target.workspaceKey();
        final String id;
        requireCurrent(target);
        try { id = scope == null ? AppearanceStore.preview(ShellAppearanceJson.parse(document), target.revision())
                : AppearanceStore.preview(scope, document, target.revision()); }
        catch (org.json.JSONException error) { throw new IllegalArgumentException(error.getMessage(), error); }
        mPreviewDialog = UiDialogs.themedBuilder(mActivity).setTitle(R.string.appearance_preview)
                .setPositiveButton(R.string.appearance_keep, (d, which) -> {
                    try {
                        if (id.equals(previewId(scope))) {
                            if (scope == null) AppearanceStore.confirm(id); else AppearanceStore.confirm(scope, id);
                        }
                    } catch (Exception error) { showError(error); }
                }).setNegativeButton(android.R.string.cancel, null).create();
        mPreviewDialog.setOnDismissListener(d -> {
            try {
                if (id.equals(previewId(scope))) {
                    if (scope == null) AppearanceStore.cancel(id); else AppearanceStore.cancel(scope, id);
                }
            } catch (Exception error) { showError(error); }
            mPreviewDialog = null;
        });
        mPreviewDialog.show();
    }

    private void chooseTheme() {
        final EditTarget target = target();
        String[] names = ShellThemes.ENTRIES.stream().map(entry -> mActivity.getString(entry.title())).toArray(String[]::new);
        final AlertDialog dialog = UiDialogs.themedBuilder(mActivity).setTitle(R.string.appearance_choose_theme)
                .setSingleChoiceItems(names, -1, null)
                .setPositiveButton(R.string.appearance_preview, null).setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnShowListener(d -> {
            Button preview = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            preview.setEnabled(false);
            dialog.getListView().setOnItemClickListener((parent, view, position, id) -> preview.setEnabled(true));
            preview.setOnClickListener(v -> {
                int selected = dialog.getListView().getCheckedItemPosition();
                if (selected < 0) return;
                try {
                    preparePreview(() -> ShellAppearanceJson.encode(ShellThemes.load(
                            ShellThemes.ENTRIES.get(selected).id(), mActivity.getAssets()::open)).toString(), target, dialog);
                } catch (Exception error) { showError(error); }
            });
        });
        showChild(dialog);
    }

    private interface DocumentSource { String read() throws Exception; }

    private void preparePreview(String document, EditTarget target, AlertDialog editor) {
        preparePreview(() -> document, target, editor);
    }

    private void preparePreview(DocumentSource source, EditTarget target, AlertDialog editor) {
        requireCurrent(target);
        if (mFileBusy || mPendingFile != null) throw new IllegalStateException(mActivity.getString(R.string.appearance_file_busy));
        mFileBusy = true;
        final var cancellation = new android.os.CancellationSignal();
        mFileCancellation = cancellation;
        mFiles.execute(() -> {
            mFileThread = Thread.currentThread();
            try {
                cancellation.throwIfCanceled();
                String document = source.read();
                prepareDocument(document, target.workspaceKey());
                cancellation.throwIfCanceled();
                mActivity.runOnUiThread(() -> {
                    if (!editor.isShowing()) return;
                    try { previewDocument(document, target); editor.dismiss(); }
                    catch (Exception error) { showError(error); }
                });
            } catch (Exception error) {
                if (!cancellation.isCanceled()) mActivity.runOnUiThread(() -> showError(error));
            } finally { finishFileTask(cancellation); }
        });
    }

    private static void prepareDocument(String document, String scope) throws org.json.JSONException {
        if (scope == null) AppearanceStore.prepareAssets(ShellAppearanceJson.parse(document));
        else AppearanceStore.prepareAssets(scope, document);
    }

    private void finishFileTask(android.os.CancellationSignal cancellation) {
        mFileThread = null;
        mActivity.runOnUiThread(() -> {
            if (mFileCancellation == cancellation) { mFileCancellation = null; mFileBusy = false; }
        });
    }

    private void pruneBundles() {
        var dialog = UiDialogs.themedBuilder(mActivity).setTitle(R.string.appearance_prune)
                .setPositiveButton(R.string.action_delete, (d, which) -> {
                    if (mFileBusy || mPendingFile != null) {
                        Toast.makeText(mActivity, R.string.appearance_file_busy, Toast.LENGTH_SHORT).show(); return;
                    }
                    mFileBusy = true;
                    var cancellation = new android.os.CancellationSignal(); mFileCancellation = cancellation;
                    mFiles.execute(() -> {
                        mFileThread = Thread.currentThread();
                        try {
                            cancellation.throwIfCanceled();
                            int removed = AppearanceStore.pruneUnusedBundles().size();
                            mActivity.runOnUiThread(() -> {
                                if (!mClosed && mPage != null && !cancellation.isCanceled()) Toast.makeText(mActivity,
                                        mActivity.getString(R.string.appearance_pruned, removed), Toast.LENGTH_SHORT).show();
                            });
                        } catch (Exception error) {
                            if (!cancellation.isCanceled()) mActivity.runOnUiThread(() -> showError(error));
                        } finally { finishFileTask(cancellation); }
                    });
                }).setNegativeButton(android.R.string.cancel, null).create();
        showChild(dialog);
    }

    private ShellPanel panel() {
        var panels = current().composition().panels();
        for (var panel : panels) if (panel.id().equals(mPanelId)) return panel;
        mPanelId = panels.get(0).id();
        return panels.get(0);
    }

    private void panelBackdropControls(LinearLayout page) {
        final CheckBox inherit = new CheckBox(mActivity);
        inherit.setText(R.string.appearance_inherit_backdrop);
        UiAppearance.text(inherit, UiColor.TEXT);
        UiAppearance.button(inherit, UiColor.ACCENT);
        mRefreshers.add(() -> inherit.setChecked(panel().style().backdrop() == null));
        inherit.setOnCheckedChangeListener((v, checked) -> {
            if (!mRendering) replacePanel(withPanelBackdrop(panel(), checked ? null : current().backdrop()));
        });
        page.addView(inherit);
        final LinearLayout override = new LinearLayout(mActivity);
        override.setOrientation(LinearLayout.VERTICAL);
        mRefreshers.add(() -> override.setVisibility(panel().style().backdrop() == null ? View.GONE : View.VISIBLE));
        slider(override, R.string.appearance_opacity, 15, 100,
                () -> Math.round(panelBackdrop().opacity() * 100),
                value -> replacePanel(withPanelBackdrop(panel(),
                        new ShellAppearance.Backdrop(value / 100f, panelBackdrop().blurRadiusDp()))));
        slider(override, R.string.appearance_blur_radius, 0, 64, () -> panelBackdrop().blurRadiusDp(),
                value -> replacePanel(withPanelBackdrop(panel(),
                        new ShellAppearance.Backdrop(panelBackdrop().opacity(), value))));
        page.addView(override);
    }

    private ShellAppearance.Backdrop panelBackdrop() {
        return current().panelBackdrop(panel().id());
    }

    static ShellPanel withPanelBackdrop(ShellPanel panel, ShellAppearance.Backdrop backdrop) {
        var style = panel.style();
        return new ShellPanel(panel.id(), panel.edge(), new ShellAppearance.PanelStyle(
                style.length(), style.alignment(), style.maxLengthDp(), style.sideGapDp(), style.edgeGapDp(),
                style.thicknessDp(), style.paddingDp(), style.radiusDp(), backdrop, style.reserveSpace(), style.hover()), panel.components());
    }

    private void replacePanel(ShellPanel replacement) {
        var appearance = current();
        var panels = new ArrayList<>(appearance.composition().panels());
        for (int i = 0; i < panels.size(); i++) {
            if (panels.get(i).id().equals(replacement.id())) panels.set(i, replacement);
        }
        apply(appearance.withComposition(new ShellComposition(panels, appearance.composition().start())));
    }

    private void panelSelector(LinearLayout page) {
        final Spinner select = mUi.spinner(new String[0]);
        final List<String> ids = new ArrayList<>();
        select.setContentDescription(mActivity.getString(R.string.appearance_panel));
        mRefreshers.add(() -> {
            var next = current().composition().panels().stream().map(ShellPanel::id).toList();
            if (!next.equals(ids)) {
                ids.clear(); ids.addAll(next);
                var adapter = new ArrayAdapter<>(mActivity, android.R.layout.simple_spinner_item, ids);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                select.setAdapter(adapter);
            }
            select.setSelection(ids.indexOf(panel().id()));
        });
        select.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!mRendering && position < ids.size() && !ids.get(position).equals(mPanelId)) {
                    mPanelId = ids.get(position); refresh();
                }
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        page.addView(select, new LinearLayout.LayoutParams(-1, mUi.dp(48)));
        final LinearLayout commands = new LinearLayout(mActivity);
        ImageButton add = addCommand(commands, R.string.appearance_add_panel, R.drawable.ic_add, () -> {
            var theme = current();
            var panels = new ArrayList<>(theme.composition().panels());
            String id = nextPanelId(panels);
            panels.add(new ShellPanel(id, ShellPanel.Edge.BOTTOM, ShellAppearance.PanelStyle.defaults(),
                    List.of(ShellComposition.Component.of(ShellComposition.Kind.SPACER))));
            mPanelId = id;
            apply(theme.withComposition(new ShellComposition(panels, theme.composition().start())));
        });
        ImageButton remove = addCommand(commands, R.string.appearance_remove_panel, R.drawable.ic_remove, () -> {
            final String id = panel().id();
            final EditTarget editTarget = target();
            var dialog = UiDialogs.themedBuilder(mActivity).setTitle(R.string.appearance_remove_panel)
                    .setMessage(id).setPositiveButton(R.string.action_delete, (d, which) -> {
                        if (!isCurrent(editTarget)) { showError(new IllegalStateException(mActivity.getString(R.string.appearance_changed))); return; }
                        var theme = current();
                        if (theme.composition().panels().size() <= 1) return;
                        var panels = theme.composition().panels().stream().filter(p -> !p.id().equals(id)).toList();
                        apply(theme.withComposition(new ShellComposition(panels, theme.composition().start())));
                    }).setNegativeButton(android.R.string.cancel, null).create();
            showChild(dialog);
        });
        mRefreshers.add(() -> {
            int count = current().composition().panels().size();
            add.setEnabled(count < 4); remove.setEnabled(count > 1);
        });
        page.addView(commands);
    }

    static String nextPanelId(List<ShellPanel> panels) {
        for (int suffix = 1; ; suffix++) {
            String id = "panel-" + suffix;
            if (panels.stream().noneMatch(panel -> panel.id().equals(id))) return id;
        }
    }

    static ShellComposition moveComponent(ShellComposition composition, String source, int index, String destination) {
        if (source.equals(destination)) throw new IllegalArgumentException("Choose another panel");
        var panels = new ArrayList<>(composition.panels());
        int from = -1, to = -1;
        for (int i = 0; i < panels.size(); i++) {
            if (panels.get(i).id().equals(source)) from = i;
            if (panels.get(i).id().equals(destination)) to = i;
        }
        if (from < 0 || to < 0) throw new IllegalArgumentException("Panel is unavailable");
        var sourceItems = new ArrayList<>(panels.get(from).components());
        var targetItems = new ArrayList<>(panels.get(to).components());
        targetItems.add(sourceItems.remove(index));
        if (sourceItems.isEmpty()) sourceItems.add(ShellComposition.Component.of(ShellComposition.Kind.SPACER));
        panels.set(from, panels.get(from).withComponents(sourceItems));
        panels.set(to, panels.get(to).withComponents(targetItems));
        return new ShellComposition(panels, composition.start());
    }

    private void editComponents() {
        final EditTarget editTarget = target();
        final String selected = panel().id();
        final ShellAppearance original = current();
        final ShellComposition[] draft = {original.composition()};
        final LinearLayout rows = new LinearLayout(mActivity); rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(mUi.dp(16), mUi.dp(8), mUi.dp(16), mUi.dp(8));
        final Runnable render = new Runnable() {
            public void run() {
                rows.removeAllViews();
                var panel = draft[0].panels().stream().filter(p -> p.id().equals(selected)).findFirst().orElseThrow();
                final List<ShellComposition.Component> items = new ArrayList<>(panel.components());
                final Runnable update = () -> {
                    var panels = draft[0].panels().stream().map(p -> p.id().equals(selected) ? p.withComponents(items) : p).toList();
                    draft[0] = new ShellComposition(panels, draft[0].start()); run();
                };
                for (int i = 0; i < items.size(); i++) {
                    final int index = i;
                    final LinearLayout row = new LinearLayout(mActivity); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    TextView title = new TextView(mActivity);
                    title.setText(componentLabel(items.get(i).type()));
                    title.setSingleLine(true);
                    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    title.setAutoSizeTextTypeUniformWithConfiguration(8, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
                    title.setTooltipText(title.getText());
                    UiAppearance.text(title, UiColor.TEXT); row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
                    ImageButton options = mUi.taskbarIconButton(R.drawable.ic_settings, R.string.appearance_component_options, true);
                    options.setOnClickListener(v -> componentOptions(options, items, index, update));
                    row.addView(options, new LinearLayout.LayoutParams(mUi.dp(40), mUi.dp(44)));
                    ImageButton up = mUi.taskbarIconButton(R.drawable.ic_arrow_up, R.string.appearance_move_up, true);
                    up.setEnabled(i > 0);
                    up.setOnClickListener(v -> { java.util.Collections.swap(items, index, index - 1); update.run(); });
                    row.addView(up, new LinearLayout.LayoutParams(mUi.dp(40), mUi.dp(44)));
                    ImageButton move = mUi.taskbarIconButton(R.drawable.ic_arrow_down, R.string.appearance_move_panel, true);
                    var targets = draft[0].panels().stream().filter(p -> !p.id().equals(selected) && p.components().size() < 24).toList();
                    move.setEnabled(!targets.isEmpty());
                    move.setOnClickListener(v -> {
                        var dialog = UiDialogs.themedBuilder(mActivity).setTitle(R.string.appearance_move_panel)
                                .setItems(targets.stream().map(ShellPanel::id).toArray(String[]::new), (d, which) -> {
                                    draft[0] = moveComponent(draft[0], selected, index, targets.get(which).id()); run();
                                }).create();
                        showChild(dialog);
                    });
                    row.addView(move, new LinearLayout.LayoutParams(mUi.dp(40), mUi.dp(44)));
                    ImageButton remove = mUi.taskbarIconButton(R.drawable.ic_remove, R.string.action_delete, true);
                    remove.setEnabled(items.size() > 1);
                    remove.setOnClickListener(v -> { items.remove(index); update.run(); });
                    row.addView(remove, new LinearLayout.LayoutParams(mUi.dp(40), mUi.dp(44)));
                    rows.addView(row);
                }
                Button add = mUi.menuItem(R.string.appearance_add_component, UiColor.TEXT);
                add.setOnClickListener(v -> {
                    var choices = java.util.Arrays.stream(ShellComposition.Kind.values()).filter(kind -> kind == ShellComposition.Kind.SPACER
                            || draft[0].panels().stream().flatMap(p -> p.components().stream()).noneMatch(item -> item.type() == kind)).toList();
                    String[] labels = choices.stream().map(AppearanceSettings.this::componentLabel).toArray(String[]::new);
                    var dialog = UiDialogs.themedBuilder(mActivity).setTitle(R.string.appearance_add_component)
                            .setItems(labels, (d, which) -> { items.add(ShellComposition.Component.of(choices.get(which))); update.run(); }).create();
                    showChild(dialog);
                });
                add.setEnabled(items.size() < 24);
                rows.addView(add);
            }
        };
        render.run();
        ScrollView scroll = new ScrollView(mActivity); scroll.addView(rows);
        AlertDialog dialog = UiDialogs.themedBuilder(mActivity).setTitle(R.string.appearance_panel_components).setView(scroll)
                .setPositiveButton(R.string.appearance_preview, null).setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                requireCurrent(editTarget);
                preview(original.withComposition(draft[0]), editTarget);
                dialog.dismiss();
            } catch (Exception error) { Toast.makeText(mActivity, error.getMessage(), Toast.LENGTH_LONG).show(); }
        }));
        showChild(dialog);
    }

    private void componentOptions(View anchor, List<ShellComposition.Component> items, int index, Runnable update) {
        var value = items.get(index);
        var menu = new PopupMenu(mActivity, anchor);
        var groups = menu.getMenu().addSubMenu(R.string.appearance_component_group);
        int[] labels = {R.string.appearance_align_start, R.string.appearance_center, R.string.appearance_align_end};
        for (var group : ShellComposition.Group.values()) {
            groups.add(0, group.ordinal(), group.ordinal(), labels[group.ordinal()]).setCheckable(true).setChecked(group == value.group())
                    .setOnMenuItemClickListener(item -> { items.set(index, value.withPresentation(group, value.battery(), value.indicator())); update.run(); return true; });
        }
        groups.setGroupCheckable(0, true, true);
        if (value.type() == ShellComposition.Kind.BATTERY) {
            var battery = menu.getMenu().addSubMenu(R.string.appearance_battery);
            int[] modes = {R.string.appearance_percent, R.string.appearance_icon, R.string.appearance_icon_percent};
            for (var mode : ShellComposition.Battery.values()) battery.add(0, mode.ordinal(), mode.ordinal(), modes[mode.ordinal()])
                    .setCheckable(true).setChecked(mode == value.battery()).setOnMenuItemClickListener(item -> {
                        items.set(index, value.withPresentation(value.group(), mode, value.indicator())); update.run(); return true;
                    });
            battery.setGroupCheckable(0, true, true);
        }
        if (value.type() == ShellComposition.Kind.TASKS) {
            var indicators = menu.getMenu().addSubMenu(R.string.appearance_task_indicator);
            int[] modes = {R.string.appearance_line, R.string.appearance_dot, R.string.appearance_none};
            for (var mode : ShellComposition.Indicator.values()) indicators.add(0, mode.ordinal(), mode.ordinal(), modes[mode.ordinal()])
                    .setCheckable(true).setChecked(mode == value.indicator()).setOnMenuItemClickListener(item -> {
                        items.set(index, value.withPresentation(value.group(), value.battery(), mode)); update.run(); return true;
                    });
            indicators.setGroupCheckable(0, true, true);
        }
        menu.show();
    }

    private String componentLabel(ShellComposition.Kind kind) {
        return mActivity.getString(switch (kind) {
            case START -> R.string.action_start;
            case TASKS -> R.string.appearance_tasks;
            case SHOW_DESKTOP -> R.string.action_show_desktop;
            case OPEN_TASKS -> R.string.action_open_tasks;
            case NOTIFICATIONS -> R.string.action_notifications;
            case KEYBOARD_LAYOUT -> R.string.appearance_keyboard_layout;
            case PHONE_SCREEN -> R.string.tooltip_phone_screen;
            case QUICK_CONTROLS -> R.string.section_quick_controls;
            case BATTERY -> R.string.appearance_battery;
            case CLOCK -> R.string.appearance_clock;
            case SPACER -> R.string.appearance_spacer;
        });
    }

    private void heading(LinearLayout page, int title) { mUi.addControlSection(page, title, mUi.dp(12)); }
    private ShellAppearance current() {
        return mWorkspaceKey == null ? AppearanceStore.current() : AppearanceStore.current(mWorkspaceKey);
    }
    private long revision(String scope) {
        return scope == null ? AppearanceStore.snapshot().revision() : AppearanceStore.snapshot(scope).revision();
    }
    private String previewId(String scope) {
        return scope == null ? AppearanceStore.snapshot().previewId() : AppearanceStore.snapshot(scope).previewId();
    }
    private EditTarget target() { return new EditTarget(mWorkspaceKey, revision(mWorkspaceKey), mGeneration); }
    private boolean isCurrent(EditTarget target) {
        return !mClosed && mPage != null && target.generation() == mGeneration
                && java.util.Objects.equals(target.workspaceKey(), mWorkspaceKey)
                && target.revision() == revision(target.workspaceKey());
    }
    private void requireCurrent(EditTarget target) {
        if (!isCurrent(target)) throw new IllegalStateException(mActivity.getString(R.string.appearance_changed));
    }
    private void apply(ShellAppearance value) {
        try {
            if (mWorkspaceKey == null) AppearanceStore.apply(value);
            else {
                var state = AppearanceStore.snapshot(mWorkspaceKey);
                AppearanceStore.apply(mWorkspaceKey, changedPatch(state.patch(), state.current(), value));
            }
        } catch (Exception error) { showError(error); refresh(); }
    }
    private void reset() {
        try {
            if (mWorkspaceKey == null) AppearanceStore.apply(ShellAppearance.defaults());
            else AppearanceStore.removeOverride(mWorkspaceKey);
        } catch (Exception error) { showError(error); }
    }
    static String changedPatch(String patch, ShellAppearance before, ShellAppearance after) throws org.json.JSONException {
        var result = new org.json.JSONObject(patch);
        mergeChanges(result, ShellAppearanceJson.encode(before), ShellAppearanceJson.encode(after));
        return result.toString();
    }
    private static void mergeChanges(org.json.JSONObject patch, org.json.JSONObject before, org.json.JSONObject after)
            throws org.json.JSONException {
        for (var keys = before.keys(); keys.hasNext();) {
            String key = keys.next();
            if (!after.has(key)) patch.put(key, org.json.JSONObject.NULL);
        }
        for (var keys = after.keys(); keys.hasNext();) {
            String key = keys.next();
            Object previous = before.opt(key), next = after.get(key);
            if (next instanceof org.json.JSONObject child && previous instanceof org.json.JSONObject old) {
                var nested = patch.optJSONObject(key);
                if (nested == null) nested = new org.json.JSONObject();
                mergeChanges(nested, old, child);
                if (nested.length() != 0) patch.put(key, nested);
            } else if (next instanceof org.json.JSONArray array && previous instanceof org.json.JSONArray old) {
                if (!array.toString().equals(old.toString())) patch.put(key, array);
            } else if (!next.equals(previous)) patch.put(key, next);
        }
    }
    private void showError(Exception error) {
        if (!mClosed && mPage != null) Toast.makeText(mActivity, error.getMessage(), Toast.LENGTH_LONG).show();
    }
    private TextView label(LinearLayout page, int title) {
        TextView text = new TextView(mActivity); text.setText(title); text.setTextSize(14);
        UiAppearance.text(text, UiColor.TEXT); page.addView(text); return text;
    }
    private Spinner choice(LinearLayout page, int title, int[] names, IntSupplier get, IntConsumer set) {
        label(page, title);
        final String[] labels = new String[names.length];
        for (int i = 0; i < names.length; i++) labels[i] = mActivity.getString(names[i]);
        final Spinner spinner = mUi.spinner(labels);
        spinner.setContentDescription(mActivity.getString(title));
        mRefreshers.add(() -> spinner.setSelection(get.getAsInt()));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!mRendering && position != get.getAsInt()) set.accept(position);
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        page.addView(spinner, new LinearLayout.LayoutParams(-1, mUi.dp(48)));
        return spinner;
    }
    private void slider(LinearLayout page, int title, int min, int max, IntSupplier get, IntConsumer set) {
        final TextView label = label(page, title);
        final SeekBar seek = new SeekBar(mActivity);
        UiAppearance.progress(seek, UiColor.ACCENT);
        seek.setMin(min); seek.setMax(max); seek.setContentDescription(mActivity.getString(title));
        final java.util.function.IntConsumer show = v -> label.setText(mActivity.getString(title) + ": " + v);
        mRefreshers.add(() -> { seek.setProgress(get.getAsInt()); show.accept(get.getAsInt()); });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                show.accept(progress);
                if (user && !bar.isPressed()) set.accept(progress);
            }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) { if (!mRendering) set.accept(bar.getProgress()); }
        });
        page.addView(seek, new LinearLayout.LayoutParams(-1, mUi.dp(40)));
    }
    private ImageButton addCommand(LinearLayout row, int title, int icon, Runnable action) {
        ImageButton button = mUi.taskbarIconButton(icon, title, false);
        button.setOnClickListener(v -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(0, mUi.dp(56), 1));
        return button;
    }
    private void refresh() {
        if (mClosed) return;
        mRendering = true;
        try { for (Runnable action : mRefreshers) action.run(); }
        finally { mRendering = false; }
    }
    private void showChild(AlertDialog dialog) {
        if (mClosed || mPage == null) return;
        mChildren.add(dialog);
        dialog.setOnDismissListener(d -> mChildren.remove(dialog));
        dialog.show();
    }
    private void dismissChildren() {
        if (mPreviewDialog != null) mPreviewDialog.dismiss();
        for (var child : List.copyOf(mChildren)) child.dismiss();
    }
    private void importDocument() {
        chooseFile(IMPORT);
    }
    private void exportDocument() {
        chooseFile(EXPORT);
    }
    private void chooseFile(int request) {
        if (mPendingFile != null || mFileBusy) {
            Toast.makeText(mActivity, R.string.appearance_file_busy, Toast.LENGTH_SHORT).show(); return;
        }
        boolean importing = request == IMPORT || request == IMPORT_BUNDLE;
        boolean bundle = request == IMPORT_BUNDLE || request == EXPORT_BUNDLE;
        try {
            var global = AppearanceStore.snapshot();
            var local = mWorkspaceKey == null ? null : AppearanceStore.snapshot(mWorkspaceKey);
            ShellAppearance value = local == null ? global.current() : local.current();
            String document = (request == SCHEMA ? ShellAppearanceSchema.document()
                    : local == null ? ShellAppearanceJson.encode(value) : new org.json.JSONObject(local.patch())).toString(2);
            EditTarget target = new EditTarget(mWorkspaceKey, local == null ? global.revision() : local.revision(), mGeneration);
            mPendingFile = new FileRequest(request, target, value, document);
            Intent intent = new Intent(importing ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType(bundle ? "application/zip" : "application/json");
            if (!importing) intent.putExtra(Intent.EXTRA_TITLE, request == SCHEMA ? "magicdesk-theme.schema.json"
                    : bundle ? "magicdesk-theme.zip" : "magicdesk-theme.json");
            mActivity.startActivityForResult(intent, request);
        } catch (Exception error) { mPendingFile = null; showError(error); }
    }
    private void invalidateFiles() {
        mGeneration++;
        if (mFileCancellation != null) mFileCancellation.cancel();
        Thread worker = mFileThread;
        if (worker != null) worker.interrupt();
    }
    boolean onResult(int request, int result, Intent data) {
        if (request < IMPORT || request > EXPORT_BUNDLE) return false;
        final FileRequest pending = mPendingFile;
        if (pending == null || pending.code() != request) return true;
        mPendingFile = null;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        if (!isCurrent(pending.target())) { showError(new IllegalStateException(mActivity.getString(R.string.appearance_changed))); return true; }
        final var uri = data.getData();
        mFileBusy = true;
        final var cancellation = new android.os.CancellationSignal();
        mFileCancellation = cancellation;
        mFiles.execute(() -> {
            mFileThread = Thread.currentThread();
            try {
                cancellation.throwIfCanceled();
                if (request == IMPORT || request == IMPORT_BUNDLE) {
                    final String document;
                    try (var descriptor = mActivity.getContentResolver().openAssetFileDescriptor(uri, "r", cancellation)) {
                        if (descriptor == null) throw new java.io.IOException("Document is unavailable");
                        try (var input = descriptor.createInputStream()) {
                            if (request == IMPORT_BUNDLE) {
                                document = ShellAppearanceJson.encode(AppearanceBundles.read(input)).toString();
                            } else {
                                byte[] bytes = input.readNBytes(ShellAppearanceJson.MAX_BYTES + 1);
                                if (bytes.length > ShellAppearanceJson.MAX_BYTES) throw new java.io.IOException("Appearance document exceeds 32 KiB");
                                document = new String(bytes, StandardCharsets.UTF_8);
                            }
                        }
                    }
                    cancellation.throwIfCanceled();
                    prepareDocument(document, pending.target().workspaceKey());
                    cancellation.throwIfCanceled();
                    mActivity.runOnUiThread(() -> {
                        try { previewDocument(document, pending.target()); }
                        catch (Exception error) { showError(error); }
                    });
                } else {
                    try (var descriptor = mActivity.getContentResolver().openAssetFileDescriptor(uri, "wt", cancellation)) {
                        if (descriptor == null) throw new java.io.IOException("Document is unavailable");
                        try (var output = descriptor.createOutputStream()) {
                            cancellation.throwIfCanceled();
                            if (request == EXPORT_BUNDLE) AppearanceBundles.write(pending.appearance(), output);
                            else output.write(pending.document().getBytes(StandardCharsets.UTF_8));
                        }
                    }
                }
            } catch (Exception error) {
                if (!cancellation.isCanceled()) mActivity.runOnUiThread(() -> showError(error));
            } finally { finishFileTask(cancellation); }
        });
        return true;
    }
    public void close() {
        mClosed = true;
        dismissPage();
        mFiles.shutdownNow();
    }
}
