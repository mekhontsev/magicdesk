package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopSelfTestFixtureLifecycleTest {
    @Test
    public void recreationRestoresImmersiveClientAndSchedulesNewFrame() throws Exception {
        verify("""
                original.applyImmersive(true);
                Bundle saved = new Bundle();
                original.onSaveInstanceState(saved);
                Client recreated = new Client();
                recreated.onRestoreInstanceState(saved);
                check(recreated.isImmersiveEnabled(), "immersive request lost");
                check(recreated.events.equals(List.of("views-restored", "bars:true",
                        "client:true", "frame:true")), "wrong restore order: " + recreated.events);
                check(original.events.contains("views-saved"), "framework save skipped");
                """);
    }

    @Test
    public void recreationAfterExitDoesNotReenterImmersive() throws Exception {
        verify("""
                original.applyImmersive(true);
                original.applyImmersive(false);
                Bundle saved = new Bundle();
                original.onSaveInstanceState(saved);
                Client recreated = new Client();
                recreated.onRestoreInstanceState(saved);
                check(!recreated.isImmersiveEnabled(), "exited request revived");
                check(recreated.events.equals(List.of("views-restored")),
                        "ordinary window issued an immersive command: " + recreated.events);
                """);
    }

    @Test
    public void freshSavedStateDoesNotInventAnImmersiveRequest() throws Exception {
        verify("""
                original.onRestoreInstanceState(new Bundle());
                check(!original.isImmersiveEnabled(), "default request must be false");
                check(original.events.equals(List.of("views-restored")),
                        "fresh state emitted an immersive marker: " + original.events);
                """);
    }

    @Test
    public void consecutiveRecreationsRetainTheRequest() throws Exception {
        verify("""
                original.applyImmersive(true);
                for (int i = 0; i < 3; i++) {
                    Bundle saved = new Bundle();
                    original.onSaveInstanceState(saved);
                    original = new Client();
                    original.onRestoreInstanceState(saved);
                    check(original.isImmersiveEnabled(), "request lost on recreation " + i);
                    check(original.events.contains("frame:true"), "new frame was not scheduled");
                }
                """);
    }

    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static class Bundle {
                    final Map<String, Boolean> values = new HashMap<>();
                    void putBoolean(String key, boolean value) { values.put(key, value); }
                    boolean getBoolean(String key, boolean fallback) {
                        return values.getOrDefault(key, fallback);
                    }
                }
                static class Activity {
                    final List<String> events = new ArrayList<>();
                    protected void onSaveInstanceState(Bundle state) { events.add("views-saved"); }
                    protected void onRestoreInstanceState(Bundle state) { events.add("views-restored"); }
                }
                static class Client extends Activity {
                    static final String EXTRA_IMMERSIVE = "self_test_immersive";
                    boolean mImmersiveEnabled;
                    void applyImmersiveBars(boolean enabled) { events.add("bars:" + enabled); }
                    void configureImmersiveWindow(boolean enabled) { events.add("client:" + enabled); }
                    void recordImmersiveFrame(boolean enabled) { events.add("frame:" + enabled); }
                """ + RuntimeSourceFixture.methods("DesktopSelfTestActivity",
                        "onSaveInstanceState", "onRestoreInstanceState", "applyImmersive",
                        "isImmersiveEnabled") + """
                }
                public static void verify() {
                    Client original = new Client();
                """ + scenario + "}\n");
    }
}
