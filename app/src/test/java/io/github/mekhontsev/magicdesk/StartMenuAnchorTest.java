package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class StartMenuAnchorTest {
    @Test public void restorationWaitsForMeasuredColumnsAndDiscardsReplacedViews() throws Exception {
        RuntimeSourceFixture.verify("""
                static class View {
                    interface OnLayoutChangeListener {
                        void onLayoutChange(View v,int l,int t,int r,int b,int ol,int ot,int or,int ob);
                    }
                }
                static class GridView extends View {
                    OnLayoutChangeListener listener;
                    boolean measured;
                    int position=-1, offset, restores;
                    void addOnLayoutChangeListener(OnLayoutChangeListener value) { listener=value; }
                    void removeOnLayoutChangeListener(OnLayoutChangeListener value) { check(listener==value,"wrong listener"); listener=null; }
                    void setSelectionFromTop(int p,int o) { check(measured,"selection before column measurement"); position=p;offset=o;restores++; }
                    void layout() { measured=true; if(listener!=null) listener.onLayoutChange(this,0,0,100,100,0,0,0,0); }
                }
                GridView mEntriesView;
                boolean mReleased;
                public static void verify() {
                    var host=new Fixture(); var grid=new GridView(); host.mEntriesView=grid;
                    host.restoreEntryAnchor(grid,19,-12);
                    check(grid.restores==0,"restored before layout"); grid.layout(); grid.layout();
                    check(grid.restores==1 && grid.position==19 && grid.offset==-12,"anchor lost or repeated");
                    var detached=new GridView(); host.mEntriesView=detached;
                    host.restoreEntryAnchor(detached,7,0); host.mEntriesView=new GridView(); detached.layout();
                    check(detached.restores==0 && detached.listener==null,"stale layout changed selection");
                    var released=host.mEntriesView; host.restoreEntryAnchor(released,1,0); host.mReleased=true; released.layout();
                    check(released.restores==0 && released.listener==null,"released host changed selection");
                }
                """ + RuntimeSourceFixture.methods("StartMenuContent", "restoreEntryAnchor"));
    }
}
