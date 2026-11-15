package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;

public final class NativeWindowScaleCorrectionTest {
    @Test public void observerRetainsScaleChangesEvenWhenFootprintIsUnchanged() throws Exception {
        verify("""
            var before = new FrameworkTaskSnapshot(); var after = new FrameworkTaskSnapshot();
            var a = new FreeformBoundsState(before); var b = new FreeformBoundsState(after);
            check(a.equals(b) && a.hashCode()==b.hashCode(), "unchanged geometry republished");
            after.windowScale = .75f;
            check(!a.equals(b), "scale change suppressed by equal footprint");
            after.windowScale = before.windowScale; after.logicalBounds = new Rect(10,20,1510,1220);
            check(!a.equals(b), "logical change suppressed by equal footprint");
            """);
    }
    @Test public void scaleReleaseIsIndependentOfGestureEdgeAndRetainsFractionalBaseline() throws Exception {
        verify("""
            var before = new FrameworkTaskSnapshot(); var after = new FrameworkTaskSnapshot();
            after.windowScale = .75f; after.bounds = new Rect(10,20,760,620);
            check(NativeWindowScaleCorrection.candidate(before, after), "scale commit missed");
            after.logicalBounds = new Rect(50,60,1050,860);
            check(NativeWindowScaleCorrection.candidate(before, after), "moving origin excluded left/top drag");
            check(!NativeWindowScaleCorrection.candidate(null, after), "initial launch corrected");
            check(!NativeWindowScaleCorrection.candidate(after, after), "duplicate publication replayed");
            """);
    }

    @Test public void actualResizeAndOurNormalizationAreNotNewScaleGestures() throws Exception {
        verify("""
            var before = new FrameworkTaskSnapshot(); var after = new FrameworkTaskSnapshot();
            after.logicalBounds = new Rect(10,20,1510,1220); after.windowScale = .75f;
            after.bounds = new Rect(10,20,1135,920);
            check(!NativeWindowScaleCorrection.candidate(before, after), "logical resize corrected again");
            after.logicalBounds = before.logicalBounds; after.windowScale = before.windowScale;
            check(!NativeWindowScaleCorrection.candidate(before, after), "move treated as scale");
            """);
    }

    @Test public void identityModeVisibilityDensityAndUnknownScaleInvalidateAdmission() throws Exception {
        verify("""
            var before = new FrameworkTaskSnapshot(); var after = new FrameworkTaskSnapshot();
            after.windowScale = .75f; after.bounds = new Rect(10,20,760,620);
            after.userId = 1; check(!NativeWindowScaleCorrection.candidate(before, after), "user"); after.userId=0;
            after.displayId = 3; check(!NativeWindowScaleCorrection.candidate(before, after), "display"); after.displayId=2;
            after.topActivityName = "different"; check(!NativeWindowScaleCorrection.candidate(before, after), "activity"); after.topActivityName="app/Main";
            after.windowingMode = 1; check(!NativeWindowScaleCorrection.candidate(before, after), "mode"); after.windowingMode=5;
            after.visible = false; check(!NativeWindowScaleCorrection.candidate(before, after), "visibility"); after.visible=true;
            after.densityDpi = 320; check(!NativeWindowScaleCorrection.candidate(before, after), "density"); after.densityDpi=160;
            after.windowScale = Float.NaN; check(!NativeWindowScaleCorrection.candidate(before, after), "unknown scale");
            """);
    }

    @Test public void commitValidationRequiresExactGeometryAndTaskIdentity() throws Exception {
        verify("""
            var before = new FrameworkTaskSnapshot(); var after = new FrameworkTaskSnapshot();
            check(NativeWindowScaleCorrection.matches(before, after), "matching task rejected");
            after.logicalBounds = new Rect(11,20,1011,820);
            check(!NativeWindowScaleCorrection.matches(before, after), "moved task accepted");
            after.logicalBounds = before.logicalBounds; after.rootTaskId++;
            check(!NativeWindowScaleCorrection.matches(before, after), "reparented task accepted");
            check(!NativeWindowScaleCorrection.matches(before, null), "removed task accepted");
            """);
    }

    private static void verify(String scenario) throws Exception {
        String source = Files.readString(Path.of(RuntimeSourceFixture.MAIN + "NativeWindowScaleCorrection.java"));
        RuntimeSourceFixture.verify("""
            static class Rect {
                int left,top,right,bottom;
                Rect(int l,int t,int r,int b) { left=l;top=t;right=r;bottom=b; }
                int width(){return right-left;} int height(){return bottom-top;}
                boolean isEmpty(){return width()<=0 || height()<=0;}
                public boolean equals(Object o){return o instanceof Rect r && left==r.left && top==r.top && right==r.right && bottom==r.bottom;}
                public int hashCode(){return java.util.Objects.hash(left,top,right,bottom);}
            }
            static class FrameworkTaskSnapshot {
                static final int ACTIVITY_TYPE_STANDARD=1, WINDOWING_MODE_FREEFORM=5;
                int taskId=1,rootTaskId=1,userId=0,displayId=2,displayAreaFeatureId=1,activityType=1,windowingMode=5,densityDpi=160;
                boolean visible=true; float windowScale=.5f;
                String componentName="app/Main",topActivityName="app/Main",topPackage="app",packageName="app",rootComponent="app/Main";
                Rect bounds=new Rect(10,20,510,420),logicalBounds=new Rect(10,20,1010,820);
            }
            static class TaskWindowGeometry {
            """ + RuntimeSourceFixture.methods("TaskWindowGeometry", "validScale") + "}\n"
                + source.substring(source.indexOf("final class NativeWindowScaleCorrection"))
                    .replaceFirst("final class", "static final class")
                + RuntimeSourceFixture.nestedClass("FrameworkTaskObservationSource", "FreeformBoundsState")
                + "public static void verify() throws Exception {" + scenario + "}");
    }
}
