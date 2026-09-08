package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class AppPresentationScaleInputTest {
    @Test public void keyboardChangePersistsWithoutTouchStop() throws Exception {
        verify("f.onProgressChanged(f.slider, 110, true); check(f.saved.equals(List.of(110)), \"keyboard choice lost\");");
    }
    @Test public void accessibilityChangePersistsSnappedValueOnce() throws Exception {
        verify("f.onProgressChanged(f.slider, 112, true); check(f.saved.equals(List.of(110)), \"snapped accessibility choice lost\");");
    }
    @Test public void touchDragPersistsOnlyOnRelease() throws Exception {
        verify("""
                f.onStartTrackingTouch(f.slider);
                f.slider.progress = 110; f.onProgressChanged(f.slider, 110, true);
                f.slider.progress = 120; f.onProgressChanged(f.slider, 120, true);
                check(f.saved.isEmpty(), "touch drag committed before release");
                f.onStopTrackingTouch(f.slider);
                check(f.saved.equals(List.of(120)), "touch release did not commit final scale");
                """);
    }
    @Test public void renderingAndDisabledControlsNeverPersist() throws Exception {
        verify("""
                f.onProgressChanged(f.slider, 110, false);
                f.mRendering = true; f.onProgressChanged(f.slider, 115, true);
                f.mRendering = false; f.mEnabled = false; f.onProgressChanged(f.slider, 120, true);
                check(f.saved.isEmpty(), "non-user/disabled edit was persisted");
                """);
    }
    private static void verify(String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static class AppPresentationProfile { static final int MIN_SCALE_PERCENT = 50, MAX_SCALE_PERCENT = 200; }
                class SeekBar {
                    int progress; int getProgress() { return progress; }
                    void setProgress(int value) { progress = value; onProgressChanged(this, value, false); }
                }
                static class Mode { boolean isChecked() { return true; } }
                class Actions { void setCustomScale(AppIdentity app, int scale) { check(app.equals(new AppIdentity(0, "example.app")), "wrong package"); saved.add(scale); } }
                final SeekBar slider = new SeekBar(); final Mode mCustomMode = new Mode();
                final Actions mActions = new Actions(); final List<Integer> saved = new ArrayList<>();
                record AppIdentity(long serial, String name) {}
                AppIdentity mApplication = new AppIdentity(0, "example.app");
                boolean mRendering, mTrackingScaleTouch, mEnabled = true;
                static final int SCALE_STEP = 5;
                void updateScaleValue(int scale) {}
                public static void verify() { Fixture f = new Fixture();
                """ + scenario + "}\n" + RuntimeSourceFixture.methods("AppPresentationSettingsView",
                "onProgressChanged", "onStartTrackingTouch", "onStopTrackingTouch", "persistScale", "snapScale"));
    }
}
