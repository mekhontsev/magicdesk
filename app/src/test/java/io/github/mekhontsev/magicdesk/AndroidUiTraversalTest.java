package io.github.mekhontsev.magicdesk;

import org.junit.Test;

/** Execute the production traversal with a deterministic, non-Binder tree. */
public final class AndroidUiTraversalTest {
    @Test public void filteredSearchBoundsOwnershipAndFreshness() throws Exception {
        RuntimeSourceFixture.verify("""
                static class JSONException extends Exception {}
                static class JSONObject {
                    static final Object NULL=new Object();
                    final Map<String,Object> values=new HashMap<>();
                    JSONObject put(String key,Object value) { values.put(key,value); return this; }
                }
                static class JSONArray {
                    final List<JSONObject> values=new ArrayList<>();
                    void put(JSONObject value) { values.add(value); }
                    int length() { return values.size(); }
                }
                static class Rect {}
                static class SystemClock { static long now; static long uptimeMillis() { return now; } }
                static class AccessibilityNodeInfo {
                    final List<AccessibilityNodeInfo> children=new ArrayList<>();
                    String text; int recycled, childReads;
                    AccessibilityNodeInfo(String t) { text=t; }
                    AccessibilityNodeInfo(AccessibilityNodeInfo n) { text=n.text; children.addAll(n.children); }
                    int getChildCount() { return children.size(); }
                    AccessibilityNodeInfo getChild(int i,int flags) { childReads++; return children.get(i); }
                    void recycle() { if(++recycled!=1) throw new AssertionError("double recycle"); }
                }
                static class AccessibilityWindowInfo {
                    AccessibilityNodeInfo root;
                    AccessibilityWindowInfo(AccessibilityNodeInfo n) { root=n; }
                    int getId() { return 1; } int getType() { return 1; } int getLayer() { return 0; }
                    boolean isActive() { return true; } boolean isFocused() { return true; }
                    String getTitle() { return "Window"; }
                    void getBoundsInScreen(Rect r) {}
                    AccessibilityNodeInfo getRoot(int flags) { return root; }
                }
                record AndroidUiSelector(String text) {
                    boolean matches(JSONObject node) { return text.equals(node.values.get("text")); }
                    boolean couldMatchRedacted(JSONObject node) { return false; }
                }
                record AndroidUiScope(int maxNodes, AndroidUiSelector selector) {}
                record Handle(AccessibilityNodeInfo node,String identity) {}
                record Pending(AccessibilityNodeInfo node,String parent,int depth) {}
                final String id="snapshot";
                final long createdAt=0;
                final JSONArray windows=new JSONArray(), nodes=new JSONArray();
                final Map<String,Handle> mHandles=new HashMap<>();
                AndroidUiScope mScope;
                boolean mHierarchyComplete=true, mRedactedMatch, mTextTruncated, mCacheCleared=true;
                long mGenerationStart,mGenerationEnd;
                int mVisited,mTextRemaining=65536;
                static JSONObject bounds(Rect r) { return new JSONObject(); }
                static String identity(AccessibilityNodeInfo n) { return n.text; }
                JSONObject describe(AccessibilityNodeInfo n) { return new JSONObject().put("text",n.text); }
                void previewField(JSONObject n,String key) {
                    Object value=n.values.get(key);
                    if(value instanceof String) n.put(key,preview((String)value));
                }
                static AccessibilityNodeInfo rows(int count) {
                    var root=new AccessibilityNodeInfo("root");
                    for(int i=0;i<count;i++) root.children.add(new AccessibilityNodeInfo("row "+i));
                    return root;
                }
                static Fixture capture(AccessibilityNodeInfo root,int max,String selector) throws Exception {
                    var f=new Fixture();
                    f.mScope=new AndroidUiScope(max,selector==null?null:new AndroidUiSelector(selector));
                    f.captureWindow(new AccessibilityWindowInfo(root),null);
                    return f;
                }
                """ + RuntimeSourceFixture.methods("AndroidUiSnapshot", "captureWindow", "exhausted", "scanLimit",
                        "preview", "stable", "complete", "observedBetween") + """
                public static void verify() throws Exception {
                    var root=rows(320); var f=capture(root,1,"row 319");
                    check(f.nodes.length()==1 && f.mVisited==321,"find beyond wire node limit");
                    check(root.recycled==1 && root.children.get(0).recycled==1,"unmatched nodes released");
                    check(root.children.get(319).recycled==0,"selected node retained");
                    root=rows(5000); f=capture(root,256,"missing");
                    check(f.mVisited==4096 && root.childReads==4095,"bounded candidate queue");
                    check(!f.complete(),"bounded traversal cannot prove absence");
                    root=rows(320); f=capture(root,2,null);
                    check(f.nodes.length()==2 && root.childReads==1,"unfiltered reads stay bounded too");
                    check(!f.complete(),"wire truncation recorded");
                    root=rows(320); f=capture(root,2,"row 0");
                    check(f.complete(),"complete filtered traversal");
                    f.observedBetween(4,5);
                    check(!f.stable() && !f.complete(),"concurrent event is not a stable result");
                    f.observedBetween(5,5); f.mCacheCleared=false;
                    check(!f.stable(),"cache invalidation failure is unknown freshness");
                    var longText=new AccessibilityNodeInfo("x".repeat(600));
                    f=capture(longText,2,longText.text);
                    check(f.nodes.length()==1 && f.mTextTruncated && f.complete(),"match precedes preview");
                    check(((String)f.nodes.values.get(0).values.get("text")).length()==512,"preview bound");
                    f=new Fixture();
                    check(f.preview("x".repeat(511)+"\\ud83d\\ude00").length()==511,"preview keeps surrogate pairs");
                    SystemClock.now=3001; f=capture(rows(1),2,null);
                    check(!f.complete() && f.mVisited==0,"time budget");
                }
                """);
    }
}
