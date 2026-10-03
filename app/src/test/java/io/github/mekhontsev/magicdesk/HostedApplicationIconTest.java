package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class HostedApplicationIconTest {
    @Test public void clientIconWinsAndMissingIconsUseOnlyTheSelectedRecipesCache() throws Exception {
        RuntimeSourceFixture.verify("""
            static class Bitmap { }
            enum DesktopExecBackend { TERMUX, SHELL }
            record DesktopApplicationShortcut(String icon, DesktopExecBackend execBackend) {
                boolean hasExecLaunch(){return true;}
            }
            static class RecentApplicationStore { record Entry(DesktopApplicationShortcut shortcut) { } }
            static class ApplicationCatalog {
                static Bitmap termux, guest; static int reads;
                static Bitmap cachedGuestIcon(String key){reads++;return guest;}
                static Bitmap cachedTermuxIcon(String key){reads++;return termux;}
            }
            """ + RuntimeSourceFixture.methods("DesktopApplicationIconResolver", "cachedIcon", "hostedIcon")
                    .replace("android.graphics.Bitmap", "Bitmap") + """
            public static void verify() {
                var recipe=new RecentApplicationStore.Entry(new DesktopApplicationShortcut("code",DesktopExecBackend.TERMUX));
                Bitmap nativeIcon=new Bitmap();
                check(hostedIcon(nativeIcon,recipe)==nativeIcon && ApplicationCatalog.reads==0, "client icon consulted cache");
                check(hostedIcon(null,recipe)==null, "cache miss invented an icon");
                ApplicationCatalog.termux=new Bitmap();
                check(hostedIcon(null,recipe)==ApplicationCatalog.termux, "Termux cache ignored");
                var guest=new RecentApplicationStore.Entry(new DesktopApplicationShortcut("guest:ubuntu/code",DesktopExecBackend.SHELL));
                ApplicationCatalog.guest=new Bitmap();
                check(hostedIcon(null,guest)==ApplicationCatalog.guest, "guest cache ignored");
                int reads=ApplicationCatalog.reads;
                check(hostedIcon(null,null)==null && cachedIcon(null)==null && ApplicationCatalog.reads==reads, "missing recipe accessed catalog");
            }
            """);
    }
}
