package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PhoneControlPanelControllerTest {
    @Test
    public void contentWidthFollowsParentMeasurementAfterResize() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Gravity { static int TOP = 1, CENTER_HORIZONTAL = 2; }
                static class View {
                    FrameLayout.LayoutParams params;
                    FrameLayout.LayoutParams getLayoutParams() { return params; }
                    static class MeasureSpec {
                        static final int UNSPECIFIED = 0, EXACTLY = 1, AT_MOST = 2;
                        static int makeMeasureSpec(int size, int mode) { return (size << 2) | mode; }
                        static int getMode(int spec) { return spec & 3; }
                        static int getSize(int spec) { return spec >>> 2; }
                    }
                }
                static class FrameLayout extends View {
                    View child;
                    int measuredChildWidth;
                    FrameLayout(Object activity) { }
                    static class LayoutParams {
                        static final int MATCH_PARENT = -1, WRAP_CONTENT = -2;
                        int width, height, gravity;
                        LayoutParams(int w, int h, int g) { width = w; height = h; gravity = g; }
                    }
                    void addView(View view, LayoutParams params) { child = view; view.params = params; }
                    protected void onMeasure(int width, int height) { measuredChildWidth = child.params.width; }
                }
                Object mActivity = new Object();
                float density = 3.25f;
                int dp(int value) { return Math.round(value * density); }
                public static void verify() {
                    Fixture f = new Fixture();
                    View content = new View();
                    FrameLayout host = (FrameLayout) f.centered(content);
                    host.onMeasure(View.MeasureSpec.makeMeasureSpec(796, View.MeasureSpec.EXACTLY), 0);
                    check(host.measuredChildWidth == 796, "initial narrow window");
                    host.onMeasure(View.MeasureSpec.makeMeasureSpec(1098, View.MeasureSpec.EXACTLY), 0);
                    check(host.measuredChildWidth == 1098, "fullscreen retained narrow content");
                    host.onMeasure(View.MeasureSpec.makeMeasureSpec(3000, View.MeasureSpec.EXACTLY), 0);
                    check(host.measuredChildWidth == f.dp(900), "large window lost maximum width");
                    host.onMeasure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST), 0);
                    check(host.measuredChildWidth == 600, "shrinking window exceeds parent");
                    f.density = 1;
                    host.onMeasure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), 0);
                    check(host.measuredChildWidth == 900, "density change retained old cap");
                    host.onMeasure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), 0);
                    check(host.measuredChildWidth == 900, "unbounded measurement collapsed content");
                    check(content.params.gravity == (Gravity.TOP | Gravity.CENTER_HORIZONTAL), "not centered");
                }
                """ + RuntimeSourceFixture.methods("PhoneControlPanelController", "centered"));
    }

    @Test
    public void appsExistsOnlyInTheDisplayToolbarWithItsSharedPrerequisites() throws Exception {
        final String status = RuntimeSourceFixture.methods("PhoneControlPanelController", "addStatus");
        assertTrue(status.contains("status.setOrientation(LinearLayout.HORIZONTAL)"));
        assertTrue(status.contains("status.setGravity(Gravity.CENTER_VERTICAL)"));
        assertTrue(status.contains("status.addView(mStatus, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1))"));
        assertTrue(status.contains("status.addView(mRuntime, runtimeParams)"));
        assertFalse(status.contains("R.string.section_apps"));
        assertFalse(status.contains("openApplications"));
        final String commands = RuntimeSourceFixture.methods("DisplayTableView", "renderCommands");
        assertTrue(commands.contains("final boolean enabled = display != null && shellReady && !busy;"));
        assertTrue(commands.contains("R.drawable.ic_sections, R.string.section_apps, enabled,"));
        assertTrue(commands.contains("mActions.openApplications(display)"));
    }

    @Test
    public void displaySectionRequiresShellButAccessAndSettingsRemainOutsideIt() throws Exception {
        final String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/DisplayTableView.java"));
        assertTrue(source.contains("mRoot.setVisibility(View.GONE)"));
        assertFalse(source.contains("mRoot.addView(heading,"));
        assertTrue(source.contains("mRoot.addView(mRows,"));
        assertTrue(source.contains("mRoot.addView(mCommands,"));
        final String render = RuntimeSourceFixture.methods("DisplayTableView", "render");
        assertTrue(render.contains("mRoot.setVisibility(shellReady ? View.VISIBLE : View.GONE)"));
        assertTrue(render.indexOf("if (!shellReady) { return; }") < render.indexOf("orderedDisplays(displays)"));
        assertTrue(RuntimeSourceFixture.methods("PhoneControlPanelController", "createHeader")
                .contains("mActions.openSettings()"));
        assertTrue(RuntimeSourceFixture.methods("PhoneControlPanelController", "addStatus")
                .contains("mActions.requestAccess()"));
    }

    @Test
    public void displayCreationBelongsToGeneralActionsAndUsesTheCurrentSelection() throws Exception {
        final String actions = RuntimeSourceFixture.methods("PhoneControlPanelController", "addDesktopActions");
        assertTrue(actions.contains("R.string.display_create, R.drawable.ic_add"));
        assertTrue(actions.contains("addGridAction(sessionActions, mCreateDisplay)"));
        assertTrue(actions.contains("mDisplayTable.showCreationDialog()"));
        assertTrue(RuntimeSourceFixture.methods("PhoneControlPanelController", "render")
                .contains("mCreateDisplay.setEnabled(state.shellReady && !busy)"));
        assertTrue(RuntimeSourceFixture.methods("DisplayTableView", "showCreationDialog")
                .contains("mCreationDialog.show(mSelectedDisplay)"));
    }

    @Test
    public void exitRequiresPositiveConfirmation() throws Exception {
        final String actions = RuntimeSourceFixture.methods("PhoneControlPanelController", "addSystemActions");
        assertTrue(actions.contains("confirmExit()"));
        assertFalse(actions.contains("mActions.exitMagicDesk()"));
        RuntimeSourceFixture.verify("""
                static class R {
                    static class string { static final int action_exit = 1, confirm_exit_magicdesk = 2; }
                }
                static class android { static class R { static class string { static final int cancel = 3; } } }
                interface Listener { void onClick(Object dialog, int which); }
                static class AlertDialog {
                    static Builder last;
                    static class Builder {
                        int title, message, negativeLabel, positiveLabel;
                        Listener positive, negative;
                        Builder(Object activity) { }
                        Builder setTitle(int id) { title = id; return this; }
                        Builder setMessage(int id) { message = id; return this; }
                        Builder setNegativeButton(int id, Listener listener) {
                            negativeLabel = id; negative = listener; return this;
                        }
                        Builder setPositiveButton(int id, Listener listener) {
                            positiveLabel = id; positive = listener; return this;
                        }
                        void show() { last = this; }
                        void cancel() { if (negative != null) negative.onClick(this, -2); }
                    }
                }
                static class Actions {
                    int exits;
                    void exitMagicDesk() { exits++; }
                }
                final Object mActivity = new Object();
                final Actions mActions = new Actions();
                public static void verify() {
                    Fixture f = new Fixture();
                    f.confirmExit();
                    check(f.mActions.exits == 0, "opening confirmation must not exit");
                    AlertDialog.Builder dialog = AlertDialog.last;
                    check(dialog.title == R.string.action_exit, "wrong title");
                    check(dialog.message == R.string.confirm_exit_magicdesk, "missing explanation");
                    check(dialog.negativeLabel == android.R.string.cancel, "missing cancel");
                    dialog.cancel();
                    check(f.mActions.exits == 0, "cancel must keep running");
                    f.confirmExit();
                    dialog = AlertDialog.last;
                    check(dialog.positiveLabel == R.string.action_exit, "missing explicit exit");
                    dialog.positive.onClick(dialog, -1);
                    check(f.mActions.exits == 1, "confirmation must use the existing exit action");
                }
                """ + RuntimeSourceFixture.methods("PhoneControlPanelController", "confirmExit"));
    }

    @Test
    public void displayChoicesUseDescendingIdsWithoutChangingTheSharedCatalog() {
        final DesktopDisplayInfo phone = display(0, "phone", true, false);
        final DesktopDisplayInfo wired = display(3, "wired", true, false);
        final DesktopDisplayInfo virtual = display(8, "virtual", true, true);
        final DesktopDisplayInfo[] catalog = {wired, phone, virtual};
        assertArrayEquals(new DesktopDisplayInfo[] {virtual, wired, phone},
                DisplayTableView.orderedDisplays(catalog));
        assertArrayEquals(new DesktopDisplayInfo[] {wired, phone, virtual}, catalog);
        assertArrayEquals(new DesktopDisplayInfo[0],
                DisplayTableView.orderedDisplays(new DesktopDisplayInfo[0]));
        assertArrayEquals(new DesktopDisplayInfo[] {phone},
                DisplayTableView.orderedDisplays(new DesktopDisplayInfo[] {phone}));
    }

    @Test
    public void displaySelectionPrefersNonBuiltInDisplaysAndPreservesManualChoice() {
        final var phone = display(0, "phone", true, false);
        final var wired = display(3, "wired", true, false);
        final var virtual = display(8, "virtual", true, true);
        final DesktopDisplayInfo[] catalog = {virtual, wired, phone};
        assertSame(virtual, DisplayTableView.selectedDisplay(catalog, null, 0));
        assertSame(virtual, DisplayTableView.selectedDisplay(catalog, null, 3));
        assertSame(phone, DisplayTableView.selectedDisplay(catalog, phone.uniqueId, 0));
        assertSame(wired, DisplayTableView.selectedDisplay(catalog, wired.uniqueId, 0));
        assertSame(virtual, DisplayTableView.selectedDisplay(catalog, virtual.uniqueId, 0));
        final var refreshed = new DesktopDisplayInfo(8, virtual.uniqueId, "Renamed", "virtual",
                2560, 1440, 160, true, false, true, false);
        assertSame(refreshed, DisplayTableView.selectedDisplay(
                new DesktopDisplayInfo[] {wired, refreshed, phone}, virtual.uniqueId, 0));
    }

    @Test
    public void removedSelectionResolvesAFreshLiveDisplayInsteadOfRetainingTheOldRecord() {
        final var phone = display(0, "phone", true, false);
        final var original = display(8, "virtual", true, true);
        final var replacement = new DesktopDisplayInfo(8, "replacement:8", "Display", "virtual",
                1920, 1080, 160, true, false, true, false);
        assertSame(replacement, DisplayTableView.selectedDisplay(
                new DesktopDisplayInfo[] {replacement, phone}, original.uniqueId, 0));
        assertSame(phone, DisplayTableView.selectedDisplay(new DesktopDisplayInfo[] {phone}, original.uniqueId, 0));
        assertSame(phone, DisplayTableView.selectedDisplay(new DesktopDisplayInfo[] {phone}, null, 3));
        assertNull(DisplayTableView.selectedDisplay(new DesktopDisplayInfo[0], original.uniqueId, 0));
    }

    @Test
    public void initialSelectionPrefersAnyNonBuiltInDisplayRegardlessOfDesktopSupport() {
        final var phone = display(0, "phone", true, false);
        final var internal = display(2, "internal", false, false);
        for (final String source : new String[] {"wired", "wireless", "virtual", "overlay"}) {
            final var external = display(1, source, false, false);
            final var catalog = DisplayTableView.orderedDisplays(new DesktopDisplayInfo[] {phone, external, internal});
            assertSame(external, DisplayTableView.selectedDisplay(catalog, null, 0));
        }
        final DesktopDisplayInfo[] builtInOnly = {internal, phone};
        assertSame(phone, DisplayTableView.selectedDisplay(builtInOnly, null, 0));
        assertSame(internal, DisplayTableView.selectedDisplay(builtInOnly, null, 2));
    }

    @Test
    public void newPhysicalAndCreatedDisplaysAreSelectedOnArrival() {
        final var phone = display(0, "phone", true, false);
        final DesktopDisplayInfo[] previous = {phone};
        assertNull(DisplayTableView.newlyAvailableDisplay(new DesktopDisplayInfo[0], previous));
        for (final String source : new String[] {"wired", "wireless", "virtual", "overlay", "internal"}) {
            final var added = display(4, source, true, false);
            assertSame(added, DisplayTableView.newlyAvailableDisplay(previous,
                    DisplayTableView.orderedDisplays(new DesktopDisplayInfo[] {phone, added})));
        }
    }

    @Test
    public void ordinaryCatalogChangesDoNotOverrideManualSelection() {
        final var phone = display(0, "phone", true, false);
        final var wired = display(4, "wired", true, false);
        final DesktopDisplayInfo[] previous = {wired, phone};
        final var changed = new DesktopDisplayInfo(4, wired.uniqueId, "Renamed", "wired",
                2560, 1440, 240, false, false, false, true);
        assertNull(DisplayTableView.newlyAvailableDisplay(previous, new DesktopDisplayInfo[] {phone, changed}));
        assertNull(DisplayTableView.newlyAvailableDisplay(previous, new DesktopDisplayInfo[] {phone}));
        assertNull(DisplayTableView.newlyAvailableDisplay(previous, new DesktopDisplayInfo[0]));
    }

    @Test
    public void reconnectAndReplacementAreNewArrivalsNotStaleIdentityReuse() {
        final var phone = display(0, "phone", true, false);
        final var original = display(4, "wired", true, false);
        final var reconnected = new DesktopDisplayInfo(5, original.uniqueId, "Display", "wired",
                1920, 1080, 160, true, false, false, false);
        assertSame(reconnected, DisplayTableView.newlyAvailableDisplay(new DesktopDisplayInfo[] {phone, original},
                new DesktopDisplayInfo[] {reconnected, phone}));
        final var replacement = new DesktopDisplayInfo(4, "replacement:4", "Display", "wired",
                1920, 1080, 160, true, false, false, false);
        assertSame(replacement, DisplayTableView.newlyAvailableDisplay(new DesktopDisplayInfo[] {phone, original},
                new DesktopDisplayInfo[] {replacement, phone}));
        assertSame(original, DisplayTableView.newlyAvailableDisplay(new DesktopDisplayInfo[] {phone},
                new DesktopDisplayInfo[] {original, phone}));
    }

    @Test
    public void batchedArrivalsUseDisplayListOrderAndDoNotSelectAgainOnNextRefresh() {
        final var phone = display(0, "phone", true, false);
        final var wired = display(4, "wired", true, false);
        final var virtual = display(8, "virtual", true, true);
        final var current = DisplayTableView.orderedDisplays(new DesktopDisplayInfo[] {wired, phone, virtual});
        assertSame(virtual, DisplayTableView.newlyAvailableDisplay(new DesktopDisplayInfo[] {phone}, current));
        assertNull(DisplayTableView.newlyAvailableDisplay(current, current));
    }

    @Test
    public void displayActionsUseTwoColumnsAndTheSameLabeledButtonsAsSessionActions() throws Exception {
        final String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/DisplayTableView.java"));
        assertTrue(source.contains("commands.setColumnCount(2)"));
        assertFalse(source.contains("toolbarColumns"));
        assertFalse(source.contains("mMore"));
        assertTrue(source.contains("mUi.controlAction(label, icon, DesktopUiFactory.COLOR_TEXT)"));
        final String buttons = RuntimeSourceFixture.methods("DisplayTableView", "button");
        assertTrue(buttons.contains("params.width = 0"));
        assertTrue(source.contains("GridLayout.spec(GridLayout.UNDEFINED, 1f)"));
        assertTrue(buttons.contains("params.height = dp(52)"));
        final String update = RuntimeSourceFixture.methods("DisplayTableView", "updateButton");
        assertTrue(update.contains("mUi.setControlIcon(button, icon)"));
        assertTrue(update.contains("button.setText(label)"));
        assertTrue(update.contains("button.setEnabled(enabled)"));
        assertTrue(source.contains("button.setOnClickListener(view -> action.run())"));
        final String shared = RuntimeSourceFixture.methods("DesktopUiFactory", "controlAction");
        assertTrue(shared.contains("button.setSingleLine(false)"));
        assertTrue(shared.contains("button.setMaxLines(2)"));
    }

    @Test public void presentationButtonsUseTheSelectedOutputAndSharedPrerequisites() throws Exception {
        RuntimeSourceFixture.verify("""
                static class View { static final int VISIBLE = 0, INVISIBLE = 4; }
                static class Button {
                    boolean enabled; int visibility; Runnable action;
                    void setVisibility(int value) { visibility = value; }
                }
                static class R {
                    static class drawable { static int ic_eye = 1, ic_close = 2, ic_file_new_window = 3; }
                    static class string { static int display_show_another = 1, display_stop_showing = 2, display_start_portable = 3; }
                }
                static class DesktopDisplayInfo { int id; DesktopDisplayInfo(int value) { id = value; } }
                static class RuntimeCapabilities { static boolean supportsDesktop(int sdk) { return sdk >= 35; } }
                enum DisplayPresentationMode {
                    DIRECT, MIRROR;
                    static DisplayPresentationMode forSource(DesktopDisplayInfo d) { return d.id == 72 ? DIRECT : MIRROR; }
                }
                static class DisplayPresentations {
                    static class Session { }
                    static Session active = new Session(), detached;
                    static Session forOutput(int id) { return id == 73 ? active : null; }
                    static void detach(Session session) { detached = session; }
                }
                static class DisplaySourceDialog {
                    static DesktopDisplayInfo output;
                    static void show(Object activity, DesktopDisplayInfo d, DesktopDisplayInfo[] catalog) { output = d; }
                }
                static class Actions { DesktopDisplayInfo output; void startPortableDesktop(DesktopDisplayInfo d) { output = d; } }
                Object mActivity = new Object();
                Actions mActions = new Actions();
                Button mShowDisplay = new Button(), mStopShowing = new Button(), mPortable = new Button();
                DesktopDisplayInfo[] mDisplays = {new DesktopDisplayInfo(72), new DesktopDisplayInfo(73)};
                void updateButton(Button b, int icon, int label, boolean enabled, Runnable action) {
                    b.enabled = enabled; b.action = action;
                }
                public static void verify() {
                    Fixture f = new Fixture();
                    var source = f.mDisplays[0]; var output = f.mDisplays[1];
                    f.renderPresentationActions(output, true, 35);
                    check(f.mShowDisplay.enabled && f.mStopShowing.enabled && f.mPortable.enabled, "missing direct actions");
                    f.mShowDisplay.action.run(); f.mStopShowing.action.run(); f.mPortable.action.run();
                    check(DisplaySourceDialog.output == output && f.mActions.output == output,
                            "presentation action used source instead of selected output");
                    check(DisplayPresentations.detached == DisplayPresentations.active, "stopped another presentation");
                    f.renderPresentationActions(source, true, 35);
                    check(!f.mStopShowing.enabled && f.mStopShowing.visibility == View.INVISIBLE,
                            "Stop showing appeared on source or removed its grid slot");
                    check(!f.mPortable.enabled, "portable action enabled for a direct virtual source");
                    f.renderPresentationActions(output, true, 34);
                    check(f.mShowDisplay.enabled && f.mStopShowing.enabled && !f.mPortable.enabled,
                            "ordinary Viewer actions required Desktop SDK");
                    f.renderPresentationActions(output, false, 35);
                    check(!f.mShowDisplay.enabled && !f.mStopShowing.enabled && !f.mPortable.enabled, "busy/unavailable actions enabled");
                    f.renderPresentationActions(null, false, 35);
                    check(f.mStopShowing.visibility == View.INVISIBLE, "empty selection retained Stop showing");
                    f.mDisplays = new DesktopDisplayInfo[]{output};
                    f.renderPresentationActions(output, true, 35);
                    check(!f.mShowDisplay.enabled, "Show offered a nonexistent source");
                }
                """ + RuntimeSourceFixture.methods("DisplayTableView", "renderPresentationActions"));
    }

    @Test
    public void radioSelectionOnlyUpdatesTheCommonToolbar() throws Exception {
        final String render = RuntimeSourceFixture.methods("DisplayTableView", "render");
        assertTrue(render.contains("reconcileRows()"));
        assertTrue(render.contains("newlyAvailableDisplay(mDisplays, next)"));
        assertTrue(render.contains("mSelectedDisplay = added != null ? added : selectedDisplay("));
        assertTrue(render.contains("mRows.check(checkedId)"));
        assertTrue(render.contains("renderCommands(mSelectedDisplay, desktops, shellReady, busy, outputAvailable)"));
        assertFalse(render.contains("button("));
        assertFalse(render.contains("mActions."));
        final String commands = RuntimeSourceFixture.methods("DisplayTableView", "renderCommands");
        assertFalse(commands.contains("removeAllViews"));
        assertFalse(commands.contains("new GridLayout"));
        assertTrue(commands.contains("mActions.controlDisplay(display)"));
        assertTrue(commands.contains("mActions.removeDisplay(display)"));
        assertFalse(commands.contains("mActions.removeDisplay(mSelectedDisplay)"));
        assertFalse(commands.contains("scrcpy"));
        final String select = RuntimeSourceFixture.methods("DisplayTableView", "selectDisplay");
        assertTrue(select.contains("if (mRendering || !mShellReady)"));
        assertTrue(select.contains("renderCommands(mSelectedDisplay, mDesktops, mShellReady, mBusy, mOutputAvailable)"));
        assertFalse(select.contains("mActions."));
    }

    @Test
    public void displayRefreshPreservesPressedRowsAndUsesTheLatestSelectionState() throws Exception {
        RuntimeSourceFixture.verify("""
                static class DesktopDisplayInfo {
                    final int id;
                    final String uniqueId, name;
                    DesktopDisplayInfo(int id, String uid, String name) {
                        this.id = id; uniqueId = uid; this.name = name;
                    }
                    boolean isBuiltIn() { return id == 0; }
                }
                static class TaskRepository { static class Snapshot { } }
                static class View {
                    static final int NO_ID = -1, VISIBLE = 0, GONE = 8;
                    static int nextId;
                    final int id = ++nextId;
                    Object tag;
                    int visibility;
                    boolean enabled, pressed, attached;
                    int getId() { return id; }
                    Object getTag() { return tag; }
                    void setTag(Object value) { tag = value; }
                    void setVisibility(int value) { visibility = value; }
                    void setEnabled(boolean value) { enabled = value; }
                }
                static class RadioButton extends View {
                    CharSequence text = "";
                    int textWrites;
                    CharSequence getText() { return text; }
                    void setText(CharSequence value) { text = value; ++textWrites; }
                }
                class RadioGroup {
                    final List<RadioButton> rows = new ArrayList<>();
                    int checkedId = View.NO_ID, additions, removals;
                    static class LayoutParams { LayoutParams(int w, int h) { } }
                    int getChildCount() { return rows.size(); }
                    RadioButton getChildAt(int index) { return index < rows.size() ? rows.get(index) : null; }
                    void addView(RadioButton row, int index, LayoutParams p) {
                        rows.add(index, row); row.attached = true; ++additions;
                    }
                    void removeViewAt(int index) {
                        RadioButton row = rows.remove(index);
                        row.attached = false; row.pressed = false; ++removals;
                    }
                    View findViewById(int id) {
                        return rows.stream().filter(row -> row.id == id).findFirst().orElse(null);
                    }
                    void check(int id) {
                        if (checkedId == id) return;
                        checkedId = id;
                        selectDisplay(id);
                    }
                    void release(RadioButton row) {
                        if (row.attached && row.pressed) check(row.getId());
                        row.pressed = false;
                    }
                }
                static class TextUtils {
                    static boolean equals(CharSequence a, CharSequence b) { return a.toString().equals(b.toString()); }
                }
                static class Activity {
                    Activity getDisplay() { return this; }
                    int getDisplayId() { return 0; }
                }
                final Activity mActivity = new Activity();
                final View mRoot = new View();
                final RadioGroup mRows = new RadioGroup();
                DesktopDisplayInfo[] mDisplays = new DesktopDisplayInfo[0];
                DesktopDisplayInfo mSelectedDisplay, commandDisplay;
                Set<Integer> mDesktops = Set.of(), commandDesktops;
                boolean mShellReady, mBusy, mOutputAvailable, mRendering, commandBusy, commandOutput;
                RadioButton createRow() { return new RadioButton(); }
                CharSequence rowLabel(DesktopDisplayInfo d, Set<Integer> desktops, TaskRepository.Snapshot tasks) {
                    return d.name + desktops.contains(d.id);
                }
                void renderCommands(DesktopDisplayInfo d, Set<Integer> desktops, boolean ready, boolean busy, boolean output) {
                    commandDisplay = d; commandDesktops = desktops; commandBusy = busy; commandOutput = output;
                }
                public static void verify() {
                    Fixture f = new Fixture();
                    var phone = new DesktopDisplayInfo(0, "phone", "Phone");
                    var virtual = new DesktopDisplayInfo(8, "virtual", "Virtual");
                    f.render(new DesktopDisplayInfo[]{phone, virtual}, Set.of(), true, false, false, null);
                    RadioButton virtualRow = f.mRows.getChildAt(0), phoneRow = f.mRows.getChildAt(1);
                    check(f.mSelectedDisplay == virtual, "initial external preference");
                    phoneRow.pressed = true;
                    for (int n = 0; n < 20; ++n) {
                        phone = new DesktopDisplayInfo(0, "phone", "Phone");
                        virtual = new DesktopDisplayInfo(8, "virtual", "Virtual");
                        f.render(new DesktopDisplayInfo[]{phone, virtual}, Set.of(), true, false, false, null);
                    }
                    check(f.mRows.getChildAt(1) == phoneRow && phoneRow.pressed, "refresh cancelled touch");
                    check(phoneRow.textWrites == 1, "unchanged text rebound");
                    check(f.mRows.additions == 2 && f.mRows.removals == 0, "refresh rebuilt children");
                    check(phoneRow.getTag() == phone, "row has stale catalog record");
                    f.mRows.release(phoneRow);
                    check(f.mSelectedDisplay == phone && f.commandDisplay == phone, "release lost selection");

                    f.render(new DesktopDisplayInfo[]{virtual, phone}, Set.of(0), true, true, true, null);
                    check(f.mSelectedDisplay == phone, "status refresh reset manual choice");
                    check(f.mRows.getChildAt(1) == phoneRow && phoneRow.textWrites == 2, "status replaced row");
                    f.mRows.check(virtualRow.id);
                    check(f.commandDisplay == virtual && f.commandBusy && f.commandOutput
                            && f.commandDesktops.equals(Set.of(0)), "selection reused old command state");

                    var wired = new DesktopDisplayInfo(4, "wired", "HDMI");
                    phoneRow.pressed = true;
                    f.render(new DesktopDisplayInfo[]{phone, wired, virtual}, Set.of(), true, false, false, null);
                    RadioButton wiredRow = f.mRows.getChildAt(1);
                    check(f.mSelectedDisplay == wired, "new display not selected");
                    check(f.mRows.getChildAt(0) == virtualRow && f.mRows.getChildAt(2) == phoneRow
                            && phoneRow.pressed, "insertion detached surviving rows");
                    f.mRows.release(phoneRow);
                    check(f.mSelectedDisplay == phone, "click lost across insertion");
                    f.mRows.check(wiredRow.id);
                    f.render(new DesktopDisplayInfo[]{phone, virtual}, Set.of(), true, false, false, null);
                    check(!wiredRow.attached && f.mSelectedDisplay == virtual, "removed selection retained");
                    check(f.mRows.getChildAt(1) == phoneRow, "removal rebuilt survivors");

                    var replacement = new DesktopDisplayInfo(8, "replacement", "Other display");
                    virtualRow.pressed = true;
                    f.render(new DesktopDisplayInfo[]{phone, replacement}, Set.of(), true, false, false, null);
                    check(!virtualRow.attached && !virtualRow.pressed, "stale display still clickable");
                    check(f.mSelectedDisplay == replacement && f.mRows.getChildAt(0) != virtualRow,
                            "reused ID kept replaced display row");
                    f.render(new DesktopDisplayInfo[0], Set.of(), true, false, false, null);
                    check(f.mRows.getChildCount() == 0 && f.mRows.checkedId == View.NO_ID
                            && f.mSelectedDisplay == null && f.commandDisplay == null, "empty catalog kept selection");
                    f.render(new DesktopDisplayInfo[]{phone}, Set.of(), false, false, false, null);
                    check(f.mRoot.visibility == View.GONE && f.mRows.getChildCount() == 0, "no-shell table rendered");
                }
                """ + RuntimeSourceFixture.methods("DisplayTableView", "render", "reconcileRows", "selectDisplay",
                        "sameDisplay", "orderedDisplays", "newlyAvailableDisplay", "selectedDisplay"));
    }

    @Test
    public void creationResolutionSnapshotsTheSelectedDisplay() {
        final VirtualDisplaySpec previous = new VirtualDisplaySpec(1920, 1080, 200);
        for (final String source : new String[] {"wired", "wireless", "phone", "internal", "virtual"}) {
            final DesktopDisplayInfo selected = new DesktopDisplayInfo(7, "display:7", "Display", source,
                    2560, 1080, 320, true, false, false, false);
            assertArrayEquals(new int[] {2560, 1080},
                    creationResolution(selected, previous));
        }
        final DesktopDisplayInfo portrait = new DesktopDisplayInfo(0, "display:0", "Phone", "phone",
                1216, 2688, 520, true, false, false, true);
        assertArrayEquals(new int[] {1216, 2688}, creationResolution(portrait, previous));
        assertEquals(1920, previous.width);
        assertEquals(1080, previous.height);
    }

    @Test
    public void creationResolutionFallsBackOnlyWhenNoDisplayIsSelected() {
        final VirtualDisplaySpec previous = new VirtualDisplaySpec(1280, 720, 160);
        assertArrayEquals(new int[] {1280, 720}, creationResolution(null, previous));
        final DesktopDisplayInfo small = new DesktopDisplayInfo(7, "display:7", "Display", "virtual",
                240, 240, 160, false, false, false, false);
        assertArrayEquals(new int[] {240, 240}, creationResolution(small, previous));
    }

    @Test
    public void creationDialogInitiallySelectsTheResolutionSnapshot() throws Exception {
        final String dialog = RuntimeSourceFixture.methods("DisplayCreationDialog", "showPrepared");
        assertTrue(dialog.contains("defaults.width, defaults.height"));
        assertTrue(dialog.contains("R.string.display_resolution_default, resolution[0], resolution[1]"));
        assertTrue(dialog.contains("R.string.display_scale, defaults.densityDpi * 100 / 160"));
        assertTrue(dialog.contains("preset.setSelection(0)"));
        assertTrue(dialog.contains("width.setEnabled(custom)"));
        assertTrue(dialog.contains("height.setEnabled(custom)"));
    }

    @Test
    public void outputControlsBelongOnlyToTheSelectedWiredDisplay() {
        assertTrue(DisplayTableView.hasOutputControls(display(3, "wired", true, false), true));
        assertFalse(DisplayTableView.hasOutputControls(display(3, "wired", true, false), false));
        assertFalse(DisplayTableView.hasOutputControls(display(0, "phone", true, false), true));
        assertFalse(DisplayTableView.hasOutputControls(display(4, "wireless", true, false), true));
        assertFalse(DisplayTableView.hasOutputControls(display(8, "virtual", true, true), true));
        assertFalse(DisplayTableView.hasOutputControls(null, true));
    }

    @Test
    public void outputButtonHasAStableSlotInTheDisplayActionGrid() throws Exception {
        final String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/DisplayTableView.java"));
        assertFalse(source.contains("mOutput.setVisibility"));
        assertFalse(source.contains("mOutputOptions"));
        final String actions = RuntimeSourceFixture.methods("PhoneControlPanelController", "addDesktopActions");
        assertTrue(source.contains("enabled && hasOutputControls(display, outputAvailable)"));
        assertTrue(source.contains("mActions.openOutputSettings(display)"));
        assertFalse(actions.contains("addControlSection"));
        final String dialog = RuntimeSourceFixture.methods("DisplayOutputDialog", "show");
        assertTrue(dialog.contains("selection.current.displayLabel"));
        assertTrue(dialog.contains("if (!configurable || modes.isEmpty())"));
        assertFalse(dialog.contains("setOnItemSelectedListener"));
    }

    @Test
    public void outputDialogSelectsThePreferenceWithoutMistakingItForTheCurrentMode() {
        final var current = new PlatformProjectionDriver.Mode("1920x1080@60", "1080p 60 Hz");
        final var preferred = new PlatformProjectionDriver.Mode("1920x1080@120", "1080p 120 Hz");
        final var modes = java.util.List.of(new PlatformProjectionDriver.Mode("", "System / native"),
                current, preferred);
        final var selection = new PlatformProjectionDriver.ModeSelection(current, preferred, current,
                modes.subList(1, 3), true, true, false);
        assertEquals(2, DisplayOutputDialog.outputModeIndex(selection, modes));
        assertEquals(0, DisplayOutputDialog.outputModeIndex(selection.withPreferredTiming(null), modes));
        assertEquals(-1, DisplayOutputDialog.outputModeIndex(selection, java.util.List.of(current)));
        assertEquals(-1, DisplayOutputDialog.outputModeIndex(selection, java.util.List.of()));
        assertEquals(-1, DisplayOutputDialog.outputModeIndex(new PlatformProjectionDriver.ModeSelection(
                current, null, null, java.util.List.of(current), false), modes));
    }

    @Test
    public void outputControlsLockOnlyTheSelectedDesktop() {
        final DesktopDisplayInfo wired = display(3, "wired", true, false);
        final PlatformProjectionDriver.Mode mode = new PlatformProjectionDriver.Mode("1920x1080@60", "1080p 60 Hz");
        final PlatformProjectionDriver.ModeSelection selection = new PlatformProjectionDriver.ModeSelection(
                mode, mode, mode, java.util.List.of(mode), true);
        assertTrue(DisplayTableView.hasOutputControls(wired, true));
        assertTrue(DisplayTableView.canConfigureOutput(wired, true, selection, java.util.Set.of(), true, false));
        for (final int activeId : new int[] {0, 3, 8}) {
            assertEquals(activeId != 3, DisplayTableView.canConfigureOutput(wired, true, selection, java.util.Set.of(activeId), true, false));
        }
        assertFalse(DisplayTableView.canConfigureOutput(wired, true, selection, java.util.Set.of(), true, true));
        assertFalse(DisplayTableView.canConfigureOutput(wired, true, selection, java.util.Set.of(), false, false));
        assertFalse(DisplayTableView.canConfigureOutput(wired, false, selection, java.util.Set.of(), true, false));
        assertFalse(DisplayTableView.canConfigureOutput(wired, true, null, java.util.Set.of(), true, false));
        assertFalse(DisplayTableView.canConfigureOutput(display(0, "phone", true, false),
                true, selection, java.util.Set.of(), true, false));
    }

    @Test
    public void outputControlsRequireSelectableModesOrASystemDefault() {
        final DesktopDisplayInfo wired = display(3, "wired", true, false);
        final PlatformProjectionDriver.ModeSelection empty = new PlatformProjectionDriver.ModeSelection(
                null, null, null, java.util.List.of(), true);
        assertFalse(DisplayTableView.canConfigureOutput(wired, true, empty, java.util.Set.of(), true, false));
        final PlatformProjectionDriver.ModeSelection systemDefault = new PlatformProjectionDriver.ModeSelection(
                null, null, null, java.util.List.of(), true, true, true);
        assertTrue(DisplayTableView.canConfigureOutput(wired, true, systemDefault, java.util.Set.of(), true, false));
        final PlatformProjectionDriver.ModeSelection readOnly = new PlatformProjectionDriver.ModeSelection(
                null, null, null, java.util.List.of(), false, true, true);
        assertFalse(DisplayTableView.canConfigureOutput(wired, true, readOnly, java.util.Set.of(), true, false));
    }

    @Test
    public void closeDesktopRequiresReadySession() {
        assertTrue(PhoneControlPanelController.canCloseDesktop(
                true, true, false));
        assertFalse(PhoneControlPanelController.canCloseDesktop(
                true, false, false));
        assertFalse(PhoneControlPanelController.canCloseDesktop(
                false, true, false));
        assertFalse(PhoneControlPanelController.canCloseDesktop(
                true, true, true));
    }

    @Test
    public void displaySelectionDoesNotDependOnOtherWorkspaces() {
        final DesktopDisplayInfo phone = display(0, "phone", true, false);
        final DesktopDisplayInfo external = display(5, "virtual", true, true);
        assertTrue(DisplayTableView.canStart(phone, true, false, 35));
        assertTrue(DisplayTableView.canStart(phone, true, false, 35));
        assertTrue(DisplayTableView.canStart(phone, true, false, 35));
        assertTrue(DisplayTableView.canStart(external, true, false, 35));
        assertTrue(DisplayTableView.canStart(external, true, false, 35));
        assertTrue(DisplayTableView.canStart(external, true, false, 35));
        assertFalse(DisplayTableView.canStart(external, false, false, 35));
        assertFalse(DisplayTableView.canStart(external, true, true, 35));
        assertFalse(DisplayTableView.canStart(null, true, false, 35));
        assertFalse(DisplayTableView.canStart(
                display(6, "internal", false, false), true, false, 35));
        assertFalse(DisplayTableView.canStart(phone, true, false, 34));
        assertFalse(DisplayTableView.canStart(external, true, false, 34));
    }

    @Test public void startIsAvailableForPortableOutputsButStillRequiresDesktopSdk() {
        final var cast = new DesktopDisplayInfo(7, "cast", "Cast", "virtual",
                1920, 1080, 160, false, true, false, false);
        assertTrue(DisplayTableView.canStart(cast, true, false, 35));
        assertFalse(DisplayTableView.canStart(cast, true, false, 34));
        assertFalse(DisplayTableView.canStart(cast, false, false, 35));
        assertFalse(DisplayTableView.canStart(cast, true, true, 35));
    }

    static DesktopDisplayInfo display(final int id, final String source,
            final boolean supported, final boolean owned) {
        return new DesktopDisplayInfo(id, "display:" + id, "Display", source,
                1920, 1080, 160, supported, false, owned, false);
    }

    private static int[] creationResolution(DesktopDisplayInfo reference, VirtualDisplaySpec previous) {
        final DisplayProfiles.CreationDefaults defaults = DisplayProfiles.snapshot(reference,
                reference == null ? null : new DisplayProfileStore.Profile(DisplayProfiles.key(reference)), previous);
        return new int[] {defaults.width, defaults.height};
    }
}
