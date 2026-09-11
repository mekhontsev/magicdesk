package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopDisplayTargetCodecTest {
    @Test
    public void hostBundlePreservesBothRolesAndRejectsIncompleteIdentity() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static class Bundle {
                    final Map<String,Object> values = new HashMap<>();
                    void putInt(String key, int value) { values.put(key, value); }
                    void putString(String key, String value) { values.put(key, value); }
                    int getInt(String key, int fallback) { return (int) values.getOrDefault(key, fallback); }
                    String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
                }
                static class DesktopDisplayTarget {
                    final int workspaceDisplayId;
                    final DesktopDisplayOutput output;
                    DesktopDisplayTarget(int id, DesktopDisplayOutput value) {
                        if (id < 0) throw new IllegalArgumentException("workspace");
                        workspaceDisplayId = id; output = value;
                    }
                """ + RuntimeSourceFixture.methods("DesktopDisplayTarget", "toBundle", "fromBundle", "restore")
                        .replace("android.os.Bundle", "Bundle") + """
                }
                public static void verify() {
                    for (DesktopDisplayOutput.Kind kind : DesktopDisplayOutput.Kind.values()) {
                        DesktopDisplayTarget target = DesktopDisplayTarget.restore(kind, 12,
                                kind == DesktopDisplayOutput.Kind.PHONE ? 0 : 7,
                                "profile", DesktopDisplayOutput.ActivationSource.ADOPTED_EXISTING);
                        Bundle bundle = target.toBundle();
                        DesktopDisplayTarget restored = DesktopDisplayTarget.fromBundle(bundle);
                        check(restored.workspaceDisplayId == 12, "workspace lost");
                        check(restored.output.sameEndpoint(target.output), "output lost");
                        check(restored.output.profileKey.equals("profile"), "profile lost");
                        check(restored.output.activationSource == target.output.activationSource, "source lost");
                        bundle.values.remove("output");
                        check(DesktopDisplayTarget.fromBundle(bundle) == null, "missing output admitted");
                    }
                    check(DesktopDisplayTarget.fromBundle(null) == null, "absent state admitted");
                    check(DesktopDisplayTarget.fromBundle(new Bundle()) == null, "empty state admitted");
                }
                """, "DesktopDisplayOutput");
    }
}
