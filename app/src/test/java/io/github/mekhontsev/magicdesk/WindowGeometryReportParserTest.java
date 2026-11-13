package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class WindowGeometryReportParserTest {
    private static final List<WindowGeometryReportParser.Target> TARGETS = List.of(
            new WindowGeometryReportParser.Target(70, "org.example.app"));

    @Test public void windowKeepsFramesAndScaleButNotTitlesOrConfigurationLocale() {
        String result = WindowGeometryReportParser.windows("""
                  Window #0 Window{abc u0 org.example.app/.Main Private document}:
                    mDisplayId=5 taskId=70 mSession=Session{abc}
                    mOwnerUid=10123 package=org.example.app
                    Requested w=1280 h=1325 mLayoutSeq=42
                    mFullConfiguration={1.0 [en_US] winConfig={ mBounds=Rect(2292, 144 - 3572, 1469) }}
                    mGlobalScale=0.25 mInvGlobalScale=4.0 mCompatScale=1.0
                    mHScale=0.25 mVScale=0.25 mDsDx=0.25 mDtDy=0.25
                    mCompatFrame=[0,0][1280,1325]
                    Frames: parent=[0,0][2560,1440] display=[0,0][2560,1440] frame=[2292,144][3572,1469]
                    mSurface=Surface(name=Private document secretScale=12345)
                    Surface: shown=true mLastHidden=false
                  Window #1 Window{def u0 org.other.app/.Main}:
                    mDisplayId=0 taskId=700
                    mGlobalScale=99
                """, TARGETS);
        assertTrue(result, result.contains("association=taskId"));
        assertTrue(result, result.contains("frame=[2292,144][3572,1469]"));
        assertTrue(result, result.contains("mBounds=Rect(2292, 144 - 3572, 1469)"));
        assertTrue(result, result.contains("mGlobalScale=0.25"));
        assertTrue(result, result.contains("mDsDx=0.25"));
        assertTrue(result, result.contains("mCompatFrame=[0,0][1280,1325]"));
        assertTrue(result, result.contains("w=1280"));
        assertFalse(result, result.contains("Private"));
        assertFalse(result, result.contains("secretScale"));
        assertFalse(result, result.contains("en_US"));
        assertFalse(result, result.contains("99"));
    }

    @Test public void explicitWindowTaskIdentityWinsOverPackageName() {
        String result = WindowGeometryReportParser.windows("""
                  Window #0 Window{abc u10 org.example.app/.Main}:
                    mDisplayId=0 taskId=71
                    mGlobalScale=0.3
                """, TARGETS);
        assertTrue(result, result.contains("matchedRecords=0"));
        assertTrue(result, result.contains("unavailable"));
        assertFalse(result, result.contains("mGlobalScale"));
    }

    @Test public void missingTaskIdentityIsOnlyACandidate() {
        String result = WindowGeometryReportParser.windows("""
                  Window #0 Window{abc u0 org.example.app/.Main}:
                    mDisplayId=5
                    mGlobalScale=0.25
                """, TARGETS);
        assertTrue(result, result.contains("association=name-candidate"));
    }

    @Test public void surfaceRetainsComposedTransformAndLayerStackWithoutInferringDisplay() {
        String result = WindowGeometryReportParser.surfaces("""
                Composition list (bottom to top)
                LayerStack=5
                  Layer [123] org.example.app/.Main Private document#123
                    visible reason= buffer=123 frame=12
                    bounds={2292,144,475.25,2612} toDisplayTransform={ scale x=0.2500 y=0.2500  tx=2292.0000 ty=144.0000 }
                    input{(0x0) touchCropId=111 touchableRegion={2292,144,475,2612}}
                  Layer [124] Caption of Task=700#124
                    bounds={99,99,99,99}
                Input list
                LayerStack=5
                  Layer [123] org.example.app/.Main#123
                    visible reason=
                    bounds={2292,144,475.25,2612}
                Layer Hierarchy
                  Layer [125] Task=70#125
                """, TARGETS);
        assertTrue(result, result.contains("layer=123 section=composition layerStack=5 candidateTasks=[70]"));
        assertTrue(result, result.contains("toDisplayTransform={ scale x=0.2500 y=0.2500"));
        assertTrue(result, result.contains("touchCropId=111"));
        assertFalse(result, result.contains("layer=124"));
        assertFalse(result, result.contains("layer=125"));
        assertFalse(result, result.contains("Private document"));
        assertFalse(result, result.contains("displayId=5"));
    }

    @Test public void surfacesDoNotMergeAmbiguousSamePackageTasks() {
        String result = WindowGeometryReportParser.surfaces("""
                Composition list (bottom to top)
                LayerStack=5
                  Layer [123] org.example.app/.Main#123
                    bounds={0,0,100,200}
                  Layer [124] Caption of Task=70Leash#124
                    bounds={0,0,20,200}
                """, List.of(TARGETS.get(0), new WindowGeometryReportParser.Target(71, "org.example.app")));
        assertTrue(result, result.contains("candidateTasks=[70, 71]"));
        assertTrue(result, result.contains("layer=124 section=composition layerStack=5 candidateTasks=[70]"));
    }

    @Test public void inputIncludesDisplayAndWindowMatricesButNoInputHistory() {
        String result = WindowGeometryReportParser.input("""
                Input Dispatcher State:
                  Display: 5
                    logicalSize=2560x1440
                        transform (ROT_0) (IDENTITY)
                    Windows:
                      0: name=abc org.example.app/.Main secretScale=12345, id=123, displayId=5, alpha=1, frame=[2292,144][2612,475], globalScale=0.25, applicationInfo.name=privateScale=54321, applicationInfo.token=0x0, touchableRegion=[2292,144][2612,475], ownerUid=10123
                      transform (ROT_0) (SCALE TRANSLATE)
                          4.0000 0.0000 -9168.0000
                          0.0000 4.0000 -576.0000
                          0.0000 0.0000 1.0000
                      1: name=org.example.application/.Main, id=999, displayId=5, globalScale=99
                      transform (ROT_0) (IDENTITY)
                  RecentQueue:
                    keyCode=123 PRIVATE INPUT
                  Connections:
                    ownerUid=999
                """, TARGETS);
        assertTrue(result, result.contains("logicalSize=2560x1440"));
        assertTrue(result, result.contains("globalScale=0.25"));
        assertTrue(result, result.contains("4.0000 0.0000 -9168.0000"));
        assertTrue(result, result.contains("ownerUid=10123"));
        assertFalse(result, result.contains("12345"));
        assertFalse(result, result.contains("54321"));
        assertFalse(result, result.contains("999"));
        assertFalse(result, result.contains("PRIVATE INPUT"));
        assertFalse(result, result.contains("keyCode"));
    }

    @Test public void unsupportedOrDeniedSourcesRemainUnknown() {
        for (String dump : List.of("", "Permission Denial: dumpsys", "new unsupported format")) {
            assertTrue(WindowGeometryReportParser.windows(dump, TARGETS).contains("Geometry unavailable"));
            assertTrue(WindowGeometryReportParser.surfaces(dump, TARGETS).contains("Geometry unavailable"));
            assertTrue(WindowGeometryReportParser.input(dump, TARGETS).contains("Geometry unavailable"));
        }
    }

    @Test public void historicalAnrGeometryCannotBecomeCurrentEvidence() {
        String historical = """
                Input Dispatcher State at time of last ANR:
                  Display: 99
                    Windows:
                      0: name=org.example.app/.Main, id=999, displayId=99, globalScale=0.125
                      transform (ROT_0) (IDENTITY)
                """;
        String result = WindowGeometryReportParser.input("Input Dispatcher State:\n" + historical, TARGETS);
        assertTrue(result, result.contains("matchedRecords=0"));
        assertFalse(result, result.contains("display=99"));
        assertFalse(result, result.contains("globalScale"));
        assertTrue(WindowGeometryReportParser.input(historical, TARGETS).contains("Geometry unavailable"));
    }

    @Test public void excerptsAreBoundedAndReportTruncation() {
        String dump = ("  Window #0 Window{abc u0 org.example.app/.Main}:\n"
                + "    taskId=70\n    mGlobalScale=0.25\n").repeat(2000);
        String result = WindowGeometryReportParser.windows(dump, TARGETS);
        assertTrue(result, result.contains("excerptTruncated=true"));
        assertTrue(result.length() < WindowGeometryReportParser.OUTPUT_LIMIT + 200);
    }
}
