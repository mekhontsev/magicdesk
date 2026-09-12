package io.github.mekhontsev.magicdesk;

import org.junit.Test;

import static org.junit.Assert.*;

public final class TaskbarOverflowControllerTest {
    @Test public void refreshKeepsOpenMenuAndReleaseClosesOnlyItsOwnPanel() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Entry { }
                static class Panel { }
                static class DesktopPanelWindowController {
                    Panel requested;
                    int hides;
                    boolean isRequested(Panel panel) { return panel != null && requested == panel; }
                    void hide(Panel panel) { check(requested == panel, "wrong panel hidden"); requested = null; hides++; }
                }
                static class Activity {
                    DesktopPanelWindowController panels = new DesktopPanelWindowController();
                    DesktopPanelWindowController panels() { return panels; }
                }
                final Activity mActivity = new Activity();
                final List<Entry> mItems = new ArrayList<>();
                Panel mPanel = new Panel(), mList = new Panel();
                public static void verify() {
                    Fixture f = new Fixture(), otherDisplay = new Fixture();
                    Entry first = new Entry(), refreshed = new Entry();
                    f.setItems(List.of(first));
                    List<Entry> shown = List.copyOf(f.mItems);
                    f.mActivity.panels.requested = f.mPanel;
                    otherDisplay.mActivity.panels.requested = otherDisplay.mPanel;
                    f.setItems(List.of(refreshed));
                    check(f.mActivity.panels.isRequested(f.mPanel), "task refresh dismissed open/pending menu");
                    check(f.mActivity.panels.hides == 0, "refresh toggled panel visibility");
                    check(f.mItems.equals(List.of(refreshed)), "next menu has stale entries");
                    check(shown.equals(List.of(first)), "open menu snapshot was rewritten");
                    check(otherDisplay.mActivity.panels.isRequested(otherDisplay.mPanel), "another display affected");
                    f.setItems(List.of());
                    check(f.mActivity.panels.hides == 1, "empty overflow kept obsolete panel");
                    f.setItems(List.of());
                    check(f.mActivity.panels.hides == 1, "hidden menu dismissed twice");
                    f.setItems(List.of(first));
                    check(f.mActivity.panels.requested == null, "refresh reopened menu");
                    Panel anotherPanel = new Panel();
                    f.mActivity.panels.requested = anotherPanel;
                    f.setItems(List.of());
                    check(f.mActivity.panels.requested == anotherPanel, "clearing overflow dismissed another panel");
                    f.mActivity.panels.requested = f.mPanel;
                    f.setItems(List.of(first));
                    f.release();
                    check(f.mActivity.panels.requested == null && f.mPanel == null && f.mList == null,
                            "release retained panel");
                    check(f.mItems.isEmpty(), "release retained entries");
                }
                """ + RuntimeSourceFixture.methods("TaskbarOverflowController", "setItems", "hide", "release"));
    }

    @Test public void rebuildingButtonHasNoPanelLifecycleSideEffects() throws Exception {
        final String button = RuntimeSourceFixture.methods("TaskbarOverflowController", "createButton");
        assertFalse(button.contains("clear()"));
        assertFalse(button.contains("hide()"));
        assertFalse(button.contains("populate()"));
        final String render = RuntimeSourceFixture.methods("TaskbarController", "renderPins");
        assertTrue(render.contains("mOverflow.setItems(items.subList(visibleCount, items.size()))"));
        assertFalse(render.contains("mOverflow.clear()"));
        assertFalse(render.contains("mOverflow.release()"));
    }
}
