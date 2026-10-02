package io.github.mekhontsev.magicdesk;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class WorkspaceAppearanceTest {
    private static final String WORK = "profile:0/workspace:work";
    private static final String OTHER = "profile:10/workspace:work";
    private static final String ACCENT = "{\"colors\":{\"accent\":\"#123456\"}}";
    private static final String SHAPE = "{\"shape\":{\"borderDp\":2}}";

    @Test public void sparseOverridesInheritLaterGlobalChangesWithoutFlattening() throws Exception {
        var store = WorkspaceAppearance.defaults().apply(WORK, ACCENT);
        assertEquals(0xff123456, store.current(WORK).palette().color(UiColor.ACCENT));
        assertEquals(store.current(), store.current(OTHER));
        var changed = store.apply(ShellAppearance.preset("light"));
        assertEquals(0xff123456, changed.current(WORK).palette().color(UiColor.ACCENT));
        assertEquals(changed.current().palette().color(UiColor.BACKGROUND), changed.current(WORK).palette().color(UiColor.BACKGROUND));
        assertEquals(changed.current().typography(), changed.current(WORK).typography());
        var saved = new JSONObject(changed.savedOverrides()).getJSONObject(WORK);
        assertEquals(1, saved.length());
        assertEquals(1, saved.getJSONObject("colors").length());
        assertSame(changed.current(WORK), changed.current(WORK));
    }

    @Test public void recursiveObjectsMergeButArraysReplace() throws Exception {
        var store = WorkspaceAppearance.defaults().apply(WORK,
                "{\"composition\":{\"start\":{\"sections\":[\"apps\"]}},\"typography\":{\"scale\":1.2}}");
        var actual = store.current(WORK);
        assertEquals(List.of(ShellComposition.Section.APPS), actual.composition().start().sections());
        assertEquals(store.current().composition().start().presentation(), actual.composition().start().presentation());
        assertEquals(store.current().composition().panels(), actual.composition().panels());
        assertEquals(1.2f, actual.typography().scale(), 0);
        assertEquals(store.current().typography().font(), actual.typography().font());
    }

    @Test public void sparseWorkspaceBackdropsInheritGlobalChangesAndSurviveRestore() throws Exception {
        var global = ShellAppearance.defaults().withBackdrop(new ShellAppearance.Backdrop(.6f, 24));
        var store = WorkspaceAppearance.defaults().apply(global).apply(WORK, "{\"backdrop\":{\"opacity\":0.5}}");
        assertEquals(new ShellAppearance.Backdrop(.5f, 24), store.current(WORK).backdrop());
        assertEquals(global.backdrop(), store.current(OTHER).panelBackdrop("main"));
        assertEquals(store.current(WORK).backdrop(), store.current(WORK).panelBackdrop("main"));
        var changed = store.apply(global.withBackdrop(new ShellAppearance.Backdrop(.8f, 32)));
        assertEquals(new ShellAppearance.Backdrop(.5f, 32), changed.current(WORK).panelBackdrop("main"));
        var saved = new JSONObject(changed.savedOverrides()).getJSONObject(WORK).getJSONObject("backdrop");
        assertEquals(1, saved.length());
        assertFalse(saved.has("blurRadiusDp"));
        var restored = WorkspaceAppearance.restore(changed.savedGlobal(), changed.savedOverrides());
        assertEquals(changed.current(), restored.current());
        assertEquals(changed.current(WORK), restored.current(WORK));
        assertNull(restored.current(WORK).composition().panel("main").style().backdrop());
    }

    @Test public void panelsAreReplacedByStablePanelIdentityNotArrayIndexes() throws Exception {
        var store = WorkspaceAppearance.defaults().apply(WORK, """
                {"composition":{"panels":[{"id":"left","edge":"left",
                  "style":{"backdrop":{"opacity":0.5}},"components":[{"type":"start"}]}]}}
                """);
        var panels = store.current(WORK).composition().panels();
        assertEquals(1, panels.size());
        assertEquals("left", panels.get(0).id());
        assertEquals(ShellPanel.Edge.LEFT, panels.get(0).edge());
        assertEquals(.5f, panels.get(0).style().backdrop().opacity(), 0);
        assertEquals(1, panels.get(0).components().size());
        assertNull(store.current(WORK).composition().panel("main"));
    }

    @Test public void replacingPatchDoesNotMergePreviousLocalValues() throws Exception {
        var store = WorkspaceAppearance.defaults().apply(WORK, ACCENT).apply(WORK, SHAPE);
        assertEquals(store.current().palette(), store.current(WORK).palette());
        assertEquals(2, store.current(WORK).shape().borderDp(), 0);
        assertFalse(new JSONObject(store.snapshot(WORK).patch()).has("colors"));
    }

    @Test public void globalAndTwoWorkspacePreviewsRemainIndependent() throws Exception {
        var store = WorkspaceAppearance.defaults().apply(WORK, ACCENT)
                .preview(ShellAppearance.preset("light")).preview(WORK, SHAPE).preview(OTHER, ACCENT);
        assertNotEquals(store.snapshot().previewId(), store.snapshot(WORK).previewId());
        assertNotEquals(store.snapshot(WORK).previewId(), store.snapshot(OTHER).previewId());
        assertEquals(ShellAppearance.preset("light"), store.current());
        assertEquals(2, store.current(WORK).shape().borderDp(), 0);
        assertEquals(store.current().palette(), store.current(WORK).palette());
        assertEquals(0xff123456, store.snapshot(WORK).committed().palette().color(UiColor.ACCENT));
        assertEquals(ShellAppearance.defaults(), store.snapshot(OTHER).committed());
        assertEquals(store.current().palette().color(UiColor.BACKGROUND), store.current(OTHER).palette().color(UiColor.BACKGROUND));
    }

    @Test public void confirmingWorkspaceDoesNotCommitGlobalPreview() throws Exception {
        var store = WorkspaceAppearance.defaults().preview(ShellAppearance.preset("light")).preview(WORK, ACCENT);
        String globalId = store.snapshot().previewId(), localId = store.snapshot(WORK).previewId();
        store = store.confirm(WORK, localId);
        assertEquals(globalId, store.snapshot().previewId());
        assertNull(store.snapshot(WORK).previewId());
        var restarted = WorkspaceAppearance.restore(store.savedGlobal(), store.savedOverrides());
        assertEquals(ShellAppearance.defaults(), restarted.current());
        assertEquals(0xff123456, restarted.current(WORK).palette().color(UiColor.ACCENT));
        assertEquals(ShellAppearance.defaults().palette().color(UiColor.BACKGROUND), restarted.current(WORK).palette().color(UiColor.BACKGROUND));
        assertEquals(List.of(WORK), restarted.listScopes());
    }

    @Test public void confirmingGlobalDoesNotPersistWorkspacePreviews() throws Exception {
        var store = WorkspaceAppearance.defaults().apply(WORK, ACCENT)
                .preview(WORK, SHAPE).preview(OTHER, ACCENT).preview(ShellAppearance.preset("light"));
        String id = store.snapshot(WORK).previewId();
        store = store.confirm(store.snapshot().previewId());
        assertEquals(id, store.snapshot(WORK).previewId());
        var restarted = WorkspaceAppearance.restore(store.savedGlobal(), store.savedOverrides());
        assertEquals(ShellAppearance.preset("light"), restarted.current());
        assertEquals(0xff123456, restarted.current(WORK).palette().color(UiColor.ACCENT));
        assertEquals(1, restarted.current(WORK).shape().borderDp(), 0);
        assertEquals(List.of(WORK), restarted.listScopes());
        assertNull(restarted.snapshot(WORK).previewId());
    }

    @Test public void eitherPreviewCancellationOrderRestoresInheritedCommittedTheme() throws Exception {
        var original = WorkspaceAppearance.defaults().apply(WORK, ACCENT);
        var preview = original.preview(WORK, SHAPE).preview(ShellAppearance.preset("contrast"));
        String localId = preview.snapshot(WORK).previewId(), globalId = preview.snapshot().previewId();
        assertEquals(original.current(WORK), preview.cancel(globalId).cancel(WORK, localId).current(WORK));
        assertEquals(original.current(WORK), preview.cancel(WORK, localId).cancel(globalId).current(WORK));
    }

    @Test public void invalidAndOverlappingPreviewsLeaveExactLeaseUnchanged() throws Exception {
        var store = WorkspaceAppearance.defaults().preview(WORK, ACCENT);
        var before = store.snapshot(WORK);
        assertThrows(IllegalStateException.class, () -> store.preview(WORK, SHAPE));
        assertThrows(IllegalArgumentException.class, () -> store.apply(WORK, "{\"shape\":{\"borderDp\":5}}"));
        assertThrows(IllegalArgumentException.class, () -> store.confirm(OTHER, before.previewId()));
        assertThrows(IllegalArgumentException.class, () -> store.cancel(OTHER, before.previewId()));
        assertThrows(IllegalArgumentException.class, () -> store.confirm(before.previewId()));
        assertThrows(IllegalArgumentException.class, () -> store.cancel(WORK, null));
        assertEquals(before, store.snapshot(WORK));
        assertEquals("{}", store.savedOverrides());
    }

    @Test public void applySupersedesOnlyItsOwnPreviewAndRejectsStaleOwners() throws Exception {
        var preview = WorkspaceAppearance.defaults().preview(WORK, ACCENT).preview(OTHER, SHAPE);
        String id = preview.snapshot(WORK).previewId(), otherId = preview.snapshot(OTHER).previewId();
        var applied = preview.apply(WORK, SHAPE);
        assertEquals(otherId, applied.snapshot(OTHER).previewId());
        assertNull(applied.snapshot(WORK).previewId());
        assertThrows(IllegalArgumentException.class, () -> applied.cancel(WORK, id));
        var confirmed = preview.confirm(WORK, id);
        assertThrows(IllegalArgumentException.class, () -> confirmed.confirm(WORK, id));
        var next = confirmed.preview(WORK, SHAPE);
        assertThrows(IllegalArgumentException.class, () -> next.cancel(WORK, id));
    }

    @Test public void removeOverrideInvalidatesLeaseWithoutTouchingOtherScopes() throws Exception {
        var preview = WorkspaceAppearance.defaults().apply(WORK, ACCENT).preview(WORK, SHAPE).preview(OTHER, ACCENT);
        String id = preview.snapshot(WORK).previewId();
        var removed = preview.removeOverride(WORK);
        assertEquals(removed.current(), removed.current(WORK));
        assertEquals(List.of(OTHER), removed.listScopes());
        assertEquals("{}", removed.savedOverrides());
        assertThrows(IllegalArgumentException.class, () -> removed.cancel(WORK, id));
        assertTrue(removed.snapshot(WORK).revision() > preview.snapshot(WORK).revision());
    }

    @Test public void emptyPatchCanPreviewAndConfirmReturnToGlobalDefaults() throws Exception {
        var store = WorkspaceAppearance.defaults().apply(WORK, ACCENT).preview(WORK, "{}");
        String id = store.snapshot(WORK).previewId();
        assertEquals(store.current(), store.current(WORK));
        assertEquals(0xff123456, store.cancel(WORK, id).current(WORK).palette().color(UiColor.ACCENT));
        var confirmed = store.confirm(WORK, id);
        assertEquals(List.of(), confirmed.listScopes());
        assertEquals("{}", confirmed.savedOverrides());
        assertTrue(WorkspaceAppearance.defaults().apply(WORK, ACCENT).apply(WORK, "{}").listScopes().isEmpty());
    }

    @Test public void readOnlyScopesAreNotRetainedAndIdentityIncludesProfileBoundary() throws Exception {
        var store = WorkspaceAppearance.defaults().apply(WORK, ACCENT);
        for (int i = 0; i < 1000; i++) assertSame(store.current(), store.current("profile:0/unused:" + i));
        assertEquals(List.of(WORK), store.listScopes());
        assertEquals(store.current(), store.current(OTHER));
        assertEquals(0xff123456, WorkspaceAppearance.restore(store.savedGlobal(), store.savedOverrides())
                .current(WORK).palette().color(UiColor.ACCENT));
        assertThrows(UnsupportedOperationException.class, () -> store.listScopes().clear());
    }

    @Test public void validatesEveryPatchBeforeAcceptingIt() throws Exception {
        var store = WorkspaceAppearance.defaults().apply(WORK, ACCENT);
        for (String patch : new String[] {null, "[]", "{} trailing", "{\"preset\":\"light\"}",
                "{\"version\":2}", "{\"command\":\"id\"}", "{\"colors\":{\"bogus\":null}}",
                "{\"colors\":{\"bogus\":\"#123456\"}}", "{\"shape\":{\"radiusScale\":3}}",
                "{\"motion\":{\"reduced\":\"true\"}}", "{\"typography\":{\"scale\":\"1.2\"}}",
                "{\"composition\":{\"panels\":[{\"id\":\"main\"}]}}",
                "{\"composition\":{\"start\":{\"sections\":[\"tools\"]}}}",
                "{\"composition\":{\"start\":{\"sections\":[\"apps\",\"apps\"]}}}",
                "{\"composition\":{\"panels\":[{\"id\":\"main\",\"components\":[{\"type\":\"tasks\",\"label\":\"x\"}]}]}}",
                "{\"colors\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}",
                " ".repeat(ShellAppearanceJson.MAX_BYTES + 1)}) {
            assertThrows(String.valueOf(patch), Exception.class, () -> store.apply(WORK, patch));
            assertThrows(String.valueOf(patch), Exception.class, () -> store.preview(WORK, patch));
            assertEquals(0xff123456, store.current(WORK).palette().color(UiColor.ACCENT));
            assertNull(store.snapshot(WORK).previewId());
        }
    }

    @Test public void scopeIdentityIsBoundedExactAndNeverNormalized() {
        for (String scope : new String[] {null, "", " ", " workspace", "workspace ", "a\nb", "a\u0000b",
                "x".repeat(513), "\u00e9".repeat(257), "x\ud800", "x\udc00"}) {
            assertThrows(IllegalArgumentException.class, () -> WorkspaceAppearance.requireScope(scope));
        }
        assertEquals("\u00e9".repeat(256), WorkspaceAppearance.requireScope("\u00e9".repeat(256)));
        assertEquals("scope:\ud83d\udcbb", WorkspaceAppearance.requireScope("scope:\ud83d\udcbb"));
    }

    @Test public void scopeCountIncludesActivePreviewsAndCanBeReleased() throws Exception {
        var state = WorkspaceAppearance.defaults();
        for (int i = 0; i < WorkspaceAppearance.MAX_SCOPES; i++) state = state.preview("workspace:" + i, ACCENT);
        var full = state;
        assertThrows(IllegalArgumentException.class, () -> full.apply("workspace:overflow", SHAPE));
        assertThrows(IllegalArgumentException.class, () -> full.preview("workspace:overflow", SHAPE));
        assertEquals(WorkspaceAppearance.MAX_SCOPES, full.listScopes().size());
        var released = full.cancel("workspace:0", full.snapshot("workspace:0").previewId());
        assertEquals(WorkspaceAppearance.MAX_SCOPES, released.apply("workspace:overflow", SHAPE).listScopes().size());
    }

    @Test public void aggregatePersistenceBudgetRejectsWithoutEvictingExistingScopes() throws Exception {
        JSONArray panels = new JSONArray();
        for (int i = 0; i < 4; i++) {
            JSONArray components = new JSONArray();
            for (int j = 0; j < 24; j++) components.put(new JSONObject().put("type", "spacer")
                    .put("widthDp", 240).put("minViewportDp", 4096).put("visibility", "expanded"));
            panels.put(new JSONObject().put("id", "panel" + i).put("components", components));
        }
        String patch = ShellAppearanceJson.encode(ShellAppearanceJson.parse(new JSONObject()
                .put("composition", new JSONObject().put("panels", panels)).toString())).toString();
        assertTrue(WorkspaceAppearancePatch.bytes(patch) > WorkspaceAppearance.MAX_OVERRIDE_BYTES / WorkspaceAppearance.MAX_SCOPES);
        var state = WorkspaceAppearance.defaults();
        int accepted = 0;
        for (; accepted < WorkspaceAppearance.MAX_SCOPES; accepted++) {
            try { state = state.apply("workspace:" + accepted, patch); }
            catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("256 KiB")); break; }
        }
        assertTrue(accepted > 0 && accepted < WorkspaceAppearance.MAX_SCOPES);
        assertEquals(accepted, state.listScopes().size());
        assertTrue(WorkspaceAppearancePatch.bytes(state.savedOverrides()) <= WorkspaceAppearance.MAX_OVERRIDE_BYTES);
        assertEquals(accepted, WorkspaceAppearance.restore(state.savedGlobal(), state.savedOverrides()).listScopes().size());
    }

    @Test public void rejectsMalformedOrUnboundedPersistenceWithoutPartialRestore() throws Exception {
        String global = WorkspaceAppearance.defaults().savedGlobal();
        for (String saved : new String[] {"[]", "{} trailing", "{\"scope\":null}", "{\"scope\":42}",
                "{\"scope\":{\"shape\":{\"borderDp\":99}}}", "{\"\":{}}", " ".repeat(WorkspaceAppearance.MAX_OVERRIDE_BYTES + 1)}) {
            assertThrows(Exception.class, () -> WorkspaceAppearance.restore(global, saved));
        }
        var overflow = new JSONObject();
        for (int i = 0; i <= WorkspaceAppearance.MAX_SCOPES; i++) overflow.put("workspace:" + i, new JSONObject(ACCENT));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceAppearance.restore(global, overflow.toString()));
    }

    @Test public void assetReferencesMustResolveToABundleBeforeStateCanChange() throws Exception {
        var store = WorkspaceAppearance.defaults();
        for (String patch : new String[] {"{\"resources\":{\"font\":\"fonts/ui.ttf\"}}",
                "{\"resources\":{\"wallpaper\":\"wallpapers/work.png\"}}",
                "{\"resources\":{\"iconAssets\":{\"desktop\":\"icons/desktop.png\"}}}"}) {
            assertThrows(IllegalArgumentException.class, () -> store.apply(WORK, patch));
            assertThrows(IllegalArgumentException.class, () -> store.preview(WORK, patch));
            ShellAppearance unbound = ShellAppearanceJson.parse(patch);
            assertThrows(IllegalArgumentException.class, () -> store.apply(unbound));
            assertThrows(IllegalArgumentException.class, () -> store.preview(unbound));
        }
        String digest = "a".repeat(64);
        var global = ShellAppearanceJson.parse("{\"resources\":{\"bundle\":\"" + digest + "\"}}");
        var bound = store.apply(global).apply(WORK, "{\"resources\":{\"font\":\"fonts/ui.ttf\"}}");
        assertEquals(digest, bound.current(WORK).resources().bundle());
        assertEquals("fonts/ui.ttf", bound.current(WORK).resources().font());
        assertThrows(IllegalArgumentException.class, () -> bound.apply(ShellAppearance.defaults()));
        assertThrows(IllegalArgumentException.class, () -> bound.preview(ShellAppearance.defaults()));
        assertEquals(digest, bound.snapshot().committed().resources().bundle());
    }

    @Test public void preparationIncludesBothGlobalAndLocalPreviewCancellationOrders() throws Exception {
        String digest = "b".repeat(64);
        var global = ShellAppearanceJson.parse("{\"resources\":{\"bundle\":\"" + digest + "\",\"font\":\"fonts/one.ttf\"}}");
        var alternate = ShellAppearanceJson.parse("{\"resources\":{\"bundle\":\"" + digest + "\",\"font\":\"fonts/two.ttf\"}}");
        var store = WorkspaceAppearance.defaults().apply(global)
                .apply(WORK, "{\"resources\":{\"wallpaper\":\"wallpapers/one.png\"}}")
                .preview(alternate).preview(WORK, "{\"resources\":{\"wallpaper\":\"wallpapers/two.png\"}}");
        assertTrue(store.resources().contains(global.resources()));
        assertTrue(store.resources().contains(alternate.resources()));
        for (String font : List.of("fonts/one.ttf", "fonts/two.ttf")) {
            for (String wallpaper : List.of("wallpapers/one.png", "wallpapers/two.png")) {
                assertTrue(store.resources().stream().anyMatch(r -> r.font().equals(font) && r.wallpaper().equals(wallpaper)));
            }
        }
    }

    @Test public void guardedGlobalPreviewChecksTheCapturedGlobalRevision() throws Exception {
        var original = WorkspaceAppearance.defaults();
        long revision = original.snapshot().revision();
        var preview = original.preview(ShellAppearance.preset("light"), revision);
        assertNotNull(preview.snapshot().previewId());
        assertEquals(ShellAppearance.preset("light"), preview.current());
        var changed = original.apply(ShellAppearance.preset("contrast"));
        assertThrows(IllegalStateException.class, () -> changed.preview(ShellAppearance.preset("light"), revision));
        assertNull(changed.snapshot().previewId());
        assertEquals(ShellAppearance.preset("contrast"), changed.current());
        var restored = changed.apply(original.current());
        assertEquals(original.current(), restored.current());
        assertThrows(IllegalStateException.class, () -> restored.preview(ShellAppearance.preset("light"), revision));
        assertNotNull(original.apply(OTHER, SHAPE).preview(ShellAppearance.preset("light"), revision).snapshot().previewId());
    }

    @Test public void guardedWorkspacePreviewRejectsLocalAndInheritedChangesWithoutMutation() throws Exception {
        var original = WorkspaceAppearance.defaults().apply(WORK, ACCENT);
        long revision = original.snapshot(WORK).revision();
        var preview = original.preview(WORK, SHAPE, revision);
        assertNotNull(preview.snapshot(WORK).previewId());
        assertEquals(2, preview.current(WORK).shape().borderDp(), 0);
        for (var changed : List.of(original.apply(WORK, SHAPE), original.apply(ShellAppearance.preset("light")),
                original.removeOverride(WORK), original.apply(OTHER, SHAPE))) {
            var snapshot = changed.snapshot(WORK);
            String saved = changed.savedOverrides();
            assertThrows(IllegalStateException.class, () -> changed.preview(WORK, ACCENT, revision));
            assertEquals(snapshot, changed.snapshot(WORK));
            assertEquals(saved, changed.savedOverrides());
        }
        var cancelled = preview.cancel(WORK, preview.snapshot(WORK).previewId());
        assertEquals(original.current(WORK), cancelled.current(WORK));
        assertThrows(IllegalStateException.class, () -> cancelled.preview(WORK, SHAPE, revision));
    }
}
